package com.babayaro.dizipal

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class NpSource(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("kind") val kind: String? = null,
    @JsonProperty("master") val master: String? = null
)

data class NpSubtitle(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("url") val url: String? = null
)

data class NpConfig(
    @JsonProperty("sources") val sources: List<NpSource>? = null,
    @JsonProperty("subtitles") val subtitles: List<NpSubtitle>? = null
)

class DizipalProvider : MainAPI() {
    override var mainUrl = "https://dizipal1592.com"
    override var name = "Dizipal"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Gündemdeki Diziler ve Filmler",
        "$mainUrl/yeni-eklenen-dizi-bolumler" to "Yeni Eklenen Bölümler",
        "$mainUrl/kesfet" to "Keşfet",
        "$mainUrl/trendler" to "Trendler"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = if (this.tagName() == "a") this else (this.selectFirst("a") ?: return null)
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (href.endsWith("/series/") || href.endsWith("/film/")) return null

        val title = this.selectFirst("span[style*='font-weight:700'], span[style*='font-weight: 700'], h2, h3, h4, .title")?.text()
            ?: link.attr("title").ifEmpty { null }
            ?: link.attr("aria-label").ifEmpty { null }
            ?: this.selectFirst("img")?.attr("alt")?.ifEmpty { null }
            ?: href.substringAfterLast("/").replace("-", " ").capitalize()

        val posterUrl = this.selectFirst("img")?.let { img ->
            val src = img.attr("data-src").ifEmpty { img.attr("src") }
            if (src.startsWith("data:")) null else fixUrlNull(src)
        }

