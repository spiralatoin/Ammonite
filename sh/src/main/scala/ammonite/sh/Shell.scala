package ammonite.sh

import java.io.{OutputStream, InputStream, PrintWriter, StringWriter}
import java.lang.reflect.InvocationTargetException

import ammonite.sh.eval.{Evaluator, Preprocessor, Compiler}
import jline.console.ConsoleReader
import acyclic.file

import scala.annotation.tailrec
import scala.collection.mutable
import scala.reflect.io.VirtualDirectory
import scala.util.Try

class Shell(){
  val dynamicClasspath = new VirtualDirectory("(memory)", None)
  val compiler = new Compiler(dynamicClasspath)
  val mainThread = Thread.currentThread()
  val preprocess = new Preprocessor
  val term = new jline.UnixTerminal()
  term.init()

  val eval = new Evaluator(
    mainThread.getContextClassLoader,
    preprocess.apply,
    compiler.compile
  )

  def action(reader: ConsoleReader): Result[(String, String, String)] = for {
    _ <- Signaller("INT", () => println("Ctrl-D to Exit"))

    _ <- Catching { case x: Throwable =>
      val sw = new StringWriter()
      x.printStackTrace(new PrintWriter(sw))

      sw.toString + "\n" +
        "Something unexpected went wrong =("
    }

    res <- Option(reader.readLine(Console.MAGENTA + "scala> " + Console.RESET))
                          .map(Result.Success(_)).getOrElse(Result.Exit)
    out <- processLine(res)
  } yield out

  def processLine(line: String) = for {
    _ <- Signaller("INT", () => mainThread.stop())
  } yield eval.processLine(line)


  def run(input: InputStream, output: OutputStream) = {
    val reader = new ConsoleReader(input, output, term)
    @tailrec def loop(): Unit = {
      val r = action(reader)
      eval.update(r)
      r match{
        case Result.Exit => reader.println("Bye!")
        case Result.Success((msg, importKeys, imports)) =>
          reader.println(msg)
          loop()
        case Result.Failure(msg) =>
          reader.println(Console.RED + msg + Console.RESET)
          loop()
      }
    }
    loop()
  }
}

object Shell{
  def main(args: Array[String]) = {
    val shell = new Shell()
    shell.run(System.in, System.out)
  }
  import scala.reflect.runtime.universe._
  def typeString[T: TypeTag](t: => T) = typeOf[T].toString

}