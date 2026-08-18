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
        "film-arsivi/" to "Film Arsivi",
        "en-cok-izlenen-filmler/" to "En Cok Izlenenler",
        "film-izle/turkce-dublaj-filmler/" to "Turkce Dublaj Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        } else {
            if (request.data.isEmpty()) "$mainUrl/page/$page/" else "$mainUrl/${request.data}page/$page/"
        }

        val doc = app.get(url).document
        val home = doc.select("li.film, .film-kutu, .movie-item, article.film").mapNotNull {
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
        val link = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = this.selectFirst(".film-adi, .title, h2, h3")?.text()?.trim()
            ?: link.attr("title").trim()
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("src")
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val doc = app.get(searchUrl).document

        return doc.select("li.film, .film-kutu, .movie-item, article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.film-adi, h1, .movie-title")?.text()?.trim() ?: ""
        val posterUrl = fixUrlNull(
            doc.selectFirst(".film-afis img, .poster img, .movie-poster img")?.attr("data-src")
                ?: doc.selectFirst(".film-afis img, .poster img, .movie-poster img")?.attr("src")
        )
        val description = doc.selectFirst(".film-ozeti, .ozet, .description, .story")?.text()?.trim()
        val year = doc.selectFirst("a[href*='/yapim-yili/'], .film-bilgisi li")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val rating = doc.selectFirst(".imdb-puani, .imdb, .rating")?.text()?.trim()?.replace(",", ".")?.toDoubleOrNull()?.times(10)?.toInt()
        val tags = doc.select("a[href*='/kategori/'], a[href*='/tur/']").map { it.text().trim() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = description
            this.year = year
            this.tags = tags
            this.rating = rating
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

        doc.select("[data-source], [data-frame], .kaynaklar a").forEach {
            val raw = it.attr("data-source").ifEmpty { it.attr("data-frame") }
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
