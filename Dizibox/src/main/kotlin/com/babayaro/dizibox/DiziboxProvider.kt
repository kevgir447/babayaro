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

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "https://www.dizibox.live/"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = if (this.tagName() == "a") this else (this.selectFirst("a.episode-card-title, a.poster-title, h3 a, h2 a, a.figure-link, a") ?: return null)
        val href = fixUrlNull(link.attr("href")) ?: return null

        // Menü ve gereksiz sayfaları atla
        if (href.contains("/arsiv/") || href.contains("/dizi-takvimi/") || href.contains("/yardim/") ||
            href.contains("/iletisim/") || href.contains("/wp-login") || href.contains("/author/") ||
            href == "$mainUrl/" || href.contains("/tum-bolumler/")) {
            return null
        }

        val title = this.selectFirst(".series-name")?.text()?.ifEmpty { null }
            ?: link.attr("title").ifEmpty { null }
            ?: this.selectFirst("h3, h2, h4, .poster-title, .title")?.text()?.ifEmpty { null }
            ?: this.selectFirst(".main-cover, .afis, img.wp-post-image")?.attr("alt")?.ifEmpty { null }
            ?: return null

        val posterUrl = this.selectFirst(".main-cover, .afis, img.wp-post-image, figure img, img[data-src], img")?.let { img ->
            val src = img.attr("data-src").ifEmpty { img.attr("data-original") }.ifEmpty { img.attr("src") }
            if (src.startsWith("data:") || src.contains("altyazi") || src.contains("dublaj") || src.endsWith(".svg")) null else fixUrlNull(src)
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
        val document = app.get(url, headers = defaultHeaders).document

        val items = document.select("article, .article-series-poster, .article-episode-card, .detailed-article, .post, .dizibox-list li, .archive-item").mapNotNull {
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
        return try {
            val searchUrl = "$mainUrl/search/${query.trim().replace(" ", "+")}/"
            val document = app.get(searchUrl, headers = defaultHeaders).document

            document.select("article, .article-series-poster, .article-episode-card, .detailed-article, .post, .entry, .search-result").mapNotNull {
                it.toSearchResponse()
            }.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document

        // Eğer bölüm linkine tıklandıysa (örn: mobland-2-sezon-7-bolum-izle), dizinin tüm bölümlerini almak için ana dizi sayfasını bul
        val seriesHref = if (url.contains("-sezon-") && url.contains("-bolum-")) {
            document.selectFirst("a[title*='izle'][href*='/diziler/'], a.figure[href*='/diziler/'], a[href*='/diziler/']")?.attr("href")?.let { fixUrlNull(it) }
        } else null

        val targetDoc = if (seriesHref != null) {
            try {
                app.get(seriesHref, headers = defaultHeaders).document
            } catch (e: Exception) {
                document
            }
        } else {
            document
        }

        val targetUrl = seriesHref ?: url

        val title = targetDoc.selectFirst("h1.title, h1.entry-title, h1, .m-b-0 a")?.text()
            ?: document.selectFirst("h1.title, h1.entry-title, h1, .m-b-0 a")?.text()
            ?: targetDoc.selectFirst("meta[property=og:title]")?.attr("content")?.replace(" izle", "")?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.replace(" izle", "")?.trim()
            ?: ""

        val posterUrl = targetDoc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: targetDoc.selectFirst(".main-cover, .poster img, .entry-thumbnail img, figure img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".main-cover, .poster img, .entry-thumbnail img, figure img")?.attr("src")

        val plot = targetDoc.selectFirst(".post-summary, .overview, .entry-content p, .story")?.text()
            ?: document.selectFirst(".post-summary, .overview, .entry-content p, .story")?.text()
            ?: targetDoc.selectFirst("meta[property=og:description]")?.attr("content")

        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(targetDoc.select(".custom-field, .meta, .info").text())?.value?.toIntOrNull()
        val tags = targetDoc.select(".genres a, .tags a, .custom-fields-container span").map { it.text() }.filter { it.isNotBlank() }

        val episodes = mutableListOf<Episode>()

        // Sezon listesini tara (#seasons-list)
        val seasonLinks = targetDoc.select("#seasons-list a").mapNotNull { fixUrlNull(it.attr("href")) }.distinct()
        val seasonDocs = mutableListOf<org.jsoup.nodes.Document>()
        seasonDocs.add(targetDoc)

        for (sLink in seasonLinks) {
            if (sLink != targetUrl) {
                try {
                    seasonDocs.add(app.get(sLink, headers = defaultHeaders).document)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        for (sDoc in seasonDocs) {
            sDoc.select("a.season-episode, a[href*='-sezon-'][href*='-bolum-'], .episodes-list a").forEach { epElem ->
                val epHref = fixUrlNull(epElem.attr("href")) ?: return@forEach
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
        }

        // Eğer sezon listesinde bölüm bulunamadıysa mevcut sayfayı tek bölüm olarak ekle
        if (episodes.isEmpty()) {
            val sMatch = Regex("""(\d+)\.\s*[sS]ezon""").find(title)
                ?: Regex("""(\d+)-sezon""").find(url)
            val seasonNum = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

            val eMatch = Regex("""(\d+)\.\s*[bB][oö]l[uü]m""").find(title)
                ?: Regex("""(\d+)-bolum""").find(url)
            val epNum = eMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

            episodes.add(
                newEpisode(url) {
                    this.name = "$seasonNum. Sezon $epNum. Bölüm"
                    this.season = seasonNum
                    this.episode = epNum
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
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
        val document = app.get(data, headers = defaultHeaders).document
        val iframes = document.select("iframe").mapNotNull {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.isNotEmpty()) fixUrl(src) else null
        }.distinct().toMutableList()

        for (fixedIframe in iframes) {
            // Dizibox King Player
            if (fixedIframe.contains("/player/king/")) {
                try {
                    val kingHtml = app.get(
                        fixedIframe,
                        headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to defaultHeaders["User-Agent"]!!
                        )
                    ).text
                    val molyIframe = Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""").find(kingHtml)?.groupValues?.get(1)

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
                                    headers = mapOf("Referer" to fixedMoly, "User-Agent" to defaultHeaders["User-Agent"]!!)
                                ).forEach(callback)
                            } catch (e: Exception) {
                                // ignore
                            }
                        }

                        // 2. CryptoJS şifre çözücü ile kontrol
                        val molyHtml = app.get(
                            fixedMoly,
                            headers = mapOf(
                                "Referer" to "$mainUrl/",
                                "User-Agent" to defaultHeaders["User-Agent"]!!
                            )
                        ).text

                        val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
                        m3u8Regex.findAll(molyHtml).forEach { match ->
                            val streamUrl = match.groupValues[1].replace("\\/", "/")
                            M3u8Helper.generateM3u8(
                                source = "$name - MolyStream",
                                name = "$name - MolyStream",
                                streamUrl = streamUrl,
                                referer = fixedMoly,
                                headers = mapOf("Referer" to fixedMoly, "User-Agent" to defaultHeaders["User-Agent"]!!)
                            ).forEach(callback)
                        }

                        val cryptoMatch = Regex("""CryptoJS\.AES\.decrypt\(\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\)""").find(molyHtml)
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
                                        headers = mapOf("Referer" to fixedMoly, "User-Agent" to defaultHeaders["User-Agent"]!!)
                                    ).forEach(callback)
                                }
                            }
                        }

                        try {
                            loadExtractor(fixedMoly, subtitleCallback, callback)
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }

            // Diğer tüm oynatıcıları otomatik tara
            try {
                loadExtractor(fixedIframe, subtitleCallback, callback)
            } catch (e: Exception) {
                // ignore
            }
        }

        return true
    }
}
