package com.babayaro.dizipal

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DizipalProvider : MainAPI() {
    override var mainUrl = "https://dizipal1592.com"
    override var name = "Dizipal"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/yabanci-dizi-izle" to "Popüler Yabancı Diziler",
        "$mainUrl/film-izle" to "Popüler Filmler",
        "$mainUrl/yeni-eklenen-dizi-bolumler" to "Son Eklenen Bölümler"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = this.selectFirst(".title, h2, h3, h4")?.text()
            ?: link.attr("title").ifEmpty { null }
            ?: this.selectFirst("img")?.attr("alt")?.ifEmpty { null }
            ?: return null

        val posterUrl = this.selectFirst("img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        }?.let { fixUrl(it) }

        val isTv = href.contains("/series/") || href.contains("/dizi")
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
        val document = app.get(url).document

        val items = document.select("article, .card, a[href*='/series/'], a[href*='/film/']").mapNotNull {
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
        val cipherMatch = Regex("""ciphertext\\?":\s*\\?"([^\\"]+)\\?"""").find(html)?.groupValues?.get(1)
        val ivMatch = Regex("""iv\\?":\s*\\?"([a-f0-9]+)\\?"""").find(html)?.groupValues?.get(1)
        val saltMatch = Regex("""salt\\?":\s*\\?"([a-f0-9]+)\\?"""").find(html)?.groupValues?.get(1)
        val keyMatch = Regex("""k\\?":\s*\\?"([^\\"]+)\\?"""").find(html)?.groupValues?.get(1)

        if (cipherMatch != null && ivMatch != null && saltMatch != null && keyMatch != null) {
            try {
                val decryptedPlayerUrl = decryptDizipal(cipherMatch, ivMatch, saltMatch, keyMatch)
                val fixedPlayerUrl = if (decryptedPlayerUrl.startsWith("//")) "https:$decryptedPlayerUrl" else decryptedPlayerUrl

                // dplayer82 player sayfasını getir
                val playerHtml = app.get(
                    fixedPlayerUrl,
                    headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                    )
                ).text

                // Master m3u8 linkini ayıkla
                val masterMatch = Regex(""""master"\s*:\s*"([^"]+)"""").find(playerHtml)?.groupValues?.get(1)
                if (masterMatch != null) {
                    val masterUrl = masterMatch.replace("\\/", "/")
                    M3u8Helper.generateM3u8(
                        source = this.name,
                        name = this.name,
                        streamUrl = masterUrl,
                        referer = fixedPlayerUrl,
                        headers = mapOf(
                            "Referer" to fixedPlayerUrl,
                            "Origin" to "https://four.dplayer82.site"
                        )
                    ).forEach(callback)
                }

                // Altyazıları ayıkla
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

                return true
            } catch (e: Exception) {
                // ignore
            }
        }

        return false
    }
}
