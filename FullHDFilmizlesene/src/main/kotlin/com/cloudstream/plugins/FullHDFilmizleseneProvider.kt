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
        "filmizle/1080p-filmler-2" to "1080p Filmler",
        "filmizle/imdb-puani-yuksek-filmler" to "IMDb Puani Yuksek",
        "filmizle/turkce-dublaj-filmler-1" to "Turkce Dublaj Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        } else {
            if (request.data.isEmpty()) "$mainUrl/page/$page/" else "$mainUrl/${request.data}/page/$page/"
        }

        val doc = app.get(url).document
        val home = doc.select(".film, .film-title, li.film, div.poster").mapNotNull {
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
        val searchUrl = "$mainUrl/arama/$query"
        val doc = app.get(searchUrl).document
        return doc.select(".film, .film-title, li.film, div.poster").mapNotNull {
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
        val description = doc.selectFirst(".film-ozeti, .ozet, .description, .story, .film-story, meta[name='description']")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
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
            if (src.isNotEmpty() && !src.contains("youtube.com") && !src.contains("youtu.be")) {
                iframes.add(fixUrl(src))
            }
        }

        // Extract scx code or embedded player
        val scxMatch = Regex("""var\s+scx\s*=\s*(\{.*?\});""").find(doc.html())
        if (scxMatch != null) {
            val jsonStr = scxMatch.groupValues[1]
            Regex(""""t"\s*:\s*\[\s*"([^"]+)"\s*\]""").findAll(jsonStr).forEach { match ->
                val code = match.groupValues[1]
                if (!code.contains("youtube")) {
                    val embedUrl = if (code.startsWith("http")) code else "$mainUrl/$code"
                    iframes.add(embedUrl)
                }
            }
        }

        for (sourceUrl in iframes.distinct()) {
            try {
                if (sourceUrl.contains("fullhdfilmizlesene") || sourceUrl.contains("atom") || sourceUrl.contains("playmix") || sourceUrl.contains("closeload") || sourceUrl.contains("rapid")) {
                    val embedDoc = app.get(sourceUrl, referer = data).text
                    val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|txt|mp4)[^\s"'<>]*)""")
                    val m3u8s = m3u8Regex.findAll(embedDoc).map { it.value.replace("\\/", "/") }.distinct().toList()

                    val headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )

                    for (videoUrl in m3u8s) {
                        val links = M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = videoUrl,
                            referer = "$mainUrl/",
                            headers = headers
                        )
                        if (links.isNotEmpty()) {
                            links.forEach { link ->
                                val customLink = newExtractorLink(
                                    source = link.source,
                                    name = link.name,
                                    url = link.url
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.headers = headers
                                    this.quality = link.quality
                                    this.isM3u8 = true
                                }
                                callback.invoke(customLink)
                            }
                        } else {
                            val link = newExtractorLink(
                                source = name,
                                name = name,
                                url = videoUrl
                            ) {
                                this.referer = "$mainUrl/"
                                this.headers = headers
                                this.quality = Qualities.P1080.value
                            }
                            callback.invoke(link)
                        }
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
