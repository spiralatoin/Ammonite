package ammonite.repl.interp

import java.io.File
import java.nio.file.Files
import acyclic.file
import ammonite.ops._
import ammonite.repl.Util.IvyMap
import pprint.{Config, PPrint}
import annotation.tailrec
import ammonite.repl._
import ammonite.repl.frontend._
import Util.CompileCache
import fastparse.core.Result

import scala.reflect.io.VirtualDirectory

/**
 * A convenient bundle of all the functionality necessary
 * to interpret Scala code. Doesn't attempt to provide any
 * real encapsulation for now.
 */
class Interpreter(prompt0: Ref[String],
                  frontEnd0: Ref[FrontEnd],
                  width: => Int,
                  height: => Int,
                  pprintConfig: pprint.Config,
                  colors0: Ref[Colors],
                  stdout: String => Unit,
                  storage: Ref[Storage],
                  predef: String){ interp =>

  val dynamicClasspath = new VirtualDirectory("(memory)", None)
  var extraJars = Seq[java.io.File]()

  def processLine(code: String,
                  stmts: Seq[String],
                  printer: Iterator[String] => Unit) = {
    if (code != "") storage().history() = storage().history() :+ code
    for{
      _ <- Catching { case ex =>
        Res.Exception(ex, "Something unexpected went wrong =(")
      }
      Preprocessor.Output(code, printSnippet) <- preprocess(stmts, eval.getCurrentLine)
      out <- evaluateLine(code, printSnippet, printer)
    } yield out
  }

  def evaluateLine(code: String, printSnippet: Seq[String], printer: Iterator[String] => Unit) = {
    val oldClassloader = Thread.currentThread().getContextClassLoader
    try{
      Thread.currentThread().setContextClassLoader(eval.evalClassloader)
      eval.processLine(
        code,
        s"ReplBridge.repl.Internal.combinePrints(${printSnippet.mkString(", ")})",
        printer
      )
    } finally Thread.currentThread().setContextClassLoader(oldClassloader)
  }

  def processModule(code: String) = processScript(code, eval.processScriptBlock)

  def processExec(code: String) = processScript(code, { (c, _) => evaluateLine(c, Seq(), _ => ()) })
 
  //common stuff in proccessModule and processExec
  def processScript(code: String, evaluate: (String, Seq[ImportData]) => Res[Evaluated]): Unit = {
    val blocks = Parsers.splitScript(code).map(preprocess(_, ""))
    val errors = blocks.collect{ case Res.Failure(err) => err }
    if(!errors.isEmpty) 
      stdout(colors0().error() + errors.mkString("\n") + colors0().reset() + "\n")
    else
      loop(blocks.collect{ case Res.Success(o) => o }, Seq())

    @tailrec def loop(blocks: Seq[Preprocessor.Output], imports: Seq[ImportData]): Unit = {
      if(!blocks.isEmpty){
        val Preprocessor.Output(code, _) = blocks.head //pretty printing results is disabled for scripts
        val ev = evaluate(code, imports)
        ev match {
          case Res.Failure(msg) =>
            throw new CompilationError(msg)
          case Res.Success(ev) =>
            eval.update(ev.imports)
            loop(blocks.tail, imports ++ ev.imports)
          case _ => loop(blocks.tail, imports)
        }
      }
    }
  }

  def handleOutput(res: Res[Evaluated]) = {
    res match{
      case Res.Skip => true
      case Res.Exit =>
        pressy.shutdownPressy()
        false
      case Res.Success(ev) =>
        eval.update(ev.imports)
        true
      case Res.Failure(msg) => true
      case Res.Exception(ex, msg) => true
    }
  }

  lazy val replApi: ReplAPI = new DefaultReplAPI {

    def imports = interp.eval.previousImportBlock
    val colors = colors0
    val prompt = prompt0
    val frontEnd = frontEnd0

    object load extends Load{

      def apply(line: String) = {
        processExec(line)
      }

      def exec(file: Path): Unit = {

        apply(read(file))
      }

      def module(file: Path): Unit = {
        processModule(read(file))
        init()
      }

      def handleJar(jar: File): Unit = {
        extraJars = extraJars ++ Seq(jar)
        eval.addJar(jar.toURI.toURL)
      }
      def jar(jar: Path): Unit = {
        handleJar(new java.io.File(jar.toString))
        init()
      }
      def ivy(coordinates: (String, String, String), verbose: Boolean = true): Unit = {
        val (groupId, artifactId, version) = coordinates
        storage().ivyCache().get((groupId, artifactId, version)) match{
          case Some(ps) => ps.map(new java.io.File(_)).map(handleJar)
          case None =>
            val resolved = IvyThing.resolveArtifact(groupId, artifactId, version, if (verbose) 2 else 1)
            storage().ivyCache() = storage().ivyCache().updated(
              (groupId, artifactId, version),
              resolved.map(_.getAbsolutePath).toSet
            )

            resolved.map(handleJar)
        }

        init()
      }
    }

    implicit lazy val pprintConfig: Ref[pprint.Config] = {
      Ref.live[pprint.Config](
        () => interp.pprintConfig.copy(
          width = width,
          height = height
        )
      )
    }

    def search(target: scala.reflect.runtime.universe.Type) = Interpreter.this.compiler.search(target)
    def compiler = Interpreter.this.compiler.compiler
    def newCompiler() = init()
    def history = storage().history()

    var wd0 = cwd
    /**
     * The current working directory of the shell, that will get picked up by
     * any ammonite.ops commands you use
     */
    implicit def wd = wd0
    /**
     * Change the working directory `wd`; if the provided path is relative it
     * gets appended on to the current `wd`, if it's absolute it replaces.
     */
    val cd = new ammonite.ops.Op1[ammonite.ops.Path, ammonite.ops.Path]{
      def apply(arg: Path) = {
        wd0 = arg
        wd0
      }
    }
    implicit def Relativizer[T](p: T)(implicit b: Path, f: T => RelPath): Path = b/f(p)

    def width = interp.width

    def height = interp.height
  }

  var compiler: Compiler = _
  var pressy: Pressy = _
  def init() = {
    compiler = Compiler(
      Classpath.jarDeps ++ extraJars,
      Classpath.dirDeps,
      dynamicClasspath,
      eval.evalClassloader,
      () => pressy.shutdownPressy()
    )
    pressy = Pressy(
      Classpath.jarDeps ++ extraJars,
      Classpath.dirDeps,
      dynamicClasspath,
      eval.evalClassloader
    )

    val cls = for {
      (classFiles, imports) <- compiler.compile(
        "object ReplBridge extends ammonite.repl.frontend.ReplAPIHolder{}".getBytes,
        _ => ()
      )
    } yield eval.loadClass("ReplBridge", classFiles)
    ReplAPI.initReplBridge(
      cls.asInstanceOf[Some[Res.Success[Class[ReplAPIHolder]]]].get.s,
      replApi
    )
  }

  val mainThread = Thread.currentThread()
  val preprocess = Preprocessor(compiler.parse)

  val eval = Evaluator(
    mainThread.getContextClassLoader,
    compiler.compile,
    0,
    storage().compileCacheLoad,
    storage().compileCacheSave,
    compiler.addToClasspath
  )

  init()
  // Run the predef. For now we assume that the whole thing is a single
  // command, and will get compiled & run at once. We hard-code the
  // line number to -1 if the predef exists so the first user-entered
  // line becomes 0
  if (predef != "") {
    processExec(predef)
  }
}
