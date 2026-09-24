package dev.mcb.callback.net

import dev.mcb.callback.data.ApiConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SuperProductivityApiTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `createTask sends multiple tagIds in body`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody("""{"data":{"id":"task-123"}}""")
        )

        val config = ApiConfig(
            host = "http://127.0.0.1:${server.port}",
            token = "test-token",
            projectId = "proj-1",
            tagIds = setOf("tag-alpha", "tag-beta"),
            defaultEstimateMinutes = 30,
        )

        val api = SuperProductivityApi(config)
        val taskId = api.createTask("Test Call", "Notes content")

        assertEquals("task-123", taskId)

        val recorded = server.takeRequest()
        assertEquals("/tasks", recorded.path)
        assertEquals("POST", recorded.method)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))

        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("Test Call", body.getString("title"))
        assertEquals("Notes content", body.getString("notes"))
        assertEquals("proj-1", body.getString("projectId"))
        assertEquals(1800000L, body.getLong("timeEstimate"))

        val tagArray = body.getJSONArray("tagIds")
        assertEquals(2, tagArray.length())
        val tagList = (0 until tagArray.length()).map { tagArray.getString(it) }
        assertTrue(tagList.contains("tag-alpha"))
        assertTrue(tagList.contains("tag-beta"))
    }

    @Test
    fun `createTask omits tagIds when empty`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":{"id":"task-456"}}""")
        )

        val config = ApiConfig(
            host = "http://127.0.0.1:${server.port}",
            token = "test-token",
            projectId = null,
            tagIds = emptySet(),
            defaultEstimateMinutes = 0,
        )

        val api = SuperProductivityApi(config)
        val taskId = api.createTask("No Tag Call", "")

        assertEquals("task-456", taskId)

        val recorded = server.takeRequest()
        val body = JSONObject(recorded.body.readUtf8())
        assertFalse(body.has("tagIds"))
        assertFalse(body.has("projectId"))
        assertFalse(body.has("timeEstimate"))
    }
}
