package site.dirt23.battery.core.friction

import kotlin.random.Random

/**
 * The friction gate's question pool, ported from the extension's friction.js. Only the pool
 * and its checking move here; the DOM mounting (shadow root styling, the why box, the
 * trusted click guard) stays in JS.
 *
 * The challenge has to change every attempt, so each call draws tasks at random: retyping a
 * phrase, arithmetic with precedence, adding three numbers, a power of two, sorting
 * numbers, and tallying two letters across a block of text.
 *
 * friction.js's header describes a per attempt escalation scheme (question count growing on
 * each loosening action). It was removed from the shipped gate, since friction that grows
 * unpredictably just invites switching it off. Nothing here paces the count; the caller
 * passes a flat one.
 *
 * Randomness comes only from the [Random] passed in, so a seeded one makes every draw
 * reproducible in tests.
 */
object FrictionTaskPool {

    /** friction.js's WORDS, verbatim including order (shuffled per draw, not sorted). */
    val WORDS = listOf(
        "amber", "basalt", "cedar", "dusk", "ember", "fjord", "gravel", "harbor",
        "ivory", "jade", "kelp", "lantern", "marble", "nimbus", "onyx", "pebble",
        "quartz", "rivet", "slate", "tundra", "umber", "velvet", "willow", "zephyr",
        "copper", "meadow", "cobalt", "thistle", "cinder", "lichen",
    )

    // --- shared helpers, mirroring friction.js's shuffle/sampleWords/normList ------------

    /** Fisher Yates, same walk as JS's shuffle: last index down to 1. */
    private fun <T> shuffle(list: MutableList<T>, random: Random): MutableList<T> {
        for (i in list.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = list[i]
            list[i] = list[j]
            list[j] = tmp
        }
        return list
    }

    /** n distinct words in random order. */
    private fun sampleWords(n: Int, random: Random): List<String> =
        shuffle(WORDS.toMutableList(), random).take(n)

    /**
     * JS's normList: trim, lower case, split on runs of whitespace, drop empties, rejoin
     * with single spaces. Lets the sort task tolerate the spacing of its displayed target.
     */
    private fun normList(s: String): String =
        s.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    // --- the six task generators ----------------------------------------------------------

    /**
     * 4 words plus a 2 digit number, one word upper cased so case matters, joined with
     * hyphens. Exact string compare, no trimming or case folding: friction.js does `v === p`.
     */
    fun phraseTask(random: Random): FrictionTask {
        val words = sampleWords(4, random)
        val upperIndex = random.nextInt(4)
        val parts = words.mapIndexed { i, w -> if (i == upperIndex) w.uppercase() else w }.toMutableList()
        val number = (10 + random.nextInt(90)).toString()
        parts.add(random.nextInt(parts.size + 1), number)
        val phrase = parts.joinToString("-")
        return FrictionTask(
            label = "Type this exactly (no pasting)",
            target = phrase,
            kind = FrictionTaskKind.TEXT,
            checker = { input -> input == phrase },
        )
    }

    /**
     * Multiply then add or subtract, so precedence matters. The subtract bound keeps the
     * answer non negative, mirroring JS's `Math.max(1, a * b - 3)`; a and b are at least 3
     * so the guard never fires, but it is ported as is.
     */
    fun arithmeticTask(random: Random): FrictionTask {
        val a = 3 + random.nextInt(7)
        val b = 3 + random.nextInt(7)
        val plus = random.nextInt(2) == 0
        val bound = if (plus) 18 else maxOf(1, a * b - 3)
        val c = 2 + random.nextInt(bound)
        val answer = if (plus) a * b + c else a * b - c
        val label = "What's $a × $b ${if (plus) "+" else "−"} $c?"
        return FrictionTask(
            label = label,
            target = null,
            kind = FrictionTaskKind.NUMERIC,
            checker = { input -> input.trim() == answer.toString() },
        )
    }

    /** Add three 2 digit numbers in your head. */
    fun sumListTask(random: Random): FrictionTask {
        val nums = List(3) { 10 + random.nextInt(90) }
        val answer = nums.sum()
        return FrictionTask(
            label = "Add all of these together",
            target = nums.joinToString("   "),
            kind = FrictionTaskKind.NUMERIC,
            checker = { input -> input.trim() == answer.toString() },
        )
    }

    /** Exponent 4..12. */
    fun powerOfTwoTask(random: Random): FrictionTask {
        val exponent = 4 + random.nextInt(9)
        val answer = 1 shl exponent
        return FrictionTask(
            label = "What's 2 to the power of $exponent?",
            target = null,
            kind = FrictionTaskKind.NUMERIC,
            checker = { input -> input.trim() == answer.toString() },
        )
    }

    /**
     * Sort seven distinct 2 digit numbers largest to smallest. The displayed target is a
     * shuffled copy, three space separated like the JS, and the check goes through
     * [normList] so ragged whitespace in the answer still passes.
     */
    fun sortDescendingTask(random: Random): FrictionTask {
        val nums = mutableListOf<Int>()
        while (nums.size < 7) {
            val n = 10 + random.nextInt(90)
            if (n !in nums) nums.add(n)
        }
        val sorted = nums.sortedDescending().joinToString(" ")
        val target = shuffle(nums.toMutableList(), random).joinToString("   ")
        return FrictionTask(
            label = "Type these numbers largest to smallest",
            target = target,
            kind = FrictionTaskKind.TEXT,
            checker = { input -> normList(input) == sorted },
        )
    }

    /**
     * Tally two letters across 8 space joined distinct words. The letter pool is every
     * distinct a..z character in the text in first occurrence order, mirroring JS's
     * `[...new Set(text.replace(/[^a-z]/g, ''))]`, and two are drawn from it at random.
     */
    fun countLettersTask(random: Random): FrictionTask {
        val words = sampleWords(8, random)
        val text = words.joinToString(" ")
        val present = LinkedHashSet<Char>()
        for (ch in text) if (ch in 'a'..'z') present.add(ch)
        val picked = shuffle(present.toMutableList(), random).take(2)
        val x = picked[0]
        val y = picked[1]
        val count = text.count { it == x || it == y }
        return FrictionTask(
            label = "How many times do the letters \"$x\" and \"$y\" appear below, combined?",
            target = text,
            kind = FrictionTaskKind.NUMERIC,
            checker = { input -> input.trim() == count.toString() },
        )
    }

    /**
     * n questions drawn at random from the whole pool, so even a one question gate differs
     * every time. Past the pool's length (6) it cycles, same as JS's `pool[i % pool.length]`.
     */
    fun buildQuestions(n: Int, random: Random): List<FrictionTask> {
        val generators = mutableListOf<(Random) -> FrictionTask>(
            ::phraseTask,
            ::arithmeticTask,
            ::sumListTask,
            ::powerOfTwoTask,
            ::sortDescendingTask,
            ::countLettersTask,
        )
        val pool = shuffle(generators, random)
        return List(n) { i -> pool[i % pool.size](random) }
    }
}

/** What the task expects, for the keyboard hint. */
enum class FrictionTaskKind { TEXT, NUMERIC }

/**
 * One friction question. [target] is the optional monospace block to read from (a phrase to
 * retype, numbers to sort or scan), and [check] is the validation the JS closure captures
 * over its generated answer. The answer stays inside the closure on both platforms; a task
 * that carried it would be an oracle next to the question.
 */
class FrictionTask(
    val label: String,
    val target: String?,
    val kind: FrictionTaskKind,
    private val checker: (String) -> Boolean,
) {
    fun check(input: String): Boolean = checker(input)
}
