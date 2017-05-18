package ammonite.runtime

import java.io.File

import acyclic.file
import ammonite.ops.{read, _}
import ammonite.runtime.tools.IvyThing
import ammonite.util._

/**
  * An extensible hook into the Ammonite REPL's import system; allows the end
  * user to hook into `import $foo.bar.{baz, qux => qua}` syntax, and in
  * response load jars or process source files before the "current" compilation
  * unit is run. Can be used to load script files, ivy dependencies, jars, or
  * files from the web.
  */
trait ImportHook{
  def handle(source: ImportHook.Source,
             tree: ImportTree,
             interp: ImportHook.InterpreterInterface): Either[String, Seq[ImportHook.Result]]
}

object ImportHook{

  /**
    * The minimal interface that is exposed to the import hooks from the
    * Interpreter. Open for extension, if someone needs more stuff, but by
    * default this is what is available.
    */
  trait InterpreterInterface{
    def wd: Path
    def loadIvy(coordinates: (String, String, String)*): Either[String, Set[File]]
  }

  /**
    * The result of processing an [[ImportHook]]. Can be either a source-file
    * to evaluate, or additional files/folders/jars to put on the classpath
    */
  sealed trait Result
  object Result{
    case class Source(code: String,
                      wrapper: Name,
                      pkg: Seq[Name],
                      source: ImportHook.Source,
                      imports: Imports,
                      exec: Boolean) extends Result
    case class ClassPath(file: Path, plugin: Boolean) extends Result
  }

  /**
    * Where a script can "come from". Used to resolve relative $file imports
    * relative to the importing script.
    */
  case class Source(path: Path)
  object File extends SourceHook(false)
  object Exec extends SourceHook(true)

  def resolveFiles(tree: ImportTree, currentScriptPath: Path, extensions: Seq[String])
                  : (Seq[(RelPath, Option[String])], Seq[Path], Seq[Path]) = {
    val relative =
      tree.prefix
        .map{case ammonite.util.Util.upPathSegment => up; case x => ammonite.ops.empty/x}
        .reduce(_/_)
    val relativeModules = tree.mappings match{
      case None => Seq(relative -> None)
      case Some(mappings) => for((k, v) <- mappings) yield relative/k -> v
    }
    def relToFile(x: RelPath) = {
      val base = currentScriptPath/up/x/up/x.last
      extensions.find(ext => exists! base/up/(x.last + ext)) match{
        case Some(p) => Right(base/up/(x.last + p): Path)
        case None => Left(base)
      }

    }
    val resolved = relativeModules.map(x => relToFile(x._1))
    val missing = resolved.collect{case Left(p) => p}
    val files = resolved.collect{case Right(p) => p}
    (relativeModules, files, missing)
  }
  class SourceHook(exec: Boolean) extends ImportHook {
    // import $file.foo.Bar, to import the file `foo/Bar.sc`
    def handle(source: ImportHook.Source, tree: ImportTree, interp: InterpreterInterface) = {

      val currentScriptPath = source.path

      val (relativeModules, files, missing) = resolveFiles(
        tree, currentScriptPath, Seq(".sc")
      )

      if (missing.nonEmpty) Left("Cannot resolve $file import: " + missing.mkString(", "))
      else {
        Right(
          for(((relativeModule, rename), filePath) <- relativeModules.zip(files)) yield {
            val (pkg, wrapper) = Util.pathToPackageWrapper(filePath, interp.wd)
            val fullPrefix = pkg ++ Seq(wrapper)

            val importData = Seq(ImportData(
              fullPrefix.last, Name(rename.getOrElse(relativeModule.last)),
              fullPrefix.dropRight(1), ImportData.TermType
            ))

            Result.Source(
              Util.normalizeNewlines(read(filePath)),
              wrapper,
              pkg,
              ImportHook.Source(filePath),
              Imports(importData),
              exec
            )
          }
        )
      }
    }
  }

  object Ivy extends BaseIvy(plugin = false)
  object PluginIvy extends BaseIvy(plugin = true)
  class BaseIvy(plugin: Boolean) extends ImportHook{
    def splitImportTree(tree: ImportTree): Either[String, Seq[String]] = {
      tree match{
        case ImportTree(Seq(part), None, _, _) => Right(Seq(part))
        case ImportTree(Nil, Some(mapping), _, _) if mapping.map(_._2).forall(_.isEmpty) =>
          Right(mapping.map(_._1))
        case _ => Left("Invalid $ivy import " + tree)
      }
    }
    def resolve(interp: InterpreterInterface, signatures: Seq[String]) = {
      val splitted = for (signature <- signatures) yield {
        signature.split(':') match{
          case Array(a, b, c) => Right((a, b, c))
          case Array(a, "", b, c) => Right((a, b + "_" + IvyThing.scalaBinaryVersion, c))
          case _ => Left(signature)
        }
      }
      val errors = splitted.collect{case Left(error) => error}
      val successes = splitted.collect{case Right(v) => v}
      if (errors.nonEmpty) Left("Invalid $ivy imports: " + errors.map("\n\t" + _).mkString)
      else interp.loadIvy(successes: _*)
    }


    def handle(source: ImportHook.Source, tree: ImportTree, interp: InterpreterInterface) = {
      // Avoid for comprehension, which doesn't work in Scala 2.10/2.11
      splitImportTree(tree) match{
        case Right(signatures) => resolve(interp, signatures) match{
          case Right(resolved) =>
            Right(resolved.map(Path(_)).map(Result.ClassPath(_, plugin)).toSeq)
          case Left(l) => Left(l)
        }
        case Left(l) => Left(l)
      }
    }
  }
  object Classpath extends BaseClasspath(plugin = false)
  object PluginClasspath extends BaseClasspath(plugin = true)
  class BaseClasspath(plugin: Boolean) extends ImportHook{
    def handle(source: ImportHook.Source, tree: ImportTree, interp: InterpreterInterface) = {
      val currentScriptPath = source.path
      val (relativeModules, files, missing) = resolveFiles(
        tree, currentScriptPath, Seq(".jar", "")
      )

      if (missing.nonEmpty) Left("Cannot resolve $cp import: " + missing.mkString(", "))
      else Right(
        for(((relativeModule, rename), filePath) <- relativeModules.zip(files))
        yield Result.ClassPath(filePath, plugin)
      )
    }
  }
}