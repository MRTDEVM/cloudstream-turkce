package com.cloudstream.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
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
        "en-cok-izlenen-filmler/" to "Cok Izlenen Filmler",
        "film-arsivi/" to "Film Arsivi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        } else {
            if (request.data.isEmpty()) "$mainUrl/page/$page/" else "$mainUrl/${request.data}page/$page/"
        }

        val doc = app.get(url).document
        val home = doc.select(".item, .content-article, div.thumbnail").mapNotNull {
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
        val linkElem = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(linkElem.attr("href"))
        if (href == mainUrl || href.endsWith("/#") || href.contains("kategori/") || href.contains("tur/")) return null

        val title = this.selectFirst(".item-title, .title, h2, h3")?.text()?.trim()
            ?.ifEmpty { null }
            ?: linkElem.attr("title").trim().ifEmpty { null }
            ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
                ?.ifEmpty { null }
                ?: this.selectFirst("img")?.attr("src")
        )

        val isTvSeries = href.contains("/dizi/") || this.selectFirst(".item-season, .item-ep") != null

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
        val searchUrl = "$mainUrl/?s=$query"
        val doc = app.get(searchUrl).document
        return doc.select(".item, .content-article, div.thumbnail").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .movie-title, .title")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "Film"

        val posterUrl = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".movie-poster img, .poster img, .entry-content img")?.attr("src")
        )
        val description = doc.selectFirst(".entry-content p, .overview, .film-story")?.text()?.trim()
        val year = doc.selectFirst("a[href*='/release-year/'], a[href*='/yil/']")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val score = Score.from10(doc.selectFirst(".imdb-score, .rating, .score")?.text()?.trim()?.replace(",", ".")?.toDoubleOrNull())
        val tags = doc.select("a[href*='/genre/'], a[href*='/kategori/']").map { it.text().trim() }

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
                    curr = String(base64Decode(curr), StandardCharsets.ISO_8859_1)
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
                    @Suppress("UNCHECKED_CAST")
                    val (startAcc, step) = op.value as Pair<Int, Int>
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

        val iframes = mutableListOf<String>()
        doc.select("iframe[src], iframe[data-src]").forEach {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.isNotEmpty() && !src.contains("youtube.com") && !src.contains("youtu.be")) {
                iframes.add(fixUrl(src))
            }
        }

        doc.select(".player-section [data-src], .player-section [data-url], [data-video]").forEach {
            val src = it.attr("data-src").ifEmpty { it.attr("data-url") }.ifEmpty { it.attr("data-video") }
            if (src.isNotEmpty() && !src.contains("youtube.com") && !src.contains("youtu.be")) {
                iframes.add(fixUrl(src))
            }
        }

        for (sourceUrl in iframes.distinct()) {
            try {
                if (sourceUrl.contains("closeload") || sourceUrl.contains("filmmakinesi") || sourceUrl.contains("playmix") || sourceUrl.contains("rapid")) {
                    val embedDoc = app.get(sourceUrl, referer = data).text

                    // Decode dynamic JS stream URL
                    val decodedStream = decodeStreamUrl(embedDoc)
                    val streamUrls = mutableListOf<String>()
                    if (decodedStream != null) {
                        streamUrls.add(decodedStream)
                    }

                    // Fallback plain regex
                    val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|txt|mp4)[^\s"'<>]*)""")
                    m3u8Regex.findAll(embedDoc).forEach { match ->
                        val videoUrl = match.value.replace("\\/", "/")
                        if (!videoUrl.contains("player") && !videoUrl.contains("favicon")) {
                            streamUrls.add(videoUrl)
                        }
                    }

                    for (videoUrl in streamUrls.distinct()) {
                        val m3u8Links = M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = videoUrl,
                            referer = sourceUrl
                        )

                        if (m3u8Links.isNotEmpty()) {
                            m3u8Links.forEach(callback)
                        } else {
                            val link = newExtractorLink(
                                source = name,
                                name = name,
                                url = videoUrl
                            ) {
                                this.referer = sourceUrl
                                this.quality = Qualities.P1080.value
                            }
                            callback.invoke(link)
                        }
                    }

                    // VTT Subtitles
                    val vttRegex = Regex("""\{"file":"([^"]+\.vtt[^"]*)","kind":"captions","label":"([^"]+)"\}""")
                    vttRegex.findAll(embedDoc).forEach { match ->
                        val subUrl = match.groupValues[1].replace("\\/", "/")
                        val subLang = match.groupValues[2]
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
