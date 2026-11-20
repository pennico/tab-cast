// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlsTest {
    @Test fun resolve() {
        val b = "https://cdn.example.com/a/b/master.m3u8?tok=1"
        assertEquals("https://cdn.example.com/a/b/720p.m3u8", Urls.resolve(b, "720p.m3u8"))
        assertEquals("https://cdn.example.com/x/seg.ts", Urls.resolve(b, "/x/seg.ts"))
        assertEquals("https://other.net/s.ts", Urls.resolve(b, "//other.net/s.ts"))
        assertEquals("http://h/s.ts", Urls.resolve(b, "http://h/s.ts"))
        assertEquals("https://cdn.example.com/a/c/s.ts?x=1", Urls.resolve(b, "../c/s.ts?x=1"))
        assertEquals("https://cdn.example.com/a/b/master.m3u8?y=2", Urls.resolve(b, "?y=2"))
        assertEquals("https://cdn.example.com/seg|1.ts", Urls.resolve("https://cdn.example.com/m.m3u8", "seg|1.ts"))
        assertEquals("data:text/plain,x", Urls.resolve(b, "data:text/plain,x"))
    }

    @Test fun parts() {
        val u = "https://user@Video.Example.com:8443/p/q.MP4?x=1#t=3"
        assertEquals("video.example.com", Urls.host(u))
        assertEquals("https://user@Video.Example.com:8443", Urls.origin(u))
        assertEquals("/p/q.MP4", Urls.path(u))
        assertEquals("mp4", Urls.ext(u))
        assertEquals("", Urls.ext("https://h/dir/"))
        assertEquals("https://x.y/v?id=1", Urls.firstUrlIn("Look at this: https://x.y/v?id=1."))
        assertNull(Urls.firstUrlIn("no link here"))
    }
}

class ClassifyTest {
    @Test fun fromUrl() {
        assertEquals(Kind.HLS, Classify.fromUrl("https://h/v/master.m3u8?t=1"))
        assertEquals(Kind.DASH, Classify.fromUrl("https://h/v/stream.mpd"))
        assertEquals(Kind.MP4, Classify.fromUrl("https://h/v/movie.mp4"))
        assertEquals(Kind.AUDIO, Classify.fromUrl("https://h/a/x.mp3"))
        assertEquals(Kind.HLS, Classify.fromUrl("https://h/api/manifest/hls_playlist/id/1?format=m3u8"))
        assertNull(Classify.fromUrl("https://h/page.html"))
        assertNull(Classify.fromUrl("blob:https://h/123"))
    }

    @Test fun segments() {
        assertTrue(Classify.isSegmentUrl("https://h/v/seg-12-v1-a1.ts"))
        assertTrue(Classify.isSegmentUrl("https://h/v/chunk-stream0-00012.m4s"))
        assertTrue(Classify.isSegmentUrl("https://h/v/init.mp4"))
        assertTrue(Classify.isSegmentUrl("https://h/v/segment_7.mp4"))
        assertTrue(Classify.isSegmentUrl("https://h/v/1080/00042.mp4"))
        assertFalse(Classify.isSegmentUrl("https://h/v/BigBuckBunny.mp4"))
        assertFalse(Classify.isSegmentUrl("https://h/v/master.m3u8"))
    }

    @Test fun sniff() {
        assertEquals(Kind.HLS, Classify.sniff("#EXTM3U\n#EXT-X-VERSION:3".toByteArray()))
        assertEquals(Kind.DASH, Classify.sniff("<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash\">".toByteArray()))
        val mp4 = byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray()
        assertEquals(Kind.MP4, Classify.sniff(mp4))
        assertNull(Classify.sniff("<!doctype html><html>".toByteArray()))
    }

    @Test fun ads() {
        assertTrue(Classify.isAdUrl("https://pubads.g.doubleclick.net/x/vast.mp4"))
        assertTrue(Classify.isAdUrl("https://cdn.site.com/ads/preroll.mp4"))
        assertFalse(Classify.isAdUrl("https://cdn.site.com/video/main.m3u8"))
    }
}

