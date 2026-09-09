package net.stho.photos.library

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import net.stho.photos.IgnoreRulesUnreadableFailure

/**
 * `.photosignore` parsing and matching.
 *
 * This is the code that decides what may be uploaded, so the glob semantics are asserted case by
 * case rather than sampled: a rule that matches more than it should leaves photographs on the
 * laptop, and one that matches less puts a photo manager's trash in the zone.
 */
class IgnoreRulesTest {

    @AfterTest
    fun cleanUp(): Unit = deleteTemporaryDirectories()

    @Test
    fun parsingSkipsCommentsBlanksAndSurroundingSpace() {
        val rules = IgnoreRules.parse(
            """
            # a comment

            *.db
                THUMB~*.DBE
            # another
            """.trimIndent(),
        )

        assertEquals(2, rules.rules.size)
        assertEquals(listOf("*.db", "THUMB~*.DBE"), rules.rules.map(IgnoreRule::source))
        // Line numbers are kept so an unused-rule report can point at the line.
        assertEquals(listOf(3, 4), rules.rules.map(IgnoreRule::line))
    }

    @Test
    fun aTrailingSlashMarksADirectoryRule() {
        val rules = IgnoreRules.parse(".dtrash/\n*.db")

        assertTrue(rules.rules[0].isDirectory)
        assertFalse(rules.rules[1].isDirectory)
        assertEquals(0, rules.matchIndex(".dtrash", ".dtrash", isDirectory = true))
        // A directory rule must not exclude a *file* of the same name.
        assertNull(rules.matchIndex(".dtrash", ".dtrash", isDirectory = false))
    }

    @Test
    fun aPatternWithoutASlashMatchesTheNameAtAnyDepth() {
        val rules = IgnoreRules.parse("*.db")

        assertEquals(0, rules.matchIndex("digikam4.db", "digikam4.db", isDirectory = false))
        assertEquals(
            0,
            rules.matchIndex("stray.db", "Album/Sub/stray.db", isDirectory = false),
            "FNM_PATHNAME stops * crossing /, so the name must be matched explicitly",
        )
    }

    @Test
    fun aPatternWithASlashIsAnchoredToTheLibraryRoot() {
        val rules = IgnoreRules.parse("Neuseeland/akaroa.psd")

        assertEquals(0, rules.matchIndex("akaroa.psd", "Neuseeland/akaroa.psd", isDirectory = false))
        // The same name elsewhere is not excluded — that is the point of anchoring.
        assertNull(rules.matchIndex("akaroa.psd", "Kalifornien/akaroa.psd", isDirectory = false))
    }

    @Test
    fun matchingIgnoresCase() {
        val rules = IgnoreRules.parse("thumb~*.dbe")

        assertEquals(0, rules.matchIndex("THUMB~JT.DBE", "A/THUMB~JT.DBE", isDirectory = false))
    }

    /**
     * Composition folding is gone from this project, so a decomposed pattern is a different
     * pattern.
     *
     * Normalising both sides to NFC would cover a pattern typed on a Mac, which commonly arrives
     * NFD. Measured on the real library, 34,729 entries carry 51 non-ASCII names and none of them
     * is decomposed — so the rule would buy nothing and make "why does this pattern not match?"
     * unanswerable by looking at the two strings. Names are compared exactly as the filesystem
     * gives them.
     */
    @Test
    fun compositionIsNotFolded() {
        // Written as escapes rather than as literals, because the difference is invisible in an
        // editor and this test is about exactly that difference.
        val name = "Hochf\u00FCgen 2004"

        val decomposed = IgnoreRules.parse("Hochfu\u0308gen*")
        assertNull(decomposed.matchIndex(name, name, isDirectory = true))

        val precomposed = IgnoreRules.parse("Hochf\u00FCgen*")
        assertEquals(0, precomposed.matchIndex(name, name, isDirectory = true))
    }

    @Test
    fun theFirstMatchingRuleWinsAndItsIndexIsReported() {
        val rules = IgnoreRules.parse("*.psd\n*.db\nNeuseeland/x.psd")

        assertEquals(1, rules.matchIndex("a.db", "a.db", isDirectory = false))
        assertNull(rules.matchIndex("a.jpg", "a.jpg", isDirectory = false))
    }

    @Test
    fun anAbsentFileMeansNoExclusionsAndIsNotAnError() {
        assertTrue(temporaryDirectory().readIgnoreRules().isEmpty)
    }

