package ammonite


package object ops extends Extensions with RelPathStuff{
  implicit val postfixOps = scala.language.postfixOps

  /**
   * The root of the filesystem
   */
  val root = ops.Path.root

  /**
   * The user's home directory
   */
  val home = Path(System.getProperty("user.home"))

  /**
   * The current working directory for this process.
   */
  lazy val cwd = ops.Path(new java.io.File(""))

  /**
    * If you want to call subprocesses using [[%]] or [[%%]] and don't care
    * what working directory they use, import this via
    *
    * `import ammonite.ops.ImplicitWd._`
    *
    * To make them use the process's working directory for each subprocess
    */
  object ImplicitWd{
    implicit lazy val implicitCwd = ops.cwd
  }

  implicit class Transformable1(p: java.nio.file.Path){
    def amm = {
      import collection.JavaConversions._
      if (p.toAbsolutePath.iterator().size == p.iterator().size) ops.Path(p)
      else ops.RelPath(p)
    }
  }

  /**
    * Extractor to let you easily pattern match on [[ops.Path]]s. Lets you do
    *
    * {{{
    *   @ val base/segment/filename = cwd
    *   base: Path = Path(Vector("Users", "haoyi", "Dropbox (Personal)"))
    *   segment: String = "Workspace"
    *   filename: String = "Ammonite"
    * }}}
    *
    * To break apart a path and extract various pieces of it.
    */
  object /{
    def unapply[T <: BasePath[T]](p: T): Option[(T, String)] = {
      if (p.segments.length > 0)
        Some((p / up, p.last))
      else None
    }
  }

  /**
    * Lets you treat any path as a file, letting you access any property you'd
    * normally access through [[stat]]-ing it by [[stat]]-ing the file for you
    * when necessary.
    */
  implicit def fileData(p: Path): stat.full = stat.full(p)

  /**
    * Used to spawn a subprocess interactively; any output gets printed to the
    * console and any input gets requested from the current console. Can be
    * used to run interactive subprocesses like `%vim`, `%python`,
    * `%ssh "www.google.com"` or `%sbt`.
    */
  val % = new Command(Vector.empty, Map.empty, Shellout.executeInteractive)
  /**
    * Spawns a subprocess non-interactively, waiting for it to complete and
    * collecting all output into a [[CommandResult]] which exposes it in a
    * convenient form. Call via `%%('whoami).out.trim` or
    * `%%('git, 'commit, "-am", "Hello!").exitCode`
    */
  val %% = new Command(Vector.empty, Map.empty, Shellout.executeStream)
}
