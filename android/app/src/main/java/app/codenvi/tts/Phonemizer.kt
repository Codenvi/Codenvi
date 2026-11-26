package app.codenvi.tts

import app.codenvi.net.moshi
import com.squareup.moshi.Types
import java.io.BufferedReader
import java.io.Reader

/**
 * English text to the phoneme tokens Kokoro speaks (docs/design.md §10.5). It is a permissively licensed replacement for espeak-ng
 * (GPL): the pronunciation dictionaries come from the Kokoro authors' misaki project (Apache-2.0, assets/tts/lexicon_en_us.tsv, about
 * 183 000 words, already in Kokoro's own phoneme alphabet), plus rules for numbers, plurals, -ed and -ing endings, identifiers
 * (camelCase, snake_case), acronyms, and a letter-to-sound fallback for words that are in no dictionary.
 */
class Phonemizer(private val lexicon: Map<String, String>, private val vocab: Map<Char, Int>) {

    /**
     * Token-id chunks (no padding ids) for [text], each at most [maxTokens] long. The first chunk is kept short so speech starts
     * quickly; the rest are longer, for smoother audio.
     */
    fun chunks(text: String, maxTokens: Int = MAX_CHUNK, firstMax: Int = FIRST_CHUNK): List<LongArray> {
        val out = ArrayList<LongArray>()
        val cur = StringBuilder()
        fun limit() = if (out.isEmpty()) firstMax else maxTokens
        fun flush() {
            val ids = toIds(cur.toString().trim())
            if (ids.isNotEmpty()) out += ids
            cur.clear()
        }
        for (sentence in sentences(text)) {
            val ph = sentencePhonemes(sentence)
            if (ph.isBlank()) continue
            if (ph.length > maxTokens) {
                flush()
                for (part in splitLong(ph, maxTokens)) { cur.append(part); flush() }
                continue
            }
            if (cur.isNotEmpty() && cur.length + ph.length + 1 > limit()) flush()
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(ph)
            if (out.isEmpty() && cur.length >= firstMax) flush()
        }
        flush()
        return out
    }

    /** The phoneme string of all of [text] (sentences joined by spaces). Mostly for tests. */
    fun phonemes(text: String): String = sentences(text).map { sentencePhonemes(it) }.filter { it.isNotBlank() }.joinToString(" ")

    fun toIds(phonemes: String): LongArray {
        val ids = ArrayList<Long>(phonemes.length)
        for (c in phonemes) vocab[c]?.let { ids += it.toLong() }
        return ids.toLongArray()
    }

