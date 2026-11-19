package app.openradio.domain

import kotlin.math.max
import kotlin.math.min

/**
 * Picks a station for a spoken request such as "play KEXP". Exact names beat prefixes, prefixes
 * beat word matches, and close misspellings still match ("radio paradize"). Spelled-out
 * callsigns ("k e x p") and spoken frequencies ("ninety point three") are normalized first.
 */
object VoiceMatch {
    fun normalize(s: String) =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private val leadingFillers = Regex(
        "^(?:(?:hey google|ok google|okay google|please|can you|could you|i want to|i d like to|let s|lets)\\s+)*" +
            "(?:play|listen to|put on|start|tune in to|tune into|tune to|switch to|turn on)(?:\\s+|$)",
    )
    private val trailingFillers = Regex("(?:\\s+(?:on nipper fm|on nipper|on nipperfm|radio station|station|please))+$|\\s+radio$")

    fun cleanQuery(raw: String): String {
        val q = normalize(raw).replace(leadingFillers, "").trim()
        // "play the radio" / "play something" name no station at all.
        if (q in NOTHING_IN_PARTICULAR) return ""
        return joinSpelledLetters(numberWordsToDigits(q.replace(trailingFillers, "").trim()))
    }

    /** "k e x p" -> "kexp": consecutive single letters are joined into one word. */
    internal fun joinSpelledLetters(q: String): String {
        val out = mutableListOf<String>()
        val run = StringBuilder()
        for (t in q.split(' ').filter { it.isNotEmpty() }) {
            if (t.length == 1 && t[0].isLetter()) {
                run.append(t)
            } else {
                if (run.isNotEmpty()) out += run.toString().also { run.clear() }
                out += t
            }
        }
        if (run.isNotEmpty()) out += run.toString()
        return out.joinToString(" ")
    }

    private val units = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90,
    )

    /**
     * "ninety point three" -> "90 3" and "one hundred and one" -> "101", matching how
     * [normalize] renders "90.3". Decimals after "point" are read digit by digit.
     */
    internal fun numberWordsToDigits(q: String): String {
        val tokens = q.split(' ').filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var value: Int? = null
        fun digitAt(i: Int): Int? = tokens.getOrNull(i)?.let { units[it] }?.takeIf { it < 10 }
        fun isNumberWord(t: String?) = t != null && t != "oh" && (t in units || t in tens)
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val v = value
            when {
                // "ninety point three": emit the whole part, then the digits after the point.
                t == "point" && v != null && digitAt(i + 1) != null -> {
                    out += v.toString()
                    value = null
                    val decimals = StringBuilder()
                    while (digitAt(i + 1) != null) decimals.append(digitAt(++i))
                    out += decimals.toString()
                }
                t == "oh" && v == null -> out += t // "oh" on its own is a word, not zero
                t in units -> {
                    val u = units.getValue(t)
                    value = when {
                        v == null -> u
                        u >= 10 -> { out += v.toString(); u } // a teen after a number starts a new one
                        v >= 100 && v % 100 == 0 -> v + u // one hundred one
                        v >= 20 && v % 10 == 0 -> v + u // ninety three
                        else -> v * 10 + u // one oh one
                    }
                }
                t in tens -> {
                    val n = tens.getValue(t)
                    value = when {
                        v == null -> n
                        v >= 100 && v % 100 == 0 -> v + n // one hundred twenty
                        v in 1..9 -> v * 100 + n // one twenty
                        else -> { out += v.toString(); n }
                    }
                }
                t == "hundred" && v != null && v in 1..9 -> value = v * 100
                t == "and" && v != null && v >= 100 && v % 100 == 0 && isNumberWord(tokens.getOrNull(i + 1)) -> Unit
                else -> {
                    v?.let { out += it.toString() }
                    value = null
                    out += t
                }
            }
            i++
        }
        value?.let { out += it.toString() }
        return out.joinToString(" ")
    }

    /** Station names get the same treatment as queries so "Radio One" equals "Radio 1". */
    private fun normalizeName(name: String) = joinSpelledLetters(numberWordsToDigits(normalize(name)))

    private val bandSuffix = Regex("(?:\\s+(?:fm|am|radio|hd))+$")
    private val radioPrefix = Regex("^radio\\s+")
    private fun core(n: String) = n.replace(bandSuffix, "").replace(radioPrefix, "").trim()
    private fun compact(s: String) = s.replace(" ", "")

    /** Damerau-free Levenshtein distance; fine for short station names. */
    internal fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun similarity(a: String, b: String): Double {
        val longest = max(a.length, b.length)
        return if (longest == 0) 0.0 else 1.0 - distance(a, b).toDouble() / longest
    }

    /** 0 = no match; higher is better. Exact 100, prefix 90, whole words 80, contains 75, fuzzy < 75. */
    fun score(cleanedQuery: String, stationName: String): Int {
        val q = cleanedQuery
        if (q.isEmpty()) return 0
        val n = normalizeName(stationName)
        val qc = core(q).ifEmpty { q }
        val nc = core(n).ifEmpty { n }
        return when {
            n == q || nc == qc -> 100
            compact(n) == compact(q) || compact(nc) == compact(qc) -> 95
            n.startsWith(q) || nc.startsWith(qc) -> 90
            " $n ".contains(" $q ") -> 80
            n.contains(q) || compact(n).contains(compact(q)) && compact(q).length >= 4 -> 75
            else -> fuzzy(qc, nc)
        }
    }

    private fun fuzzy(q: String, n: String): Int {
        // Whole-name similarity on the compact form tolerates spacing and small misspellings.
        val whole = similarity(compact(q), compact(n))
        if (compact(q).length >= 4 && whole >= 0.8) return (whole * 70).toInt()
        // Every spoken word close to some word of the name ("radio paradize" vs "radio paradise").
        val qWords = q.split(' ').filter { it.length >= 3 }
        val nWords = n.split(' ')
        if (qWords.isNotEmpty() && qWords.all { w -> nWords.any { similarity(w, it) >= 0.75 } }) return 60
        return 0
    }

    data class Scored(val station: Station, val score: Int)

    /** Best match with its score; ties keep the earlier (preferred) candidate. */
    fun bestScored(query: String, candidates: List<Station>, minScore: Int = MIN_SCORE): Scored? {
        val q = cleanQuery(query)
        if (q.isEmpty()) return null
        var best: Scored? = null
        for (c in candidates) {
            val s = score(q, c.name)
            if (s >= minScore && (best == null || s > best.score)) best = Scored(c, s)
        }
        return best
    }

    /** @param candidates ordered by preference (e.g. favorites first, then directory results) */
    fun best(query: String, candidates: List<Station>): Station? = bestScored(query, candidates)?.station

    private val NOTHING_IN_PARTICULAR = setOf(
        "", "radio", "the radio", "some radio", "something", "music", "some music", "anything", "nipper fm",
    )

    const val MIN_SCORE = 55
    /** Scores at or above this mean the user almost certainly named this station. */
    const val STRONG_SCORE = 90
}
