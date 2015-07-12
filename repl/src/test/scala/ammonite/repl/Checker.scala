package ammonite.repl

import ammonite.repl.frontend._
import ammonite.repl.interp.Interpreter
import utest._

/**
 * A test REPL which does not read from stdin or stdout files, but instead lets
 * you feed in lines or sessions programmatically and have it execute them.
 */
class Checker {
  def predef = ""
  var allOutput = ""


  val tempDir = java.nio.file.Files.createTempDirectory("ammonite-tester").toFile

  val interp = new Interpreter(
    Ref[String](""),
    Ref(null),
    pprint.Config.Defaults.PPrintConfig.copy(height = 15),
    Ref(ColorSet.BlackWhite),
    stdout = allOutput += _,
    storage = Ref(Storage(tempDir)),
    predef = predef
  )

  def session(sess: String): Unit = {
    // Remove the margin from the block and break
    // it into blank-line-delimited steps
    val margin = sess.lines.filter(_.trim != "").map(_.takeWhile(_ == ' ').length).min
    val steps = sess.replace("\n" + margin, "\n").split("\n\n")

    for(step <- steps){
      // Break the step into the command lines, starting with @,
      // and the result lines
      val (cmdLines, resultLines) =
        step.lines
            .map(_.drop(margin))
            .partition(_.startsWith("@"))

      val commandText = cmdLines.map(_.stripPrefix("@ ")).toVector

      // Make sure all non-empty, non-complete command-line-fragments
      // are considered incomplete during the parse
      for (incomplete <- commandText.inits.toSeq.drop(1).dropRight(1)){
        assert(Parsers.split(incomplete.mkString("\n")) == None)
      }

      // Finally, actually run the complete command text through the
      // interpreter and make sure the output is what we expect
      val expected = resultLines.mkString("\n").trim
      allOutput += commandText.map("\n@ " + _).mkString("\n")

      val (processed, printed) = run(commandText.mkString("\n"))
      interp.handleOutput(processed)
      if (expected.startsWith("error: ")){
        printed match{
          case Res.Success(v) => assert({v; allOutput; false})
          case Res.Failure(failureMsg) =>
            val expectedStripped =
              expected.stripPrefix("error: ").replaceAll(" *\n", "\n")
            val failureStripped = failureMsg.replaceAll("\u001B\\[[;\\d]*m", "").replaceAll(" *\n", "\n")
            failLoudly(assert(failureStripped.contains(expectedStripped)))
        }
      }else{
        if (expected != "")
          failLoudly(assert(printed == Res.Success(expected)))
      }
    }
  }

  def run(input: String) = {
//    println("RUNNING")
//    println(input)
//    print(".")
    val msg = collection.mutable.Buffer.empty[String]
    val processed = interp.processLine(input, Parsers.split(input).get.get.value, _.foreach(msg.append(_)))
    val printed = processed.map(_ => msg.mkString)

    interp.handleOutput(processed)
    (processed, printed)
  }


  def fail(input: String,
           failureCheck: String => Boolean = _ => true) = {
    val (processed, printed) = run(input)

    printed match{
      case Res.Success(v) => assert({v; allOutput; false})
      case Res.Failure(s) =>

        failLoudly(assert(failureCheck(s)))
    }
  }

  def result(input: String, expected: Res[Evaluated]) = {
    val (processed, printed) = run(input)
    assert(processed == expected)
  }
  def failLoudly[T](t: => T) =
    try t
    catch{ case e: utest.AssertionError =>
      println("FAILURE TRACE\n" + allOutput)
      throw e
    }

}
