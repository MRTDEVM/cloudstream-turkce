package com.cloudstream.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Base64
import java.nio.charset.StandardCharsets

class FullHDFilmizleseneProvider : MainAPI() {
    override var mainUrl = "https://www.fullhdfilmizlesene.now"
    override var name = "FullHDFilmizlesene"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "" to "Son Eklenen Filmler",
        "filmizle/1080p-filmler-2" to "1080p Filmler",
        "filmizle/imdb-puani-yuksek-filmler" to "IMDb Puani Yuksek",
        "filmizle/turkce-dublaj-filmler-1" to "Turkce Dublaj Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        } else {
            if (request.data.isEmpty()) "$mainUrl/yeni-filmler/$page" else "$mainUrl/${request.data}/$page"
        }

        val doc = app.get(url).document
        val home = doc.select("div.film").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Card structure: <div class="film"> <a class="tt" href="...">Title izle</a> <h2 class="film-tt"><span class="film-title">Title</span></h2> <picture>...</picture> </div>
        val linkElem = this.selectFirst("a.tt[href]") ?: this.selectFirst("a[href*='/film/']") ?: return null
        val href = fixUrl(linkElem.attr("href"))
        if (href == mainUrl || href.endsWith("/#") || href.contains("/filmizle/") || href.contains("/kategori/")) return null

        // Get title from span.film-title (cleaner) or from the link text (contains "izle")
        val title = this.selectFirst("span.film-title")?.text()?.trim()
            ?.ifEmpty { null }
            ?: linkElem.text().replace(Regex("""\s*-\s*.*?izle$"""), "").replace(Regex("""\s+izle$"""), "").trim()
                .ifEmpty { null }
            ?: return null

        // Poster from <picture> -> <img> or from <img> with data-src
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
                ?.ifEmpty { null }
                ?: this.selectFirst("img")?.attr("src")
                    ?.let { if (it.startsWith("data:")) null else it }
                ?: this.selectFirst("source[srcset]")?.attr("srcset")?.split(" ")?.firstOrNull()
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "utf-8")
        val searchUrl = "$mainUrl/arama/$encodedQuery"
        val doc = app.get(searchUrl).document

        // Search page may use different card structure than main page
        val results = mutableListOf<SearchResponse>()

        // Try div.film cards first
        doc.select("div.film").mapNotNull { it.toSearchResult() }.let { results.addAll(it) }

        // If no div.film results, try a.tt links directly
        if (results.isEmpty()) {
            doc.select("a.tt[href*='/film/']").forEach { link ->
                val href = fixUrl(link.attr("href"))
                val rawTitle = link.text().trim()
                val title = rawTitle.replace(Regex("""\s*-\s*.*?izle$"""), "").replace(Regex("""\s+izle$"""), "").trim()
                if (title.isNotEmpty()) {
                    val parent = link.parent()
                    val posterUrl = fixUrlNull(
                        parent?.selectFirst("img[data-src]")?.attr("data-src")
                            ?: parent?.selectFirst("img[src]")?.attr("src")
                                ?.let { if (it.startsWith("data:")) null else it }
                    )
                    results.add(newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = posterUrl
                    })
                }
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "Film"
        // Clean title: remove " izle" suffix and year in title
        val title = rawTitle.replace(Regex("""(?i)\s*(izle|film izle).*"""), "").trim()

        val posterUrl = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("img[data-src*='/poster/']")?.attr("data-src")
                ?: doc.selectFirst(".film-afis img, .poster img")?.attr("src")
        )

        // Description from div.film-ozeti
        val description = doc.selectFirst(".film-ozeti .ozet-ic p, .film-ozeti p, .ozet-ic")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        val year = doc.selectFirst("span.film-yil")?.text()?.filter { it.isDigit() }?.toIntOrNull()
            ?: doc.selectFirst("a[href*='/yapim-yili/']")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val score = Score.from10(doc.selectFirst(".imdb, .imdb-puani")?.text()?.trim()?.replace(",", ".")?.toDoubleOrNull())

        val tags = doc.select(".film-info a[href*='/filmizle/']").map { it.text().trim() }.filter { it.isNotEmpty() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = description
            this.year = year
            this.tags = tags
            this.score = score
        }
    }

    /**
     * Decode FullHDFilmizlesene scx embed codes.
     * The encoding is: original URL → Base64 → ROT13
     * So decoding is: ROT13 → Base64 decode
     */
    private fun decodeScxCode(encoded: String): String? {
        return try {
            // Step 1: ROT13
            val rot13 = encoded.map { c ->
                when (c) {
                    in 'a'..'z' -> ((c.code - 97 + 13) % 26 + 97).toChar()
                    in 'A'..'Z' -> ((c.code - 65 + 13) % 26 + 65).toChar()
                    else -> c
                }
            }.joinToString("")

            // Step 2: Base64 decode
            val pad = (4 - rot13.length % 4) % 4
            val padded = rot13 + "=".repeat(pad)
            String(Base64.decode(padded, Base64.DEFAULT), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decode rapidvid.net video URL from the _() function.
     * The encoding is: original URL → Base64 → char shift with key → Base64 → reverse
     * So decoding is: reverse → Base64 decode → reverse char shift → Base64 decode
     *
     * If the key or parameters change, this function extracts them dynamically from the page.
     */
    private fun decodeRapidvidUrl(embedHtml: String): String? {
        // Find the av() or _() call with the encoded source
        val avMatch = Regex("""(?:av|_)\s*\(\s*['"]([^'"]+)['"]""").find(embedHtml) ?: return null
        val encoded = avMatch.groupValues[1]

        // Extract the decoder key and shift from the _() function
        val funcMatch = Regex("""function\s+_\s*\(\s*\w+\s*\)\s*\{([\s\S]*?)\}""").find(embedHtml)
        val key: String
        val modVal: Int
        val addVal: Int

        if (funcMatch != null) {
            val funcBody = funcMatch.groupValues[1]
            val keyMatch = Regex(""""([^"]{2,5})"\s*\[\s*\w+\s*%\s*(\d+)\s*\]""").find(funcBody)
            val shiftMatch = Regex("""charCodeAt\s*\(\s*0\s*\)\s*%\s*(\d+)\s*\+\s*(\d+)""").find(funcBody)
            key = keyMatch?.groupValues?.get(1) ?: "K9L"
            modVal = shiftMatch?.groupValues?.get(1)?.toIntOrNull() ?: 5
            addVal = shiftMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1
        } else {
            key = "K9L"
            modVal = 5
            addVal = 1
        }

        return try {
            // Step 1: Reverse the string and Base64 decode
            val reversed = encoded.reversed()
            val pad1 = (4 - reversed.length % 4) % 4
            val step1 = String(Base64.decode(reversed + "=".repeat(pad1), Base64.DEFAULT), StandardCharsets.ISO_8859_1)

            // Step 2: Reverse the character shift
            val shifted = StringBuilder()
            for (i in step1.indices) {
                val r = key[i % key.length]
                val n = step1[i].code - (r.code % modVal + addVal)
                shifted.append(n.toChar())
            }

            // Step 3: Base64 decode to get the final URL
            val pad2 = (4 - shifted.length % 4) % 4
            String(Base64.decode(shifted.toString() + "=".repeat(pad2), Base64.DEFAULT), StandardCharsets.UTF_8)
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
        val pageHtml = app.get(data).text

        // Extract the scx variable from inline JavaScript
        val scxMatch = Regex("""var\s+scx\s*=\s*(\{.*?\})\s*;""").find(pageHtml)
        if (scxMatch != null) {
            val scxJson = scxMatch.groupValues[1]

            // Extract all embed codes from scx.  Structure: {"source":{"tt":"base64name","sx":{"p":[...],"t":["code"]}}}
            // "t" = single/full video codes, "p" = part codes
            val codeMatches = Regex(""""t"\s*:\s*\[\s*"([^"]+)"\s*\]""").findAll(scxJson)

            for (codeMatch in codeMatches) {
                val encodedCode = codeMatch.groupValues[1]
                val embedUrl = decodeScxCode(encodedCode)

                if (embedUrl != null && embedUrl.startsWith("http")) {
                    try {
                        if (embedUrl.contains("rapidvid")) {
                            processRapidvidEmbed(embedUrl, data, subtitleCallback, callback)
                        } else {
                            // Other embed types - try loadExtractor
                            loadExtractor(embedUrl, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        // Ignore individual embed errors
                    }
                }
            }

            // Also check for part-based codes (multi-part movies)
            val partMatches = Regex(""""p"\s*:\s*\[\s*((?:"[^"]+"\s*,?\s*)+)\s*\]""").findAll(scxJson)
            for (partMatch in partMatches) {
                val partCodes = Regex(""""([^"]+)"""").findAll(partMatch.groupValues[1])
                for ((partIdx, partCode) in partCodes.withIndex()) {
                    val partUrl = decodeScxCode(partCode.groupValues[1])
                    if (partUrl != null && partUrl.startsWith("http")) {
                        try {
                            if (partUrl.contains("rapidvid")) {
                                processRapidvidEmbed(partUrl, data, subtitleCallback, callback, "Part ${partIdx + 1}")
                            } else {
                                loadExtractor(partUrl, subtitleCallback, callback)
                            }
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }
            }
        }

        return true
    }

    /**
     * Process a rapidvid.net embed page: decode the video URL and extract subtitles.
     */
    private suspend fun processRapidvidEmbed(
        embedUrl: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        partLabel: String? = null
    ) {
        val embedHtml = app.get(embedUrl, referer = refererUrl).text

        // Decode the video URL from the av()/_() function
        val videoUrl = decodeRapidvidUrl(embedHtml) ?: return

        val displayName = if (partLabel != null) "$name ($partLabel)" else "$name (Sesli Oynatıcı)"

        val playerHeaders = mapOf(
            "Referer" to "https://rapidvid.net/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        // Emit the master M3U8 link (contains both audio and video tracks)
        val masterLink = newExtractorLink(
            source = name,
            name = displayName,
            url = videoUrl,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = "https://rapidvid.net/"
            this.headers = playerHeaders
            this.quality = Qualities.P1080.value
        }
        callback.invoke(masterLink)

        // Also generate resolution sub-links as fallbacks
        try {
            val m3u8Links = M3u8Helper.generateM3u8(
                source = name,
                streamUrl = videoUrl,
                referer = "https://rapidvid.net/",
                headers = playerHeaders
            )
            m3u8Links.forEach { link ->
                val customLink = newExtractorLink(
                    source = link.source,
                    name = link.name,
                    url = link.url,
                    type = if (link.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://rapidvid.net/"
                    this.headers = playerHeaders
                    this.quality = link.quality
                }
                callback.invoke(customLink)
            }
        } catch (_: Exception) {
            // M3u8Helper might fail, master link already emitted
        }

        // Extract VTT subtitles - flexible regex for various JSON orderings
        val vttRegex = Regex(""""file"\s*:\s*"([^"]+\.vtt[^"]*)".{0,50}?"label"\s*:\s*"([^"]+)"""")
        vttRegex.findAll(embedHtml).forEach { match ->
            val subUrl = match.groupValues[1].replace("\\/", "/")
            val subLang = match.groupValues[2]
                .replace("\\u00fc", "ü").replace("\\u00e7", "ç")
                .replace("\\u0131", "ı").replace("\\u00f6", "ö")
                .trim()
            subtitleCallback.invoke(
                SubtitleFile(
                    lang = subLang,
                    url = subUrl
                )
            )
        }
    }
}
