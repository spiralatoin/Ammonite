package ammonite.terminal

import java.io.{OutputStreamWriter, InputStreamReader, OutputStream, InputStream}

import scala.annotation.tailrec
import scala.collection.mutable

/**
 * The core logic around a terminal; it defines the base `filters` API
 * through which anything (including basic cursor-navigation and typing)
 * interacts with the terminal.
 *
 * Maintains basic invariants, such as "cursor should always be within
 * the buffer", and "ansi terminal should reflect most up to date TermState"
 */
object TermCore {
  /**
   * Computes how tall a line of text is when wrapped at `width`.
   *
   * Even 0-character lines still take up one row!
   *
   * width = 2
   * 0 -> 1
   * 1 -> 1
   * 2 -> 1
   * 3 -> 2
   * 4 -> 2
   * 5 -> 3
   */
  def fragHeight(length: Int, width: Int) = math.max(1, (length - 1) / width + 1)

  def splitBuffer(buffer: Vector[Char]) = {
    val frags = mutable.Buffer.empty[Int]
    frags.append(0)
    for(c <- buffer){
      if (c == '\n') frags.append(0)
      else frags(frags.length - 1) = frags(frags.length - 1) + 1
    }
    frags
  }
  def calculateHeight(buffer: Vector[Char],
                      cursor: Int,
                      width: Int,
                      prompt: String): (Int, Int, Int) = {
    val rowLengths = splitBuffer(buffer)

    calculateHeight0(rowLengths, cursor, width - prompt.length)
  }

  def findChunk() = {

  }
  /**
   * Given a buffer with characters and newlines, calculates how high
   * the buffer is and where the cursor goes inside of it.
   */
  def calculateHeight0(rowLengths: Seq[Int],
                       cursor: Int,
                       width: Int): (Int, Int, Int) = {
    val fragHeights =
      rowLengths
        .inits
        .toVector
        .reverse // We want shortest-to-longest, inits gives longest-to-shortest
        .filter(_.nonEmpty) // Without the first empty prefix
        .map{ x =>
          fragHeight(
            x.last + (if (x.sum + x.length - 1 == cursor) 1 else 0),
            width
          )
        }
//    Debug("fragHeights " + fragHeights)
    val totalHeight = fragHeights.sum

    var leftoverCursor = cursor
//    Debug("leftoverCursor " + leftoverCursor)
    var totalPreHeight = 0
    var done = false
    // Don't check if the cursor exceeds the last chunk, because
    // even if it does there's nowhere else for it to go
    for(i <- 0 until rowLengths.length -1 if !done) {
      val delta = rowLengths(i) + (if (i == rowLengths.length - 1) 0 else 1) // length of frag and the '\n' after it
//      Debug("delta " + delta)
      val nextCursor = leftoverCursor - delta
      if (nextCursor >= 0) {
//        Debug("nextCursor " + nextCursor)
        leftoverCursor = nextCursor
        totalPreHeight += fragHeights(i)
      }else done = true
    }
//    Debug("totalPreHeight " + totalPreHeight)
//    Debug("leftoverCursor " + leftoverCursor)
//    Debug("width " + width)
    val cursorY = totalPreHeight + leftoverCursor / width
    val cursorX = leftoverCursor % width
    (totalHeight, cursorY, cursorX)
  }

  type Filter = PartialFunction[TermInfo, TermAction]

