package ammonite.integration

import utest._
import ammonite.ops._
import ammonite.ops.ImplicitWd._
import utest.framework.TestSuite

/**
 * Run a small number of scripts using the Ammonite standalone executable,
 * to make sure that this works. Otherwise it tends to break since the
 * standalone executable has a pretty different classloading environment
 * from the "run in SBT on raw class files" that the rest of the tests use.
 *
 * These are also the only tests that cover all the argument-parsing
 * and configuration logic inside, which the unit tests don't cover since
 * they call the REPL programmatically
 */
object StandaloneTests extends TestSuite{
  // Prepare standalone executable
  val scalaVersion = scala.util.Properties.versionNumberString
  println("StandaloneTests")
  val tests = TestSuite {
    val ammVersion = ammonite.Constants.version
    val executableName = s"ammonite-repl-$ammVersion-$scalaVersion"
    val Seq(executable) = ls.rec! cwd |? (_.last == executableName)
    val replStandaloneResources = cwd/'integration/'src/'test/'resources/'ammonite/'integration
    val shellAmmoniteResources = cwd/'shell/'src/'main/'resources/'ammonite/'shell
    //use Symbol to wrap symbols with dashes.
    val emptyPrefdef = shellAmmoniteResources/"empty-predef.scala"
    val exampleBarePredef = shellAmmoniteResources/"example-predef-bare.scala"

    //we use an empty predef file here to isolate the tests from external forces.
    def exec(name: String, args: String*) = {
      %%bash(
        executable,
        "--predef-file",
        emptyPrefdef,
        replStandaloneResources/name,
        args
      )
    }

    'hello{
      val evaled = exec("Hello.scala")
      assert(evaled.out.trim == "Hello World")
    }

    'complex{
      val evaled = exec("Complex.scala")
      assert(evaled.out.trim.contains("Spire Interval [0, 10]"))
    }

    'shell{
      // make sure you can load the example-predef.scala, have it pull stuff in
      // from ivy, and make use of `cd!` and `wd` inside the executed script.
      val res = %%bash(
        executable,
        "--predef-file",
        exampleBarePredef,
        "-c",
        """val x = wd
          |@
          |cd! 'repl/'src
          |@
          |println(wd relativeTo x)""".stripMargin
      )

      val output = res.out.trim
      assert(output == "repl/src")
    }
    'main{
      val evaled = exec("Main.scala")
      assert(evaled.out.string.contains("Hello! 1"))
    }
    'args{
      'full{
        val evaled = exec("Args.scala", "3", "Moo", (cwd/'omg/'moo).toString)
        assert(evaled.out.string.contains("Hello! MooMooMoo omg/moo."))
      }
      'default{
        val evaled = exec("Args.scala", "3", "Moo")
        assert(evaled.out.string.contains("Hello! MooMooMoo ."))
      }
      // Need a way for `%%` to capture stderr before we can specify these
      // tests a bit more tightly; currently the error just goes to stdout
      // and there's no way to inspect/validate it =/
      'tooFew{
        val errorMsg = intercept[ShelloutException]{
          exec("Args.scala", "3")
        }.result.err.string
        assert(errorMsg.contains("Unspecified value parameter s"))
      }
      'cantParse{
        val errorMsg = intercept[ShelloutException]{
          exec("Args.scala", "foo", "moo")
        }.result.err.string
        assert(errorMsg.contains("Cannot parse value \"foo\" into arg `i: Int`"))
        // Ensure we're properly truncating the random stuff we don't care about
        // which means that the error stack that gets printed is short-ish
        assert(errorMsg.lines.length < 12)

      }
    }
  }
}
