import ammonite.ops._
val scalaVersion = scala.util.Properties.versionNumberString.split("\\.").dropRight(1).mkString(".")
val ammVersion = ammonite.Constants.version
load.jar(cwd/'shell/'target/s"scala-$scalaVersion"/s"ammonite-shell_$scalaVersion-$ammVersion.jar")
@
val sess = ammonite.shell.ShellSession()
import sess._
import ammonite.ops._
import ammonite.shell.PPrints._
ammonite.shell.Configure(repl, wd)
