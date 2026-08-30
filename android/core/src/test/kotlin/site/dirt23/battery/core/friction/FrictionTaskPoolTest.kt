package site.dirt23.battery.core.friction

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The extension's friction pool, ported generator by generator from friction.js. Each task
 * type gets a correct answer check plus wrong or reformatted answers that must still fail
 * (or still pass, where the JS is lenient), and buildQuestions gets its own coverage. The
 * property test at the end sweeps seeds and recomputes each answer independently of the
 * checker under test.
 *
 * A task exposes only what the gate draws: a label, an optional block of text, and a checker.
 * The answers here are worked out off the label and the target, the way a user would, which
 * also proves the question is answerable from what is on screen.
 */
class FrictionTaskPoolTest {

    // --- reading a task the way the screen shows it ---------------------------------------

    /** Which generator made a task, told from its prompt. */
    private enum class Type { PHRASE, ARITHMETIC, SUM_LIST, POWER_OF_TWO, SORT_DESCENDING, COUNT_LETTERS }

    private val arithmeticPrompt = Regex("""^What's (\d+) × (\d+) ([+−]) (\d+)\?$""")
    private val powerPrompt = Regex("""^What's 2 to the power of (\d+)\?$""")
    private val lettersPrompt = Regex("""^How many times do the letters "(.)" and "(.)" appear below, combined\?$""")

    private fun typeOf(task: FrictionTask): Type = when {
        task.label == "Type this exactly (no pasting)" -> Type.PHRASE
        task.label == "Add all of these together" -> Type.SUM_LIST
        task.label == "Type these numbers largest to smallest" -> Type.SORT_DESCENDING
        powerPrompt.matches(task.label) -> Type.POWER_OF_TWO
        arithmeticPrompt.matches(task.label) -> Type.ARITHMETIC
        lettersPrompt.matches(task.label) -> Type.COUNT_LETTERS
        else -> error("unrecognized prompt: ${task.label}")
    }

    /** The numbers in a task's target block, in the order they are shown. */
    private fun shownNumbers(task: FrictionTask): List<Int> =
        task.target!!.trim().split(Regex("\\s+")).map { it.toInt() }

    private fun arithmeticAnswer(label: String): Int {
        val m = arithmeticPrompt.matchEntire(label)!!
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        val c = m.groupValues[4].toInt()
        return if (m.groupValues[3] == "+") a * b + c else a * b - c
    }

    private fun letterCount(task: FrictionTask): Int {
        val m = lettersPrompt.matchEntire(task.label)!!
        val x = m.groupValues[1][0]
        val y = m.groupValues[2][0]
        return task.target!!.count { it == x || it == y }
    }

    /** The answer a user would arrive at, worked out from the prompt and the block only. */
    private fun answerOf(task: FrictionTask): String = when (typeOf(task)) {
        Type.PHRASE -> task.target!!
        Type.ARITHMETIC -> arithmeticAnswer(task.label).toString()
        Type.SUM_LIST -> shownNumbers(task).sum().toString()
        Type.POWER_OF_TWO -> (1 shl powerPrompt.matchEntire(task.label)!!.groupValues[1].toInt()).toString()
        Type.SORT_DESCENDING -> shownNumbers(task).sortedDescending().joinToString(" ")
        Type.COUNT_LETTERS -> letterCount(task).toString()
    }

    // --- phraseTask: exact compare, no trimming and no case folding (phraseTask in friction.js) -----

    @Test
    fun `phraseTask accepts only the exact rendered phrase`() {
        val task = FrictionTaskPool.phraseTask(Random(1))
        assertEquals(FrictionTaskKind.TEXT, task.kind)
        assertTrue(task.check(task.target!!))
    }

    @Test
    fun `phraseTask rejects wrong case, extra whitespace, and different separators`() {
        val task = FrictionTaskPool.phraseTask(Random(2))
        val phrase = task.target!!
        assertFalse(task.check(phrase.lowercase()))
        assertFalse(task.check(phrase.uppercase()))
        assertFalse(task.check(" $phrase"))
        assertFalse(task.check("$phrase "))
        assertFalse(task.check(phrase.replace("-", " ")))
    }

