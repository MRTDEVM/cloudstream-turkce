package com.cloudstream.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FullHDFilmizleseneProvider : MainAPI() {
    override var mainUrl = "https://www.fullhdfilmizlesene.now"
    override var name = "FullHDFilmizlesene"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "" to "Son Eklenen Filmler",
        "en-cok-izlenen-filmler/" to "En Cok Izlenenler",
        "film-arsivi/" to "Film Arsivi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        } else {
            if (request.data.isEmpty()) "$mainUrl/page/$page/" else "$mainUrl/${request.data}page/$page/"
        }

        val doc = app.get(url).document
        val home = doc.select(".film, .film-title, li.film").mapNotNull {
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

        val title = this.selectFirst(".film-title, .title, h2, h3")?.text()?.trim()
            ?.ifEmpty { null }
            ?: linkElem.attr("title").trim().ifEmpty { null }
            ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
                ?.ifEmpty { null }
                ?: this.selectFirst("img")?.attr("src")
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/arama/$query/"
        val doc = app.get(searchUrl).document
        return doc.select(".film, .film-title, li.film").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .film-title, .title")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "Film"

        val posterUrl = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".film-afis img, .poster img, .movie-poster img")?.attr("data-src")
                ?: doc.selectFirst(".film-afis img, .poster img, .movie-poster img")?.attr("src")
        )
        val description = doc.selectFirst(".film-ozeti, .ozet, .description, .story, .film-story")?.text()?.trim()
        val year = doc.selectFirst("a[href*='/yapim-yili/'], .film-bilgisi li")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val score = Score.from10(doc.selectFirst(".imdb-puani, .imdb, .rating")?.text()?.trim()?.replace(",", ".")?.toDoubleOrNull())
        val tags = doc.select("a[href*='/kategori/'], a[href*='/tur/']").map { it.text().trim() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = description
            this.year = year
            this.tags = tags
            this.score = score
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

        for (sourceUrl in iframes.distinct()) {
            loadExtractor(sourceUrl, subtitleCallback, callback)
        }

        return true
    }
}