class ManifestsTest {
    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
        360p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
        https://cdn2.example.com/1080p/index.m3u8?tok=a
        #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
        720p/index.m3u8
    """.trimIndent()

    @Test fun hlsMaster() {
        val i = Manifests.parseHls(master, "https://cdn.example.com/v/master.m3u8")
        assertTrue(i.isMaster)
        assertEquals(1080, i.maxHeight)
        assertEquals(3, i.variants)
        assertEquals("https://cdn2.example.com/1080p/index.m3u8?tok=a", i.bestVariantUrl)
        assertFalse(i.audioOnly)
    }

    @Test fun hlsMediaVodAndLive() {
        val vod = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6.0,\na.m4s\n#EXTINF:4.5,\nb.m4s\n#EXT-X-ENDLIST\n"
        val i = Manifests.parseHls(vod, "https://h/v/x.m3u8")
        assertFalse(i.isMaster); assertFalse(i.live); assertTrue(i.fmp4)
        assertEquals(10.5, i.durationSec!!, 0.001)
        val live = "#EXTM3U\n#EXTINF:6.0,\na.ts\n#EXTINF:6.0,\nb.ts\n"
        val l = Manifests.parseHls(live, "https://h/v/x.m3u8")
        assertTrue(l.live); assertNull(l.durationSec)
    }

    @Test fun hlsDrm() {
        val fp = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST"
        assertTrue(Manifests.parseHls(fp, "https://h/x.m3u8").drm)
        val aes = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://k/key\"\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST"
        assertFalse(Manifests.parseHls(aes, "https://h/x.m3u8").drm)
    }

    @Test fun dash() {
        val mpd = """<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT1H2M3.5S">
            <Period><AdaptationSet mimeType="video/mp4"><Representation id="1" height="720" width="1280"/><Representation id="2" height="1080"/></AdaptationSet></Period></MPD>"""
        val i = Manifests.parseDash(mpd)
        assertEquals(3723.5, i.durationSec!!, 0.01)
        assertEquals(1080, i.maxHeight)
        assertFalse(i.live); assertFalse(i.drm)
        assertTrue(Manifests.parseDash("<MPD type=\"dynamic\"><ContentProtection schemeIdUri=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"/></MPD>").let { it.live && it.drm })
    }

    @Test fun iso() {
        assertEquals(90.0, Manifests.isoDuration("PT1M30S")!!, 0.0)
        assertEquals(0.5, Manifests.isoDuration("PT0.5S")!!, 0.0)
        assertNull(Manifests.isoDuration("bogus"))
        assertEquals("1:02:03", Manifests.formatDuration(3723.0))
        assertEquals("4:05", Manifests.formatDuration(245.0))
    }
}

class RewriterTest {
    private val map = { u: String -> RelayServer.relayUrl("192.0.2.2:9000", "sid", u) }

    @Test fun hls() {
        val src = "#EXTM3U\r\n#EXT-X-KEY:METHOD=AES-128,URI=\"/keys/k1\"\r\n#EXT-X-MAP:URI=\"init.mp4\"\r\n#EXTINF:6,\r\nseg1.m4s?x=1\r\n#EXTINF:6,\r\nhttps://cdn2.net/seg2.m4s\r\n#EXT-X-ENDLIST\r\n"
        val out = ManifestRewriter.rewriteHls(src, "https://cdn.example.com/v/a/index.m3u8", map)
        val lines = out.lines()
        assertEquals("#EXT-X-KEY:METHOD=AES-128,URI=\"http://192.0.2.2:9000/x/sid/https/cdn.example.com/keys/k1\"", lines[1])
        assertEquals("#EXT-X-MAP:URI=\"http://192.0.2.2:9000/x/sid/https/cdn.example.com/v/a/init.mp4\"", lines[2])
        assertEquals("http://192.0.2.2:9000/x/sid/https/cdn.example.com/v/a/seg1.m4s?x=1", lines[4])
        assertEquals("http://192.0.2.2:9000/x/sid/https/cdn2.net/seg2.m4s", lines[6])
        assertEquals("#EXT-X-ENDLIST", lines[7])
        assertEquals(src.count { it == '\n' }, out.count { it == '\n' })
    }

    @Test fun hlsLeavesSkdAndData() {
        val src = "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"skd://abc\"\n#EXTINF:1,\nseg.ts"
        val out = ManifestRewriter.rewriteHls(src, "https://h/x.m3u8", map)
        assertTrue(out.contains("URI=\"skd://abc\""))
        assertTrue(out.endsWith("http://192.0.2.2:9000/x/sid/https/h/seg.ts"))
    }

    @Test fun dash() {
        val src = """<MPD><BaseURL>https://cdn.example.com/v/</BaseURL><Period><AdaptationSet>
            <SegmentTemplate media="${'$'}RepresentationID${'$'}/${'$'}Number${'$'}.m4s" initialization="/root/init.mp4?a=1&amp;b=2"/>
            <Representation id="v1"><BaseURL>rel/</BaseURL></Representation></AdaptationSet></Period></MPD>"""
        val out = ManifestRewriter.rewriteDash(src, "https://www.example.com/m/stream.mpd", map)
        assertTrue(out.contains("<BaseURL>http://192.0.2.2:9000/x/sid/https/cdn.example.com/v/</BaseURL>"))
        assertTrue(out.contains("media=\"${'$'}RepresentationID${'$'}/${'$'}Number${'$'}.m4s\""))
        assertTrue(out.contains("initialization=\"http://192.0.2.2:9000/x/sid/https/www.example.com/root/init.mp4?a=1&amp;b=2\""))
        assertTrue(out.contains("<BaseURL>rel/</BaseURL>"))
    }

    @Test fun target() {
        assertEquals("sid" to "https://cdn.x/a/b.m3u8?q=1", RelayServer.parseTarget("/x/sid/https/cdn.x/a/b.m3u8?q=1"))
        assertNull(RelayServer.parseTarget("/x/sid/ftp/cdn.x/a"))
        assertNull(RelayServer.parseTarget("/etc/passwd"))
        assertEquals("http://h:1/x/s/http/a.b:8080/p?q", RelayServer.relayUrl("h:1", "s", "http://a.b:8080/p?q"))
    }
}

class SiteRulesTest {
    @Test fun parseAndMatch() {
        val json = """{"rules":[{"name":"Ex","match":"(^|\\.)example\\.com$","prefer":"cdn\\.example","ignore":["/ads/"],
            "headers":{"Referer":"https://www.example.com/"},"resolver":"browser","script":"return 1","relay":true}]}"""
        val rules = SiteRules.parse(json)
        assertEquals(1, rules.size)
        val r = rules[0]
        assertTrue(r.matches("https://www.example.com/watch/1"))
        assertFalse(r.matches("https://example.com.evil.net/"))
        assertEquals("browser", r.resolver)
        assertEquals(true, r.relay)
        val s = CatchSession("https://www.example.com/watch/1", rules)
        s.onRequest("https://cdn.example.com/v/master.m3u8", emptyMap(), 1)
        s.onRequest("https://other.net/v/master.m3u8", emptyMap(), 2)
        s.onRequest("https://cdn.example.com/ads/x.m3u8", emptyMap(), 3)
        val ranked = s.ranked()
        assertEquals(2, ranked.size)
        assertEquals("https://cdn.example.com/v/master.m3u8", ranked[0].url)
        assertEquals("https://www.example.com/", ranked[0].headers["Referer"])
        assertTrue(SiteRules.scriptsJson(rules).contains("return 1"))
    }

    @Test fun builtInFileParses() {
        val rules = SiteRules.parse(java.io.File("src/main/assets/site-rules.json").readText())
        assertNotNull(rules)
        assertEquals("https://player.vimeo.com/video/76979871?h=", SiteRules.ytdlpUrl(rules, "https://vimeo.com/76979871"))
        assertEquals("https://player.vimeo.com/video/123?h=abc12", SiteRules.ytdlpUrl(rules, "https://vimeo.com/123/abc12"))
        assertEquals("https://player.vimeo.com/video/76979871?h=x", SiteRules.ytdlpUrl(rules, "https://player.vimeo.com/video/76979871?h=x"))
        assertEquals("https://example.com/v", SiteRules.ytdlpUrl(rules, "https://example.com/v"))
        assertEquals("browser", SiteRules.forUrl(rules, "https://www.dailymotion.com/video/x").single().resolver)
    }

    @Test(expected = SiteRules.ParseException::class) fun badRegex() {
        SiteRules.parse("""{"rules":[{"match":"(unclosed"}]}""")
    }
}

class CatchSessionTest {
    @Test fun ranksPlayingMasterFirst() {
        val s = CatchSession("https://news.example.com/article")
        s.onRequest("https://ads.doubleclick.net/vast/ad.mp4", mapOf("Range" to "bytes=0-"), 1)
        s.onRequest("https://cdn.example.com/v/master.m3u8", mapOf("Referer" to "https://player.example.com/embed/1", "Origin" to "https://player.example.com", "Sec-Fetch-Mode" to "cors"), 2)
        s.onRequest("https://cdn.example.com/v/720p/index.m3u8", mapOf("Referer" to "https://player.example.com/embed/1"), 3)
        s.onRequest("https://cdn.example.com/v/720p/seg-1.ts", emptyMap(), 4)
        s.onRequest("https://cdn.example.com/v/preview.mp4", mapOf("Range" to "bytes=0-"), 5)
        s.onJs(JsEvent("play", url = "blob:https://player.example.com/abc", frameUrl = "https://player.example.com/embed/1", duration = 1800.0, height = 720), 6)
        s.updateInfo("https://cdn.example.com/v/master.m3u8", StreamInfo(Kind.HLS, isMaster = true, maxHeight = 1080))
        val r = s.ranked()
        assertEquals("https://cdn.example.com/v/master.m3u8", r[0].url)
        assertTrue(r[0].fromPlaying)
        assertEquals(1800.0, r[0].info!!.durationSec!!, 0.0)
        assertEquals("https://player.example.com", r[0].headers["Origin"])
        assertNull(r[0].headers["Sec-Fetch-Mode"])
        assertNull(Classify.fromUrl("https://framatube.org/static/streaming-playlists/hls/x/abc-segments-sha256.json"))
        assertTrue(Classify.isSegmentUrl("https://vod-adaptive-ak.vimeocdn.com/exp=1~acl=x/avf/3e8c/segment.mp4?pathsig=8c9&r=dXM%3D&sid=0&st=video"))
        assertTrue(r.last().ad)
        assertTrue(r[0].describe().startsWith("HLS · 1080p"))
    }

    @Test fun progressiveFromElement() {
        val s = CatchSession("https://site.example/v")
        s.onJs(JsEvent("media", url = "https://files.example/movie", via = "element", frameUrl = "https://site.example/v"), 1)
        s.onJs(JsEvent("play", url = "https://files.example/movie", frameUrl = "https://site.example/v", duration = 600.0, height = 1080), 2)
        val c = s.ranked().single()
        assertEquals(Kind.UNKNOWN, c.kind)
        assertTrue(c.fromPlaying)
        assertEquals("https://site.example/v", c.headers["Referer"])
        s.updateInfo(c.url, StreamInfo(Kind.MP4, sizeBytes = 123456789))
        val d = s.ranked().single()
        assertEquals(Kind.MP4, d.kind)
        assertEquals("video/mp4", d.mime)
        assertEquals(600.0, d.info!!.durationSec!!, 0.0)
    }

    @Test fun jsEventParse() {
        val e = JsEvent.parse("""{"t":"media","url":"https://h/x","via":"xhr","ct":"application/vnd.apple.mpegurl","frame":"https://h/","top":true,"dur":null}""")!!
        assertEquals("xhr", e.via); assertTrue(e.top); assertNull(e.duration)
        val s = CatchSession("https://h/")
        s.onJs(e, 1)
        assertEquals(Kind.HLS, s.ranked().single().kind)
    }
}
