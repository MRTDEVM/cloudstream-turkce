package com.cloudstream.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Base64
import java.nio.charset.StandardCharsets

class HdfilmcehennemiProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Son Eklenen Filmler",
        "dizi/" to "Son Eklenen Diziler",
        "category/tavsiye-filmler-izle2/" to "Tavsiye Filmler",
        "imdb-7-puan-uzeri-filmler-2/" to "IMDb 7+ Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        } else {
            if (request.data.isEmpty()) "$mainUrl/page/$page/" else "$mainUrl/${request.data}page/$page/"
        }

        val doc = app.get(url).document
        val home = doc.select("a.poster, div.poster, div.mini-poster, .poster-wrapper").mapNotNull {
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
        val linkElem = if (this.tagName() == "a") this else this.selectFirst("a[href]") ?: return null
        val href = fixUrl(linkElem.attr("href"))
        if (href == mainUrl || href.endsWith("/#") || href.contains("/category/") || href.contains("/tur/") || href.contains("/page/")) return null

        val title = this.selectFirst(".poster-title, .mini-poster-title, .title, h2, h3")?.text()?.trim()
            ?.ifEmpty { null }
            ?: linkElem.attr("title").trim().ifEmpty { null }
            ?: linkElem.attr("aria-label").trim().ifEmpty { null }
            ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
                ?.ifEmpty { null }
                ?: this.selectFirst("img")?.attr("src")
                    ?.let { if (it.startsWith("data:")) null else it }
                ?: this.selectFirst("img")?.attr("srcset")?.split(" ")?.firstOrNull()
        )

        val isTvSeries = href.contains("/dizi/") || this.selectFirst(".badge-dizi, .is-series, .mini-poster-meta")?.text()?.contains("Dizi", ignoreCase = true) == true

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=${query}"
        val jsonResp = app.get(
            searchUrl,
            referer = mainUrl,
            headers = mapOf("X-Requested-With" to "fetch", "Accept" to "application/json")
        ).text

        val results = mutableListOf<SearchResponse>()
        val combinedHtml = jsonResp
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\n", "\n")
        val fragDoc = org.jsoup.Jsoup.parse(combinedHtml)
        fragDoc.select("a[href*='hdfilmcehennemi']").forEach { link ->
            val href = fixUrl(link.attr("href"))
            if (!href.contains("/category/") && !href.contains("/tur/") && href != mainUrl) {
                val title = link.selectFirst("strong, .title, h3, h4")?.text()?.trim()
                    ?: link.attr("title").trim()
                val poster = fixUrlNull(
                    link.selectFirst("img")?.attr("data-src")
                        ?: link.selectFirst("img")?.attr("src")?.let { if (it.startsWith("data:")) null else it }
                )
                if (title.isNotEmpty()) {
                    val isSeries = href.contains("/dizi/")
                    if (isSeries) {
                        results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster })
                    } else {
                        results.add(newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster })
                    }
                }
            }
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("h1, .poster-title, .movie-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "Film"
        val title = rawTitle.replace(Regex("""(?i)\s*(izle|film izle|hd film izle).*"""), "").trim()

        val posterUrl = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".poster-media img, .movie-poster img, .poster img")?.attr("data-src")
                ?: doc.selectFirst(".poster-media img, .movie-poster img, .poster img")?.attr("src")
        )
        val description = doc.selectFirst(".movie-story, .story, .overview, p.description, .entry-content, .film-ozeti, .ozet, meta[name='description']")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
        val year = doc.selectFirst("a[href*='/yil/'], span.year, .release-date")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val score = Score.from10(doc.selectFirst(".imdb-score, .rating, .score")?.text()?.trim()?.replace(",", ".")?.toDoubleOrNull())
        val tags = doc.select("a[href*='/tur/']").map { it.text().trim() }.filter { it.isNotEmpty() }

        val isTvSeries = url.contains("/dizi/") || doc.select(".season-wrapper, .episode-list, .season").isNotEmpty()

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            doc.select(".season-wrapper, .season").forEachIndexed { seasonIdx, seasonElem ->
                val seasonNum = seasonIdx + 1
                seasonElem.select("a[href*='/bolum/'], .episode-item a, a[href*='/dizi/']").forEachIndexed { epIdx, epElem ->
                    val epUrl = fixUrl(epElem.attr("href"))
                    val epName = epElem.text().trim().ifEmpty { "Bolum ${epIdx + 1}" }
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epIdx + 1
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = score
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = score
            }
        }
    }

    /**
     * Unpack Dean Edwards packed JavaScript code p,a,c,k,e,d format
     */
    private fun unpackJs(packedCode: String): String {
        val match = Regex("""\}\s*\(\s*'([\s\S]*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([\s\S]*?)'\.split\('\|'\)""").find(packedCode)
            ?: return packedCode
        val payload = match.groupValues[1]
        val radix = match.groupValues[2].toIntOrNull() ?: 36
        val syms = match.groupValues[4].split("|")
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        fun lookup(word: String): String {
            var res = 0
            for (c in word) {
                val idx = chars.indexOf(c)
                if (idx >= 0) {
                    res = res * radix + idx
                }
            }
            return if (res < syms.size && syms[res].isNotEmpty()) syms[res] else word
        }

        return Regex("""\b\w+\b""").replace(payload) { lookup(it.value) }
    }

    /**
     * Generic decoder for closeload & rplayer dc_ functions.
     * Dynamically detects operations (reverse, atob, rot, xor) from JS function body.
     */
    private fun decodeStreamUrl(embedHtml: String): String? {
        val unpacked = unpackJs(embedHtml)

        val callMatch = Regex("""dc_[A-Za-z0-9_]+\s*\(\s*\[(.*?)\]\s*\)""").find(unpacked) ?: return null
        val rawArray = callMatch.groupValues[1]
        val parts = rawArray.split(",").map { it.trim('"', '\'', ' ', ';') }

        val funcMatch = Regex("""function\s+dc_[A-Za-z0-9_]+\s*\([^)]*\)\s*\{([\s\S]*?)(?:return\s+unmix;|return\s+result;)""").find(unpacked) ?: return null
        val body = funcMatch.groupValues[1]

        var curr = parts.joinToString("")

        data class Op(val index: Int, val type: String, val value: Any?)
        val ops = mutableListOf<Op>()

        Regex("""atob\(""").findAll(body).forEach { ops.add(Op(it.range.first, "atob", null)) }
        Regex("""reverse\(""").findAll(body).forEach { ops.add(Op(it.range.first, "reverse", null)) }
        Regex("""replace\(/\[a-zA-Z\]/g""").findAll(body).forEach { match ->
            val sub = body.substring(match.range.first, (match.range.first + 200).coerceAtMost(body.length))
            val shiftMatch = Regex("""o\s*-\s*base\s*\+\s*(\d+)""").find(sub)
            val shift = shiftMatch?.groupValues?.get(1)?.toIntOrNull() ?: 6
            ops.add(Op(match.range.first, "rot", shift))
        }
        Regex("""for\s*\(""").findAll(body).forEach { match ->
            val accMatch = Regex("""var\s+acc\s*=\s*(\d+)""").find(body)
            val stepMatch = Regex("""acc\s*=\s*\(\s*acc\s*\+\s*(\d+)\s*\)""").find(body)
            if (accMatch != null && stepMatch != null) {
                val acc = accMatch.groupValues[1].toInt()
                val step = stepMatch.groupValues[1].toInt()
                ops.add(Op(match.range.first, "xor", Pair(acc, step)))
            }
        }

        ops.sortBy { it.index }

        for (op in ops) {
            when (op.type) {
                "atob" -> {
                    val pad = (4 - curr.length % 4) % 4
                    curr += "=".repeat(pad)
                    curr = String(Base64.decode(curr, Base64.DEFAULT), StandardCharsets.ISO_8859_1)
                }
                "reverse" -> {
                    curr = curr.reversed()
                }
                "rot" -> {
                    val shift = op.value as Int
                    curr = curr.map { c ->
                        when (c) {
                            in 'a'..'z' -> ((c.code - 97 + shift) % 26 + 97).toChar()
                            in 'A'..'Z' -> ((c.code - 65 + shift) % 26 + 65).toChar()
                            else -> c
                        }
                    }.joinToString("")
                }
                "xor" -> {
                    val pair = op.value as Pair<*, *>
                    val startAcc = pair.first as Int
                    val step = pair.second as Int
                    var acc = startAcc
                    val unmix = StringBuilder()
                    for (char in curr) {
                        val byte = char.code and 0xFF
                        acc = (acc + step) % 256
                        val plain = byte xor acc
                        acc = (acc + byte) % 256
                        unmix.append(plain.toChar())
                    }
                    curr = unmix.toString()
                    break
                }
            }
        }

        return if (curr.startsWith("http")) curr else null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val pageHtml = doc.html()

        val embedSources = mutableListOf<Pair<String, String>>() // Pair(embedUrl, label)

        // 1. Check video alternatives (data-video) grouped by language container
        val altDivs = doc.select(".alternative-links")
        if (altDivs.isNotEmpty()) {
            altDivs.forEach { div ->
                val langAttr = div.attr("data-lang")
                val langLabel = when (langAttr) {
                    "tr" -> "Türkçe Dublaj"
                    "en" -> "Türkçe Altyazılı"
                    else -> "TR-EN Dual"
                }

                div.select("button[data-video], a[data-video]").forEach { btn ->
                    val videoId = btn.attr("data-video")
                    val btnName = btn.text().trim().ifEmpty { "Alternatif" }

                    if (videoId.isNotEmpty()) {
                        try {
                            // Call AJAX endpoint /video/{id}/ to fetch JSON iframe
                            val jsonUrl = "$mainUrl/video/$videoId/"
                            val jsonResp = app.get(
                                jsonUrl,
                                referer = data,
                                headers = mapOf("X-Requested-With" to "fetch", "Accept" to "application/json")
                            ).text

                            val iframeMatch = Regex("""(?:data-src|src)\\?=\\?"([^"\\]+)""").find(jsonResp)
                            if (iframeMatch != null) {
                                val rawIframe = iframeMatch.groupValues[1]
                                if (!rawIframe.isNullOrEmpty()) {
                                    val iframeUrl = fixUrl(rawIframe.replace("\\/", "/"))
                                    embedSources.add(Pair(iframeUrl, "$langLabel ($btnName)"))
                                }
                            }
                        } catch (_: Exception) {
                            // Ignore
                        }
                    }
                }
            }
        }

        // 2. Direct iframes in page if any
        doc.select("iframe[src], iframe[data-src]").forEach {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.isNotEmpty() && !src.contains("youtube.com") && !src.contains("youtu.be")) {
                embedSources.add(Pair(fixUrl(src), "Varsayılan"))
            }
        }

        for ((sourceUrl, optionLabel) in embedSources.distinctBy { it.first }) {
            try {
                if (sourceUrl.contains("hdfilmcehennemi") || sourceUrl.contains("rapid") || sourceUrl.contains("closeload") || sourceUrl.contains("playmix") || sourceUrl.contains("rplayer")) {
                    val embedDoc = app.get(sourceUrl, referer = data).text

                    val streamUrls = mutableListOf<String>()

                    // Try dc_ decoder
                    val decodedStream = decodeStreamUrl(embedDoc)
                    if (decodedStream != null) {
                        streamUrls.add(decodedStream)
                    }

                    // Direct m3u8/txt URLs in embed HTML
                    val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|txt|mp4)[^\s"'<>]*)""")
                    m3u8Regex.findAll(embedDoc).forEach { match ->
                        val videoUrl = match.value.replace("\\/", "/")
                        if (!videoUrl.contains("player") && !videoUrl.contains("favicon") && !videoUrl.contains(".vtt")) {
                            streamUrls.add(videoUrl)
                        }
                    }

                    val playerHeaders = mapOf(
                        "Referer" to "https://hdfilmcehennemi.mobi/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )

                    for (videoUrl in streamUrls.distinct()) {
                        val streamName = "$name - $optionLabel (Sesli Oynatıcı)"

                        // 1. Emit Master M3U8 URL directly (Ensures ExoPlayer loads audio track + video)
                        val masterLink = newExtractorLink(
                            source = name,
                            name = streamName,
                            url = videoUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://hdfilmcehennemi.mobi/"
                            this.headers = playerHeaders
                            this.quality = Qualities.P1080.value
                        }
                        callback.invoke(masterLink)

                        // 2. Resolution sub-links as fallbacks
                        try {
                            val m3u8Links = M3u8Helper.generateM3u8(
                                source = name,
                                streamUrl = videoUrl,
                                referer = "https://hdfilmcehennemi.mobi/",
                                headers = playerHeaders
                            )
                            m3u8Links.forEach { link ->
                                val customLink = newExtractorLink(
                                    source = link.source,
                                    name = "$name - $optionLabel (${link.name})",
                                    url = link.url,
                                    type = if (link.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://hdfilmcehennemi.mobi/"
                                    this.headers = playerHeaders
                                    this.quality = link.quality
                                }
                                callback.invoke(customLink)
                            }
                        } catch (_: Exception) {
                            // M3u8Helper fallback
                        }
                    }

                    // VTT Subtitles - flexible regex for various JSON orderings
                    val vttRegex = Regex(""""file"\s*:\s*"([^"]+\.vtt[^"]*)".{0,50}?"label"\s*:\s*"([^"]+)"""")
                    vttRegex.findAll(embedDoc).forEach { match ->
                        val subUrl = match.groupValues[1].replace("\\/", "/")
                        val subLang = match.groupValues[2]
                            .replace("\\u00fc", "ü").replace("\\u00e7", "ç")
                            .replace("\\u0131", "ı").replace("\\u00f6", "ö")
                        subtitleCallback.invoke(
                            SubtitleFile(
                                lang = subLang,
                                url = subUrl
                            )
                        )
                    }
                } else {
                    loadExtractor(sourceUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Ignore individual embed error
            }
        }

        return true
    }
}
