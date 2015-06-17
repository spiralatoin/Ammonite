package ammonite.repl

import acyclic.file
import java.io.{File, FileInputStream, IOException, FileWriter}
import org.yaml.snakeyaml.Yaml
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions.asScalaBuffer
import ammonite.pprint

trait Storage{
  
  def loadHistory: History

  def saveHistory(h: History): Unit
}

object Storage{
  val defaultPath = new java.io.File(System.getProperty("user.home") + "/.ammonite") 
  def apply(dir: File = defaultPath) = new Storage{
  
    if(dir.exists){
      if(!dir.isDirectory){
        dir.delete()
        dir.mkdir()
      }
    } else {
      dir.mkdir()
    }

    def loadHistory: History = {
      val yaml = new Yaml
      val res = new History
      try{
        val list = yaml.load(new FileInputStream(dir + "/history"))
        list match {
          case a: java.util.List[String] => res ++= a
          case _ =>
        }
        res
      } catch {
        case e: IOException => new History
      }
    }

    def saveHistory(h: History): Unit = {
      val yaml = new Yaml
      val fw = new FileWriter(dir + "/history")
      yaml.dump(h.toArray, fw)
    }
  }

}

class History extends ArrayBuffer[String]{
  def last(lines: Int) = {
    drop(length - lines)
  }
}

object History{
  import pprint._
  implicit def historyPPrint(implicit c: Config): PPrint[History] = new PPrint(
    new PPrinter[History]{
      def render(t: History, c: Config)={
        val seq = "\n...\n" +: t.last(c.lines()).flatMap{ code => Seq("@ ", code, "\n") }
        seq.iterator
      }
    },
    c
  )
}

