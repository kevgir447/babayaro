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
        if (slug.contains("filmleri", ignoreCase = true) ||
            slug.contains("dizileri", ignoreCase = true) ||
            slug.contains("kategori", ignoreCase = true) ||
            slug.contains("tur-", ignoreCase = true) ||
            href.contains("/tur/") || href.contains("/kategori/") || href.contains("/yil/") || href.contains("/page/")) {
            return null
        }

        val isValidMovie = (pathSegments.size == 1 && (slug.contains("-izle") || slug.contains("-izleyin"))) ||
                           (pathSegments.size == 2 && pathSegments[0] == "dizi")
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer" to "$mainUrl/"
            )
        ).document

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
            ?: document.selectFirst(".poster-wrapper img, .poster img, img[itemprop=image]")?.let { img ->
                val src = img.attr("data-cfsrc").ifEmpty {
                    img.attr("data-src").ifEmpty {
                        img.attr("src")
                    }
                }
                if (src.startsWith("data:")) null else fixUrlNull(src)
            }

        val plot = document.selectFirst(".film-content, .overview, .film-synopsis, p.text-muted, meta[property=og:description]")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }

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
        val iframes = document.select("iframe, a[data-src], nav.nav-tabs a, .card-video a").mapNotNull {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.isNotEmpty()) fixUrl(src) else null
        }.distinct().toMutableList()

        // Sayfa içindeki tüm iframe ve player kaynaklarını tara
        for (fixedIframe in iframes) {
            try {
                val playerRes = app.get(
                    fixedIframe,
                    headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                ).text

                // 1. Packed javascript çözümlemesi
                val unpacked = getAndUnpack(playerRes)

                // 2. m3u8 veya master playlist yakalama
                val targetText = "$playerRes\n$unpacked"
                val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|txt)[^\s"'<>]*)""")
                m3u8Regex.findAll(targetText).forEach { match ->
                    val streamUrl = match.groupValues[1].replace("\\/", "/")
                    if (streamUrl.contains("master") || streamUrl.contains(".m3u8") || streamUrl.contains("playlist") || streamUrl.contains(".txt?")) {
                        M3u8Helper.generateM3u8(
                            source = this.name,
                            name = this.name,
                            streamUrl = streamUrl,
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

            // Standart Cloudstream oynatıcılarını da çağır (VidMoly vb.)
            try {
                loadExtractor(fixedIframe, subtitleCallback, callback)
            } catch (e: Exception) {
                // ignore
            }
        }

        return true
    }
}
