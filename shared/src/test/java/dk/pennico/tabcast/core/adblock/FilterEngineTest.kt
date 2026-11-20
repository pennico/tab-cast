// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core.adblock

import dk.pennico.tabcast.core.adblock.FilterEngine.Level.AGGRESSIVE
import dk.pennico.tabcast.core.adblock.FilterEngine.Level.OFF
import dk.pennico.tabcast.core.adblock.FilterEngine.Level.STANDARD
import dk.pennico.tabcast.core.adblock.FilterEngine.Verdict.ALLOW
import dk.pennico.tabcast.core.adblock.FilterEngine.Verdict.BLOCK
import dk.pennico.tabcast.core.adblock.FilterEngine.Verdict.NONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

class FilterEngineTest {
    private fun engine(vararg lines: String): FilterEngine = FilterBuilder().apply { lines.forEach { addLine(it) } }.build()

    private fun FilterEngine.v(url: String, page: String = "https://news.example.org/a", type: Int = T.SCRIPT, level: FilterEngine.Level = STANDARD) =
        check(FilterEngine.Request(url, type, page), level)

    @Test fun hostRules() {
        val e = engine("||ads.example.com^", "0.0.0.0 tracker.example.net", "||adserver.test^")
        assertEquals(BLOCK, e.v("https://ads.example.com/x.js"))
        assertEquals(BLOCK, e.v("https://cdn.ads.example.com/a.js"))
        assertEquals(NONE, e.v("https://notads.example.com/a.js"))
        assertEquals(NONE, e.v("https://example.com/ads.example.com/a.js"))
        assertEquals(BLOCK, e.v("https://tracker.example.net:8443/p"))
        assertEquals(BLOCK, e.v("https://adserver.test/"))
        assertTrue(e.blockedHost("x.ads.example.com"))
        assertFalse(e.blockedHost("example.com"))
    }

    /** Standard = cross-site only (Brave's default); Aggressive also first-party. Off = nothing. */
    @Test fun levels() {
        val e = engine("/banner/*/img^")
        val firstParty = "https://news.example.org/banner/top/img?w=300" // ("img.png" wouldn't match: '.' isn't a separator)
        assertEquals(NONE, e.v(firstParty, type = T.IMAGE))
        assertEquals(BLOCK, e.v(firstParty, type = T.IMAGE, level = AGGRESSIVE))
        assertEquals(BLOCK, e.v("https://cdn.other.net/banner/x/img?y=1", type = T.IMAGE))
        assertEquals(NONE, e.v("https://cdn.other.net/banner/x/img?y=1", type = T.IMAGE, level = OFF))
        // same site, other subdomain = first party
        assertEquals(NONE, e.v("https://static.example.org/banner/x/img", type = T.IMAGE))
    }

    @Test fun patterns() {
        val e = engine("/banner/*/img^", "|https://ad.", "swf|", "-ad-300x250.", "||cdn.example.com/ads/")
        val p = "https://page.test/"
        assertEquals(BLOCK, e.v("https://x.net/banner/foo/img", p))      // ^ matches the end
        assertEquals(BLOCK, e.v("https://x.net/banner/a/b/img/1", p))
        assertEquals(NONE, e.v("https://x.net/banner/foo/imgx", p))
        assertEquals(BLOCK, e.v("https://ad.doubleclick.test/x", p))
        assertEquals(NONE, e.v("https://bad.test/https://ad.x", p))
        assertEquals(BLOCK, e.v("https://x.net/movie.swf", p))
        assertEquals(NONE, e.v("https://x.net/movie.swf?x", p))
        assertEquals(BLOCK, e.v("https://x.net/img/top-ad-300x250.png", p))
        assertEquals(NONE, e.v("https://x.net/img/topbad-ad-300x2500.png", p))
        assertEquals(BLOCK, e.v("https://cdn.example.com/ads/x.js", p))
        assertEquals(BLOCK, e.v("https://eu.cdn.example.com/ads/x.js", p))
        assertEquals(NONE, e.v("https://xcdn.example.com/ads/x.js", p))
    }