    @Test
    fun aPresentButUnreadableFileAborts() {
        val root = temporaryDirectory()
        // Invalid UTF-8: the file exists, so exclusions were intended, and continuing without
        // them is how `.dtrash` gets uploaded.
        Path(root, IgnoreRules.FILENAME)
            .writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x80.toByte(), 0x81.toByte()))

        assertFailsWith<IgnoreRulesUnreadableFailure> { root.readIgnoreRules() }
    }

    // ------------------------------------------------------------------- further glob cases

    @Test
    fun aQuestionMarkMatchesExactlyOneCharacter() {
        val rules = IgnoreRules.parse("IMG_?.jpg")

        assertEquals(0, rules.matchIndex("IMG_1.jpg", "IMG_1.jpg", isDirectory = false))
        assertNull(rules.matchIndex("IMG_10.jpg", "IMG_10.jpg", isDirectory = false))
        assertNull(rules.matchIndex("IMG_.jpg", "IMG_.jpg", isDirectory = false))
    }

    @Test
    fun bracketsMatchOneCharacterOutOfASet() {
        val set = IgnoreRules.parse("[abc].db")
        assertEquals(0, set.matchIndex("c.db", "c.db", isDirectory = false))
        assertNull(set.matchIndex("d.db", "d.db", isDirectory = false))

        val range = IgnoreRules.parse("IMG_[0-9].jpg")
        assertEquals(0, range.matchIndex("IMG_7.jpg", "IMG_7.jpg", isDirectory = false))
        assertNull(range.matchIndex("IMG_x.jpg", "IMG_x.jpg", isDirectory = false))

        val negated = IgnoreRules.parse("[!ab].db")
        assertEquals(0, negated.matchIndex("c.db", "c.db", isDirectory = false))
        assertNull(negated.matchIndex("a.db", "a.db", isDirectory = false))
    }

    /**
     * `FNM_PATHNAME`: no wildcard crosses a separator, and there is no `**` to opt out with. An
     * anchored rule therefore names one directory level and no more — which is what makes
     * "anchored to the root" mean what it says.
     */
    @Test
    fun wildcardsNeverCrossASeparator() {
        val star = IgnoreRules.parse("Album/*.psd")
        assertEquals(0, star.matchIndex("x.psd", "Album/x.psd", isDirectory = false))
        assertNull(star.matchIndex("x.psd", "Album/Sub/x.psd", isDirectory = false))

        val question = IgnoreRules.parse("Album/a?c.jpg")
        assertEquals(0, question.matchIndex("abc.jpg", "Album/abc.jpg", isDirectory = false))
        assertNull(question.matchIndex("c.jpg", "Album/a/c.jpg", isDirectory = false))

        val bracket = IgnoreRules.parse("Album[/]x.jpg")
        assertNull(bracket.matchIndex("x.jpg", "Album/x.jpg", isDirectory = false))
    }

    @Test
    fun anAnchoredRuleCanAlsoBeADirectoryRule() {
        val rules = IgnoreRules.parse("Album/.dtrash/")

        assertEquals(0, rules.matchIndex(".dtrash", "Album/.dtrash", isDirectory = true))
        assertNull(rules.matchIndex(".dtrash", "Other/.dtrash", isDirectory = true))
        assertNull(rules.matchIndex(".dtrash", "Album/.dtrash", isDirectory = false))
    }

    /**
     * `#` starts a comment only at the start of a line, so a trailing note is part of the pattern
     * and quietly stops it matching. The unused-rule report is what catches that.
     */
    @Test
    fun aHashOnlyStartsACommentAtTheStartOfALine() {
        val rules = IgnoreRules.parse("*.db # digiKam")

        assertEquals(1, rules.rules.size)
        assertEquals("*.db # digiKam", rules.rules[0].source)
        assertNull(rules.matchIndex("a.db", "a.db", isDirectory = false))
    }

    /** fnmatch's own escape, inherited: a backslash quotes the next character. */
    @Test
    fun aBackslashQuotesTheNextCharacter() {
        val rules = IgnoreRules.parse("""\*.db""")

        assertEquals(0, rules.matchIndex("*.db", "*.db", isDirectory = false))
        assertNull(rules.matchIndex("a.db", "a.db", isDirectory = false))
    }

    /**
     * A backslash with nothing after it has nothing to quote, and matches nothing — glibc's own
     * answer. The rule then shows up in the unused-rule report instead of quietly excluding names
     * that happen to end in a backslash.
     */
    @Test
    fun aTrailingBackslashMatchesNothing() {
        val rules = IgnoreRules.parse("""a\""")

        assertNull(rules.matchIndex("""a\""", """a\""", isDirectory = false))
        assertNull(rules.matchIndex("a", "a", isDirectory = false))
    }

    /** Also fnmatch's: an unclosed `[` is an ordinary character, not a refused rule. */
    @Test
    fun anUnterminatedBracketIsALiteral() {
        val rules = IgnoreRules.parse("[abc.db")

        assertEquals(0, rules.matchIndex("[abc.db", "[abc.db", isDirectory = false))
        assertNull(rules.matchIndex("a.db", "a.db", isDirectory = false))
    }

    /**
     * `FNM_PERIOD` is not set, so a leading dot is an ordinary character. There is no built-in
     * dot rule anywhere in this tool, and a wildcard that refused to see hidden files would be
     * one — `.DS_Store` is excluded because the file says so, not because it starts with a dot.
     */
    @Test
    fun wildcardsMatchALeadingDot() {
        val rules = IgnoreRules.parse("*")

        assertEquals(0, rules.matchIndex(".DS_Store", ".DS_Store", isDirectory = false))
    }

    @Test
    fun aBareSlashIsNotARule() {
        assertTrue(IgnoreRules.parse("/\n\n   \n").isEmpty)
    }
}
