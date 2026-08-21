package com.cloudstream.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Base64
import java.nio.charset.StandardCharsets

class FilmMakinesiProvider : MainAPI() {
    override var mainUrl = "https://filmmakinesi.to"
    override var name = "FilmMakinesi"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Son Eklenen Filmler",
        "yabanci-dizi-izle-1/" to "Son Eklenen Diziler",
        "film-izle/olmeden-izlenmesi-gerekenler-fm1/" to "Tavsiye Filmler",
        "filmler-1/" to "Tum Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        } else {
            if (request.data.isEmpty()) "$mainUrl/sayfa/$page/" else "$mainUrl/${request.data}sayfa/$page/"
        }

        val doc = app.get(url).document
        val home = doc.select(".slide, .item, a.slide").mapNotNull {
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
        val linkElem = if (this.tagName() == "a") this else this.selectFirst("a[href*='/film/'], a[href*='/dizi/']") ?: this.selectFirst("a[href]") ?: return null
        val href = fixUrl(linkElem.attr("href"))
        if (href == mainUrl || href.endsWith("/#") || href.contains("/tur/") || href.contains("filmler-1") || href.contains("/sayfa/")) return null

        val title = this.selectFirst(".item-title a, .item-title, .title:not(.title--area *)")?.text()?.trim()
            ?.ifEmpty { null }
            ?: linkElem.attr("title").trim().ifEmpty { null }
            ?: linkElem.attr("alt").trim().ifEmpty { null }
            ?: return null

        if (title.equals("Sonuçlar", ignoreCase = true) || title.equals("Keşfet", ignoreCase = true)) return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
                ?.ifEmpty { null }
                ?: this.selectFirst("img")?.attr("src")
        )

        val isTvSeries = href.contains("/dizi/")

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
        val doc = app.post(mainUrl, data = mapOf("s" to query)).document
        return doc.select(".item, a[href*='/film/'], a[href*='/dizi/']").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "Film"
        val title = rawTitle.replace(Regex("""(?i)\s*(izle|film izle|full hd).*"""), "")
            .replace(Regex("""\s*\(\d{4}\)\s*$"""), "").trim()

        val posterUrl = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".movie-poster img, .poster img, .entry-content img")?.attr("src")
        )
        val description = doc.selectFirst(".entry-content p, .overview, .film-story, .movie-story, .story, meta[name='description']")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
        val year = doc.selectFirst("a[href*='/yil/']")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val score = Score.from10(doc.selectFirst(".imdb-score, .rating, .score")?.text()?.trim()?.replace(",", ".")?.toDoubleOrNull())
        val tags = doc.select("a[href*='/tur/']").map { it.text().trim() }.filter { it.isNotEmpty() }

        val isTvSeries = url.contains("/dizi/") || doc.select(".season-wrapper, .episodes").isNotEmpty()

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            doc.select(".season-wrapper, .season-list").forEachIndexed { sIdx, sElem ->
                val seasonNum = sIdx + 1
                sElem.select("a[href*='/bolum/'], .episode a").forEachIndexed { epIdx, epElem ->
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
     * Generic decoder for closeload dc_ functions.
     * Dynamically detects operations (reverse, atob, rot, xor) from the JS function body
     * and applies them in order to decode the stream URL.
     */
    private fun decodeStreamUrl(embedHtml: String): String? {
        val callMatch = Regex("""dc_[A-Za-z0-9_]+\s*\(\s*\[(.*?)\]\s*\)""").find(embedHtml) ?: return null
        val rawArray = callMatch.groupValues[1]
        val parts = rawArray.split(",").map { it.trim('"', '\'', ' ', ';') }

        val funcMatch = Regex("""function\s+dc_[A-Za-z0-9_]+\s*\([^)]*\)\s*\{([\s\S]*?)(?:return\s+unmix;|return\s+result;)""").find(embedHtml) ?: return null
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

        val iframes = mutableListOf<Pair<String, String>>()

        // 1. Get iframes (lazy-loaded with data-src)
        doc.select("iframe[src], iframe[data-src]").forEach {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.isNotEmpty() && !src.contains("youtube.com") && !src.contains("youtu.be")) {
                iframes.add(Pair(fixUrl(src), "Varsayılan"))
            }
        }

        // 2. Get player buttons with data-video_url (the actual attribute name used on the site)
        doc.select("#player-section [data-video_url], .player-section [data-video_url], .video-parts [data-video_url]").forEach {
            val src = it.attr("data-video_url")
            if (src.isNotEmpty() && !src.contains("youtube.com") && !src.contains("youtu.be")) {
                val btnName = it.text().trim().ifEmpty { "Alternatif" }
                iframes.add(Pair(fixUrl(src), btnName))
            }
        }

        // 3. Fallback: data-src, data-url, data-video in player section
        doc.select(".player-section [data-src], .player-section [data-url], [data-video]").forEach {
            val src = it.attr("data-src").ifEmpty { it.attr("data-url") }.ifEmpty { it.attr("data-video") }
            if (src.isNotEmpty() && !src.contains("youtube.com") && !src.contains("youtu.be")) {
                val btnName = it.text().trim().ifEmpty { "Alternatif" }
                iframes.add(Pair(fixUrl(src), btnName))
            }
        }

        for ((sourceUrl, labelName) in iframes.distinctBy { it.first }) {
            try {
                if (sourceUrl.contains("closeload") || sourceUrl.contains("filmmakinesi") || sourceUrl.contains("playmix") || sourceUrl.contains("rapid")) {
                    val embedDoc = app.get(sourceUrl, referer = data).text

                    val streamUrls = mutableListOf<String>()

                    // Try dc_ decoder first (for closeload embeds)
                    val decodedStream = decodeStreamUrl(embedDoc)
                    if (decodedStream != null) {
                        streamUrls.add(decodedStream)
                    }

                    // Also capture any direct m3u8/txt/mp4 URLs in the embed page
                    val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|txt|mp4)[^\s"'<>]*)""")
                    m3u8Regex.findAll(embedDoc).forEach { match ->
                        val videoUrl = match.value.replace("\\/", "/")
                        if (!videoUrl.contains("player") && !videoUrl.contains("favicon") && !videoUrl.contains(".vtt")) {
                            streamUrls.add(videoUrl)
                        }
                    }

                    // Determine the correct referer for this embed
                    val embedReferer = when {
                        sourceUrl.contains("closeload") -> "https://closeload.filmmakinesi.to/"
                        sourceUrl.contains("rapid") -> "https://rapid.filmmakinesi.to/"
                        else -> "$mainUrl/"
                    }

                    for (videoUrl in streamUrls.distinct()) {
                        val playerHeaders = mapOf(
                            "Referer" to embedReferer,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        )

                        // Emit Master M3U8 URL directly (ensures ExoPlayer loads audio + video)
                        val masterLink = newExtractorLink(
                            source = name,
                            name = "$name - $labelName",
                            url = videoUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = embedReferer
                            this.headers = playerHeaders
                            this.quality = Qualities.P1080.value
                        }
                        callback.invoke(masterLink)

                        // Also emit resolution sub-links as fallbacks
                        try {
                            val m3u8Links = M3u8Helper.generateM3u8(
                                source = name,
                                streamUrl = videoUrl,
                                referer = embedReferer,
                                headers = playerHeaders
                            )
                            m3u8Links.forEach { link ->
                                val customLink = newExtractorLink(
                                    source = link.source,
                                    name = "$name - $labelName (${link.name})",
                                    url = link.url,
                                    type = if (link.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = embedReferer
                                    this.headers = playerHeaders
                                    this.quality = link.quality
                                }
                                callback.invoke(customLink)
                            }
                        } catch (_: Exception) {
                            // M3u8Helper might fail, master link already emitted
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