    @Test fun options() {
        val e = engine("||t.example^\$script,third-party", "||img.example^\$~image", "||d.example^\$domain=a.com|~b.a.com",
            "/pixel.gif\$1p", "||m.example^\$match-case")
        val page = "https://a.com/"
        assertEquals(BLOCK, e.v("https://t.example/x", page, T.SCRIPT))
        assertEquals(NONE, e.v("https://t.example/x", page, T.IMAGE))
        assertEquals(NONE, e.v("https://img.example/x.png", page, T.IMAGE))
        assertEquals(BLOCK, e.v("https://img.example/x.js", page, T.SCRIPT))
        assertEquals(BLOCK, e.v("https://d.example/x", "https://www.a.com/"))
        assertEquals(NONE, e.v("https://d.example/x", "https://b.a.com/"))
        assertEquals(NONE, e.v("https://d.example/x", "https://c.com/"))
        // $1p: only first-party, so only at Aggressive (Standard never blocks first-party requests)
        assertEquals(BLOCK, e.v("https://a.com/pixel.gif", page, T.IMAGE, AGGRESSIVE))
        assertEquals(NONE, e.v("https://x.net/pixel.gif", page, T.IMAGE, AGGRESSIVE))
    }

    @Test fun exceptionsAndImportant() {
        val e = engine("||ads.example.com^", "@@||ads.example.com/ok/", "||must.example^\$important", "@@||must.example^")
        assertEquals(ALLOW, e.v("https://ads.example.com/ok/x.js"))
        assertEquals(BLOCK, e.v("https://ads.example.com/no/x.js"))
        assertEquals(BLOCK, e.v("https://must.example/x"))
    }

    @Test fun badfilterAndSkipped() {
        val b = FilterBuilder()
        listOf("||x.example^", "||x.example^\$badfilter", "/^https?:\\/\\/ad[0-9]\\./", "||y.example^\$csp=script-src 'none'",
            "||z.example^\$removeparam=utm", "||p.example^\$popup", "! comment", "[Adblock Plus 2.0]").forEach { b.addLine(it) }
        val e = b.build()
        assertEquals(NONE, e.v("https://x.example/a"))
        assertEquals(NONE, e.v("https://y.example/a"))
        assertEquals(1, b.stats["skipped.regex"])
        assertEquals(1, b.stats["badfilter.removed"])
        assertTrue(b.stats.keys.any { it.startsWith("skipped.option.csp") })
    }

    @Test fun pageLevel() {
        val e = engine("||ads.example.com^", "@@||trusted.example^\$document", "@@||nohide.example^\$generichide",
            "||malware-ads.test^\$document", "||pop.test^\$document,popup")
        assertEquals(FilterEngine.PAGE_ALLOWED, e.pageFlags("https://www.trusted.example/x"))
        assertEquals(FilterEngine.PAGE_GENERICHIDE, e.pageFlags("https://nohide.example/"))
        assertEquals(0, e.pageFlags("https://other.example/"))
        assertTrue(e.blocksDocument("https://malware-ads.test/land?x", "https://news.example.org/"))
        assertFalse(e.blocksDocument("https://ads.example.com/", "https://news.example.org/")) // not a $document rule
        assertFalse(e.blocksDocument("https://pop.test/", "https://news.example.org/"))       // popup rules are skipped
    }

