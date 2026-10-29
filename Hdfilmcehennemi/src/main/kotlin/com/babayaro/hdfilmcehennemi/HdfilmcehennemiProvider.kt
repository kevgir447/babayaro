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
        "$mainUrl/" to "Son Eklenen Filmler",
        "$mainUrl/populer-filmleri-izle-3/" to "Popüler Filmler",
        "$mainUrl/tur/aksiyon-filmleri-izleyin-1/" to "Aksiyon Filmleri",
        "$mainUrl/tur/komedi-filmleri-izleyin-1/" to "Komedi Filmleri",
        "$mainUrl/tur/bilim-kurgu-filmleri-izle-1/" to "Bilim Kurgu"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkElem = this.selectFirst("a") ?: return null
        val href = fixUrlNull(linkElem.attr("href")) ?: return null
        val title = this.selectFirst("h2, h3, h4, .poster-info h2, .poster-title")?.text()
            ?: linkElem.attr("title").ifEmpty { null }
            ?: this.selectFirst("img")?.attr("alt")?.replace(" izle", "")?.trim()
            ?: return null

        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty {
                img.attr("src")
            }
        }?.let { fixUrl(it) }

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document

        val items = document.select(".poster-wrapper, .card, article").mapNotNull {
            it.toSearchResponse()
        }

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
        val document = app.get(url).document

        val title = document.selectFirst("h1.title, h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: ""

        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".poster-wrapper img, .poster img")?.attr("src")

        val plot = document.selectFirst(".film-content, .overview, .film-synopsis, p.text-muted")?.text()

        val year = document.selectFirst(".meta .year, .year")?.text()?.toIntOrNull()
        val tags = document.select(".tags a, .genres a").map { it.text() }

        val isTv = url.contains("/dizi/") || document.select(".season-tab, .episode-item").isNotEmpty()

        return if (isTv) {
            val episodes = mutableListOf<Episode>()
            document.select(".episode-item, .bolum-item, a[href*=-bolum]").forEach { epElem ->
                val epHref = fixUrl(epElem.attr("href"))
                val epName = epElem.text()
                val seasonNum = Regex("""(\d+)\.\s*sezon""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = Regex("""(\d+)\.\s*b[oö]l[uü]m""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epName
                        this.season = seasonNum
                        this.episode = epNum
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframes = document.select("iframe").map { it.attr("src") }.toMutableList()

        // Sayfa içindeki tüm iframe ve player kaynaklarını tara
        for (iframe in iframes) {
            val fixedIframe = fixUrl(iframe)
            if (fixedIframe.contains("hdfilmcehennemi.mobi") || fixedIframe.contains("rapidrame") || fixedIframe.contains("embed")) {
                try {
                    val playerRes = app.get(fixedIframe, headers = mapOf("Referer" to "$mainUrl/")).text

                    // 1. Packed javascript çözümlemesi
                    val unpacked = getAndUnpack(playerRes)

                    // 2. m3u8 veya master playlist yakalama
                    val targetText = "$playerRes\n$unpacked"
                    val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|txt)[^\s"'<>]*)""")
                    m3u8Regex.findAll(targetText).forEach { match ->
                        val streamUrl = match.groupValues[1]
                        val cleanStreamUrl = streamUrl.replace("\\/", "/")
                        if (cleanStreamUrl.contains("master") || cleanStreamUrl.endsWith(".m3u8")) {
                            M3u8Helper.generateM3u8(
                                source = this.name,
                                name = this.name,
                                streamUrl = cleanStreamUrl,
                                referer = fixedIframe,
                                headers = mapOf("Referer" to fixedIframe, "Origin" to "https://hdfilmcehennemi.mobi")
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
            }

            // Standart Cloudstream oynatıcılarını da çağır (VidMoly vb.)
            loadExtractor(fixedIframe, subtitleCallback, callback)
        }

        return true
    }
}
