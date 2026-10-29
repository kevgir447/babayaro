package com.babayaro.hdfilmcehennemi

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HdfilmcehennemiProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Vizyondakiler",
        "$mainUrl/imdb-7-puan-uzeri-filmler-2/" to "IMDb 7 Üzeri",
        "$mainUrl/en-cok-yorumlananlar-2/" to "En Popüler Filmler",
        "$mainUrl/tur/aksiyon-filmleri-izleyin-1/" to "Aksiyon Macera",
        "$mainUrl/tur/bilim-kurgu-filmleri-izle-1/" to "Bilim Kurgu ve Fantastik"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkElem = if (this.tagName() == "a") this else (this.selectFirst("a") ?: return null)
        val href = fixUrlNull(linkElem.attr("href")) ?: return null

        val pathSegments = href.substringAfter("://").substringAfter("/").trim('/').split('/').filter { it.isNotEmpty() }
        val slug = pathSegments.firstOrNull() ?: return null

        // Kategori, tür, yıl, sayfa ve liste linklerini kesinlikle atla
        if (slug.contains("filmler", ignoreCase = true) ||
            slug.contains("diziler", ignoreCase = true) ||
            slug.contains("kategori", ignoreCase = true) ||
            slug.contains("tur-", ignoreCase = true) ||
            slug.contains("yorumlananlar", ignoreCase = true) ||
            slug.contains("begenilen", ignoreCase = true) ||
            slug.contains("robotu", ignoreCase = true) ||
            slug.contains("istek", ignoreCase = true) ||
            slug == "apk" || slug.startsWith("iletisim") || slug.startsWith("serifilmlerim") ||
            href.contains("/tur/") || href.contains("/kategori/") || href.contains("/yil/") || href.contains("/page/")) {
            return null
        }

        val isValidMovie = (pathSegments.size == 1) || (pathSegments.size == 2 && pathSegments[0] == "dizi")
        if (!isValidMovie) return null

        val title = this.selectFirst("h2, h3, h4, .poster-info h2, .poster-title, .title")?.text()
            ?: linkElem.attr("title").ifEmpty { null }
            ?: this.selectFirst("img")?.attr("alt")?.replace(" izle", "")?.trim()
            ?: pathSegments.last().replace("-izle", "").replace("-", " ")

        if (title.isBlank() ||
            title.contains("Filmleri", ignoreCase = true) ||
            title.contains("Kategorisi", ignoreCase = true) ||
            title.contains("Sayfa", ignoreCase = true) ||
            title.contains("HDFilmCehennemi", ignoreCase = true)) return null

        val posterUrl = this.selectFirst("img")?.let { img ->
            val src = img.attr("data-cfsrc").ifEmpty {
                img.attr("data-src").ifEmpty {
                    img.attr("data-original").ifEmpty {
                        img.attr("data-lazy-src").ifEmpty {
                            img.attr("src")
                        }
                    }
                }
            }
            if (src.startsWith("data:") || src.isEmpty() || src.endsWith(".svg")) null else fixUrlNull(src)
        } ?: return null // Afişi olmayan sahte kartları kesinlikle atla

        val isTv = href.contains("/dizi/")
        val tvType = if (isTv) TvType.TvSeries else TvType.Movie

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        }
    }

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "https://www.hdfilmcehennemi.nl/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, headers = defaultHeaders).document

        // Üst menü, alt menü ve sidebar'ı temizle
        document.select("header, footer, nav, .menu, .navbar, .site-header, .site-footer, aside, .sidebar").remove()

        val items = document.select(".poster-wrapper, .card, article, .poster, .film-item, a.poster").mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = items.isNotEmpty()
        )
    }

    private data class SearchResultWrapper(
        @JsonProperty("query") val query: String? = null,
        @JsonProperty("results") val results: List<String>? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrl = "$mainUrl/search?q=${query.trim()}"
            val res = app.get(
                searchUrl,
                headers = mapOf(
                    "User-Agent" to defaultHeaders["User-Agent"]!!,
                    "X-Requested-With" to "fetch",
                    "Content-Type" to "application/json",
                    "Referer" to "$mainUrl/"
                )
            ).parsedSafe<SearchResultWrapper>()

            res?.results?.mapNotNull { htmlChunk ->
                val doc = Jsoup.parse(htmlChunk)
                val a = doc.selectFirst("a") ?: return@mapNotNull null
                val href = fixUrl(a.attr("href"))
                val title = doc.selectFirst(".title, h4")?.text()
                    ?: a.attr("title").ifEmpty { null }
                    ?: doc.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val posterUrl = doc.selectFirst("img")?.let { img ->
                    img.attr("src").ifEmpty { img.attr("data-src") }
                }?.let { fixUrl(it) }

                val typeStr = doc.selectFirst(".type")?.text() ?: ""
                val isTv = typeStr.contains("Dizi", ignoreCase = true) || href.contains("/dizi/")
                val tvType = if (isTv) TvType.TvSeries else TvType.Movie

                if (isTv) {
                    newTvSeriesSearchResponse(title, href, tvType) {
                        this.posterUrl = posterUrl
                    }
                } else {
                    newMovieSearchResponse(title, href, tvType) {
                        this.posterUrl = posterUrl
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst("h1.title, h1, .movie-title, .film-title")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: ""

        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".poster-wrapper img, .poster img, img[itemprop=image], .post-info-poster img")?.let { img ->
                val src = img.attr("data-cfsrc").ifEmpty {
                    img.attr("data-src").ifEmpty {
                        img.attr("data-original").ifEmpty {
                            img.attr("src")
                        }
                    }
                }
                if (src.startsWith("data:")) null else fixUrlNull(src)
            }

        val plot = document.selectFirst("article p, article, .film-content, .overview, .film-synopsis, p.text-muted, meta[property=og:description], .story, #film-content")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }

        val year = document.selectFirst(".meta .year, .year, a[href*=-filmleri-izle], .post-info-year-country a")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val tags = document.select(".tags a, .genres a, a[href*=/tur/], .post-info-genres a").map { it.text() }

        val isTv = url.contains("/dizi/") || document.select(".season-tab, .episode-item, .seasons, a[href*=/bolum-]").isNotEmpty()

        return if (isTv) {
            val episodes = mutableListOf<Episode>()
            document.select(".seasons a, .episode-item, .bolum-item, a[href*=/bolum-], a[href*=-bolum]").forEach { epElem ->
                val epHref = fixUrlNull(epElem.attr("href")) ?: return@forEach
                val epName = epElem.selectFirst(".mini-poster-title, h4")?.text() ?: epElem.text()
                val seasonNum = Regex("""(\d+)\.\s*sezon""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""sezon-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = Regex("""(\d+)\.\s*b[oö]l[uü]m""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""bolum-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epName
                        this.season = seasonNum
                        this.episode = epNum
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    private data class VideoResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: VideoData? = null
    )
    private data class VideoData(
        @JsonProperty("html") val html: String? = null
    )

    private fun extractStreamsFromJs(jsCode: String, domain: String, referer: String): List<String> {
        return try {
            val polyfill = """
                var b64chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
                function atob(input) {
                  var str = String(input).replace(/=+$/, "");
                  var output = "";
                  for (var bc = 0, bs, buffer, idx = 0; buffer = str.charAt(idx++); ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0) {
                    buffer = b64chars.indexOf(buffer);
                  }
                  return output;
                }
                function btoa(input) {
                  var str = String(input);
                  var output = "";
                  for (var block, charCode, idx = 0, map = b64chars;
                       str.charAt(idx | 0) || (map = "=", idx % 1);
                       output += map.charAt(63 & block >> 8 - idx % 1 * 8)) {
                    charCode = str.charCodeAt(idx += 3/4);
                    block = block << 8 | charCode;
                  }
                  return output;
                }
                var location = { hostname: "$domain", href: "https://$domain/" };
                var window = { location: location };
                var document = { location: location, referrer: "$referer" };
            """.trimIndent()

            val fullCode = """
                $polyfill
                $jsCode
                var __found_streams = [];
                for (var __k in this) {
                    try {
                        var __val = this[__k];
                        if (typeof __val === "string" && (__val.indexOf(".m3u8") !== -1 || __val.indexOf(".txt") !== -1 || __val.indexOf("master") !== -1)) {
                            __found_streams.push(__val);
                        }
                    } catch(e) {}
                }
                __found_streams.join("###");
            """.trimIndent()

            val contextClass = Class.forName("org.mozilla.javascript.Context")
            val enterMethod = contextClass.getMethod("enter")
            val exitMethod = contextClass.getMethod("exit")
            val ctx = enterMethod.invoke(null)
            try {
                val setOptMethod = contextClass.getMethod("setOptimizationLevel", Int::class.javaPrimitiveType)
                setOptMethod.invoke(ctx, -1)
                val initStdMethod = contextClass.getMethod("initStandardObjects")
                val scope = initStdMethod.invoke(ctx)
                val evalMethod = contextClass.getMethod(
                    "evaluateString",
                    Class.forName("org.mozilla.javascript.Scriptable"),
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Any::class.java
                )
                val res = evalMethod.invoke(ctx, scope, fullCode, "script", 1, null)?.toString() ?: ""
                res.split("###").filter { it.startsWith("http") }
            } finally {
                exitMethod.invoke(null)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = defaultHeaders).document
        val iframes = mutableListOf<String>()

        document.select("iframe, a[data-src], nav.nav-tabs a, .card-video a, button[data-src], div[data-src]").forEach {
            val src = it.attr("data-src").ifEmpty { it.attr("src") }
            if (src.isNotEmpty()) {
                val fixed = fixUrl(src)
                if (!iframes.contains(fixed)) iframes.add(fixed)
            }
        }

        // Alternative video butonlarını sorgula (fetch("/video/{id}/"))
        document.select("button[data-video], .alternative-link[data-video]").forEach { btn ->
            val videoId = btn.attr("data-video").trim()
            if (videoId.isNotEmpty()) {
                try {
                    val res = app.get(
                        "$mainUrl/video/$videoId/",
                        headers = mapOf(
                            "Referer" to data,
                            "User-Agent" to defaultHeaders["User-Agent"]!!,
                            "X-Requested-With" to "fetch"
                        )
                    ).parsedSafe<VideoResponse>()
                    res?.data?.html?.let { htmlChunk ->
                        Jsoup.parse(htmlChunk).select("iframe").forEach { iframe ->
                            val s = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
                            if (s.isNotEmpty()) {
                                val fixed = fixUrl(s)
                                if (!iframes.contains(fixed)) iframes.add(fixed)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        // rapidrame_id parametresi varsa doğrudan rplayer linkini de ekle
        val rapidrameIds = mutableListOf<String>()
        iframes.forEach { u ->
            Regex("""rapidrame_id=([a-zA-Z0-9]+)""").find(u)?.groupValues?.get(1)?.let {
                rapidrameIds.add(it)
            }
        }
        rapidrameIds.distinct().forEach { id ->
            val rplayerUrl = "$mainUrl/rplayer/$id/"
            if (!iframes.contains(rplayerUrl)) iframes.add(rplayerUrl)
        }

        for (fixedIframe in iframes) {
            try {
                val domain = fixedIframe.substringAfter("://").substringBefore("/").substringBefore(":")
                val playerRes = app.get(
                    fixedIframe,
                    headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to defaultHeaders["User-Agent"]!!
                    )
                ).text

                // 1. Packed JS varsa unpack et
                val unpacked = getAndUnpack(playerRes)

                // 2. Rhino JS motoruyla şifreli stream linklerini çöz
                val scriptsToRun = mutableListOf<String>()
                if (unpacked.isNotEmpty()) scriptsToRun.add(unpacked)

                Jsoup.parse(playerRes).select("script").forEach { s ->
                    val text = s.data()
                    if (text.contains("charCodeAt") && (text.contains(".split(") || text.contains("eval("))) {
                        scriptsToRun.add(text)
                    }
                }

                val allStreams = mutableListOf<String>()
                scriptsToRun.distinct().forEach { sc ->
                    val streams = extractStreamsFromJs(sc, domain, fixedIframe)
                    allStreams.addAll(streams)
                }

                val targetText = "$playerRes\n$unpacked\n" + allStreams.joinToString("\n")
                val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|txt)[^\s"'<>]*)""")
                m3u8Regex.findAll(targetText).forEach { match ->
                    val streamUrl = match.groupValues[1].replace("\\/", "/")
                    allStreams.add(streamUrl)
                }

                allStreams.distinct().forEach { streamUrl ->
                    if (streamUrl.contains("master") || streamUrl.contains(".m3u8") || streamUrl.contains("playlist") || streamUrl.contains(".txt?")) {
                        M3u8Helper.generateM3u8(
                            source = this.name,
                            name = this.name,
                            streamUrl = streamUrl,
                            referer = fixedIframe,
                            headers = mapOf("Referer" to fixedIframe, "Origin" to "https://$domain")
                        ).forEach(callback)
                    }
                }

                // 3. VTT altyazıları çekme
                val subRegex = Regex(""""file"\s*:\s*"([^"]+\.vtt)"[^}]*"label"\s*:\s*"([^"]+)"""")
                subRegex.findAll(targetText).forEach { subMatch ->
                    val subFile = subMatch.groupValues[1].replace("\\/", "/")
                    val subLabel = subMatch.groupValues[2]
                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = subLabel,
                            url = subFile
                        )
                    )
                }
            } catch (e: Exception) {
                // ignore
            }

            try {
                loadExtractor(fixedIframe, subtitleCallback, callback)
            } catch (e: Exception) {
                // ignore
            }
        }

        return true
    }
}
