package site.dirt23.battery.core.friction

import kotlin.random.Random

/**
 * The friction gate's question pool, ported from the extension's friction.js. Only the pool
 * and its checking move here; the DOM mounting (shadow root styling, the why box, the
 * trusted click guard) stays in JS.
 *
 * The challenge has to change every attempt, so each call draws tasks at random: retyping a
 * phrase, arithmetic with precedence, adding three numbers, a power of two, sorting
 * numbers, and tallying a letter across a block of text. Every task is tuned to cost about
 * the same half minute of attention, so no draw is a lucky one.
 *
 * A round is a list of [TaskType]s drawn by [drawTypes]; a wrong answer keeps the types and
 * generates fresh values for them. Redrawing the types made a miss a free spin, while
 * keeping the exact question would turn the counting tasks into "try 11, then 12".
 *
 * Nothing here paces the count; the caller passes the flat `frictionCount` setting.
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
     * 3 words plus a 2 digit number, one word upper cased so case matters, joined with
     * hyphens. Exact string compare, no trimming or case folding: friction.js does `v === p`.
     */
    fun phraseTask(random: Random): FrictionTask {
        val words = sampleWords(3, random)
        val upperIndex = random.nextInt(3)
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

    /** Exponent 6..10: high enough to have to double your way up, low enough that you can. */
    fun powerOfTwoTask(random: Random): FrictionTask {
        val exponent = 6 + random.nextInt(5)
        val answer = 1 shl exponent
        return FrictionTask(
            label = "What's 2 to the power of $exponent?",
            target = null,
            kind = FrictionTaskKind.NUMERIC,
            checker = { input -> input.trim() == answer.toString() },
        )
    }

    /**
     * Sort five distinct 2 digit numbers largest to smallest. The displayed target is a
     * shuffled copy, three space separated like the JS, and the check goes through
     * [normList] so ragged whitespace in the answer still passes.
     */
    fun sortDescendingTask(random: Random): FrictionTask {
        val nums = mutableListOf<Int>()
        while (nums.size < 5) {
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
     * Tally one letter across 5 space joined distinct words. The letter pool is every
     * distinct a..z character in the text in first occurrence order, mirroring JS's
     * `[...new Set(text.replace(/[^a-z]/g, ''))]`, and one is drawn from it at random.
     */
    fun countLettersTask(random: Random): FrictionTask {
        val words = sampleWords(5, random)
        val text = words.joinToString(" ")
        val present = LinkedHashSet<Char>()
        for (ch in text) if (ch in 'a'..'z') present.add(ch)
        val letters = present.toList()
        val x = letters[random.nextInt(letters.size)]
        val count = text.count { it == x }
        return FrictionTask(
            label = "How many times does the letter \"$x\" appear below?",
            target = text,
            kind = FrictionTaskKind.NUMERIC,
            checker = { input -> input.trim() == count.toString() },
        )
    }

    /**
     * The task types for a round: n drawn at random from the whole pool, phrase included, so
     * even a one question gate differs every time. Past the pool's length (6) it cycles,
     * same as JS's `pool[i % pool.length]`. A retry keeps the list and generates it again.
     */
    fun drawTypes(n: Int, random: Random): List<TaskType> {
        val pool = shuffle(TaskType.entries.toMutableList(), random)
        return List(n) { i -> pool[i % pool.size] }
    }

    /** n fresh questions in one go: [drawTypes] generated. */
    fun buildQuestions(n: Int, random: Random): List<FrictionTask> =
        drawTypes(n, random).map { it.generate(random) }
}

/** The six generators, in friction.js's pool order (phrase first, then TASKS). */
enum class TaskType(val generate: (Random) -> FrictionTask) {
    PHRASE(FrictionTaskPool::phraseTask),
    ARITHMETIC(FrictionTaskPool::arithmeticTask),
    SUM_LIST(FrictionTaskPool::sumListTask),
    POWER_OF_TWO(FrictionTaskPool::powerOfTwoTask),
    SORT_DESCENDING(FrictionTaskPool::sortDescendingTask),
    COUNT_LETTERS(FrictionTaskPool::countLettersTask),
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
