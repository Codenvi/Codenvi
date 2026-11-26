package app.codenvi.tts

/** Spells numbers as English words, so the phonemizer can read "42" as "forty two". */
object NumberWords {
    private val ones = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val scales = arrayOf("", "thousand", "million", "billion", "trillion")

    /** 0..999 */
    private fun below1000(n: Int): String {
        val parts = ArrayList<String>()
        if (n >= 100) { parts += ones[n / 100]; parts += "hundred" }
        val r = n % 100
        if (r > 0) {
            if (r < 20) parts += ones[r]
            else { parts += tens[r / 10]; if (r % 10 > 0) parts += ones[r % 10] }
        }
        return parts.joinToString(" ")
    }

    /** A whole number (digits only, no separators). Very long digit strings are read digit by digit. */
    fun cardinal(digits: String): String {
        val d = digits.trimStart('0').ifEmpty { "0" }
        if (d.length > 15) return d.map { ones[it - '0'] }.joinToString(" ")
        var n = d.toLong()
        if (n == 0L) return "zero"
        val groups = ArrayList<String>()
        var scale = 0
        while (n > 0) {
            val g = (n % 1000).toInt()
            if (g > 0) groups += (below1000(g) + if (scales[scale].isEmpty()) "" else " " + scales[scale])
            n /= 1000
            scale++
        }
        return groups.reversed().joinToString(" ")
    }

    /** "1st", "2nd", "21st": the word form of an ordinal. */
    fun ordinal(digits: String): String {
        val words = cardinal(digits).split(' ').toMutableList()
        val last = words.last()
        words[words.lastIndex] = when (last) {
            "one" -> "first"; "two" -> "second"; "three" -> "third"; "five" -> "fifth"; "eight" -> "eighth"; "nine" -> "ninth"
            "twelve" -> "twelfth"
            else -> if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        }
        return words.joinToString(" ")
    }

    /** A four-digit year such as 1999 ("nineteen ninety nine") or 2005 ("two thousand five"). */
    fun year(y: Int): String {
        if (y in 2000..2009) return cardinal(y.toString())
        val hi = y / 100
        val lo = y % 100
        return when {
            lo == 0 -> cardinal(hi.toString()) + " hundred"
            lo < 10 -> cardinal(hi.toString()) + " oh " + ones[lo]
            else -> cardinal(hi.toString()) + " " + cardinal(lo.toString())
        }
    }

    /** "3.14" -> "three point one four". [text] may contain thousands commas. */
    fun decimal(text: String): String {
        val clean = text.replace(",", "")
        val dot = clean.indexOf('.')
        if (dot < 0) return cardinal(clean)
        val whole = clean.substring(0, dot).ifEmpty { "0" }
        val frac = clean.substring(dot + 1)
        return cardinal(whole) + " point " + frac.map { ones[it - '0'] }.joinToString(" ")
    }
}
