package ammonite.main

import ammonite.ops._
import ammonite.util.Util
import utest._

/**
  * Tests around Ammonite's CLI handling of main methods, argument parsing,
  * and the associated error behavior if the caller messes up.
 */
object MainTests extends TestSuite{
  def exec(p: RelPath, args: String*) = new InProcessMainMethodRunner('mains/p, Nil, args)
  override def utestTruncateLength = 60000


  def stripInvisibleMargin(s: String) = {
    val lines = s.lines.toArray
    val leftMargin = lines.filter(_.trim.nonEmpty).map(_.takeWhile(_ == ' ').length).min
    lines.map(_.drop(leftMargin)).mkString(Util.newLine)
  }

  val tests = TestSuite {
    println("Running MainTests")

    'hello{
      val evaled = exec("Hello.sc")
      assert(evaled.out.trim == "Hello World")
    }

    // Not really related to main methods, but related since most of the main
    // logic revolves around handling arguments. Make sure this fails properly
    'badAmmoniteFlag{
      val evaled = new InProcessMainMethodRunner('mains/"Hello.sc", List("--doesnt-exist"), Nil)
      assert(!evaled.success)
      val expected = "Unknown Ammonite option: --doesnt-exist"
      assert(evaled.err.toString.contains(expected))
    }
    //make sure scripts with symbols in path names work fine

    'main{
      'single{
        val evaled = exec("Main.sc")
        assert(evaled.success)
        val out = evaled.out
        assert(out.contains("Hello! 1"))
      }
      'multiple{
        'positiveNoArgs{
          val evaled = exec("MultiMain.sc", "mainA")
          assert(evaled.success)
          val out = evaled.out
          assert(out == "Hello! 1" + Util.newLine)
        }
        'positiveArgs{
          val evaled = exec("MultiMainDoc.sc", "functionB", "2", "foo")
          assert(evaled.success)
          val out = evaled.out
          assert(out == "Hello! foofoo ." + Util.newLine)
        }
        'specifyMain{
          val evaled = exec("MultiMain.sc")
          assert(!evaled.success)
          val out = evaled.err
          val expected = stripInvisibleMargin(
            s"""
              Need to specify a subcommand to call when running MultiMain.sc

              Available subcommands:

                mainA

                functionB
                  --i     Int
                  --s     String
                  --path  ammonite.ops.Path (default $pwd)
            """.stripMargin
          )
          assert(out.contains(expected.trim))
        }
        'specifyMainDoc{
          val evaled = exec("MultiMainDoc.sc")
          assert(!evaled.success)
          val out = evaled.err
          val expected = stripInvisibleMargin(
            s"""
              Need to specify a subcommand to call when running MultiMainDoc.sc

              Available subcommands:

                mainA

                functionB
                  --i     Int: how many times to repeat the string to make it very very long,
                          more than it originally was
                  --s     String: the string to repeat
                  --path  ammonite.ops.Path (default $pwd)
            """
          )
          assert(out.contains(expected.trim))
        }
        'cantFindMain{
          val evaled = exec("MultiMainDoc.sc", "doesntExist")
          assert(!evaled.success)
          val out = evaled.err
          val expected = stripInvisibleMargin(
            s"""
              Unable to find subcommand: doesntExist

              Available subcommands:

                mainA

                functionB
                  --i     Int: how many times to repeat the string to make it very very long,
                          more than it originally was
                  --s     String: the string to repeat
                  --path  ammonite.ops.Path (default $pwd)
            """
          )
          assert(out.contains(expected.trim))
        }
      }
    }

    'args{
      'full{
        val evaled = exec("Args.sc", "-i", "3", "--s", "Moo", (pwd/'omg/'moo).toString)
        assert(evaled.success)
        assert(evaled.out == Util.normalizeNewlines("\"Hello! MooMooMoo moo.\"\n"))
      }

      'default{
        val evaled = exec("Args.sc", "3", "Moo")
        assert(evaled.success)
        assert(
          evaled.out == Util.normalizeNewlines("\"Hello! MooMooMoo Ammonite.\"\n") ||
          // For some reason, on windows CI machines the repo gets clone as lowercase (???)
          evaled.out == Util.normalizeNewlines("\"Hello! MooMooMoo ammonite.\"\n")
        )
      }
      'manualPrintln{
        val evaled = exec("Args2.sc", "3", "Moo")
        assert(evaled.success)
        assert(
          evaled.out == Util.normalizeNewlines("Hello! MooMooMoo Ammonite.\n") ||
          // For some reason, on windows CI machines the repo gets clone as lowercase (???)
          evaled.out == Util.normalizeNewlines("Hello! MooMooMoo ammonite.\n")
        )
      }
      val argsUsageMsg =
        s"""
           |Arguments provided did not match expected signature:
           |
           |main
           |  --i     Int
           |  --s     String
           |  --path  ammonite.ops.Path (default $pwd)
           |""".stripMargin
      'tooFew{
        val evaled = exec("Args.sc", "3")
        assert(!evaled.success)

        assert(evaled.err.contains(
          Util.normalizeNewlines(
            s"""Missing argument: (--s: String)
               |$argsUsageMsg""".stripMargin
          )
        ))
      }
      'badHalfFlag{
        // Make sure if someone passes in a flag without a corresponding RHS
        // value, it gets treated as a keyword rather than a dumb parameter
        // and raises an error if it doesn't exist
        val evaled = exec("Args.sc", "3", "lol", "-doesntexist")
        assert(!evaled.success)

        assert(evaled.err.contains(
          Util.normalizeNewlines(
            s"""Unknown argument: "-doesntexist"
               |$argsUsageMsg""".stripMargin
          )
        ))
      }
      'goodHalfFlag{
        // Make sure if someone passes in a flag without a corresponding RHS
        // value, it gets treated as a keyword rather than a dumb parameter
        // and raises an error if it doesn't exist
        val evaled = exec("Args.sc", "3", "-s")
        assert(!evaled.success)

        assert(evaled.err.contains(
          Util.normalizeNewlines(
            s"""Option (--s: String) is missing a corresponding value
               |$argsUsageMsg""".stripMargin
          )
        ))
      }
      'varargs{
        // Make sure varargs are able to slurp up everything, including args
        // which start with `--`. This allows a user to define a main method
        // taking `String*`, slurping up all args un-changed, and then passing
        // them on to their own custom argument parsing code (e.g. scopt)
        val evaled = exec("Varargs.sc",
          // Normal args get fulfilled
          "--i", "31337", "zomg",
          // Make sure single-dash -cow has the single-dash preserved
          "-cow", "--omg",
          // Random non-keyword args get passed straight through
          "bbq",
          // Keyword args that match an earlier argument get passed through too
          "-i", "x",
          // And so do flags without a paired argument
          "--i"
        )

        assert(evaled.success)
        val out = evaled.out
        assert(
          out.contains("31337"),
          out.contains("zomg"),
          out.contains("ArrayBuffer(-cow, --omg, bbq, -i, x, --i)")
        )
      }
      'argsGivenButNoMain{
        val evaled = exec("Hello.sc", "a", "b", "\"")
        assert(!evaled.success)

        assert(evaled.err.contains(
          """Script Hello.sc does not take arguments: "a" "b" "\"""""
        ))
      }
      'tooMany{
        val evaled = exec("Args.sc", "3", "4", "5", "6", "7")
        assert(!evaled.success)

        assert(evaled.err.contains(
          Util.normalizeNewlines(
            s"""Unknown arguments: "6" "7"
               |$argsUsageMsg""".stripMargin
          )
        ))
      }
      'multipleErrors{
        val evaled = exec("Args.sc", "3", "-i", "4", "--unknown", "6")
        assert(!evaled.success)

        assert(evaled.err.contains(
          Util.normalizeNewlines(
            s"""Missing argument: (--s: String)
               |Unknown arguments: "--unknown" "6"
               |Duplicate arguments for (--i: Int): "3" "4"
               |$argsUsageMsg""".stripMargin
          )
        ))
      }
      'cantParse{
        val evaled = exec("Args.sc", "foo", "moo")
        assert(!evaled.success)

        val exMsg = """java.lang.NumberFormatException: For input string: "foo""""
        assert(evaled.err.contains(
          Util.normalizeNewlines(
            s"""The following argument failed to parse:
               |
               |--i: Int = "foo" failed to parse with $exMsg
               |
               |expected signature:
               |
               |main
               |  --i     Int
               |  --s     String
               |  --path  ammonite.ops.Path (default $pwd)
               |""".stripMargin
          )
        ))
        // Ensure we're properly truncating the random stuff we don't care about
        // which means that the error stack that gets printed is short-ish
        assert(evaled.err.lines.length < 20)

      }
    }
  }
}
