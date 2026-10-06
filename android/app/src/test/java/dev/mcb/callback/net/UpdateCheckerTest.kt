package dev.mcb.callback.net

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UpdateCheckerTest {

    @Test
    fun newerTagIsDetected() {
        assertTrue(UpdateChecker.isNewer("v0.3.0", "0.2.0"))
        assertTrue(UpdateChecker.isNewer("v0.10.0", "0.9.0"))
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"))
    }

    @Test
    fun sameOrOlderTagIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("v0.2.0", "0.2.0"))
        assertFalse(UpdateChecker.isNewer("v0.1.0", "0.2.0"))
        assertFalse(UpdateChecker.isNewer("v0.2", "0.2.0"))
    }

    @Test
    fun latestParsesTagAndUrl() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"tag_name":"v0.3.0","html_url":"https://example/r"}"""))
            val release = UpdateChecker("o/r", server.url("").toString().trimEnd('/')).latest()
            assertEquals(Release("v0.3.0", "https://example/r"), release)
            assertEquals("/repos/o/r/releases/latest", server.takeRequest().path)
        }
    }

    @Test
    fun latestThrowsOnHttpError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            assertThrows(IOException::class.java) {
                UpdateChecker("o/r", server.url("").toString().trimEnd('/')).latest()
            }
        }
    }
}