  /**
   * Blockingly reads a line from the given input stream and returns it.
   *
   * @param prompt The prompt to display when requesting input
   * @param input The input-stream where characters come in, e.g. System.in
   * @param output The output-stream where print-outs go, e.g. System.out
   * @param filters A set of actions that can be taken depending on the input,
   *                to manipulate the internal state of the terminal.
   * @param displayTransform code to manipulate the display of the buffer and
   *                         cursor, without actually changing the logical
   *                         values inside them.
   */
  def readLine(prompt: String,
               input: InputStream,
               output: OutputStream,
               filters: PartialFunction[TermInfo, TermAction] = PartialFunction.empty,
               displayTransform: (Vector[Char], Int) => (Vector[Char], Int) = (x, i) => (x, i))
               : Option[String] = {

    val ansiRegex = "\u001B\\[[;\\d]*m".r
    val noAnsiPrompt = prompt.replaceAll(ansiRegex.regex, "")
    def redrawLine(buffer: Vector[Char], cursor: Int) = {
      ansi.restore()
      ansi.clearScreen(0)
//      Debug("CURSOR " + cursor)
      val (transformedBuffer, transformedCursor) = displayTransform(buffer, cursor)
//      Debug("TransCURSOR " + transformedCursor)
      writer.write(prompt)
      var i = 0
      var currWidth = 0
      while(i < transformedBuffer.length){
        if (currWidth >= width - noAnsiPrompt.length){
          writer.write(" " * noAnsiPrompt.length)
          currWidth = 0
        }

        ansiRegex.findPrefixMatchOf(transformedBuffer.drop(i)) match{
          case Some(m) =>
            Debug("Some(m) " + m.source + " | " + m.source.length)
            writer.write(transformedBuffer.slice(i, i + m.end).toArray)
            i += m.end
          case None =>
            Debug("None")
            writer.write(transformedBuffer(i))
            if (transformedBuffer(i) == '\n') currWidth += 9999
            currWidth += 1
            i += 1
        }
      }

      ansi.restore()
      val (nextHeight, cursorY, cursorX) = calculateHeight(buffer, cursor, width, noAnsiPrompt)
//      Debug("DOWN " + cursorY)
//      Debug("RIGHT " + cursorX)
      ansi.down(cursorY)
      ansi.right(cursorX)
      ansi.right(noAnsiPrompt.length)
      writer.flush()
    }


    @tailrec def rec(lastState: TermState, areaHeight: Int): Option[String] = {
      redrawLine(lastState.buffer, lastState.cursor)
      filters(TermInfo(lastState, width - prompt.length)) match {
        case TermState(s, b, c) =>
          val newCursor = math.max(math.min(c, b.length), 0)

          val (nextHeight, cursorY, cursorX) = calculateHeight(b, newCursor, width, noAnsiPrompt)
//          Debug("nextHeight " + nextHeight)
          if (nextHeight > areaHeight) {
//            Debug(s"New Height $areaHeight -> $nextHeight")
            // Print enough lines to ensure there's enough space for
            // everything at the new height. By printing, we get the
            // correct behavior of scrolling the page up (if at the
            // bottom of the screen) or moving the cursor down (if not
            // at the bottom). We then move back to the start of our area
            ansi.restore()
            writer.write("\n" * (nextHeight - 1))
            writer.flush()
            ansi.up(nextHeight - 1)
            ansi.save()
          }
          rec(TermState(s, b, newCursor), nextHeight)
        case Result(s) =>
          redrawLine(lastState.buffer, lastState.buffer.length)
          writer.write(10)
          writer.write(13)
          writer.flush()
          Some(s)
        case Exit =>
          None
      }
    }
    lazy val reader = new InputStreamReader(input)
    lazy val writer = new OutputStreamWriter(output)
    lazy val ansi = new Ansi(writer)
    lazy val (width, height, initialConfig) = TTY.init()
    try {
      ansi.save()
      rec(TermState(LazyList.continually(reader.read()), Vector.empty, 0), 1)
    }finally{
      // Don't close these! Closing these closes stdin/stdout,
      // which seems to kill the entire program

      // reader.close()
      // writer.close()
      TTY.stty(initialConfig)
    }
  }
}

case class TermInfo(ts: TermState, width: Int)

sealed trait TermAction
case class TermState(inputs: LazyList[Int], buffer: Vector[Char], cursor: Int) extends TermAction
object TermState{
  def unapply(ti: TermInfo): Option[(LazyList[Int], Vector[Char], Int)] = {
    TermState.unapply(ti.ts)
  }
  def unapply(ti: TermAction): Option[(LazyList[Int], Vector[Char], Int)] = ti match{
    case ts: TermState => TermState.unapply(ts)
    case _ => None
  }

}
case object Exit extends TermAction
case class Result(s: String) extends TermAction
