package ammonite.shell

import ammonite.ops._
/**
 * Convenience entry-point useful to kick off a shell with
 */
object TestMain {
  val examplePredef = "shell/src/main/resources/ammonite/shell/example-predef-bare.sc"
  def main(args: Array[String]): Unit = {
    System.setProperty("ammonite-sbt-build", "true")
    ammonite.Main.main(args ++ Array(
      "--home", "target/tempAmmoniteHome",
      "--predef-file", examplePredef
    ))
  }
}
