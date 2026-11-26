package app.codenvi

import app.codenvi.tts.NumberWords
import app.codenvi.tts.Phonemizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonemizerTest {
    private val phonemizer: Phonemizer by lazy {
        val loader = javaClass.classLoader!!
        val lexicon = loader.getResourceAsStream("tts/lexicon_en_us.tsv")!!.reader(Charsets.UTF_8).use { Phonemizer.loadLexicon(it) }
        val vocab = Phonemizer.loadVocab(loader.getResourceAsStream("tts/kokoro_vocab.json")!!.reader(Charsets.UTF_8).readText())
        Phonemizer(lexicon, vocab)
    }

    @Test fun numbersAreSpelledOut() {
        assertEquals("forty two", NumberWords.cardinal("42"))
        assertEquals("one thousand two hundred thirty four", NumberWords.cardinal("1234"))
        assertEquals("zero", NumberWords.cardinal("000"))
        assertEquals("twenty first", NumberWords.ordinal("21"))
        assertEquals("nineteen ninety nine", NumberWords.year(1999))
        assertEquals("two thousand five", NumberWords.year(2005))
        assertEquals("nineteen oh five", NumberWords.year(1905))
        assertEquals("three point one four", NumberWords.decimal("3.14"))
    }

    @Test fun knownWordsUseTheDictionary() {
        assertEquals("həlˈO", phonemizer.phonemes("hello"))
        assertEquals("ɹˈɛkəɹd", phonemizer.phonemes("record"))
        assertEquals("ɐ", phonemizer.phonemes("a"))
    }

    @Test fun punctuationStaysAttachedAndSentencesAreJoined() {
        assertEquals("həlˈO, wˈɜɹld!", phonemizer.phonemes("Hello, world!"))
        assertTrue(phonemizer.phonemes("One. Two.").contains(". "))
    }

    @Test fun endingsAreBuiltFromTheStem() {
        // "records" = record + z, "recorded" = record + ɪd, "recording" = record + ɪŋ
        assertEquals(phonemizer.phonemes("record") + "z", phonemizer.phonemes("records"))
        assertEquals(phonemizer.phonemes("record") + "ɪd", phonemizer.phonemes("recorded"))
        assertEquals(phonemizer.phonemes("record") + "ɪŋ", phonemizer.phonemes("recording"))
    }

    @Test fun identifiersAndAcronymsAreSplit() {
        assertEquals(phonemizer.phonemes("read me"), phonemizer.phonemes("readMe"))
        assertEquals(phonemizer.phonemes("read me"), phonemizer.phonemes("read_me"))
        // an acronym is spelled letter by letter
        assertTrue(phonemizer.phonemes("API").contains("ˈA"))
        // digits become words
        assertEquals(phonemizer.phonemes("forty two"), phonemizer.phonemes("42"))
        assertEquals(phonemizer.phonemes("fifty percent"), phonemizer.phonemes("50%"))
    }

    @Test fun unknownWordsStillGetSomePronunciation() {
        val ph = phonemizer.phonemes("zorblax")
        assertTrue(ph.isNotBlank())
        assertTrue(ph.contains("ˈ")) // stressed
    }

    @Test fun chunksFitTheModelAndTheFirstIsShort() {
        val text = (1..80).joinToString(" ") { "This is sentence number $it of a rather long reply that goes on and on." }
        val chunks = phonemizer.chunks(text)
        assertTrue(chunks.size > 3)
        assertTrue(chunks.first().size <= Phonemizer.FIRST_CHUNK + 40)
        assertTrue(chunks.all { it.size in 1..Phonemizer.MAX_CHUNK })
        assertTrue(chunks.all { chunk -> chunk.all { it in 1..177 } }) // 0 is the padding id; every id is a real symbol
    }

    @Test fun anOverlongSentenceIsSplit() {
        val text = (1..400).joinToString(" ") { "word" } + "."
        val chunks = phonemizer.chunks(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.size <= Phonemizer.MAX_CHUNK })
    }

    @Test fun emptyAndSymbolOnlyTextGivesNoChunks() {
        assertTrue(phonemizer.chunks("").isEmpty())
        assertTrue(phonemizer.chunks("   ###  ").isEmpty())
    }
}
