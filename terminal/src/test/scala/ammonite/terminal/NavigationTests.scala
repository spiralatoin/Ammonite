package ammonite.terminal

import utest._


object NavigationTests extends TestSuite{

  val tests = TestSuite{
    'simple{
      // Tests for a simple, not-wrap-around
      // grid of characters
      val check = Checker(
        width = 5,
        """
          abcd
          e_fgh
          ijkl
        """

      )

      import check._

      'noop - check(
        """
        abcd
        e_gh
        ijkl
        """,
        (g, v) => (g, v)
      )

      'upsAndDowns{

        'down - check(
          """
          abcd
          efgh
          i_kl
          """,
          down
        )
        'up - check(
          """
          a_cd
          efgh
          ijkl
          """,
          up
        )
        'updown - check(
          """
          abcd
          e_gh
          ijkl
          """,
          up, down
        )
        'upup - check(
          """
          _bcd
          efgh
          ijkl
          """,
          up, up
        )
        'downdown- check(
          """
          abcd
          efgh
          ijkl_
          """,
          down, down
        )
        'upupdown - check(
          """
          abcd
          _fgh
          ijkl
          """,
          up, up, down
        )
        'downdownup - check(
          """
          abcd
          efgh_
          ijkl
          """,
          down, down, up
        )
      }
      'startEnd{
        'end - check(
          """
          abcd
          efgh_
          ijkl
          """,
          end
        )
        'start - check(
          """
          abcd
          _fgh
          ijkl
          """,
          home
        )
      }
    }
    'jagged{
      // tests where the lines of characters
      // are of uneven lengths
      val check = Checker(
        width = 10,
        """
          abcdefg
          hijk
          lm_nopqr
          s
          tuvwxyz
        """
      )

      import check._

      'truncate - check(
        """
        abcdefg
        hijk
        lmnopqr
        s
        t_vwxyz
        """,
        down, down
      )
      'truncateBackUp - check(
        """
        abcdefg
        hijk
        l_nopqr
        s
        tuvwxyz
        """,
        down, down, up, up
      )
      'upup- check(
        """
        ab_defg
        hijk
        lmnopqr
        s
        tuvwxyz
        """,
        up, up
      )
      'endup- check(
        """
        abcdefg
        hijk_
        lmnopqr
        s
        tuvwxyz
        """,
        end, up
      )
    }
    'wrapping{
      // tests where some lines are so long that they start
      // wrapping onto the next ones. Navigating around they
      // should behave like separate lines
      val check = Checker(
        width = 7,
        """
          abcdefg\
          hijk
          l_mnopqr\
          s
          tuvwxyz
        """
      )
      import check._
      'updown{
        * - {
          check
            .run(up)
            .check(
              """
              abcdefg\
              h_jk
              lmnopqr\
              s
              tuvwxyz
              """
            )
            .run(up)
            .check(
              """
              a_cdefg\
              hijk
              lmnopqr\
              s
              tuvwxyz
              """
            )
        }
        * - {
          check
          .run(down)
          .check(
            """
            abcdefg\
            hijk
            lmnopqr\
            s_
            tuvwxyz
            """
          )
          .run(down)
          .check(
            """
            abcdefg\
            hijk
            lmnopqr\
            s
            t_vwxyz
            """
          )
        }

      }
      'startend{

        * - check(
          """
          abcdefg\
          hijk
          lmnopqr\
          _
          tuvwxyz
          """,
          end
        )
        * - check(
          """
          abcdefg\
          hijk
          _mnopqr\
          s
          tuvwxyz
          """,
          home
        )
        * - check(
          """
          abcdefg\
          _ijk
          lmnopqr\
          s
          tuvwxyz
          """,
          up, home
        )
        * - check(
          """
          abcdefg\
          _ijk
          lmnopqr\
          s
          tuvwxyz
          """,
          up, home, home, home
        )
        * - check(
          """
          abcdefg\
          _ijk
          lmnopqr\
          s
          tuvwxyz
          """,
          up, up, end
        )
      }
    }
    'wordnav{
      // Tests of word-by-word navigation
      val check = Checker(
        width = 10,
        """
          s.dropPref\
          ix(
            b_ase.map\
          (x.toInt)
          )
        """
      )
      import check._

      * - {
        check
          .run(wordLeft)
          .check(
            """
            s.dropPref\
            ix(
              _ase.map\
            (x.toInt)
            )
            """
          )
          .run(wordRight)
          .check(
            """
            s.dropPref\
            ix(
              base_map\
            (x.toInt)
            )
            """
          )
      }
      * - {
        check
          .run(wordLeft, wordLeft)
          .check(
            """
            s._ropPref\
            ix(
              base.map\
            (x.toInt)
            )
            """
          )
          .run(wordLeft)
          .check(
            """
            _.dropPref\
            ix(
              base.map\
            (x.toInt)
            )
            """
          )
          .run(wordLeft)
          .check(
            """
            _.dropPref\
            ix(
              base.map\
            (x.toInt)
            )
            """
          )
      }

      * - {
        check
          .run(wordRight)
          .check(
            """
            s.dropPref\
            ix(
              base_map\
            (x.toInt)
            )
            """
          )
          .run(wordRight)
          .check(
            """
            s.dropPref\
            ix(
              base.map\
            _x.toInt)
            )
            """
          )
          .run(wordRight)
          .check(
            """
            s.dropPref\
            ix(
              base.map\
            (x_toInt)
            )
            """
          )
          .run(wordRight)
          .check(
            """
            s.dropPref\
            ix(
              base.map\
            (x.toInt_
            )
            """
          )
          .run(wordRight)
          .check(
            """
            s.dropPref\
            ix(
              base.map\
            (x.toInt)
            )_
            """
          )
      }
    }

  }
}