    @Test
    fun `phraseTask has one upper cased word and one 2 digit number joined by hyphens`() {
        // Ranges from phraseTask in friction.js: 4 distinct pool words, one upper cased, a 2 digit
        // number spliced in at a random position, joined with '-'.
        repeat(50) { seed ->
            val task = FrictionTaskPool.phraseTask(Random(seed.toLong()))
            val parts = task.target!!.split("-")
            assertEquals(5, parts.size)
            val numberParts = parts.filter { it.toIntOrNull() != null }
            assertEquals(1, numberParts.size)
            val number = numberParts[0].toInt()
            assertTrue(number in 10..99)
            val wordParts = parts.filter { it.toIntOrNull() == null }
            assertEquals(4, wordParts.size)
            assertEquals(1, wordParts.count { it == it.uppercase() })
            // Every word part, folded back to lower case, comes from the pool and none repeat.
            val lower = wordParts.map { it.lowercase() }
            assertTrue(lower.all { it in FrictionTaskPool.WORDS })
            assertEquals(lower.toSet().size, lower.size)
        }
    }

    // --- arithmeticTask: trim only compare (arithmetic in friction.js) ------------------------------

    @Test
    fun `arithmeticTask accepts the answer with surrounding whitespace and rejects off by one`() {
        val task = FrictionTaskPool.arithmeticTask(Random(3))
        val answer = arithmeticAnswer(task.label)
        assertEquals(FrictionTaskKind.NUMERIC, task.kind)
        assertTrue(task.check(answer.toString()))
        assertTrue(task.check("  $answer  "))
        assertTrue(task.check("\t$answer\n"))
        assertFalse(task.check((answer + 1).toString()))
        assertFalse(task.check((answer - 1).toString()))
        assertFalse(task.check(""))
    }

    @Test
    fun `arithmeticTask stays inside friction js's ranges and never goes negative`() {
        // a, b in 3..9 (arithmetic in friction.js). c in 2..19 on the plus branch, or bounded so the
        // subtraction stays at or above zero on the minus branch.
        repeat(200) { seed ->
            val task = FrictionTaskPool.arithmeticTask(Random(seed.toLong()))
            val m = arithmeticPrompt.matchEntire(task.label)!!
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val plus = m.groupValues[3] == "+"
            val c = m.groupValues[4].toInt()
            assertTrue(a in 3..9)
            assertTrue(b in 3..9)
            if (plus) {
                assertTrue(c in 2..19)
            } else {
                assertTrue(c >= 2)
                assertTrue(a * b - c >= 0)
            }
            // The checker agrees with the sum worked out from the prompt, nothing else.
            val expected = if (plus) a * b + c else a * b - c
            assertTrue(task.check(expected.toString()))
            assertFalse(task.check((expected + 1).toString()))
        }
    }

    // --- sumListTask: trim only compare (sumList in friction.js) ----------------------------------

    @Test
    fun `sumListTask accepts trimmed sum and rejects a wrong total`() {
        val task = FrictionTaskPool.sumListTask(Random(4))
        val nums = shownNumbers(task)
        assertEquals(3, nums.size)
        assertTrue(nums.all { it in 10..99 })
        assertTrue(task.check(" ${nums.sum()} "))
        assertFalse(task.check((nums.sum() + 1).toString()))
    }

    // --- powerOfTwoTask: trim only compare (powerOfTwo in friction.js) -------------------------------

    @Test
    fun `powerOfTwoTask covers exponents 4 through 12 and rejects a neighboring power`() {
        repeat(100) { seed ->
            val task = FrictionTaskPool.powerOfTwoTask(Random(seed.toLong()))
            val exponent = powerPrompt.matchEntire(task.label)!!.groupValues[1].toInt()
            assertTrue(exponent in 4..12)
            assertTrue(task.check((1 shl exponent).toString()))
        }
        val task = FrictionTaskPool.powerOfTwoTask(Random(5))
        val answer = 1 shl powerPrompt.matchEntire(task.label)!!.groupValues[1].toInt()
        assertTrue(task.check(" $answer "))
        assertFalse(task.check((answer * 2).toString()))
        assertFalse(task.check((answer / 2).toString()))
    }

    // --- sortDescendingTask: normList compare, tolerant of whitespace (sortDescending in friction.js) ----

    @Test
    fun `sortDescendingTask tolerates ragged whitespace but not a wrong order`() {
        val task = FrictionTaskPool.sortDescendingTask(Random(6))
        val shown = shownNumbers(task)
        assertEquals(7, shown.size)
        assertEquals(shown.toSet().size, shown.size) // all distinct
        assertTrue(shown.all { it in 10..99 })
        val descending = shown.sortedDescending()
        // Exact join passes.
        assertTrue(task.check(descending.joinToString(" ")))
        // Extra and ragged whitespace, including leading/trailing, still normalizes to the
        // same token list, so it passes too (friction.js's normList: trim, split on \s+).
        assertTrue(task.check("  " + descending.joinToString("   ") + "  "))
        assertTrue(task.check(descending.joinToString("\t")))
        // Ascending order fails.
        assertFalse(task.check(descending.reversed().joinToString(" ")))
        // Dropping a number fails.
        assertFalse(task.check(descending.drop(1).joinToString(" ")))
        // A digit typo fails.
        val typo = descending.toMutableList()
        typo[0] = typo[0] + 1
        assertFalse(task.check(typo.joinToString(" ")))
    }