    @Test fun cosmetic() {
        val b = FilterBuilder()
        listOf("example.com##.ad-box", "example.com,shop.example.net##div[id^=\"sponsor\"]", "##.banner-ad", "##.side-ad > img",
            "###top-ad", "##a[href^=\"https://ads.\"]", "example.com#@#.banner-ad", "~example.org##.wide-ad",
            "example.com##.x:has-text(Sponsored)", "example.com##+js(nowebrtc)", "example.com#?#.y:-abp-has(.z)", "#@#.never-hidden",
            "##.never-hidden").forEach { b.addLine(it) }
        val e = b.build()
        val spec = e.specificSelectors("www.example.com", STANDARD)
        assertEquals(listOf(".ad-box", "div[id^=\"sponsor\"]"), spec)
        assertEquals(emptyList<String>(), e.specificSelectors("www.example.com", OFF))
        val aggr = e.specificSelectors("news.test", AGGRESSIVE)
        assertTrue(aggr.contains("a[href^=\"https://ads.\"]"))
        assertEquals(emptyList<String>(), e.genericSelectors("news.test", listOf(".banner-ad"), STANDARD))
        assertEquals(listOf(".banner-ad", ".side-ad > img", "#top-ad"),
            e.genericSelectors("news.test", listOf(".banner-ad", ".side-ad", "#top-ad", ".nothing"), AGGRESSIVE))
        // exception on example.com; generic exclusion on example.org; a generic #@# removes it everywhere
        assertEquals(emptyList<String>(), e.genericSelectors("www.example.com", listOf(".banner-ad"), AGGRESSIVE))
        assertEquals(listOf(".wide-ad"), e.genericSelectors("news.test", listOf(".wide-ad"), AGGRESSIVE))
        assertEquals(emptyList<String>(), e.genericSelectors("www.example.org", listOf(".wide-ad"), AGGRESSIVE))
        assertEquals(emptyList<String>(), e.genericSelectors("news.test", listOf(".never-hidden"), AGGRESSIVE))
        assertTrue(b.stats["skipped.cosmetic-procedural"]!! >= 1)
        assertEquals(emptyList<String>(), e.specificSelectors("www.example.com", STANDARD, FilterEngine.PAGE_ELEMHIDE))
    }

    @Test fun cacheRoundTrip() {
        val e = engine("||ads.example.com^", "@@||ads.example.com/ok/", "/banner/*/img^", "example.com##.ad-box", "##.banner-ad")
        val buf = ByteArrayOutputStream()
        e.write(DataOutputStream(buf))
        val r = FilterEngine.read(DataInputStream(ByteArrayInputStream(buf.toByteArray())))
        assertEquals(BLOCK, r.v("https://ads.example.com/x"))
        assertEquals(ALLOW, r.v("https://ads.example.com/ok/x"))
        assertEquals(BLOCK, r.v("https://x.net/banner/1/img", type = T.IMAGE))
        assertEquals(listOf(".ad-box"), r.specificSelectors("example.com", STANDARD))
        assertEquals(listOf(".banner-ad"), r.genericSelectors("x.test", listOf(".banner-ad"), AGGRESSIVE))
        assertEquals(e.info, r.info)
    }

    @Test fun requestTypesAndSites() {
        assertEquals(T.SCRIPT, T.of("https://x/a.js?v=1", null, false))
        assertEquals(T.IMAGE, T.of("https://x/p", mapOf("Accept" to "image/avif,image/webp,*/*"), false))
        assertEquals(T.STYLESHEET, T.of("https://x/p", mapOf("Accept" to "text/css,*/*;q=0.1"), false))
        assertEquals(T.SUBDOCUMENT, T.of("https://x/frame", mapOf("Accept" to "text/html,application/xhtml+xml"), false))
        assertEquals(T.MEDIA, T.of("https://x/v.m3u8", null, false))
        assertEquals(T.UNKNOWN, T.of("https://x/collect?v=2", mapOf("Accept" to "*/*"), false))
        assertEquals(T.DOCUMENT, T.of("https://x/", null, true))
        assertEquals("bbc.co.uk", Domains.site("www.bbc.co.uk"))
        assertEquals("example.com", Domains.site("a.b.example.com"))
        assertEquals("dr.dk", Domains.site("www.dr.dk"))
        assertEquals("abc.net.au", Domains.site("news.abc.net.au"))
        assertEquals("192.0.2.1", Domains.site("192.0.2.1"))
        assertEquals("ads.example.com", "https://u:p@ads.example.com:8080/x".let { val r = Domains.hostRange(it); it.substring(r.first, r.last + 1) })
    }
}

