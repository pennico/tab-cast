// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TV "Test" button's sample videos (v0.1.1: v0.1.0's Google sample answered 403 and the TV showed an error). */
class TestVideosTest {
    private fun v(url: String) = TestVideos.Video(url, "video/mp4", url, null)

    @Test fun skipsDeadLinksAndPicksTheFirstThatAnswers() {
        FakeHttp { _, path, h ->
            when (path) {
                "/dead.mp4" -> FakeHttp.Resp(403, "application/xml", "<Error/>".toByteArray())
                "/moved.mp4" -> FakeHttp.Resp(302, "text/plain", ByteArray(0), mapOf("Location" to "/ok.mp4"))
                "/ok.mp4" -> {
                    assertEquals("bytes=0-1023", h["range"])
                    FakeHttp.Resp(206, "video/mp4", ByteArray(1024))
                }
                else -> FakeHttp.Resp(404, "text/plain", ByteArray(0))
            }
        }.use { s ->
            val base = "http://127.0.0.1:${s.port}"
            val pick = TestVideos.firstWorking(listOf(v("$base/dead.mp4"), v("$base/moved.mp4"), v("$base/never.mp4"))) { TestVideos.httpCheck(it) }
            assertEquals("$base/moved.mp4", pick.video?.url)
            assertEquals(listOf("127.0.0.1: HTTP 403"), pick.failures)

            val none = TestVideos.firstWorking(listOf(v("$base/dead.mp4"), v("http://127.0.0.1:1/x.mp4"))) { TestVideos.httpCheck(it) }
            assertNull(none.video)
            assertEquals(listOf("127.0.0.1: HTTP 403", "127.0.0.1: no answer"), none.failures)
        }
    }

    @Test fun listHasNoGoogleSampleAndMixesHosts() {
        assertTrue(TestVideos.LIST.none { "googleapis" in it.url })
        assertTrue(TestVideos.LIST.map { Urls.host(it.url) }.distinct().size >= 2)
        assertEquals(Kind.MP4, Classify.fromUrl(TestVideos.LIST.first().url))
    }
}