    // ------------------------------------------------------------------ sentences and tokens
    private fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?…])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }

    /** Cuts an over-long phoneme string at spaces. */
    private fun splitLong(ph: String, max: Int): List<String> {
        val parts = ArrayList<String>()
        var rest = ph
        while (rest.length > max) {
            var cut = rest.lastIndexOf(' ', max)
            if (cut < max / 2) cut = max
            parts += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }

    private val tokenRegex = Regex("""\d+(?:st|nd|rd|th)\b|\$?\d[\d,]*(?:\.\d+)?%?|[A-Za-z0-9_]+(?:['’][A-Za-z]+)?|\S""")

    private fun sentencePhonemes(sentence: String): String {
        val tokens = tokenRegex.findAll(sentence).map { it.value }.toList()
        val out = StringBuilder()
        fun space() { if (out.isNotEmpty() && out.last() != ' ' && out.last() != '(' && out.last() != '“' && out.last() != '"') out.append(' ') }
        for ((i, tok) in tokens.withIndex()) {
            val c = tok[0]
            when {
                c.isDigit() || (c == '$' && tok.length > 1) -> { space(); out.append(numberPhonemes(tok)) }
                c.isLetter() || c == '_' -> {
                    space()
                    val next = tokens.getOrNull(i + 1)
                    out.append(wordPhonemes(tok, next != null && next[0].isLetter() && next[0].lowercaseChar() in "aeiou"))
                }
                tok.length == 1 && c in ",.!?;:…—" -> out.append(c)
                tok.length == 1 && c in "“”\"()" -> {
                    if (c == '(' || c == '“' || (c == '"' && (out.isEmpty() || out.last() == ' '))) { space(); out.append(c) } else out.append(c)
                }
                tok == "&" -> { space(); out.append(word("and")) }
                tok == "+" -> { space(); out.append(word("plus")) }
                tok == "=" -> { space(); out.append(word("equals")) }
                tok == "@" -> { space(); out.append(word("at")) }
                tok == "%" -> { space(); out.append(word("percent")) }
                tok == "-" || tok == "–" || tok == "/" || tok == "\\" -> {} // joins words: just a space
                else -> {}
            }
        }
        return out.toString().trim()
    }

    // ------------------------------------------------------------------ numbers
    private fun numberPhonemes(tok: String): String {
        var t = tok
        val money = t.startsWith("$")
        if (money) t = t.substring(1)
        val percent = t.endsWith("%")
        if (percent) t = t.dropLast(1)
        val ordinal = Regex("^(\\d+)(st|nd|rd|th)$").find(t)
        val words = when {
            ordinal != null -> NumberWords.ordinal(ordinal.groupValues[1])
            t.length == 4 && t.all { it.isDigit() } && t.toInt() in 1100..2099 && !money && !percent -> NumberWords.year(t.toInt())
            else -> NumberWords.decimal(t)
        } + if (money) " dollars" else if (percent) " percent" else ""
        return words.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { word(it) }
    }

    // ------------------------------------------------------------------ words
    private val spelled = setOf(
        "api", "css", "html", "url", "uri", "sql", "cpu", "gpu", "ui", "ux", "ide", "sdk", "apk", "tts", "stt", "jwt", "ssh", "http", "https",
        "cli", "npm", "usb", "ci", "cd", "db", "pdf", "png", "svg", "xml", "yaml", "dns", "ip", "vps", "os", "aws", "gui", "ram", "ssl", "tls",
    )
    private val overrides = mapOf(
        "json" to "ʤˈAsən", "github" to "gˈɪthʌb", "gitlab" to "gˈɪtlæb", "kotlin" to "kˈɑtlɪn", "gradle" to "gɹˈAdᵊl", "codenvi" to "kˈOdɛnvi",
        "whisper" to "wˈɪspəɹ", "kokoro" to "kOkˈɔɹO", "onnx" to "ˈɑnˌɛks", "nginx" to "ˈɛnʤɪnˌɛks", "postgres" to "pˈOstgɹɛs", "sqlite" to "ˈɛskjuˌlIt",
        "regex" to "ɹˈɛʤɛks", "async" to "ˈAsɪŋk", "config" to "kˈɑnfɪg", "repo" to "ɹˈipO", "dev" to "dˈɛv", "src" to "sˈɔɹs", "bool" to "bˈul",
        "args" to "ˈɑɹgz", "auth" to "ˈɔθ", "bash" to "bˈæʃ", "linux" to "lˈɪnəks", "ubuntu" to "ubˈuntu", "android" to "ˈændɹɔɪd", "tailwind" to "tˈAlwɪnd",
        "vite" to "vˈit", "yaml" to "jˈæməl", "uuid" to "jˈuˌjuˈIdˈi", "oauth" to "ˈOˌɔθ", "ok" to "ˌOkˈA",
    )

    private fun word(w: String): String = wordPhonemes(w, false)

    private fun wordPhonemes(raw: String, nextStartsWithVowel: Boolean): String {
        val t = raw.replace('’', '\'')
        // identifiers: snake_case, camelCase, letters next to digits
        if (t.contains('_') || identifierBoundary.containsMatchIn(t)) {
            return identifierBoundary.split(t.replace('_', ' ')).flatMap { it.split(' ') }.filter { it.isNotEmpty() }
                .joinToString(" ") { part ->
                    if (part.all { it.isDigit() }) numberPhonemes(part) else wordPhonemes(part, false)
                }
        }
        val lower = t.lowercase()
        if (lower == "a") return "ɐ"
        if (lower == "the") return if (nextStartsWithVowel) "ði" else "ðə"
        overrides[lower]?.let { return it }
        lexicon[t]?.let { return it }
        lexicon[lower]?.let { return it }
        morph(lower)?.let { return it }
        if (lower in spelled || (t.length in 2..5 && t.all { it.isUpperCase() })) return spell(lower)
        if (lower.length <= 4 && lower.none { it in "aeiouy" }) return spell(lower)
        return lts(lower)
    }

    /** Where an identifier breaks into words: lower→Upper (fooBar), ACRONYMWord (HTTPServer), letter↔digit. */
    private val identifierBoundary = Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|(?<=[A-Za-z])(?=\\d)|(?<=\\d)(?=[A-Za-z])")

    private val letterNames = mapOf(
        'a' to "ˈA", 'b' to "bˈi", 'c' to "sˈi", 'd' to "dˈi", 'e' to "ˈi", 'f' to "ˈɛf", 'g' to "ʤˈi", 'h' to "ˈAʧ", 'i' to "ˈI",
        'j' to "ʤˈA", 'k' to "kˈA", 'l' to "ˈɛl", 'm' to "ˈɛm", 'n' to "ˈɛn", 'o' to "ˈO", 'p' to "pˈi", 'q' to "kjˈu", 'r' to "ˈɑɹ",
        's' to "ˈɛs", 't' to "tˈi", 'u' to "jˈu", 'v' to "vˈi", 'w' to "dˈʌbᵊlju", 'x' to "ˈɛks", 'y' to "wˈI", 'z' to "zˈi",
    )

    private fun spell(w: String): String = w.filter { it.isLetter() }.map { letterNames[it.lowercaseChar()] ?: "" }.joinToString(" ")

    // ------------------------------------------------------------------ endings
    private fun lastSound(ph: String): Char = ph.trimEnd('ˈ', 'ˌ').lastOrNull() ?: ' '

    private fun sSuffix(ph: String): String = when (lastSound(ph)) {
        's', 'z', 'ʃ', 'ʒ', 'ʧ', 'ʤ' -> "ɪz"
        'p', 't', 'k', 'f', 'θ' -> "s"
        else -> "z"
    }

    private fun edSuffix(ph: String): String = when (lastSound(ph)) {
        't', 'd' -> "ɪd"
        'p', 'k', 'f', 'θ', 's', 'ʃ', 'ʧ' -> "t"
        else -> "d"
    }

    private fun dedouble(s: String) = if (s.length >= 2 && s.last() == s[s.length - 2]) s.dropLast(1) else s

    /** Plurals, possessives, -ed, -ing and -ly of words that are in the dictionary. */
    private fun morph(w: String): String? {
        if (w.length < 4) return null
        fun base(s: String) = lexicon[s]
        if (w.endsWith("'s")) base(w.dropLast(2))?.let { return it + sSuffix(it) }
        if (w.endsWith("ies")) base(w.dropLast(3) + "y")?.let { return it + "z" }
        if (w.endsWith("es")) base(w.dropLast(2))?.let { return it + (if (lastSound(it) in "szʃʒʧʤ") "ɪz" else sSuffix(it)) }
        if (w.endsWith("s") && !w.endsWith("ss")) base(w.dropLast(1))?.let { return it + sSuffix(it) }
        if (w.endsWith("ied")) base(w.dropLast(3) + "y")?.let { return it + "d" }
        if (w.endsWith("ed")) for (stem in listOf(w.dropLast(2), w.dropLast(1), dedouble(w.dropLast(2)))) base(stem)?.let { return it + edSuffix(it) }
        if (w.endsWith("ing") && w.length > 4) for (stem in listOf(w.dropLast(3), w.dropLast(3) + "e", dedouble(w.dropLast(3)))) base(stem)?.let { return it + "ɪŋ" }
        if (w.endsWith("ly") && w.length > 4) base(w.dropLast(2))?.let { return it + "li" }
        return null
    }

    // ------------------------------------------------------------------ letter-to-sound fallback
    private fun isVowel(c: Char) = c in "aeiouy"

    /** A rough English pronunciation for a word that is in no dictionary (names, new technical words). */
    private fun lts(w: String): String {
        val out = StringBuilder()
        var stressed = false
        fun v(p: String) { if (!stressed) { stressed = true; out.append('ˈ') }; out.append(p) }
        var i = 0
        while (i < w.length) {
            val c = w[i]
            fun at(s: String) = w.startsWith(s, i)
            when {
                at("tch") -> { out.append("ʧ"); i += 3 }
                at("dge") -> { out.append("ʤ"); i += 3 }
                at("igh") -> { v("I"); i += 3 }
                at("sh") -> { out.append("ʃ"); i += 2 }
                at("ch") -> { out.append("ʧ"); i += 2 }
                at("th") -> { out.append("θ"); i += 2 }
                at("ph") -> { out.append("f"); i += 2 }
                at("ck") -> { out.append("k"); i += 2 }
                at("ng") -> { out.append("ŋ"); i += 2 }
                at("nk") -> { out.append("ŋk"); i += 2 }
                at("qu") -> { out.append("kw"); i += 2 }
                at("wh") -> { out.append("w"); i += 2 }
                at("gh") -> { if (i == 0) out.append("g"); i += 2 }
                at("kn") && i == 0 -> { out.append("n"); i += 2 }
                at("wr") && i == 0 -> { out.append("ɹ"); i += 2 }
                at("mb") && i == w.length - 2 -> { out.append("m"); i += 2 }
                at("ee") || at("ea") || at("ie") -> { v("i"); i += 2 }
                at("oo") -> { v("u"); i += 2 }
                at("ou") -> { v("W"); i += 2 }
                at("ow") -> { v(if (i + 2 >= w.length) "O" else "W"); i += 2 }
                at("ai") || at("ay") || at("ei") -> { v("A"); i += 2 }
                at("oa") -> { v("O"); i += 2 }
                at("oi") || at("oy") -> { v("Y"); i += 2 }
                at("au") || at("aw") -> { v("ɔ"); i += 2 }
                at("ew") || at("ue") -> { v("u"); i += 2 }
                at("ar") -> { v("ɑɹ"); i += 2 }
                at("er") || at("ir") || at("ur") -> { v("ɜɹ"); i += 2 }
                at("or") -> { v("ɔɹ"); i += 2 }
                c == 'x' -> { out.append("ks"); i++ }
                c == 'c' -> { out.append(if (i + 1 < w.length && w[i + 1] in "eiy") "s" else "k"); i++ }
                c == 'g' -> { out.append(if (i + 1 < w.length && w[i + 1] in "eiy" && i > 0) "ʤ" else "g"); i++ }
                c == 'j' -> { out.append("ʤ"); i++ }
                c == 'q' -> { out.append("k"); i++ }
                c == 'r' -> { out.append("ɹ"); i++ }
                c == 's' -> {
                    val between = i > 0 && i + 1 < w.length && isVowel(w[i - 1]) && isVowel(w[i + 1])
                    out.append(if (between) "z" else "s"); i++
                }
                c == 'y' && i == 0 -> { out.append("j"); i++ }
                c == 'e' && i == w.length - 1 && i > 0 && !isVowel(w[i - 1]) && w.length > 2 -> i++ // silent final e
                isVowel(c) -> {
                    val magic = i + 2 == w.length - 1 && w[i + 2] == 'e' && !isVowel(w[i + 1])
                    val p = when (c) {
                        'a' -> if (magic) "A" else "æ"
                        'e' -> if (magic || w.length <= 2) "i" else "ɛ"
                        'i' -> if (magic) "I" else "ɪ"
                        'o' -> if (magic) "O" else "ɑ"
                        'u' -> if (magic) "ju" else "ʌ"
                        else -> if (i == w.length - 1) "i" else "ɪ" // y
                    }
                    v(p); i++
                }
                c.isLetter() -> { out.append(c); i++ } // b d f h k l m n p t v w z
                else -> i++
            }
        }
        return out.toString()
    }

    companion object {
        /** Kokoro accepts at most 510 phoneme tokens; chunks stay well below that. */
        const val MAX_CHUNK = 380
        /** A short first chunk, so the voice starts quickly. */
        const val FIRST_CHUNK = 120

        /** The lexicon file: one "word<TAB>phonemes" per line. */
        fun loadLexicon(reader: Reader): Map<String, String> {
            val map = HashMap<String, String>(200_000)
            BufferedReader(reader).useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab > 0) map[line.substring(0, tab)] = line.substring(tab + 1)
                }
            }
            return map
        }

        /** The vocabulary file: a JSON object from each phoneme symbol to its token id. */
        fun loadVocab(json: String): Map<Char, Int> {
            val type = Types.newParameterizedType(Map::class.java, String::class.java, Integer::class.javaObjectType)
            val raw = moshi.adapter<Map<String, Int>>(type).fromJson(json) ?: emptyMap()
            return raw.filterKeys { it.length == 1 }.mapKeys { it.key[0] }
        }
    }
}
