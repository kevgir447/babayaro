package com.babayaro.dizibox

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DiziboxProvider : MainAPI() {
    override var mainUrl = "https://www.dizibox.live"
    override var name = "Dizibox"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/tum-bolumler/?tip=populer" to "Popüler Bölümler",
        "$mainUrl/tum-bolumler/" to "Son Eklenen Bölümler",
        "$mainUrl/arsiv/" to "Dizi Arşivi"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = if (this.tagName() == "a") this else (this.selectFirst("a.poster-title, a.episode-card-title, a") ?: return null)
        val href = fixUrlNull(link.attr("href")) ?: return null

        val title = link.attr("title").ifEmpty { null }
            ?: this.selectFirst(".series-name, .poster-title, h2, h3, h4, .title")?.text()
            ?: this.selectFirst("img")?.attr("alt")?.ifEmpty { null }
            ?: return null

        val posterUrl = this.selectFirst("img")?.let { img ->
            val src = img.attr("data-src").ifEmpty { img.attr("data-original") }.ifEmpty { img.attr("src") }
            if (src.startsWith("data:")) null else fixUrlNull(src)
        }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            if (request.data.contains("?")) {
                val parts = request.data.split("?")
                "${parts[0]}page/$page/?${parts[1]}"
            } else {
                "${request.data}page/$page/"
            }
        }
        val document = app.get(url).document

        val items = document.select("article, .article-series-poster, .article-episode-card, .post, .dizibox-list li, .archive-item").mapNotNull {
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
        val searchUrl = "$mainUrl/?s=${query.trim()}"
        val document = app.get(searchUrl).document

        return document.select("article, .post, .entry, .search-result").mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.title, h1.entry-title, h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.replace(" izle", "")?.trim()
            ?: ""

        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".poster img, .entry-thumbnail img")?.attr("src")

        val plot = document.selectFirst(".overview, .entry-content p, .story")?.text()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(document.select(".meta, .info").text())?.value?.toIntOrNull()
        val tags = document.select(".genres a, .tags a").map { it.text() }

        val episodes = mutableListOf<Episode>()

        // Dizibox bölüm listesini ayrıştır
        document.select("a[href*='-sezon-'], a[href*='-bolum-'], .episodes-list a").forEach { epElem ->
            val epHref = fixUrl(epElem.attr("href"))
            val epText = epElem.text()

            val sMatch = Regex("""(\d+)\.\s*[sS]ezon""").find(epText)
                ?: Regex("""(\d+)-sezon""").find(epHref)
            val seasonNum = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

            val eMatch = Regex("""(\d+)\.\s*[bB][oö]l[uü]m""").find(epText)
                ?: Regex("""(\d+)-bolum""").find(epHref)
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

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, keyLen: Int = 32, ivLen: Int = 16): Pair<ByteArray, ByteArray> {
        val md5 = MessageDigest.getInstance("MD5")
        val keyAndIv = ByteArray(keyLen + ivLen)
        var currentHash = ByteArray(0)
        var offset = 0

        while (offset < keyAndIv.size) {
            md5.reset()
            md5.update(currentHash)
            md5.update(password)
            md5.update(salt)
            currentHash = md5.digest()

            val copyLen = minOf(currentHash.size, keyAndIv.size - offset)
            System.arraycopy(currentHash, 0, keyAndIv, offset, copyLen)
            offset += copyLen
        }

        val key = keyAndIv.copyOfRange(0, keyLen)
        val iv = keyAndIv.copyOfRange(keyLen, keyLen + ivLen)
        return Pair(key, iv)
    }

    private fun decryptCryptoJs(cipherTextB64: String, password: String): String? {
        return try {
            val raw = Base64.decode(cipherTextB64, Base64.DEFAULT)
            if (raw.size < 16) return null
            val salt = raw.copyOfRange(8, 16)
            val cipherBytes = raw.copyOfRange(16, raw.size)

            val (key, iv) = evpBytesToKey(password.toByteArray(Charsets.UTF_8), salt)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val decrypted = cipher.doFinal(cipherBytes)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            null
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

            // Dizibox King Player
            if (fixedIframe.contains("/player/king/")) {
                try {
                    val kingHtml = app.get(fixedIframe, headers = mapOf("Referer" to "$mainUrl/")).text
                    val molyIframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(kingHtml)?.groupValues?.get(1)

                    if (molyIframe != null) {
                        val fixedMoly = fixUrl(molyIframe)

                        // 1. Doğrudan sheila m3u8 endpoint'i (Hızlı ve stabil)
                        val sheilaUrl = if (fixedMoly.contains("/embed/")) {
                            fixedMoly.replace("/embed/", "/embed/sheila/")
                        } else null

                        if (sheilaUrl != null) {
                            try {
                                M3u8Helper.generateM3u8(
                                    source = "$name - MolyStream",
                                    name = "$name - MolyStream",
                                    streamUrl = sheilaUrl,
                                    referer = fixedMoly,
                                    headers = mapOf("Referer" to fixedMoly)
                                ).forEach(callback)
                            } catch (e: Exception) {
                                // ignore
                            }
                        }

                        // 2. CryptoJS şifre çözücü ile kontrol
                        val molyHtml = app.get(fixedMoly, headers = mapOf("Referer" to "$mainUrl/")).text
                        val cryptoMatch = Regex("""CryptoJS\.AES\.decrypt\(\"([^\"]+)\"\s*,\s*\"([^\"]+)\"\)""").find(molyHtml)
                        if (cryptoMatch != null) {
                            val ct = cryptoMatch.groupValues[1]
                            val pw = cryptoMatch.groupValues[2]
                            val decryptedHtml = decryptCryptoJs(ct, pw)

                            if (decryptedHtml != null) {
                                val fileMatch = Regex("""file\s*:\s*["']([^"']+)["']""").find(decryptedHtml)?.groupValues?.get(1)
                                if (fileMatch != null) {
                                    val streamUrl = fixUrl(fileMatch)
                                    M3u8Helper.generateM3u8(
                                        source = this.name,
                                        name = this.name,
                                        streamUrl = streamUrl,
                                        referer = fixedMoly,
                                        headers = mapOf("Referer" to fixedMoly)
                                    ).forEach(callback)
                                }
                            }
                        }

                        loadExtractor(fixedMoly, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }

            // Diğer tüm oynatıcıları otomatik tara
            loadExtractor(fixedIframe, subtitleCallback, callback)
        }

        return true
    }
}
