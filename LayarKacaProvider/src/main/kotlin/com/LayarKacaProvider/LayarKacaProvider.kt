package com.LayarKacaProvider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class LayarKacaProvider : MainAPI() {
    // Domain LK21 sering berganti. Nilai ini hanya titik awal,
    // ensureMainUrl() akan mencari domain yang aktif secara otomatis.
    override var mainUrl = "https://tv.lk21official.dev"
    override var name = "LayarKaca21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private val fallbackDomains = listOf(
        "https://tv.lk21official.dev",
        "https://tv13.lk21official.cc",
        "https://tv8.lk21official.cc"
    )

    private var domainChecked = false

    // Sementara: kalau server gagal menghasilkan link, tampilkan 1 baris "DEBUG ..." di daftar link
    // supaya kelihatan gagal di tahap mana. Ubah ke false kalau pemutaran sudah beres.
    private val debugLinks = true

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------
    private fun originOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun hasHome(base: String): Boolean {
        return app.get(base).document.select("div.widget article").isNotEmpty()
    }

    /**
     * Cari domain LK21 yang sedang aktif.
     * 1) Halaman landing resmi d21.team (tombol hijau "KE HALAMAN LK21")
     * 2) Daftar domain cadangan
     */
    private suspend fun ensureMainUrl() {
        if (domainChecked) return

        try {
            val href = app.get("https://d21.team").document
                .selectFirst("a.green-button")?.attr("href")
            if (!href.isNullOrBlank()) {
                val origin = originOf(app.get(href).url)
                if (origin != null && hasHome(origin)) {
                    mainUrl = origin
                    domainChecked = true
                    return
                }
            }
        } catch (e: Exception) {
        }

        for (candidate in listOf(mainUrl) + fallbackDomains) {
            try {
                if (hasHome(candidate)) {
                    mainUrl = candidate
                    domainChecked = true
                    return
                }
            } catch (e: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------
    // MAIN PAGE
    // ------------------------------------------------------------------
    override val mainPage: List<MainPageData> = mainPageOf(
        "latest-movies" to "Film Terbaru",
        "top-series-today" to "Series Unggulan",
        "latest-series" to "Series Update",
        "latest-korea" to "Korea Terbaru",
        "latest-action" to "Action Terbaru",
        "latest-horror" to "Horror Terbaru",
        "latest-romance" to "Romance Terbaru",
        "latest-comedy" to "Comedy Terbaru",
        "latest-thailand" to "Thailand Terbaru",
        "latest-india" to "India Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureMainUrl()
        val type = request.data

        val items = if (page <= 1) {
            app.get(mainUrl).document
                .select("div.widget[data-type='$type'] li.slider")
                .mapNotNull { toSearchResult(it) }
        } else {
            // Situs memuat halaman berikutnya lewat /loadmore/<type>/page/<n> (potongan HTML)
            try {
                app.get("$mainUrl/loadmore/$type/page/$page", referer = "$mainUrl/")
                    .document.select("li.slider")
                    .mapNotNull { toSearchResult(it) }
            } catch (e: Exception) {
                emptyList()
            }
        }

        return newHomePageResponse(request.name, items)
    }

    // ------------------------------------------------------------------
    // SEARCH
    // ------------------------------------------------------------------
    data class Lk21SearchResponse(val data: List<Lk21SearchItem>?)
    data class Lk21SearchItem(
        val title: String,
        val slug: String,
        val poster: String?,
        val type: String?,
        val year: Int?,
        val quality: String?
    )

    // Respon "saran pencarian" yang dipakai kotak search situs: {"results":[{title,slug,type}]}
    data class SuggestResponse(val results: List<SuggestItem>? = null)
    data class SuggestItem(
        val title: String? = null,
        val slug: String? = null,
        val type: String? = null
    )

    private fun suggestToResult(item: SuggestItem): SearchResponse? {
        val slug = item.slug
        val title = item.title
        if (slug.isNullOrBlank() || title.isNullOrBlank()) return null

        val type = item.type.orEmpty().lowercase()
        // Hasil saran juga memuat artis, sutradara, genre, dll. Itu bukan film.
        if (type in listOf("artist", "director", "genre", "translator", "country", "year")) return null

        val href = fixUrl(slug)
        return if (type.contains("series")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureMainUrl()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val headers = mapOf(
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "User-Agent" to userAgent
        )

        // 1) API pencarian lengkap (ada poster, tahun, kualitas)
        try {
            val response = app.get(
                "https://gudangvape.com/search.php?s=$encoded&page=1",
                headers = headers
            ).text
            val json = tryParseJson<Lk21SearchResponse>(response)

            val list = json?.data?.mapNotNull { item ->
                val href = fixUrl(item.slug)
                val posterUrl = item.poster?.let {
                    if (it.startsWith("http")) it else "https://poster.assetsy.de/wp-content/uploads/$it"
                }
                val quality = getQualityFromString(item.quality)
                val isSeries = item.type?.contains("series", ignoreCase = true) == true

                if (isSeries) {
                    newTvSeriesSearchResponse(item.title, href, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                        this.quality = quality
                        this.year = item.year
                    }
                } else {
                    newMovieSearchResponse(item.title, href, TvType.Movie) {
                        this.posterUrl = posterUrl
                        this.quality = quality
                        this.year = item.year
                    }
                }
            }.orEmpty()
            if (list.isNotEmpty()) return list
        } catch (e: Exception) {
        }

        // 2) API saran pencarian (sama dengan kotak search di website, sesuai script.js)
        try {
            val text = app.get("https://gudangvape.com/?s=$encoded", headers = headers).text
            val list = tryParseJson<SuggestResponse>(text)?.results.orEmpty()
                .mapNotNull { suggestToResult(it) }
            if (list.isNotEmpty()) return list
        } catch (e: Exception) {
        }

        // 3) Halaman pencarian HTML sebagai pilihan terakhir
        try {
            val doc = app.get("$mainUrl/search?s=$encoded", referer = "$mainUrl/").document
            return doc.select("div#post-container article")
                .mapNotNull { toSearchResult(it) }
                .distinctBy { it.url }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val anchor = element.selectFirst("a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"))
        val img = element.selectFirst("img")

        var title = element.selectFirst("h3.poster-title, h2.entry-title, div.title")
            ?.text()?.trim().orEmpty()
        if (title.isEmpty()) {
            title = img?.attr("alt")?.replace(Regex("\\s*\\(\\d{4}\\)\\s*\$"), "")?.trim().orEmpty()
        }
        if (title.isEmpty()) return null

        // Poster: kartu "lazyload" tidak punya atribut src, hanya data-src
        val posterUrl = img?.attr("data-src")?.ifBlank { null }
            ?: img?.attr("src")?.ifBlank { null }

        val quality = getQualityFromString(element.selectFirst("span.label")?.text())
        val year = element.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
        val isSeries = element.selectFirst("span.episode") != null

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
                this.year = year
            }
        }
    }

    // ------------------------------------------------------------------
    // LOAD DETAIL
    // ------------------------------------------------------------------
    data class SeasonEpisode(
        val s: Int? = null,
        val episode_no: Int? = null,
        val title: String? = null,
        val slug: String? = null
    )

    // Isi <script id="watch-history-data"> : data bersih dari situs
    data class WatchData(
        val title: String? = null,
        val rating: String? = null,
        val poster: String? = null,
        val slug: String? = null,
        val year: Int? = null
    )

    override suspend fun load(url: String): LoadResponse {
        ensureMainUrl()

        var res = app.get(fixUrl(url))
        var document = res.document

        // Halaman pengalihan (tombol "Buka Sekarang" / Nontondrama)
        val redirectHref = document
            .selectFirst("a:contains(Buka Sekarang), a.btn:contains(Nontondrama)")
            ?.attr("href")
        if (!redirectHref.isNullOrBlank()) {
            res = app.get(fixUrl(redirectHref))
            document = res.document
        }

        val pageUrl = res.url
        val base = originOf(pageUrl) ?: mainUrl
        val yearRegex = Regex("\\s*\\((\\d{4})\\)\\s*\$")

        val watch = tryParseJson<WatchData>(document.select("script#watch-history-data").html())
        val h1 = document.selectFirst("div.movie-info h1, h1")?.text()?.trim().orEmpty()

        val title = (watch?.title ?: h1.replace(yearRegex, "")).trim()
        val year = watch?.year ?: yearRegex.find(h1)?.groupValues?.get(1)?.toIntOrNull()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.ifBlank { null } ?: watch?.poster
        val plot = document.selectFirst("div.synopsis")?.text()?.trim()
        val ratingScore = watch?.rating
        val tags = document.select("div.tag-list a[href*=/genre/]").map { it.text() }
        val actors = document.select("div.detail p:contains(Bintang Film) a")
            .map { ActorData(Actor(it.text(), "")) }
        val recommendations = document
            .select("div.widget:has(h2:contains(Terkait)) article")
            .mapNotNull { toSearchResult(it) }

        // Daftar episode ada di <script id="season-data"> (JSON)
        val episodes = ArrayList<Episode>()
        val seasonJson = document.select("script#season-data").html()
        if (seasonJson.isNotBlank()) {
            tryParseJson<Map<String, List<SeasonEpisode>>>(seasonJson)?.forEach { (_, list) ->
                list.forEach { ep ->
                    val slug = ep.slug
                    if (!slug.isNullOrBlank()) {
                        episodes.add(newEpisode("$base/${slug.trimStart('/')}") {
                            this.name = "Episode ${ep.episode_no ?: ""}".trim()
                            this.season = ep.s
                            this.episode = ep.episode_no
                        })
                    }
                }
            }
        }

        return if (episodes.isNotEmpty()) {
            val sorted = episodes.sortedWith(compareBy<Episode>({ it.season ?: 0 }, { it.episode ?: 0 }))
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, sorted) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = Score.from(ratingScore, 10)
                this.tags = tags
                this.actors = actors
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = Score.from(ratingScore, 10)
                this.tags = tags
                this.actors = actors
                this.recommendations = recommendations
            }
        }
    }

    // ------------------------------------------------------------------
    // LOAD LINKS
    // ------------------------------------------------------------------
    data class EmbedResponse(val embedUrl: String? = null)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var res = app.get(data, referer = "$mainUrl/")
        var document = res.document

        val redirectHref = document
            .selectFirst("a:contains(Buka Sekarang), a.btn:contains(Nontondrama)")
            ?.attr("href")
        if (!redirectHref.isNullOrBlank()) {
            res = app.get(fixUrl(redirectHref))
            document = res.document
        }
        val pageUrl = res.url

        var count = 0
        val counting: (ExtractorLink) -> Unit = {
            count++
            callback(it)
        }

        val playerLinks = document.select("ul#player-list li a")
            .map { it.attr("data-url").ifBlank { it.attr("href") } }
        val mainIframe = document.select("iframe#main-player").attr("src")

        val sources = (playerLinks + mainIframe)
            .filter { it.isNotBlank() }
            .map { fixUrl(it) }
            .distinctBy { it.substringBefore("?") }

        sources.forEach { loadPlayer(it, pageUrl, subtitleCallback, counting) }

        return count > 0
    }

    /**
     * Pemutar LK21 sekarang berupa halaman pembungkus (videonode.de/iframe3/<server>/<id>).
     * Halaman itu memanggil POST /api.php (host=<server>&id=<id>) yang membalas
     * {"embedUrl": "..."}. Alamat embed itulah yang diserahkan ke extractor.
     */
    private suspend fun loadPlayer(
        playerUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var got = false
        val cb: (ExtractorLink) -> Unit = {
            got = true
            callback(it)
        }

        // --- Langkah 1: minta embedUrl lewat /api.php ---
        var embedUrl: String? = null
        try {
            val playerOrigin = originOf(playerUrl)
            val html = app.get(playerUrl, referer = referer).text

            val host = Regex("var host\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            val id = Regex("var id\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            val apiPath = Regex("xhr\\.open\\(\"POST\",\\s*\"([^\"]+)\"").find(html)
                ?.groupValues?.get(1) ?: "/api.php"

            if (host != null && id != null && playerOrigin != null) {
                val apiUrl = if (apiPath.startsWith("http")) apiPath else playerOrigin + apiPath
                val apiText = app.post(
                    apiUrl,
                    data = mapOf("host" to host, "id" to id),
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to playerOrigin,
                        "Referer" to playerUrl,
                        "User-Agent" to userAgent
                    )
                ).text
                embedUrl = tryParseJson<EmbedResponse>(apiText)?.embedUrl
            }
        } catch (e: Exception) {
        }

        // --- Langkah 2: serahkan ke extractor ---
        val embed = embedUrl
        if (!embed.isNullOrBlank()) {
            val finalUrl = if (embed.startsWith("//")) "https:$embed" else embed
            loadExtractor(finalUrl, playerUrl, subtitleCallback, cb)
            if (!got) scrapeStreams(finalUrl, playerUrl, subtitleCallback, cb)
            // Domain server CAST sering berganti (f16px.com -> mfw09.org, ...), pola /e/<id> sama
            if (!got && finalUrl.contains("/e/")) {
                try {
                    F16Extractor().getUrl(finalUrl, playerUrl)?.forEach { cb(it) }
                } catch (e: Exception) {
                }
            }
        }

        // --- Cadangan: cara lama (iframe langsung di halaman) ---
        if (!got) {
            loadExtractor(playerUrl, referer, subtitleCallback, cb)
            if (!got) scrapeStreams(playerUrl, referer, subtitleCallback, cb)
        }

        // --- Diagnosa: kenapa tidak ada link ---
        if (!got && debugLinks) {
            val server = Regex("/iframe\\d*/([a-z0-9]+)/").find(playerUrl)?.groupValues?.get(1) ?: "?"
            val status = if (embed.isNullOrBlank()) {
                "gagal ambil embedUrl"
            } else {
                "embed ${originOf(embed)?.substringAfter("://")} tanpa link"
            }
            callback.invoke(
                newExtractorLink(
                    source = "DEBUG",
                    name = "DEBUG $server: $status",
                    url = embed ?: playerUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    /** Cari iframe bersarang dan link .m3u8 / .mp4 di dalam sebuah halaman. */
    private suspend fun scrapeStreams(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer)
            val wrapperUrl = response.url
            val page = response.document

            page.select("iframe").forEach {
                val src = it.attr("src")
                if (src.isNotBlank()) {
                    loadExtractor(fixUrl(src), wrapperUrl, subtitleCallback, callback)
                }
            }

            val html = page.html().replace("\\/", "/").replace("\\u0026", "&")
            val originUrl = originOf(wrapperUrl) ?: mainUrl
            Regex("(?i)https?://[^\\s\"'<>\\\\]+?\\.(?:m3u8|mp4)(?:\\?[^\\s\"'<>\\\\]*)?")
                .findAll(html)
                .map { it.value }
                .distinct()
                .forEach { streamUrl ->
                    val isM3u8 = streamUrl.contains("m3u8", ignoreCase = true)
                    callback.invoke(
                        newExtractorLink(
                            source = "LK21 VIP",
                            name = "LK21 VIP",
                            url = streamUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = wrapperUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to wrapperUrl,
                                "Origin" to originUrl
                            )
                        }
                    )
                }
        } catch (e: Exception) {
        }
    }
}
