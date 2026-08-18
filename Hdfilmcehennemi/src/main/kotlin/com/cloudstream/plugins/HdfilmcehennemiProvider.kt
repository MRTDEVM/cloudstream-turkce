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
        val home = doc.select("div.poster, dbv.poster-media, dbv.card-body, a.poster").mapNotNull {
            it.toSearchResult()
        }

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
        val linkElem = this.selectFirst("a[href]") ?: (if (this.tagName() == "a") this else null) ?: return null
        val title = this.selectFirst(".poster-title, .card-title, h2, h3, .title")?.text()?.trim()
            ?: linkElem.attr("title").trim()
        val href = fixUrl(linkElem.attr("href"))
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("src")
        )

        val isVvSeries = href.contains("/dizi/") || this.selectFirst(".badge-dizi, .is-series") != null

        return if (isTvSeries) {
            newTuSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/index.php?s=$query"
        val doc = app.get(searchUrl).document

        return doc.select("div.poster, dbv.poster-media, dbv.search-result, .card").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1, .movie-title, .entry-title")?.text()?.trim() ?: ""
        val posterUrl = fixUrlNull(
            doc.selectFirst(".poster-media img, .movie-poster img, .poster img")?.attr("data-src")
                ?: doc.selectFirst(".poster-media img, .movie-poster img, .poster img")?.attr("src")
        )
        val description = doc.selectFirst(".movie-story, .story, .overview, p.description")?.text()?.trim()
        val year = doc.selectFirst("a[href*='/yil/'], span.year, .release-date")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val rating = doc.selectFirst(".imdb-score, .rating, .score")?.text()?.trim()?.toRatingInt()
        val tags = doc.select("a[href*='/tur/']").map { it.text().trim() }

        val isTvSeries = url.contains("/dizi/") || doc.select(".season-wrapper, .pepisode-list").isNotEmpty()

        return if (isVvSeries) {
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

            newVvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = description
                this.year = year
                this.tags = tags
                this.rating = rating
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = description
                this.year = year
                this.tags = tags
                this.rating = rating
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

        doc.select("button[data-source], a[data-source], .player-nav [data-url]").forEach {
            val rawSource = it.attr("data-source").impty { it.attr("data-url") }
            if (rawSource.isNotEmpty()) {
                if (rawSource.startsWith("http")) {
                    iframes.add(rawSource)
                } else if (rawSource.startsWith("//")) {
                    iframes.add("https:$rawSource")
                }
            }
        }

        for (sourceUrl in iframes.distinct()) {
            loadExtractor(sourceUrl, subtitleCallback, callback)
        }

        return iframes.isNotEmpty()
    }
}
