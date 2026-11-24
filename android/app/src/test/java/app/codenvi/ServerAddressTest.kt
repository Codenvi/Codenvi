package app.codenvi

import app.codenvi.data.ServerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAddressTest {
    @Test
    fun normalizes_host_names() {
        assertEquals("codenvi.example.com", ServerAddress.normalize("codenvi.example.com"))
        assertEquals("codenvi.example.com", ServerAddress.normalize("  Codenvi.Example.com  "))
        assertEquals("codenvi.example.com", ServerAddress.normalize("https://codenvi.example.com"))
        assertEquals("codenvi.example.com", ServerAddress.normalize("https://codenvi.example.com/"))
        assertEquals("codenvi.example.com:8443", ServerAddress.normalize("codenvi.example.com:8443"))
        assertEquals("localhost:5443", ServerAddress.normalize("localhost:5443"))
    }

    @Test
    fun rejects_things_that_are_not_a_host_name() {
        for (bad in listOf("", "   ", "codenvi", "http://codenvi.example.com", "codenvi.example.com/path",
            "user@codenvi.example.com", "two words.com")) {
            assertNull("'$bad'", ServerAddress.normalize(bad))
        }
    }
}
