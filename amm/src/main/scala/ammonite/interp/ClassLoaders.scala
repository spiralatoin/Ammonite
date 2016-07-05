package ammonite.interp

import java.net.{URL, URLClassLoader}
import java.nio.{ByteBuffer, file}

import ammonite.ops._
import ammonite.util.{Imports, Util}

import scala.collection.mutable




/**
  * Represents a single "frame" of the `sess.save`/`sess.load` stack/tree.
  *
  * Exposes `imports` and `classpath` as readable but only writable
  * in particular ways: `imports` can only be updated via `mergeImports`,
  * while `classpath` can only be added to.
  */
class Frame(val classloader: SpecialClassLoader,
            val pluginClassloader: SpecialClassLoader,
            private[this] var imports0: Imports,
            private[this] var classpath0: Seq[java.io.File]){
  def imports = imports0
  def classpath = classpath0
  def addImports(additional: Imports) = {
    imports0 = imports0 ++ additional
  }
  def addClasspath(additional: Seq[java.io.File]) = {
    classpath0 = classpath0 ++ additional
  }
}

object SpecialClassLoader{
  val simpleNameRegex = "[a-zA-Z0-9_]+".r

  /**
    * Stats all loose class-files in the current classpath that could
    * conceivably be part of some package, i.e. their directory path
    * doesn't contain any non-package-identifier segments, and aggregates
    * their names and mtimes as a "signature" of the current classpath
    */
  def initialClasspathHash(classloader: ClassLoader): Array[Byte] = {
    val allClassloaders = {
      val all = mutable.Buffer.empty[ClassLoader]
      var current = classloader
      while(current != null){
        all.append(current)
        current = current.getParent
      }
      all
    }

    def findMtimes(d: file.Path): Seq[(Path, Long)] = {
      def skipSuspicious(path: Path) = {
        simpleNameRegex.findPrefixOf(path.last) == Some(path.last)
      }
      ls.rec(skip = skipSuspicious)! Path(d) | (x => (x, x.mtime.toMillis))
    }

    val classpathFolders =
      allClassloaders.collect{case cl: java.net.URLClassLoader => cl.getURLs}
                     .flatten
                     .filter(_.getProtocol == "file")
                     .map(_.toURI)
                     .map(java.nio.file.Paths.get)
                     .filter(java.nio.file.Files.isDirectory(_))

    val classFileMtimes = classpathFolders.flatMap(f => findMtimes(f))

    val hashes = classFileMtimes.map{ case (name, mtime) =>
      Util.md5Hash(Iterator(
        name.toString.getBytes,
        (0 until 64 by 8).iterator.map(mtime >> _).map(_.toByte).toArray
      ))
    }
    Util.md5Hash(hashes.iterator)
  }
}
/**
  * Classloader used to implement the jar-downloading
  * command-evaluating logic in Ammonite.
  *
  * http://stackoverflow.com/questions/3544614/how-is-the-control-flow-to-findclass-of
  */
class SpecialClassLoader(parent: ClassLoader, parentHash: Array[Byte])
  extends URLClassLoader(Array(), parent){

  /**
    * Files which have been compiled, stored so that our special
    * classloader can get at them.
    */
  val newFileDict = mutable.Map.empty[String, Array[Byte]]
  def findClassPublic(name: String) = findClass(name)
  val specialLocalClasses = Set(
    "ammonite.frontend.ReplBridge",
    "ammonite.frontend.ReplBridge$"
  )
  override def findClass(name: String): Class[_] = {
    val loadedClass = this.findLoadedClass(name)
    if (loadedClass != null) loadedClass
    else if (newFileDict.contains(name)) {
      val bytes = newFileDict(name)
      defineClass(name, bytes, 0, bytes.length)
    }else if (specialLocalClasses(name)) {
      import ammonite.ops._
      val bytes = read.bytes(
        this.getResourceAsStream(name.replace('.', '/') + ".class")
      )

      defineClass(name, bytes, 0, bytes.length)
    } else super.findClass(name)
  }
  def add(url: URL) = {
    classpathSignature0 = Util.md5Hash(Iterator(classpathSignature0, jarSignature(url)))
    addURL(url)
  }

  private def jarSignature(url: URL) = {
    val buffer = ByteBuffer.allocate(8)
    buffer.putLong(Path(url.getFile, root).mtime.toMillis)
    buffer.array() ++ url.getFile.getBytes
  }

  def initialClasspathHash = parentHash
  private[this] var classpathSignature0 = initialClasspathHash
  def classpathSignature: Array[Byte] = classpathSignature0
  def allJars: Seq[URL] = {
    this.getURLs ++ ( parent match{
      case t: SpecialClassLoader => t.allJars
      case _ => Nil
    })
  }
}
