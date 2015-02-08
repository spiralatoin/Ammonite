package test.ammonite

import ammonite.all._
import ammonite.ops.{Pipeable, FilterMapExt}

import utest._

import scala.collection.generic.{SeqFactory, GenericTraversableTemplate}
import scala.collection.{TraversableLike, mutable}

object ExampleTests extends TestSuite{

  val tests = TestSuite {
    var wd = processWorkingDir
    'cd{
      // cd /usr/local/bin/
      wd /= 'user/'local/'bin

      assert(wd - processWorkingDir == 'user/'local/'bin)
    }
    'findWc{
      // find . -name '*.txt' | xargs wc -l
      wd /= 'core/'src/'test/'resources/'testdata

      val lines = ls.rec(wd) |? (_.ext == "txt") | read.lines | (_.length) sum

      assert(lines == 14)
    }
    'addUpScalaSize{
      ls.rec! processWorkingDir |? (_.ext == "scala") | (_.size) |& (_ + _)
    }
    'concatAll{
      ls.rec! wd |? (_.ext == "scala") | read |> write! wd/'target/'test/"omg.txt"
    }

    'rename{
//      val d1/"omg"/x1 = wd
//      val d2/"omg"/x2 = wd
//      ls! wd |? (_.ext == "scala") | (x => mv! x ! x.pref)
    }
    'allSubpathsResolveCorrectly{
      for(abs <- ls.rec! wd){
        val rel = abs - wd
        assert(rel.ups == 0)
        assert(wd / rel == abs)
      }
    }

    'grep{
      import ammonite.pprint.Config.Defaults._
      val items = Seq(123, 456, 789)
      items |? grep! "45"
      items |? grep! "^[123456]+$".r
      assert(
        (items |? grep! "45") == Seq(456),
        (items |? grep! "45".r) == Seq(456),
        (items |? grep! "[123456]+".r) == Seq(123, 456),
        (items |? grep! "^[123456]+$".r) == Seq(123, 456),
        (items |? grep! "[123456]".r) == Seq(123, 456),
        (items |? grep! "^[123456]$".r) == Seq()
      )
    }
    'pprint{
      import ammonite.pprint.Config.Defaults._

      assert(
        ammonite.pprint.PPrint(root/'hello/'world) == "root/'hello/'world",
        ammonite.pprint.PPrint('hello/'world) == "'hello/'world",
        ammonite.pprint.PPrint(empty/'world) == "empty/'world",
        ammonite.pprint.PPrint(empty/'hello/'world) == "'hello/'world",
        ammonite.pprint.PPrint(empty/"hello world") == "empty/\"hello world\""
      )
      implicit val wd = root/'hello

      assert(
        ammonite.pprint.PPrint(root/'hello/'world/"whee.txt") == "wd/'world/\"whee.txt\"",
        ammonite.pprint.PPrint(root/'hello/'world) == "wd/'world",
        ammonite.pprint.PPrint(root/'hello) == "wd",
        ammonite.pprint.PPrint(root) == "wd/up"
      )
    }
  }
}
