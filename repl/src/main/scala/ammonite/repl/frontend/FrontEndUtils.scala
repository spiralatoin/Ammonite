package ammonite.repl.frontend

import java.io.OutputStreamWriter

import ammonite.terminal.LazyList

import scala.annotation.tailrec

/**
 * Created by haoyi on 8/29/15.
 */
object FrontEndUtils {
  def width = ammonite.terminal.TTY.consoleDim("cols")
  def height = ammonite.terminal.TTY.consoleDim("lines")
  def tabulate(snippets: Seq[String], width: Int) = {
    val gap = 2
    val maxLength = snippets.maxBy(_.replaceAll("\u001B\\[[;\\d]*m", "").length).length + gap
    val columns = math.max(1, width / maxLength)

    snippets.grouped(columns).flatMap{
      case first :+ last => first.map(_.padTo(width / columns, ' ')) :+ last :+ "\n"
    }
  }

  @tailrec def findPrefix(strings: Seq[String], i: Int = 0): String = {
    if (strings.count(_.length > i) == 0) strings(0).take(i)
    else if(strings.map(_(i)).distinct.length > 1) strings(0).take(i)
    else findPrefix(strings, i + 1)
  }

  def printCompletions(completions: Seq[String],
                       details: Seq[String],
                       writer: OutputStreamWriter,
                       rest: LazyList[Int],
                       b: Vector[Char],
                       c: Int) = {
    // If we find nothing, we find nothing
    if (completions.length != 0 || details.length != 0) {
      writer.write("\n")
      details.foreach(writer.write)
      writer.write("\n")
    }

    writer.flush()
    // If we find no completions, we've already printed the details to abort
    if (completions.length != 0){
      FrontEndUtils.tabulate(completions, FrontEndUtils.width).foreach(writer.write)
      writer.flush()
    }
  }

}
