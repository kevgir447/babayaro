package com.babayaro.dizikorea

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DizikoreaProvider : MainAPI() {
    override var mainUrl = "https://dizikorea3.com"
    override var name = "DiziKorea"
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler",
        "$mainUrl/kore-dizileri-izle-dq1" to "Kore Dizileri",
        "$mainUrl/cin-dizileri-izle-dq2" to "Çin Dizileri",
        "$mainUrl/japon-dizileri-izle-dq3" to "Japon Dizileri",
        "$mainUrl/tayland-dizileri-izle-dq4" to "Tayland Dizileri"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = if (this.tagName() == "a") this else (this.selectFirst("a") ?: return null)
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (href.endsWith("/dizi") || href.endsWith("/film")) return null

        val title = link.attr("title").ifEmpty { null }
            ?: this.selectFirst(".title, h2, h3, h4, span.title, .site-sidebar-trend-title")?.text()
            ?: this.selectFirst("img")?.attr("alt")?.ifEmpty { null }
            ?: href.substringAfterLast("/").replace("-izle", "").replace("-", " ")

        val posterUrl = this.selectFirst("img")?.let { img ->
            val src = img.attr("data-src").ifEmpty { img.attr("src") }
            if (src.startsWith("data:")) null else fixUrlNull(src)
        }

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?sayfa=$page"
        val document = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).document

        val items = document.select("a.dual-slide-card, a.site-sidebar-trend-item, .series-card, .poster-item, a[href*='/dizi/'], a[href*='/film/']").mapNotNull {
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

    private data class DizikoreaSearchItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("description") val description: String? = null
    )

    private data class DizikoreaSearchResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("items") val items: List<DizikoreaSearchItem>? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/ara?q=${query.trim()}"
        val res = app.get(
            searchUrl,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$mainUrl/"
            )
        ).parsedSafe<DizikoreaSearchResponse>()

        return res?.items?.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val slug = item.slug ?: return@mapNotNull null
            val href = "$mainUrl/dizi/$slug"
            val posterUrl = item.poster?.let { fixUrl(it) }

            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                this.posterUrl = posterUrl
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.replace(" izle", "")?.trim()
            ?: ""

        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".series-poster img, .poster img")?.attr("src")

        val plot = document.selectFirst(".series-summary, .overview, meta[property=og:description]")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }

        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(title)?.value?.toIntOrNull()
        val tags = document.select(".series-genres a, .tags a, .genres a").map { it.text() }

        val episodes = mutableListOf<Episode>()

        // Dizikorea bölüm listesini çek
        document.select("a[href*='/bolum-']").forEach { epElem ->
            val epHref = fixUrl(epElem.attr("href"))
            val epText = epElem.text()

            val sMatch = Regex("""sezon-(\d+)""").find(epHref)
            val seasonNum = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

            val eMatch = Regex("""bolum-(\d+)""").find(epHref)
                ?: Regex("""(\d+)\.\s*[bB][oö]l[uü]m""").find(epText)
            val epNum = eMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

            if (!episodes.any { it.data == epHref }) {
                episodes.add(
                    newEpisode(epHref) {
                        this.name = epText.ifEmpty { "$seasonNum. Sezon $epNum. Bölüm" }
                        this.season = seasonNum
                        this.episode = epNum
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframes = document.select("iframe").map { it.attr("src") }.toMutableList()

        for (iframe in iframes) {
            val fixedIframe = fixUrl(iframe)

            // PlayerDkorea kaynağını çöz
            if (fixedIframe.contains("playerdkorea") || fixedIframe.contains("playerkorea")) {
                try {
                    val playerHtml = app.get(fixedIframe, headers = mapOf("Referer" to "$mainUrl/")).text
                    val unpacked = getAndUnpack(playerHtml)
                    val combined = "$playerHtml\n$unpacked"

                    val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
                    m3u8Regex.findAll(combined).forEach { match ->
                        val streamUrl = match.groupValues[1].replace("\\/", "/")
                        M3u8Helper.generateM3u8(
                            source = "$name - PlayerDKorea",
                            name = "$name - PlayerDKorea",
                            streamUrl = streamUrl,
                            referer = fixedIframe,
                            headers = mapOf("Referer" to fixedIframe)
                        ).forEach(callback)
                    }

                    // VTT Altyazı kontrolü
                    val subRegex = Regex("""(https?://[^\s"'<>]+\.vtt[^\s"'<>]*)""")
                    subRegex.findAll(combined).forEach { match ->
                        subtitleCallback.invoke(
                            SubtitleFile(
                                lang = "Türkçe",
                                url = match.groupValues[1].replace("\\/", "/")
                            )
                        )
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }

            // VidMoly, Filemoon ve diğer oynatıcıları otomatik aktar
            loadExtractor(fixedIframe, subtitleCallback, callback)
        }

        return true
    }
}
