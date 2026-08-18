package com.cloudstream.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FilmMakinesiProvider : MainAPI() {
    override var mainUrl = "https://filmmakinesi.to"
    override var name = "FilmMakinesi"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Son Eklenen Filmler",
        "tur/filmler/" to "Filmler",
        "tur/diziler/" to "Diziler",
        "en-cok-izlenen-filmler/" to "En Cok Izlenenler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        } else {
            if (request.data.isEmpty()) "$mainUrl/page/$page/" else "$mainUrl/${request.data}page/$page/"
        }

        val doc = app.get(url).document
        val home = doc.select("article.item, div.movie-box, .film-kutu, .poster-media").mapNotNull {
            it.toSearchResponse()
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

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = this.selectFirst("h2, h3, .movie-title, .title")?.text()?.trim()
            ?: link.attr("title").trim()
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("src")
        )

        val isTvSeries = href.contains("/dizi/") || this.select(".is-series, .badge-dizi").isNotEmpty()

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

        return doc.select("article.item, div.movie-box, .film-kutu").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title, h1, .movie-title")?.text()?.trim() ?: ""
        val posterUrl = fixUrlNull(
            doc.selectFirst(".movie-poster img, .poster img, .entry-content img")?.attr("data-src")
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

        doc.select("[data-source], .sources-list button").forEach {
            val raw = it.attr("data-source").ifEmpty { it.attr("data-url") }
            if (raw.isNotEmpty()) {
                if (raw.startsWith("http")) iframes.add(raw)
                else if (raw.startsWith("//")) iframes.add("https:$raw")
            }
        }

        for (source in iframes.distinct()) {
            loadExtractor(source, subtitleCallback, callback)
        }

        return iframes.isNotEmpty()
    }
}