/**
 * The shipped lists (shared/src/main/assets/filters): how long they take to compile, how much memory the engine holds,
 * how fast it matches, and what it does on requests of public test pages. Numbers go into the 0.2.2 report.
 */
class RealListsTest {
    private val dir = File("src/main/assets/filters")

    private fun build(): Pair<FilterEngine, FilterBuilder> =
        FilterLists.build(FilterLists.parseManifest(File(dir, "lists.txt").readText())) { name ->
            File(dir, "$name.txt").takeIf { it.exists() }?.bufferedReader()
        }

    @Test fun shippedLists() {
        build() // warm-up (JIT), then measure
        val t0 = System.nanoTime()
        val (e, b) = build()
        val compileMs = (System.nanoTime() - t0) / 1e6
        val buf = ByteArrayOutputStream()
        e.write(DataOutputStream(buf))
        val t1 = System.nanoTime()
        val r = FilterEngine.read(DataInputStream(ByteArrayInputStream(buf.toByteArray())))
        val loadMs = (System.nanoTime() - t1) / 1e6

        val news = "https://www.dr.dk/nyheder"
        val cases = listOf(
            Triple("https://securepubads.g.doubleclick.net/tag/js/gpt.js", T.SCRIPT, BLOCK),
            Triple("https://www.google-analytics.com/analytics.js", T.SCRIPT, BLOCK),
            Triple("https://www.googletagmanager.com/gtm.js?id=GTM-X", T.SCRIPT, BLOCK),
            Triple("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", T.SCRIPT, BLOCK),
            Triple("https://connect.facebook.net/en_US/fbevents.js", T.SCRIPT, BLOCK),
            Triple("https://www.dr.dk/assets/app.js", T.SCRIPT, NONE),
            Triple("https://drod21t.akamaized.net/all/clear/none/25/x/stream_fmp4/master_manifest.m3u8", T.MEDIA, NONE),
            Triple("https://archive.org/download/BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4", T.MEDIA, NONE),
            Triple("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", T.MEDIA, NONE),
            Triple("https://cdn.jsdelivr.net/npm/hls.js@1", T.SCRIPT, NONE),
        )
        for ((url, type, want) in cases) assertEquals(url, want, r.check(FilterEngine.Request(url, type, news), STANDARD))
        assertEquals(NONE, r.check(FilterEngine.Request("https://www.youtube.com/s/player/x/base.js", T.SCRIPT, "https://www.youtube.com/watch?v=x"), STANDARD))

        // matching speed over a mix of URLs, Standard and Aggressive
        val urls = cases.map { it.first } + List(200) { "https://cdn$it.example-cdn.net/static/js/chunk-$it.min.js?v=$it" } +
            List(200) { "https://www.dr.dk/images/crop/$it/photo-$it.jpg?w=800" }
        val n = 20_000
        var blocked = 0
        val t2 = System.nanoTime()
        for (i in 0 until n) {
            val u = urls[i % urls.size]
            if (r.check(FilterEngine.Request(u, T.UNKNOWN, news), if (i % 2 == 0) STANDARD else AGGRESSIVE) == BLOCK) blocked++
        }
        val usPer = (System.nanoTime() - t2) / 1e3 / n
        val css = r.specificSelectors("www.dr.dk", AGGRESSIVE)
        println("ADBLOCK compile ${"%.0f".format(compileMs)} ms (JVM, server), cache ${buf.size() / 1024} KB, load ${"%.0f".format(loadMs)} ms, " +
            "engine arrays ${r.memoryBytes() / 1024} KB, match ${"%.1f".format(usPer)} µs/request, aggressive highly-generic selectors ${css.size}")
        println("ADBLOCK rules: ${b.stats}")
        println("ADBLOCK lists: ${r.info.filterKeys { it.startsWith("list.") }}")
        assertTrue("matching too slow: $usPer µs", usPer < 200)
        assertTrue(r.memoryBytes() < 24L shl 20)
    }
}
