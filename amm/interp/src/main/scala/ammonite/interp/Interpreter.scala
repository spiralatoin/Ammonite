package ammonite.interp

import java.io.{File, OutputStream, PrintStream}
import java.util.regex.Pattern

import scala.collection.mutable
import scala.tools.nsc.Settings
import ammonite.ops._
import ammonite.runtime.Evaluator.addToClasspath
import ammonite.runtime._
import fastparse.all._

import annotation.tailrec
import ammonite.util.ImportTree
import ammonite.util.Util.ScriptOutput.BlockMetadata
import ammonite.util.Util._
import ammonite.util._

import scala.reflect.io.VirtualDirectory


/**
 * A convenient bundle of all the functionality necessary
 * to interpret Scala code. Doesn't attempt to provide any
 * real encapsulation for now.
 */
class Interpreter(val printer: Printer,
                  val storage: Storage,
                  customPredefs: Seq[Interpreter.PredefInfo],
                  // Allows you to set up additional "bridges" between the REPL
                  // world and the outside world, by passing in the full name
                  // of the `APIHolder` object that will hold the bridge and
                  // the object that will be placed there. Needs to be passed
                  // in as a callback rather than run manually later as these
                  // bridges need to be in place *before* the predef starts
                  // running, so you can use them predef to e.g. configure
                  // the REPL before it starts
                  extraBridges: Interpreter => Seq[(String, String, AnyRef)],
                  val wd: Path,
                  verboseOutput: Boolean = true)
  extends ImportHook.InterpreterInterface{ interp =>



  //this variable keeps track of where should we put the imports resulting from scripts.
  private var scriptImportCallback: Imports => Unit = eval.update

  var lastException: Throwable = null

  private var _compilationCount = 0
  def compilationCount = _compilationCount


  val mainThread = Thread.currentThread()
  val eval = Evaluator(mainThread.getContextClassLoader, getClass.getClassLoader, 0)

  val dynamicClasspath = new VirtualDirectory("(memory)", None)
  var compiler: Compiler = null
  val onCompilerInit = mutable.Buffer.empty[scala.tools.nsc.Global => Unit]
  var pressy: Pressy = _
  val beforeExitHooks = mutable.Buffer.empty[Any ⇒ Any]
  val watchedFiles = mutable.Buffer.empty[(Path, Long)]

  def evalClassloader = eval.frames.head.classloader

  def reInit() = {
    if(compiler != null)
      init()
  }

  def init() = {
    // Note we not only make a copy of `settings` to pass to the compiler,
    // we also make a *separate* copy to pass to the presentation compiler.
    // Otherwise activating autocomplete makes the presentation compiler mangle
    // the shared settings and makes the main compiler sad
    val settings = Option(compiler).fold(new Settings)(_.compiler.settings.copy)
    compiler = Compiler(
      Classpath.classpath ++ eval.frames.head.classpath,
      dynamicClasspath,
      evalClassloader,
      eval.frames.head.pluginClassloader,
      () => pressy.shutdownPressy(),
      settings
    )

    onCompilerInit.foreach(_(compiler.compiler))

    pressy = Pressy(
      Classpath.classpath ++ eval.frames.head.classpath,
      dynamicClasspath,
      evalClassloader,

      settings.copy()
    )
  }

  val bridges = extraBridges(this) :+ ("ammonite.interp.InterpBridge", "interp", interpApi)
  for ((name, shortName, bridge) <- bridges ){
    APIHolder.initBridge(evalClassloader, name, bridge)
  }
  // import ammonite.repl.ReplBridge.{value => repl}
  // import ammonite.runtime.InterpBridge.{value => interp}
  val bridgePredefs =
    for ((name, shortName, bridge) <- bridges)
    yield Interpreter.PredefInfo(
      Name(s"${shortName}Bridge"),
      s"import $name.{value => $shortName}",
      true,
      None
    )


  val importHooks = Ref(Map[Seq[String], ImportHook](
    Seq("file") -> ImportHook.File,
    Seq("exec") -> ImportHook.Exec,
    Seq("ivy") -> ImportHook.Ivy,
    Seq("cp") -> ImportHook.Classpath,
    Seq("plugin", "ivy") -> ImportHook.PluginIvy,
    Seq("plugin", "cp") -> ImportHook.PluginClasspath
  ))

  val predefs = {
    val (sharedPredefContent, sharedPredefPath) = storage.loadSharedPredef
    val (predefContent, predefPath) = storage.loadPredef
    bridgePredefs ++ customPredefs ++ Seq(
      Interpreter.PredefInfo(
        Name("UserSharedPredef"),
        sharedPredefContent,
        false,
        sharedPredefPath
      ),
      Interpreter.PredefInfo(
        Name("UserPredef"),
        predefContent,
        false,
        predefPath
      )
    )
  }

  // Use a var and a for-loop instead of a fold, because when running
  // `processModule0` user code may end up calling `processModule` which depends
  // on `predefImports`, and we should be able to provide the "current" imports
  // to it even if it's half built
  var predefImports = Imports()
  for {
    predefInfo <- predefs
    if predefInfo.code.nonEmpty
  }{
    processModule(
      predefInfo.code,
      CodeSource(
        predefInfo.name,
        Seq(),
        Seq(Name("ammonite"), Name("predef")),
        predefInfo.path
      ),
      true,
      "",
      predefInfo.hardcoded
    ) match{
      case Res.Success(processed) =>
        predefImports = predefImports ++ processed.blockInfo.last.finalImports

      case Res.Failure(ex, msg) =>
        ex match{
          case Some(e) => throw new RuntimeException("Error during Predef: " + msg, e)
          case None => throw new RuntimeException("Error during Predef: " + msg)
        }

      case Res.Exception(ex, msg) =>
        throw new RuntimeException("Error during Predef: " + msg, ex)

      case _ => ???
    }
  }

  reInit()



  def resolveSingleImportHook(source: CodeSource, tree: ImportTree) = {
    val strippedPrefix = tree.prefix.takeWhile(_(0) == '$').map(_.stripPrefix("$"))
    val hookOpt = importHooks().collectFirst{case (k, v) if strippedPrefix.startsWith(k) => (k, v)}
    for{
      (hookPrefix, hook) <- Res(hookOpt, "Import Hook could not be resolved")
      hooked <- Res(
        hook.handle(source, tree.copy(prefix = tree.prefix.drop(hookPrefix.length)), this)
      )
      hookResults <- Res.map(hooked){
        case res: ImportHook.Result.Source =>
          res.codeSource.path.foreach(interpApi.watch)
          for{
            processed <- processModule(
              res.code, res.codeSource,
              autoImport = false, extraCode = "", hardcoded = false
            )
          } yield {
            // For $file imports, we do not propagate any imports from the imported scripted
            // to the enclosing session. Instead, the imported script wrapper object is
            // brought into scope and you're meant to use the methods defined on that.
            //
            // Only $exec imports merge the scope of the imported script into your enclosing
            // scope, but those are comparatively rare.
            if (!res.exec) res.hookImports
            else processed.blockInfo.last.finalImports ++ res.hookImports
          }
        case res: ImportHook.Result.ClassPath =>

          if (res.plugin) handlePluginClasspath(res.file.toIO)
          else handleEvalClasspath(res.file.toIO)

          Res.Success(Imports())
      }
    } yield {
      reInit()
      hookResults
    }
  }

  def resolveImportHooks(source: CodeSource,
                         stmts: Seq[String]): Res[ImportHookInfo] = {
      val hookedStmts = mutable.Buffer.empty[String]
      val importTrees = mutable.Buffer.empty[ImportTree]
      for(stmt <- stmts) {
        Parsers.ImportSplitter.parse(stmt) match{
          case f: Parsed.Failure => hookedStmts.append(stmt)
          case Parsed.Success(parsedTrees, _) =>
            var currentStmt = stmt
            for(importTree <- parsedTrees){
              if (importTree.prefix(0)(0) == '$') {
                val length = importTree.end - importTree.start
                currentStmt = currentStmt.patch(
                  importTree.start, (importTree.prefix(0) + ".$").padTo(length, ' '), length
                )
                importTrees.append(importTree)
              }
            }
            hookedStmts.append(currentStmt)
        }
      }

      for (hookImports <- Res.map(importTrees)(resolveSingleImportHook(source, _)))
      yield ImportHookInfo(
        Imports(hookImports.flatten.flatMap(_.value)),
        hookedStmts,
        importTrees
      )
    }

  def processLine(code: String, stmts: Seq[String], fileName: String): Res[Evaluated] = {
    val preprocess = Preprocessor(compiler.parse)
    val wrapperName = Name("cmd" + eval.getCurrentLine)
    for{
      _ <- Catching { case ex =>
        Res.Exception(ex, "Something unexpected went wrong =(")
      }

      ImportHookInfo(hookImports, hookStmts, _) <- resolveImportHooks(

        CodeSource(wrapperName, Seq(), Seq(Name("$file")), Some(wd/"<console>")),
        stmts
      )

      processed <- preprocess.transform(
        hookStmts,
        eval.getCurrentLine,
        "",
        Seq(Name("$sess")),
        wrapperName,
        predefImports ++ eval.frames.head.imports ++ hookImports,
        prints => s"ammonite.repl.ReplBridge.value.Internal.combinePrints($prints)",
        extraCode = ""
      )
      (out, tag) <- evaluateLine(
        processed, printer,
        fileName, Name("cmd" + eval.getCurrentLine)
      )
    } yield out.copy(imports = out.imports ++ hookImports)

  }


  def withContextClassloader[T](t: => T) = {
    val oldClassloader = Thread.currentThread().getContextClassLoader
    try{
      Thread.currentThread().setContextClassLoader(evalClassloader)
      t
    } finally {
      Thread.currentThread().setContextClassLoader(oldClassloader)
    }
  }


  def compileClass(processed: Preprocessor.Output,
                   printer: Printer,
                   fileName: String): Res[(Util.ClassFiles, Imports)] = for {
    compiled <- Res.Success{
      compiler.compile(processed.code.getBytes, printer, processed.prefixCharLength, fileName)
    }
    _ = _compilationCount += 1
    (classfiles, imports) <- Res[(Util.ClassFiles, Imports)](
      compiled,
      "Compilation Failed"
    )
  } yield {
    (classfiles, imports)
  }


  def evaluateLine(processed: Preprocessor.Output,
                   printer: Printer,
                   fileName: String,
                   indexedWrapperName: Name): Res[(Evaluated, Tag)] = {

    for{
      _ <- Catching{ case e: ThreadDeath => Evaluator.interrupted(e) }
      (classFiles, newImports) <- compileClass(
        processed,
        printer,
        fileName
      )
      res <- withContextClassloader{
        eval.processLine(
          classFiles,
          newImports,
          printer,
          fileName,
          indexedWrapperName
        )

      }
    } yield (res, Tag("", ""))
  }


  def processScriptBlock(processed: Preprocessor.Output,
                         codeSource0: CodeSource,
                         indexedWrapperName: Name) = {
    val codeSource = codeSource0.copy(wrapperName = indexedWrapperName)
    val fullyQualifiedName = codeSource.jvmPathPrefix

    val tag = Tag(
      Interpreter.cacheTag(processed.code.getBytes),
      Interpreter.cacheTag(eval.frames.head.classloader.classpathHash)
    )

    for {
      (classFiles, newImports) <- compileClass(
        processed, printer, codeSource.printablePath
      )
      cls <- eval.loadClass(fullyQualifiedName, classFiles)

      res <- eval.processScriptBlock(
        cls,
        newImports,
        codeSource.wrapperName,
        codeSource.pkgName
      )
    } yield {
      storage.compileCacheSave(fullyQualifiedName, tag, (classFiles, newImports))

      (res, tag)
    }
  }


  def processModule(code: String,
                    codeSource: CodeSource,
                    autoImport: Boolean,
                    extraCode: String,
                    hardcoded: Boolean): Res[ScriptOutput.Metadata] = {

    val tag = Tag(
      Interpreter.cacheTag(code.getBytes),
      Interpreter.cacheTag(
        if (hardcoded) Array.empty[Byte]
        else eval.frames.head.classloader.classpathHash
      )
    )


    val cachedScriptData = storage.classFilesListLoad(codeSource.filePathPrefix, tag)


    // Lazy, because we may not always need this if the script is already cached
    // and none of it's blocks end up needing to be re-compiled. We don't know up
    // front if any blocks will need re-compilation, because it may import $file
    // another script which gets changed, and we'd only know when we reach that block
    lazy val splittedScript = Preprocessor.splitScript(Interpreter.skipSheBangLine(code))

    for{
      blocks <- cachedScriptData match {
        case None => splittedScript.map(_.map(_ => None))
        case Some(scriptOutput) =>
          Res.Success(scriptOutput.classFiles.zip(scriptOutput.processed.blockInfo).map(Some(_)))
      }

      data <- processCorrectScript(
        blocks,
        splittedScript,
        predefImports,
        codeSource,
        processScriptBlock(_, codeSource, _),
        autoImport,
        extraCode
      )
    } yield {
      reInit()

      storage.classFilesListSave(
        codeSource.filePathPrefix,
        data.blockInfo,
        tag
      )
      data
    }
  }


  def processExec(code: String): Res[Imports] = {
    init()
    for {
      blocks <- Preprocessor.splitScript(Interpreter.skipSheBangLine(code))
      processedData <- processCorrectScript(
        blocks.map(_ => None),
        Res.Success(blocks),
        eval.frames.head.imports,
        CodeSource(
          Name("cmd" + eval.getCurrentLine),
          Seq(),
          Seq(Name("$sess")),
          Some(wd/"<console>")
        ),
        { (processed, indexedWrapperName) =>
          evaluateLine(
            processed,
            printer,
            s"Exec.sc",
            indexedWrapperName
          )
        },
        autoImport = true,
        ""
      )
    } yield processedData.blockInfo.last.finalImports
  }



  type BlockData = Option[(ClassFiles, ScriptOutput.BlockMetadata)]


  def processCorrectScript(blocks: Seq[BlockData],
                           splittedScript: => Res[IndexedSeq[(String, Seq[String])]],
                           startingImports: Imports,
                           codeSource: CodeSource,
                           evaluate: (Preprocessor.Output, Name) => Res[(Evaluated, Tag)],
                           autoImport: Boolean,
                           extraCode: String): Res[ScriptOutput.Metadata] = {

    // we store the old value, because we will reassign this in the loop
    val outerScriptImportCallback = scriptImportCallback

    /**
      * Iterate over the blocks of a script keeping track of imports.
      *
      * We keep track of *both* the `scriptImports` as well as the `lastImports`
      * because we want to be able to make use of any import generated in the
      * script within its blocks, but at the end we only want to expose the
      * imports generated by the last block to who-ever loaded the script
      *
      * @param blocks the compilation block of the script, separated by `@`s.
      *               Each one is a tuple containing the leading whitespace and
      *               a sequence of statements in that block
      *
      * @param scriptImports the set of imports that apply to the current
      *                      compilation block, excluding that of the last
      *                      block that was processed since that is held
      *                      separately in `lastImports` and treated
      *                      specially
      *
      * @param lastImports the imports created by the last block that was processed;
      *                    only imports created by that
      *
      * @param wrapperIndex a counter providing the index of the current block, so
      *                     e.g. if `Foo.sc` has multiple blocks they can be named
      *                     `Foo_1` `Foo_2` etc.
      *
      * @param perBlockMetadata an accumulator for the processed metadata of each block
      *                         that is fed in
      */
    @tailrec def loop(blocks: Seq[BlockData],
                      scriptImports: Imports,
                      lastImports: Imports,
                      wrapperIndex: Int,
                      perBlockMetadata: List[ScriptOutput.BlockMetadata])
                      : Res[ScriptOutput.Metadata] = {
      if (blocks.isEmpty) {
        // No more blocks
        // if we have imports to pass to the upper layer we do that
        if (autoImport) outerScriptImportCallback(lastImports)
        Res.Success(ScriptOutput.Metadata(perBlockMetadata))
      } else {
        // imports from scripts loaded from this script block will end up in this buffer
        var nestedScriptImports = Imports()
        scriptImportCallback = { imports =>
          nestedScriptImports = nestedScriptImports ++ imports
        }
        // pretty printing results is disabled for scripts
        val indexedWrapperName = Interpreter.indexWrapperName(codeSource.wrapperName, wrapperIndex)


        def compileRunBlock(leadingSpaces: String, hookInfo: ImportHookInfo) = {
          init()
          val printSuffix = if (wrapperIndex == 1) "" else  " #" + wrapperIndex
          printer.info("Compiling " + codeSource.printablePath + printSuffix)
          for{
            processed <- Preprocessor(compiler.parse).transform(
              hookInfo.stmts,
              "",
              leadingSpaces,
              codeSource.pkgName,
              indexedWrapperName,
              scriptImports ++ hookInfo.imports,
              _ => "scala.Iterator[String]()",
              extraCode = extraCode
            )
            (ev, tag) <- evaluate(processed, indexedWrapperName)
          } yield BlockMetadata(
            VersionedWrapperId(ev.wrapper.map(_.encoded).mkString("."), tag),
            leadingSpaces,
            hookInfo,
            ev.imports
          )
        }
        val res = blocks.head match{
          case None  =>
            for{
              allSplittedChunks <- splittedScript
              (leadingSpaces, stmts) = allSplittedChunks(wrapperIndex - 1)
              hookInfo <- resolveImportHooks(codeSource, stmts)
              res <- compileRunBlock(leadingSpaces, hookInfo)
            } yield res

          case Some((classFiles, blockMetadata)) =>
            blockMetadata.hookInfo.trees.foreach(resolveSingleImportHook(codeSource, _))
            val envHash = Interpreter.cacheTag(eval.frames.head.classloader.classpathHash)
            if (envHash != blockMetadata.id.tag.env) {
              compileRunBlock(blockMetadata.leadingSpaces, blockMetadata.hookInfo)
            } else{
              addToClasspath(classFiles, dynamicClasspath)
              val cls = eval.loadClass(blockMetadata.id.wrapperPath, classFiles)
              val evaluated =
                try cls.map(eval.evalMain(_))
                catch Evaluator.userCodeExceptionHandler

              evaluated.map(_ => blockMetadata)
            }
        }

        res match{
          case Res.Success(blockMetadata) =>
            val last =
              blockMetadata.hookInfo.imports ++
              blockMetadata.finalImports ++
              nestedScriptImports

            loop(
              blocks.tail,
              scriptImports ++ last,
              last,
              wrapperIndex + 1,
              blockMetadata :: perBlockMetadata
            )

          case r: Res.Failure => r
          case r: Res.Exception => r
          case Res.Skip =>
            loop(blocks.tail, scriptImports, lastImports, wrapperIndex + 1, perBlockMetadata)

        }
      }
    }
    // wrapperIndex starts off as 1, so that consecutive wrappers can be named
    // Wrapper, Wrapper2, Wrapper3, Wrapper4, ...
    try {

      for(res <- loop(blocks, startingImports, Imports(), wrapperIndex = 1, List()))
      // We build up `blockInfo` backwards, since it's a `List`, so reverse it
      // before giving it to the outside world
      yield ScriptOutput.Metadata(res.blockInfo.reverse)
    } finally scriptImportCallback = outerScriptImportCallback
  }

  def handleOutput(res: Res[Evaluated]): Unit = {
    res match{
      case Res.Skip => // do nothing
      case Res.Exit(value) => pressy.shutdownPressy()
      case Res.Success(ev) => eval.update(ev.imports)
      case Res.Failure(ex, msg) => lastException = ex.getOrElse(lastException)
      case Res.Exception(ex, msg) => lastException = ex
    }
  }
  def loadIvy(coordinates: coursier.Dependency*) = {
    val cacheKey = (interpApi.repositories().hashCode.toString, coordinates)

    storage.ivyCache().get(cacheKey) match{
      case Some(res) => Right(res.map(new java.io.File(_)))
      case None =>
        ammonite.runtime.tools.IvyThing.resolveArtifact(
          interpApi.repositories(),
          coordinates,
          verbose = verboseOutput
        )match{
          case Right(loaded) =>
            val loadedSet = loaded.toSet
            storage.ivyCache() = storage.ivyCache().updated(
              cacheKey, loadedSet.map(_.getAbsolutePath)
            )
            Right(loadedSet)
          case Left(l) =>
            Left(l)
        }
    }


  }
  abstract class DefaultLoadJar extends LoadJar {
    def handleClasspath(jar: File): Unit

    def cp(jar: Path): Unit = {
      handleClasspath(new java.io.File(jar.toString))
      reInit()
    }
    def cp(jars: Seq[Path]): Unit = {
      jars.map(_.toString).map(new java.io.File(_)).foreach(handleClasspath)
      reInit()
    }
    def ivy(coordinates: coursier.Dependency*): Unit = {
      loadIvy(coordinates:_*) match{
        case Left(failureMsg) =>
          throw new Exception(failureMsg)
        case Right(loaded) =>
          loaded.foreach(handleClasspath)

      }

      reInit()
    }
  }

  def handleEvalClasspath(jar: File) = {
    eval.frames.head.addClasspath(Seq(jar))
    evalClassloader.add(jar.toURI.toURL)
  }
  def handlePluginClasspath(jar: File) = {
    eval.frames.head.pluginClassloader.add(jar.toURI.toURL)
  }
  lazy val interpApi: InterpAPI = new InterpAPI{ outer =>

    def watch(p: Path) = {
      watchedFiles.append(p -> p.mtime.toMillis)
    }

    def configureCompiler(callback: scala.tools.nsc.Global => Unit) = {
      interp.onCompilerInit.append(callback)
      if (compiler != null){
        callback(compiler.compiler)
      }
    }

    val beforeExitHooks = interp.beforeExitHooks

    val repositories = Ref(ammonite.runtime.tools.IvyThing.defaultRepositories)

    object load extends DefaultLoadJar with Load {

      def handleClasspath(jar: File) = handleEvalClasspath(jar)

      def apply(line: String) = processExec(line) match{
        case Res.Failure(ex, s) => throw new CompilationError(s)
        case Res.Exception(t, s) => throw t
        case _ =>
      }

      def exec(file: Path): Unit = {
        watch(file)
        apply(normalizeNewlines(read(file)))
      }

      def module(file: Path) = {
        watch(file)
        val (pkg, wrapper) = Util.pathToPackageWrapper(
          Seq(Name("dummy")),
          file relativeTo wd
        )
        processModule(
          normalizeNewlines(read(file)),
          CodeSource(
            wrapper,
            pkg,
            Seq(Name("$file")),
            Some(wd/"Main.sc")
          ),
          true,
          "",
          hardcoded = false
        ) match{
          case Res.Failure(ex, s) => throw new CompilationError(s)
          case Res.Exception(t, s) => throw t
          case x => //println(x)
        }
        reInit()
      }

      object plugin extends DefaultLoadJar {
        def handleClasspath(jar: File) = handlePluginClasspath(jar)
      }

    }
  }

}

