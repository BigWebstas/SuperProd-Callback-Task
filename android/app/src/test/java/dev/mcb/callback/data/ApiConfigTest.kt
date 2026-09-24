package dev.mcb.callback.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiConfigTest {

    @Test
    fun `default values and multiple tagIds`() {
        val config = ApiConfig(
            host = "192.168.1.50",
            token = "secret-token",
            projectId = "project-1",
            tagIds = setOf("tag-1", "tag-2"),
            defaultEstimateMinutes = 15,
        )

        assertEquals("http://192.168.1.50:3876", config.baseUrl)
        assertEquals(setOf("tag-1", "tag-2"), config.tagIds)
        assertEquals("tag-1", config.tagId)
        assertTrue(config.useLocalhostHostHeader)
        assertTrue(config.isConfigured)
    }

    @Test
    fun `backwards compatible constructor with single tagId`() {
        val config = ApiConfig(
            host = "192.168.1.50:9876",
            token = "secret-token",
            projectId = null,
            tagId = "single-tag",
            defaultEstimateMinutes = 0,
        )

        assertEquals("http://192.168.1.50:9876", config.baseUrl)
        assertEquals(setOf("single-tag"), config.tagIds)
        assertEquals("single-tag", config.tagId)
    }

    @Test
    fun `null single tagId yields empty tagIds set`() {
        val config = ApiConfig(
            host = "https://sp.example.com",
            token = "secret-token",
            projectId = null,
            tagId = null,
            defaultEstimateMinutes = 0,
        )

        assertEquals("https://sp.example.com", config.baseUrl)
        assertTrue(config.tagIds.isEmpty())
        assertNull(config.tagId)
        assertFalse(config.useLocalhostHostHeader)
    }
}