    @Test
    fun `sortDescendingTask displays the whole answer set in an order that is not the answer`() {
        // The block is a shuffled copy of the seven numbers, so sorting what is shown is the
        // whole job. A shuffle can land back in descending order on some seed, so the wrong
        // order check only runs when the display is not already the answer.
        repeat(50) { seed ->
            val task = FrictionTaskPool.sortDescendingTask(Random(seed.toLong()))
            val shown = shownNumbers(task)
            assertEquals(7, shown.size)
            assertTrue(task.check(shown.sortedDescending().joinToString(" ")))
            if (shown != shown.sortedDescending()) {
                assertFalse(task.check(shown.joinToString(" ")))
            }
        }
    }

    // --- countLettersTask: trim only compare (countLetters in friction.js) ----------------------------

    @Test
    fun `countLettersTask tallies both letters combined and rejects an off count`() {
        val task = FrictionTaskPool.countLettersTask(Random(8))
        val m = lettersPrompt.matchEntire(task.label)!!
        assertEquals(8, task.target!!.split(" ").size)
        assertTrue(m.groupValues[1] != m.groupValues[2])
        val count = letterCount(task)
        assertTrue(count > 0)
        assertTrue(task.check(" $count "))
        assertFalse(task.check((count + 1).toString()))
    }

    @Test
    fun `countLettersTask draws its two letters from characters actually present`() {
        repeat(100) { seed ->
            val task = FrictionTaskPool.countLettersTask(Random(seed.toLong()))
            val m = lettersPrompt.matchEntire(task.label)!!
            val text = task.target!!
            assertTrue(m.groupValues[1][0] in text.toSet())
            assertTrue(m.groupValues[2][0] in text.toSet())
        }
    }

    // --- buildQuestions: draw, coverage, and cycling (the TASKS draw in friction.js) ----------------

    @Test
    fun `buildQuestions returns exactly n tasks`() {
        val random = Random(9)
        assertEquals(1, FrictionTaskPool.buildQuestions(1, random).size)
        assertEquals(3, FrictionTaskPool.buildQuestions(3, random).size)
        assertEquals(6, FrictionTaskPool.buildQuestions(6, random).size)
        assertEquals(20, FrictionTaskPool.buildQuestions(20, random).size)
    }

    @Test
    fun `buildQuestions draws every question from the pool, phrase included`() {
        // buildQuestions(6) exhausts the shuffled pool exactly once, so all 6 generators,
        // phrase among them, must show up regardless of how the pool itself was ordered.
        repeat(30) { seed ->
            val out = FrictionTaskPool.buildQuestions(6, Random(seed.toLong()))
            assertEquals(Type.entries.toSet(), out.map { typeOf(it) }.toSet())
            assertTrue(out.any { typeOf(it) == Type.PHRASE })
        }
    }

    @Test
    fun `all six task types are reachable across seeds even with a single question gate`() {
        val seenTypes = mutableSetOf<Type>()
        for (seed in 0 until 200) {
            val out = FrictionTaskPool.buildQuestions(1, Random(seed.toLong()))
            seenTypes.add(typeOf(out[0]))
            if (seenTypes.size == Type.entries.size) break
        }
        assertEquals(Type.entries.toSet(), seenTypes)
    }

    @Test
    fun `buildQuestions cycles the pool once n exceeds its length`() {
        // pool[i % pool.length] in friction.js: the type sequence repeats with a period of
        // 6 (the pool size) no matter what n is asked for.
        val poolSize = Type.entries.size
        val n = poolSize * 2 + 3
        val out = FrictionTaskPool.buildQuestions(n, Random(10))
        val types = out.map { typeOf(it) }
        for (i in poolSize until n) {
            assertEquals(types[i % poolSize], types[i], "index $i should repeat index ${i % poolSize}'s type")
        }
    }

    // --- property test: every generated task's own answer passes its own checker ---------

    @Test
    fun `every generated task's independently recomputed answer passes its checker`() {
        for (seed in 0 until 500) {
            val random = Random(seed.toLong())
            val questions = FrictionTaskPool.buildQuestions(6, random)
            for (task in questions) {
                val expected = answerOf(task)
                assertTrue(task.check(expected), "seed $seed, ${typeOf(task)} failed its own answer: $expected")
            }
        }
    }
}