object Interpreter{
  
  val SheBang = "#!"
  val SheBangEndPattern = Pattern.compile(s"""((?m)^!#.*)$newLine""")

  /**
    * Information about a particular predef file or snippet. [[hardcoded]]
    * represents whether or not we cache the snippet forever regardless of
    * classpath, which is true for many "internal" predefs which only do
    * imports from Ammonite's own packages and don't rely on external code
    */
  case class PredefInfo(name: Name, code: String, hardcoded: Boolean, path: Option[Path])

  /**
    * This gives our cache tags for compile caching. The cache tags are a hash
    * of classpath, previous commands (in-same-script), and the block-code.
    * Previous commands are hashed in the wrapper names, which are contained
    * in imports, so we don't need to pass them explicitly.
    */
  def cacheTag(classpathHash: Array[Byte]): String = {
    val bytes = Util.md5Hash(Iterator(
      classpathHash
    ))
    bytes.map("%02x".format(_)).mkString
  }

  def skipSheBangLine(code: String)= {
    if (code.startsWith(SheBang)) {
      val matcher = SheBangEndPattern matcher code
      val shebangEnd = if (matcher.find) matcher.end else code.indexOf(newLine)
      val numberOfStrippedLines = newLine.r.findAllMatchIn( code.substring(0, shebangEnd) ).length
      (newLine * numberOfStrippedLines) + code.substring(shebangEnd)
    } else
      code
  }

  def indexWrapperName(wrapperName: Name, wrapperIndex: Int): Name = {
    Name(wrapperName.raw + (if (wrapperIndex == 1) "" else "_" + wrapperIndex))
  }

  def initPrinters(output: OutputStream,
                   info: OutputStream,
                   error: OutputStream,
                   verboseOutput: Boolean) = {
    val colors = Ref[Colors](Colors.Default)
    val printStream = new PrintStream(output, true)
    val errorPrintStream = new PrintStream(error, true)
    val infoPrintStream = new PrintStream(info, true)

    def printlnWithColor(stream: PrintStream, color: fansi.Attrs, s: String) = {
      stream.println(color(s).render)
    }

    val printer = Printer(
      printStream.print,
      printlnWithColor(errorPrintStream, colors().warning(), _),
      printlnWithColor(errorPrintStream, colors().error(), _),
      s => if (verboseOutput) printlnWithColor(infoPrintStream, fansi.Attrs.Empty, s)
    )
    (colors, printStream, errorPrintStream, printer)
  }
}