        val isTv = href.contains("/series/") || href.contains("/bolum/")
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
        val url = if (page <= 1) request.data else "${request.data}?sayfa=$page"
        val document = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).document

        val items = document.select("a.tc, a.sr, a.ecl, a[href*='/series/'], a[href*='/film/'], a[href*='/bolum/'], article, .card").mapNotNull {
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

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        val results = mutableListOf<SearchResponse>()

        // Hem dizi hem film kategorisinde arama yap
        listOf(
            "$mainUrl/yabanci-dizi-izle?ara=$cleanQuery",
            "$mainUrl/film-izle?ara=$cleanQuery"
        ).forEach { url ->
            try {
                val doc = app.get(url).document
                doc.select("article, .card, a[href*='/series/'], a[href*='/film/']").forEach { elem ->
                    elem.toSearchResponse()?.let { results.add(it) }
                }
            } catch (e: Exception) {
                // ignore
            }
        }

        return results.distinctBy { it.url }
    }

    private data class EpisodeSchema(
        @JsonProperty("@type") val type: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null
    )

    private data class SeasonSchema(
        @JsonProperty("@type") val type: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("episode") val episodes: List<EpisodeSchema>? = null
    )

    private data class SeriesSchema(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("containsSeason") val containsSeason: List<SeasonSchema>? = null
    )


    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.replace(" izle", "")?.trim()
            ?: ""

        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img")?.attr("src")

        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("p.desc, .overview")?.text()

        val isTv = url.contains("/series/") || document.html().contains("containsSeason")

        return if (isTv) {
            val episodes = mutableListOf<Episode>()

            // JSON-LD schema üzerinden sezon ve bölümleri eksiksiz çek
            val schemaScript = document.select("script[type=application/ld+json]").find {
                it.html().contains("containsSeason")
            }?.html()

            if (schemaScript != null) {
                try {
                    val parsed = AppUtils.parseJson<SeriesSchema>(schemaScript)
                    parsed.containsSeason?.forEachIndexed { sIdx, season ->
                        val seasonNum = sIdx + 1
                        season.episodes?.forEachIndexed { epIdx, ep ->
                            val epNum = ep.episodeNumber ?: (epIdx + 1)
                            val epHref = ep.url?.let { fixUrl(it) } ?: return@forEachIndexed
                            episodes.add(
                                newEpisode(epHref) {
                                    this.name = ep.name ?: "$seasonNum. Sezon $epNum. Bölüm"
                                    this.season = seasonNum
                                    this.episode = epNum
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }

            // Eğer schema boşsa sayfa linklerini tara
            if (episodes.isEmpty()) {
                document.select("a[href*='/bolum/']").forEach { link ->
                    val epHref = fixUrl(link.attr("href"))
                    val epText = link.text()
                    val sMatch = Regex("""(\d+)x(\d+)""").find(epHref)
                    val seasonNum = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val epNum = sMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1

                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epText.ifEmpty { "$seasonNum. Sezon $epNum. Bölüm" }
                            this.season = seasonNum
                            this.episode = epNum
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = posterUrl
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
            }
        }
    }

    private fun String.hexToByteArray(): ByteArray {
        val len = length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password, "HmacSHA512"))
        val hLen = 64
        val l = (keyLengthBytes + hLen - 1) / hLen
        val r = keyLengthBytes - (l - 1) * hLen
        val derivedKey = ByteArray(keyLengthBytes)
        var offset = 0

        for (i in 1..l) {
            val block = ByteBuffer.allocate(salt.size + 4).put(salt).putInt(i).array()
            var u = mac.doFinal(block)
            val t = u.clone()
            for (j in 1 until iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) {
                    t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
            }
            val bytesToCopy = if (i == l) r else hLen
            System.arraycopy(t, 0, derivedKey, offset, bytesToCopy)
            offset += bytesToCopy
        }
        return derivedKey
    }

    private fun decryptDizipal(ciphertextB64: String, ivHex: String, saltHex: String, keyStr: String): String {
        val salt = saltHex.hexToByteArray()
        val iv = ivHex.hexToByteArray()
        val ciphertext = Base64.decode(ciphertextB64.replace("\\/", "/"), Base64.DEFAULT)

        val key = pbkdf2HmacSha512(keyStr.toByteArray(Charsets.UTF_8), salt, 999, 32)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val decrypted = cipher.doFinal(ciphertext)
        return String(decrypted, Charsets.UTF_8)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text

        // Next.js payload içinden enc ve k değerlerini yakala
        val cleanHtml = html.replace("\\\\", "").replace("\\\"", "\"")
        val encMatch = Regex(""""enc"\s*:\s*\{"ciphertext"\s*:\s*"([^"]+)"\s*,\s*"iv"\s*:\s*"([^"]+)"\s*,\s*"salt"\s*:\s*"([^"]+)"\}\s*,\s*"k"\s*:\s*"([^"]+)"""").find(cleanHtml)

        var playerUrl: String? = null
        if (encMatch != null) {
            val cipher = encMatch.groupValues[1]
            val iv = encMatch.groupValues[2]
            val salt = encMatch.groupValues[3]
            val key = encMatch.groupValues[4]
            try {
                val decrypted = decryptDizipal(cipher, iv, salt, key)
                playerUrl = if (decrypted.startsWith("//")) "https:$decrypted" else decrypted
            } catch (e: Exception) {
                // ignore
            }
        }

        if (playerUrl != null) {
            try {
                // dplayer82 player sayfasını getir
                val playerHtml = app.get(
                    playerUrl,
                    headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                ).text

                val origin = try {
                    val uri = java.net.URI(playerUrl)
                    "${uri.scheme}://${uri.host}"
                } catch (e: Exception) {
                    "https://four.dplayer82.site"
                }

                val dplayerHeaders = mapOf(
                    "Referer" to playerUrl,
                    "Origin" to origin,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )

                var foundMaster = false

                // 1. window.NP_CONFIG üzerinden kaynakları ve altyazıları çek
                // ExoPlayer'a master m3u8 playlistini doğrudan veriyoruz.
                // M3u8Helper parçalama yaparsa ayrık ses (dual audio / demuxed HLS) kaybolur ve ses gelmez.
                // Master playlist doğrudan verildiğinde ExoPlayer hem görüntüyü hem sesi eksiksiz senkronize eder.
                val npConfigMatch = Regex("""window\.NP_CONFIG\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(playerHtml)?.groupValues?.get(1)
                if (npConfigMatch != null) {
                    try {
                        val config = AppUtils.parseJson<NpConfig>(npConfigMatch)
                        config.sources?.forEach { src ->
                            val masterUrl = src.master?.replace("\\/", "/")
                            if (!masterUrl.isNullOrEmpty()) {
                                foundMaster = true
                                val title = src.title?.ifEmpty { null } ?: "Orijinal"
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "${this.name} - $title",
                                        url = masterUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = playerUrl
                                        this.quality = Qualities.Unknown.value
                                        this.headers = dplayerHeaders
                                    }
                                )
                            }
                        }

                        config.subtitles?.forEach { sub ->
                            val subUrl = sub.url?.replace("\\/", "/")
                            if (!subUrl.isNullOrEmpty()) {
                                subtitleCallback.invoke(
                                    SubtitleFile(
                                        lang = sub.label ?: sub.lang ?: "Türkçe",
                                        url = subUrl
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                // 2. Regex fallback: master m3u8 linklerini doğrudan çek
                if (!foundMaster) {
                    val masterRegex = Regex(""""master"\s*:\s*"([^"]+)"""")
                    masterRegex.findAll(playerHtml).forEach { match ->
                        val masterUrl = match.groupValues[1].replace("\\/", "/")
                        foundMaster = true
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} - Orijinal",
                                url = masterUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = playerUrl
                                this.quality = Qualities.Unknown.value
                                this.headers = dplayerHeaders
                            }
                        )
                    }
                }

                // 3. m3u8 direkt link fallback (eğer master bulunamazsa)
                if (!foundMaster) {
                    val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
                    m3u8Regex.findAll(playerHtml).forEach { match ->
                        val streamUrl = match.groupValues[1].replace("\\/", "/")
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = playerUrl
                                this.quality = Qualities.Unknown.value
                                this.headers = dplayerHeaders
                            }
                        )
                    }
                }

                // 4. Altyazıları regex ile de tara (fallback)
                val subRegex = Regex(""""label"\s*:\s*"([^"]+)"[^}]*"url"\s*:\s*"([^"]+)"""")
                subRegex.findAll(playerHtml).forEach { sm ->
                    val label = sm.groupValues[1]
                    val subUrl = sm.groupValues[2].replace("\\/", "/")
                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = label,
                            url = subUrl
                        )
                    )
                }
            } catch (e: Exception) {
                // ignore
            }

            try {
                loadExtractor(playerUrl, subtitleCallback, callback)
            } catch (e: Exception) {
                // ignore
            }
        }

        // Sayfa içindeki standart iframe'ler varsa kontrol et
        val doc = org.jsoup.Jsoup.parse(html)
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotEmpty()) {
                val fixed = fixUrl(src)
                try {
                    loadExtractor(fixed, subtitleCallback, callback)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        return true
    }
}
