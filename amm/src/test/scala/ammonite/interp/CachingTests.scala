package ammonite.interp

import ammonite.TestUtils._
import ammonite.main
import ammonite.main.{Defaults, Scripts}
import ammonite.ops._
import ammonite.runtime.Storage
import ammonite.runtime.tools.IvyConstructor._
import ammonite.util.{Res, Util}
import utest._

object CachingTests extends TestSuite{
  val tests = TestSuite{
    println("ScriptTests")

    val scriptPath = pwd/'amm/'src/'test/'resources/'scripts

    val resourcesPath = pwd/'amm/'src/'test/'resources


    val tempDir = tmp.dir(prefix="ammonite-tester")
    'noAutoIncrementWrapper{
      val storage = Storage.InMemory()
      val interp = createTestInterp(storage)
      Scripts.runScript(pwd, scriptPath/"ThreeBlocks.sc", interp)
      try{
        Class.forName("cmd0")
        assert(false)
      } catch {
        case e: ClassNotFoundException => assert(true)
        case e: Exception => assert(false)
      }
    }
    'blocks{
      def check(fileName: String, expected: Int) = {
        val storage = Storage.InMemory()
        val interp = createTestInterp(storage)
        val n0 = storage.compileCache.size

        assert(n0 == 1) // customLolz predef
        Scripts.runScript(pwd, scriptPath/fileName, interp)

        val n = storage.compileCache.size
        assert(n == expected)

      }
      * - check("OneBlock.sc", 2)
      * - check("TwoBlocks.sc", 3)
      * - check("ThreeBlocks.sc", 4)
    }

    'processModuleCaching{
      def check(script: RelPath){
        val storage = new Storage.Folder(tempDir)

        val interp1 = createTestInterp(
          storage,
          Defaults.predefString
        )

        Scripts.runScript(pwd, resourcesPath/script, interp1)

        assert(interp1.compilerManager.compiler != null)
        val interp2 = createTestInterp(
          storage,
          Defaults.predefString
        )
        assert(interp2.compilerManager.compiler == null)

        Scripts.runScript(pwd, resourcesPath/script, interp2)
        assert(interp2.compilerManager.compiler == null)
      }

      'testOne - check('scriptLevelCaching/"scriptTwo.sc")
      'testTwo - check('scriptLevelCaching/"scriptOne.sc")
      'testThree - check('scriptLevelCaching/"QuickSort.sc")
      'testLoadModule - check('scriptLevelCaching/"testLoadModule.sc")
      'testFileImport - check('scriptLevelCaching/"testFileImport.sc")
      'testIvyImport - check('scriptLevelCaching/"ivyCacheTest.sc")
      'testIvyResource- {
        if (!scala2_12) check('scriptLevelCaching/"ivyCachedResourceTest.sc")
      }

    }

    'testRunTimeExceptionForCachedScripts{
      val storage = new Storage.Folder(tempDir)
      val numFile = pwd/'amm/'target/'test/'resources/'scriptLevelCaching/"num.value"
      rm(numFile)
      write(numFile, "1")
      val interp1 = createTestInterp(
        storage,
        Defaults.predefString
      )

      Scripts.runScript(
        pwd,
        resourcesPath/'scriptLevelCaching/"runTimeExceptions.sc",
        interp1
      )

      val interp2 = createTestInterp(
        storage,
        Defaults.predefString
      )
      val Res.Exception(ex, _) = Scripts.runScript(
        pwd,
        resourcesPath/'scriptLevelCaching/"runTimeExceptions.sc",
        interp2
      )

      assert(
        interp2.compilerManager.compiler == null &&
        ex.toString == "java.lang.ArithmeticException: / by zero"
      )
    }

    'persistence{

      val tempDir = ammonite.ops.Path(
        java.nio.file.Files.createTempDirectory("ammonite-tester-x")
      )

      val interp1 = createTestInterp(new Storage.Folder(tempDir))
      val interp2 = createTestInterp(new Storage.Folder(tempDir))
      Scripts.runScript(pwd, scriptPath/"OneBlock.sc", interp1)
      Scripts.runScript(pwd, scriptPath/"OneBlock.sc", interp2)
      val n1 = interp1.compilationCount
      val n2 = interp2.compilationCount
      assert(n1 == 2) // customLolz predef + OneBlock.sc
      assert(n2 == 0) // both should be cached
    }
    'tags{
      val storage = Storage.InMemory()
      val interp = createTestInterp(storage)
      Scripts.runScript(pwd, scriptPath/"TagBase.sc", interp)
      Scripts.runScript(pwd, scriptPath/"TagPrevCommand.sc", interp)

      interp.loadIvy("com.lihaoyi" %% "scalatags" % "0.6.2")
      Scripts.runScript(pwd, scriptPath/"TagBase.sc", interp)
      val n = storage.compileCache.size
      assert(n == 5) // customLolz predef + two blocks for each loaded file
    }

    'compilerInit{
      val tempDir = ammonite.ops.Path(
        java.nio.file.Files.createTempDirectory("ammonite-tester-x")
      )

      val interp1 = createTestInterp(new Storage.Folder(tempDir))
      val interp2 = createTestInterp(new Storage.Folder(tempDir))

      Scripts.runScript(pwd, scriptPath/"cachedCompilerInit.sc", interp1)
      Scripts.runScript(pwd, scriptPath/"cachedCompilerInit.sc", interp2)
      assert(interp2.compilationCount == 0)
    }

    'changeScriptInvalidation{
      // This makes sure that the compile caches are properly utilized, and
      // flushed, in a variety of circumstances: changes to the number of
      // blocks in the predef, predefs containing magic imports, and changes
      // to the script being run. For each change, the caches should be
      // invalidated, and subsequently a single compile should be enough
      // to re-fill the caches
      val predefFile = tmp("""
        val x = 1337
        @
        val y = x
        import $ivy.`com.lihaoyi::scalatags:0.6.2`, scalatags.Text.all._
        """)
      val scriptFile = tmp("""div("<('.'<)", y).render""")

      def processAndCheckCompiler(f: ammonite.interp.Compiler => Boolean) ={
        val interp = createTestInterp(
          new Storage.Folder(tempDir){
            override val predef = predefFile
          },
          Defaults.predefString
        )
        Scripts.runScript(pwd, scriptFile, interp)
        assert(f(interp.compilerManager.compiler))
      }

      processAndCheckCompiler(_ != null)
      processAndCheckCompiler(_ == null)

      rm! predefFile
      write(
        predefFile,
        """
        import $ivy.`com.lihaoyi::scalatags:0.6.2`; import scalatags.Text.all._
        val y = 31337
        """
      )

      processAndCheckCompiler(_ != null)
      processAndCheckCompiler(_ == null)

      rm! scriptFile
      write(
        scriptFile,
        """div("(>'.')>", y).render"""
      )

      processAndCheckCompiler(_ != null)
      processAndCheckCompiler(_ == null)
    }
    'changeImportedScriptInvalidation{

      val storageFolder = tmp.dir()

      val storage = new Storage.Folder(storageFolder)
      def runScript(script: Path, expectedCount: Int) = {
        val interp = createTestInterp(storage)
        val res = Scripts.runScript(script / up, script, interp, Nil)

        val count = interp.compilationCount
        assert(count == expectedCount)
        res
      }
      def createScript(s: String, dir: Path = null, name: String) = {
        val tmpFile = tmp(s, dir = dir, suffix = name + ".sc", prefix = "script")
        val ident = tmpFile.last.stripSuffix(".sc")
        (tmpFile, ident)
      }
      'simple{


        val (upstream, upstreamIdent) = createScript(
          """println("barr")
            |val x = 1
            |
          """.stripMargin,
          name = "upstream"
        )

        val (downstream, _) = createScript(
          s"""import $$file.$upstreamIdent
            |println("hello11")
            |
            |println($upstreamIdent.x)
          """.stripMargin,
          dir = upstream/up,
          name = "downstream"
        )


        // Upstream, downstream, and hardcoded predef
        runScript(downstream, 3)
        runScript(downstream, 0)
        runScript(downstream, 0)

        // Make sure when we change the upstream code, the downstream script
        // recompiles too
        ammonite.ops.write.over(
          upstream,
          """println("barr")
            |val x = 2
            |
          """.stripMargin
        )

        runScript(downstream, 2)
        runScript(downstream, 0)
        runScript(downstream, 0)

        // But if we change the downstream code, the upstream does *not* recompile
        ammonite.ops.write.over(
          downstream,
          s"""import $$file.$upstreamIdent
             |println("hello")
             |
            |println($upstreamIdent.x)
          """.stripMargin
        )


        runScript(downstream, 1)
        runScript(downstream, 0)
        runScript(downstream, 0)

        // If upstream gets deleted, make sure the $file import fails to resolve
        rm(upstream)

        val Res.Failure(_, msg1) = runScript(downstream, 0)

        assert(msg1.startsWith("Cannot resolve $file import"))
        val Res.Failure(_, msg2) = runScript(downstream, 0)
        assert(msg2.startsWith("Cannot resolve $file import"))

        // And make sure that if the upstream re-appears with the exact same code,
        // the file import starts working again without needing compilation
        ammonite.ops.write.over(
          upstream,
          """println("barr")
            |val x = 2
            |
          """.stripMargin
        )

        runScript(downstream, 0)
        runScript(downstream, 0)

        // But if it gets deleted and re-appears with different contents, both
        // upstream and downstream need to be recompiled
        rm(upstream)

        val Res.Failure(_, msg3) = runScript(downstream, 0)

        assert(msg3.startsWith("Cannot resolve $file import"))

        ammonite.ops.write.over(
          upstream,
          """println("Hohohoho")
            |val x = 2
            |
          """.stripMargin
        )

        runScript(downstream, 2)
        runScript(downstream, 0)
      }

      'diamond{
        val (upstream, upstreamIdent) = createScript(
          """println("uppstreamm")
            |val x = 1
            |
          """.stripMargin,
          name = "upstream"
        )

        val (middleA, middleAIdent) = createScript(
          s"""import $$file.$upstreamIdent
             |println("middleeeeA")
             |val a = $upstreamIdent.x + 1
             |
           """.stripMargin,
          dir = upstream/up,
          name = "middleA"
        )

        val (middleB, middleBIdent) = createScript(
          s"""import $$file.$upstreamIdent
             |println("middleeeeB")
             |val b = $upstreamIdent.x + 2
             |
           """.stripMargin,
          dir = upstream/up,
          name = "middleB"
        )

        val (downstream, _) = createScript(
          s"""import $$file.$middleAIdent
             |import $$file.$middleBIdent
             |println("downstreammm")
             |println($middleAIdent.a + $middleBIdent.b)
          """.stripMargin,
          dir = upstream/up,
          name = "downstream"
        )


        // predefs + upstream + middleA + middleB + downstream
        // ensure we don't compile `upstream` twice when it's depended upon twice
        runScript(downstream, 5)
        runScript(downstream, 0)
        runScript(downstream, 0)

        write.append(downstream, Util.newLine + "val dummy = 1")

        runScript(downstream, 1)
        runScript(downstream, 0)
        runScript(downstream, 0)


        write.append(middleA, Util.newLine + "val dummy = 1")

        // Unfortunately, this currently causes `middleB` to get re-processed
        // too, as it is only evaluated "after" middleA and thus it's
        // processing environment has changed.
        runScript(downstream, 3)
        runScript(downstream, 0)
        runScript(downstream, 0)

        write.append(middleB, Util.newLine + "val dummy = 1")

        runScript(downstream, 2)
        runScript(downstream, 0)
        runScript(downstream, 0)

        write.append(upstream, Util.newLine + "val dummy = 1")

        runScript(downstream, 4)
        runScript(downstream, 0)
        runScript(downstream, 0)
      }
    }
  }
}
