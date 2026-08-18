package com.cloudstream.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class HdfilmcehennemiProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Son Eklenen Filmler",
        "diziler/" to "Son Eklenen Diziler",
        "populer-filmler/" to "Populer Filmler",
        "en-cok-begenilen-filmler/" to "En Cok Begenilenler"
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
        if (href == mainUrl || href.endsWith("/#") || href.contains("category/") || href.contains("/tur/")) return null

        val title = this.selectFirst(".poster-title, .mini-poster-title, .title")?.text()?.trim()
            ?.ifEmpty { null }
            ?: linkElem.attr("title").trim().ifEmpty { null }
            ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src")
                ?.ifEmpty { null }
                ?: this.selectFirst("img")?.attr("data-src")
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
        val searchUrl = "$mainUrl/search/$query/"
        val doc = app.get(searchUrl).document
        return doc.select("a.poster, div.poster, div.mini-poster").mapNotNull {
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
                ?: doc.selectFirst(".poster-media img, .movie-poster img, .poster img")?.attr("src")
        )
        val description = doc.selectFirst(".movie-story, .story, .overview, p.description, .entry-content")?.text()?.trim()
        val year = doc.selectFirst("a[href*='/yil/'], span.year, .release-date")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val score = Score.from10(doc.selectFirst(".imdb-score, .rating, .score")?.text()?.trim()?.replace(",", ".")?.toDoubleOrNull())
        val tags = doc.select("a[href*='/tur/']").map { it.text().trim() }

        val isTvSeries = url.contains("/dizi/") || doc.select(".season-wrapper, .episode-list").isNotEmpty()

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            doc.select(".season-wrapper, .season").forEachIndexed { seasonIdx, seasonElem ->
                val seasonNum = seasonIdx + 1
                seasonElem.select("a[href*='/bolum/'], .episode-item a").forEachIndexed { epIdx, epElem ->
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
            if (src.isNotEmpty()) iframes.add(fixUrl(src))
        }

        doc.select("button[data-source], a[data-source], .player-nav [data-url], [data-video]").forEach {
            val rawSource = it.attr("data-source").ifEmpty { it.attr("data-url") }.ifEmpty { it.attr("data-video") }
            if (rawSource.isNotEmpty()) {
                if (rawSource.startsWith("http")) {
                    iframes.add(rawSource)
                } else if (rawSource.startsWith("//")) {
                    iframes.add("https:$rawSource")
                }
            }
        }

        for (sourceUrl in iframes.distinct()) {
            try {
                if (sourceUrl.contains("hdfilmcehennemi") || sourceUrl.contains("rapid") || sourceUrl.contains("closeload") || sourceUrl.contains("playmix")) {
                    val embedDoc = app.get(sourceUrl, referer = data).text

                    // Extract all stream links
                    val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|txt|mp4)[^\s"'<>]*)""")
                    m3u8Regex.findAll(embedDoc).forEach { match ->
                        val videoUrl = match.value.replace("\\/", "/")
                        val isM3u8 = videoUrl.contains(".m3u8") || videoUrl.contains(".txt")
                        
                        val m3u8Links = M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = videoUrl,
                            referer = sourceUrl
                        )

                        if (m3u8Links.isNotEmpty()) {
                            m3u8Links.forEach(callback)
                        } else {
                            callback.invoke(
                                ExtractorLink(
                                    source = name,
                                    name = name,
                                    url = videoUrl,
                                    referer = sourceUrl,
                                    quality = Qualities.P1080.value,
                                    isM3u8 = isM3u8
                                )
                            )
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
