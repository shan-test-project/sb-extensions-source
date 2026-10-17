package eu.kanade.tachiyomi.animeextension.en.av1encodes

import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import extensions.utils.Source
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parallelMapNotNullBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.delay
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class AV1Encodes : Source() {

    override val name = "AV1Encodes"
    override val lang = "en"
    override val supportsLatest = true

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT

    private val prefQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    override val client: OkHttpClient = network.client.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 10 })
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", DESKTOP_UA)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        .set("Accept-Language", "en-US,en;q=0.9")
        .set("Sec-Ch-Ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\"")
        .set("Sec-Ch-Ua-Mobile", "?0")
        .set("Sec-Ch-Ua-Platform", "\"Windows\"")
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "same-origin")
        .set("Sec-Fetch-User", "?1")
        .set("Upgrade-Insecure-Requests", "1")
        .set("Referer", "${runCatching { baseUrl }.getOrDefault(PREF_DOMAIN_DEFAULT)}/")

    private fun getVideoHeaders(): Headers = Headers.Builder()
        .set("User-Agent", DESKTOP_UA)
        .set("Referer", "${runCatching { baseUrl }.getOrDefault(PREF_DOMAIN_DEFAULT)}/")
        .build()

    private val noRedirectClient: OkHttpClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private data class CachedVideos(val cachedAt: Long, val videos: List<Video>)
    private val videoCache = ConcurrentHashMap<String, CachedVideos>()

    private suspend fun getCachedVideos(episodeUrl: String): List<Video> {
        val key = normalizeEpisodePath(episodeUrl)
        val now = System.currentTimeMillis()
        videoCache[key]?.let { cached ->
            if (now - cached.cachedAt < VIDEO_CACHE_TTL_MS) return cached.videos
            videoCache.remove(key, cached)
        }

        val videos = extractAllVideos(episodeUrl)
        if (videos.isNotEmpty()) {
            if (videoCache.size >= MAX_VIDEO_CACHE_SIZE) {
                videoCache.entries.minByOrNull { it.value.cachedAt }?.let { oldest ->
                    videoCache.remove(oldest.key, oldest.value)
                }
            }
            videoCache[key] = CachedVideos(System.currentTimeMillis(), videos)
        }
        return videos
    }

    // ══════════════════════════════════════════════════════════════════════════
    // POPULAR
    // ══════════════════════════════════════════════════════════════════════════

    override fun popularAnimeRequest(page: Int): Request = if (page == 1) {
        GET(baseUrl, headers)
    } else {
        GET("$baseUrl/anime?page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()
        val path = response.request.url.encodedPath
        return if (path == "/" || path.isBlank()) {
            parseHomePage(doc)
        } else {
            parseAnimeListPage(doc)
        }
    }

    private fun parseHomePage(doc: Document): AnimesPage {
        val spotlights = doc.select("article.spotlight-slide").mapNotNull { slide ->
            val a = slide.selectFirst("a[href*='/anime/']") ?: return@mapNotNull null
            val href = a.attr("href").let {
                if (it.startsWith("http")) it.removePrefix(baseUrl) else it
            }
            if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
            val titleText = slide.selectFirst(".spotlight-title, h3, h4")?.text()?.trim()
                ?: a.text().trim()
            if (titleText.isBlank() || titleText.equals("Watch Now", ignoreCase = true)) return@mapNotNull null
            val img = slide.selectFirst("img.spotlight-backdrop, img")
            val imgUrl = extractImgUrl(img) ?: extractBg(slide)
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = titleText
                thumbnail_url = imgUrl
            }
        }

        val cards = doc.select(
            "article.anime-card, article[class*='card'], div.anime-item, .anime-item, .sidebar-list-panel li, .sidebar-list-panel article, section.content-layout article",
        ).mapNotNull { card ->
            val a = if (card.tagName() == "a") {
                card
            } else {
                card.selectFirst("h3 > a, h4 > a, .card-body a, a[href*='/anime/']")
                    ?: return@mapNotNull null
            }
            val href = a.attr("href").let {
                if (it.startsWith("http")) it.removePrefix(baseUrl) else it
            }
            if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
            val titleText = card.selectFirst("h3, h4")?.text()?.trim() ?: a.text().trim()
            if (titleText.isBlank() || titleText.equals("Watch Now", ignoreCase = true)) return@mapNotNull null
            val img = card.selectFirst("div.poster-wrap > img, img")
            val imgUrl = extractImgUrl(img) ?: extractBg(card)
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = titleText
                thumbnail_url = imgUrl
            }
        }

        val allAnimes = (spotlights + cards).distinctBy { it.url }
        if (allAnimes.isNotEmpty()) {
            return AnimesPage(allAnimes.fetchMissingCovers(), hasNextPage = true)
        }

        // Fallback to stats page parser if homepage cards were not found
        return AnimesPage(parseStatsPage(doc), false)
    }

    private val seasonRegex by lazy { Regex("""\[S\d""") }
    private val animeNameRegex by lazy { Regex("""\[S\d{1,2}(?:-E\d+)?]\s*([^\[]+?)\s*\[""") }
    private val specialCharactersRegex by lazy { Regex("[^a-z0-9]+") }

    private fun parseStatsPage(doc: Document): List<SAnime> {
        val seen = mutableSetOf<String>()
        val animes = mutableListOf<SAnime>()

        var searchContext: Element = doc
        val header = doc.select("h1,h2,h3,h4,h5,h6").firstOrNull {
            it.text().contains("Top Downloads", ignoreCase = true)
        }
        if (header != null) {
            val sibling = header.nextElementSibling()
            searchContext = if (sibling != null && sibling.text().length > 20) {
                sibling
            } else {
                header.parent() ?: doc
            }
        }

        searchContext.select("a[href*='/anime/'],div[class*='card'],div[class*='item'],li")
            .filter { el ->
                val text = el.text().trim()
                text.contains(seasonRegex) || text.length in 10..200
            }
            .forEach { el ->
                val link = el.selectFirst("a[href*='/anime/']")
                    ?: el.takeIf { it.tagName() == "a" && it.attr("href").contains("/anime/") }
                if (link != null) {
                    val url = link.attr("href").let {
                        if (it.startsWith("http")) it.removePrefix(baseUrl) else it
                    }
                    if (url.startsWith("/anime/") && seen.add(url)) {
                        animes.add(
                            SAnime.create().apply {
                                setUrlWithoutDomain(url)
                                title = extractCleanTitle(el.text())
                                thumbnail_url = getListImageUrl(el)
                            },
                        )
                    }
                    return@forEach
                }

                val animeName = extractCleanTitle(el.text().trim())
                val slug = animeName.lowercase(Locale.US).replace(specialCharactersRegex, "-").trim('-')
                if (slug.length < 3 || !seen.add("/anime/$slug")) return@forEach
                animes.add(
                    SAnime.create().apply {
                        setUrlWithoutDomain("/anime/$slug")
                        title = animeName
                    },
                )
            }

        if (animes.isEmpty()) {
            animeNameRegex.findAll(searchContext.text())
                .map { it.groupValues[1].trim() }
                .distinct()
                .take(20)
                .forEach { animeName ->
                    val slug = animeName.lowercase(Locale.US)
                        .replace(specialCharactersRegex, "-").trim('-')
                    if (slug.length >= 3 && seen.add("/anime/$slug")) {
                        animes.add(
                            SAnime.create().apply {
                                setUrlWithoutDomain("/anime/$slug")
                                title = extractCleanTitle(animeName)
                            },
                        )
                    }
                }
        }

        return animes.fetchMissingCovers()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LATEST
    // ══════════════════════════════════════════════════════════════════════════

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()
        val animes = doc.select("article.anime-card").mapNotNull { card ->
            val a = card.selectFirst("h4 > a, .card-body a") ?: return@mapNotNull null
            val href = a.attr("href").let {
                if (it.startsWith("http")) it.removePrefix(baseUrl) else it
            }
            if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = a.text().trim()
                thumbnail_url = card.selectFirst("div.poster-wrap > img, img")?.let { img: Element ->
                    img.attr("abs:data-src").ifBlank { null }
                        ?: img.attr("abs:data-lazy-src").ifBlank { null }
                        ?: img.attr("abs:src").ifBlank { null }
                }
            }
        }.distinctBy { it.url }
        return AnimesPage(animes, false)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SEARCH
    // ══════════════════════════════════════════════════════════════════════════

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url.toString(), headers)
        }

        var sortValue = ""
        var typeValue = ""
        var genreValue = ""
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sortValue = SORT_VALUES.getOrElse(filter.state) { "" }
                is TypeFilter -> typeValue = TYPE_VALUES.getOrElse(filter.state) { "" }
                is GenreFilter -> genreValue = GENRE_VALUES.getOrElse(filter.state) { "" }
                else -> {}
            }
        }

        Log.d(TAG, "searchAnimeRequest: sort=$sortValue type=$typeValue genre=$genreValue")

        // Genre takes priority — uses path-based route
        // With this:
        val url = "$baseUrl/anime".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
        if (genreValue.isNotBlank()) {
            Log.d(TAG, "searchAnimeRequest: routing to /anime?genres=$genreValue&page=$page")
            url.addQueryParameter("genres", genreValue)
        }
        if (sortValue.isNotBlank()) url.addQueryParameter("sort", sortValue)
        if (typeValue.isNotBlank()) url.addQueryParameter("type", typeValue)
        return GET(url.build().toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()
        val url = response.request.url
        val path = url.encodedPath
        val hasQueryParams = url.querySize > 0
        Log.d(TAG, "searchAnimeParse: path=$path fullUrl=$url hasQueryParams=$hasQueryParams")
        return when {
            // Search page uses card layout
            path == "/search" -> parseCardList(doc)

            // Airing pages use card layout
            path.startsWith("/airing") -> parseCardList(doc)

            // Anime browse + sort + genres all use the SAME plain list layout
            path == "/anime" -> parseAnimeListPage(doc)

            else -> parseCardList(doc)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CARD LIST PARSER  —  /airing/*, /search, /genre/*
    // ══════════════════════════════════════════════════════════════════════════

    private fun parseCardList(doc: Document): AnimesPage {
        // Try article cards first (same structure as latestUpdatesParse)
        var animes = doc.select("article.anime-card, article[class*='card'], article[class*='anime'], div.anime-item, .anime-item")
            .mapNotNull { card ->
                val a = if (card.tagName() == "a") {
                    card
                } else {
                    card.selectFirst("h3 > a, h4 > a, .card-body a, a.anime-link, a[href*='/anime/']")
                        ?: return@mapNotNull null
                }
                val href = a.attr("href").let {
                    if (it.startsWith("http")) it.removePrefix(baseUrl) else it
                }
                if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
                val img = card.selectFirst("div.poster-wrap > img, img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = (card.selectFirst("h3, h4")?.text() ?: a.text()).trim()
                    thumbnail_url = img?.let { it: Element ->
                        it.attr("abs:data-src").ifBlank { null }
                            ?: it.attr("abs:data-lazy-src").ifBlank { null }
                            ?: it.attr("abs:src").ifBlank { null }
                    }
                }
            }.distinctBy { it.url }

        // Fallback: h3-based selector (original logic)
        if (animes.isEmpty()) {
            val contentRoot = doc.selectFirst(
                "main, #main, #content, .content, [class*='anime-list'], [class*='anime-grid'], " +
                    "[class*='result'], [class*='listing'], [class*='airing'], section.animes",
            ) ?: doc
            animes = contentRoot.select("h3").mapNotNull { h3 ->
                val block = h3.parent() ?: return@mapNotNull null
                val a = block.selectFirst("a[href*='/anime/']")
                    ?: block.parent()?.selectFirst("a[href*='/anime/']")
                    ?: return@mapNotNull null
                val href = a.attr("href").let {
                    if (it.startsWith("http")) it.removePrefix(baseUrl) else it
                }
                if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
                val img = block.parent()?.selectFirst("img") ?: block.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = h3.text().trim()
                    thumbnail_url = img?.let { it: Element ->
                        it.attr("abs:data-src").ifBlank { null }
                            ?: it.attr("abs:data-lazy-src").ifBlank { null }
                            ?: it.attr("abs:src").ifBlank { null }
                    }
                }
            }.distinctBy { it.url }
        }

        Log.d(TAG, "parseCardList: found ${animes.size} animes")
        val hasNextPage = doc.selectFirst(
            ".pagination a[rel=next], .pagination .next:not(.disabled), " +
                "nav.pagination a:contains(Next), [aria-label=Next page]",
        ) != null
        return AnimesPage(animes, hasNextPage)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PLAIN LIST PARSER  —  /anime (A-Z browse)
    // ══════════════════════════════════════════════════════════════════════════

    private fun parseAnimeListPage(doc: Document): AnimesPage {
        val animes = doc.select("li.anime-index-item > a, li > a[href*='/anime/']").mapNotNull { a ->
            val href = a.attr("href").let {
                if (it.startsWith("http")) it.removePrefix(baseUrl) else it
            }
            if (!href.startsWith("/anime/") || href == "/anime/" || href == "/anime/list") return@mapNotNull null
            val titleText = a.text().trim().ifBlank { return@mapNotNull null }
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = titleText
            }
        }.distinctBy { it.url }

        val hasNextPage = doc.selectFirst(
            "a.next-page, a[rel=next], .pagination .next, a:contains(Next)",
        ) != null

        return AnimesPage(animes.fetchMissingCovers(), hasNextPage)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // N+1 COVER FETCHER
    // ══════════════════════════════════════════════════════════════════════════

    private fun List<SAnime>.fetchMissingCovers(): List<SAnime> {
        return parallelMapNotNullBlocking { anime ->
            if (anime.thumbnail_url != null) return@parallelMapNotNullBlocking anime
            runCatching {
                val doc = client.newCall(animeDetailsRequest(anime)).awaitSuccess().useAsJsoup()
                val img = doc.selectFirst(
                    "img.anime-poster, img.poster, .anime-hero img, " +
                        "[class*='poster'] img, [class*='hero'] img, main img",
                )
                anime.thumbnail_url =
                    img?.attr("abs:data-src")?.ifBlank { img.attr("abs:src") }
                        ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                anime
            }.getOrDefault(anime)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ANIME DETAIL
    // ══════════════════════════════════════════════════════════════════════════

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.useAsJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst(
                ".anime-hero h1, h1.anime-title, [class*='anime-hero'] h1, [class*='detail'] h1, main h1, h1",
            )?.text()?.trim() ?: ""

            val img = doc.selectFirst(
                "img.anime-poster, img.poster, .anime-hero img, [class*='poster'] img, [class*='hero'] img, main img",
            )
            thumbnail_url = img?.attr("abs:data-src")?.ifBlank { img.attr("abs:src") }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: extractBg(
                    doc.selectFirst(
                        ".anime-poster, .poster, .anime-hero, [class*='poster'], [class*='hero']",
                    ) ?: doc,
                )

            description = doc.selectFirst(
                ".anime-synopsis, .synopsis, .description, [class*='synopsis'], [class*='description'], [class*='overview'], .desc",
            )?.text()?.trim()
            genre = doc.select(
                ".genre-tag, .tag, a[href*='/genre/'], a[href*='/tag/'], [class*='genre'] a",
            ).joinToString { it.text().trim() }.ifBlank { null }
            author = doc.selectFirst(".studio, .studio-name, [class*='studio']")?.text()?.trim()
            status = if (doc.selectFirst("[class*='airing'], .status-airing, .airing-badge") != null) {
                SAnime.ONGOING
            } else {
                SAnime.COMPLETED
            }
            initialized = true
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EPISODE LIST
    // ══════════════════════════════════════════════════════════════════════════

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = Jsoup.parse(response.bodyString())
        val urlPath = response.request.url.encodedPath
        val slug = urlPath.split("/").last { it.isNotBlank() }
        Log.d(TAG, "episodeListParse: slug=$slug quality=$prefQuality")

        val seasons = doc.select(".season-tab[data-season], .season-option[data-season], [data-season]")
            .map { it.attr("data-season") }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf("1") }
        Log.d(TAG, "episodeListParse: seasons=$seasons")

        val episodeNumberRegex = Regex("""E(\d+)""", RegexOption.IGNORE_CASE)
        val qualityList = listOf(prefQuality) + QUALITY_VALUES.filter { it != prefQuality }

        return seasons.sortedByDescending { it.toIntOrNull() ?: 0 }.parallelCatchingFlatMapBlocking { season ->
            for (quality in qualityList) {
                val encodedRes = URLEncoder.encode(quality, "UTF-8").replace("+", "%20")
                val epPageUrl = "$baseUrl/episodes/$slug/$season/$encodedRes"
                Log.d(TAG, "episodeListParse: fetching episodes page → $epPageUrl")

                val epHtml = try {
                    client.newCall(
                        GET(epPageUrl, headers.newBuilder().set("Referer", "$baseUrl/anime/$slug").build()),
                    ).awaitSuccess().bodyString()
                } catch (e: Exception) {
                    Log.w(TAG, "episodeListParse: failed for $epPageUrl: ${e.message}")
                    continue
                }

                if (epHtml.contains("Access Blocked")) {
                    Log.w(TAG, "episodeListParse: access blocked for $epPageUrl")
                    continue
                }

                if (epHtml.trim().startsWith("[")) {
                    val items = runCatching { epHtml.parseAs<List<EpisodeItem>>() }.getOrNull()
                    if (!items.isNullOrEmpty()) {
                        Log.d(TAG, "episodeListParse: JSON array found ${items.size} episodes")
                        return@parallelCatchingFlatMapBlocking items.sortedByDescending { it.num }.map { item ->
                            val fn = Uri.decode(item.href.substringBefore("?").substringAfterLast("/"))
                            val epNum = if (item.num > 0) item.num.toFloat() else parseEpisodeNumber(fn, season)
                            val finalNum = if (item.num > 0) {
                                val s = season.toIntOrNull() ?: 1
                                if (s > 1) ((s - 1) * 100 + epNum) else epNum
                            } else {
                                epNum
                            }
                            SEpisode.create().apply {
                                setUrlWithoutDomain(item.href)
                                name = if (item.label.isNotBlank()) {
                                    val audioTag = if (item.audio.isNotBlank()) " [${item.audio}]" else ""
                                    if (season != "1" && season.isNotBlank()) {
                                        "Season $season Ep ${item.num} - ${item.label}$audioTag"
                                    } else {
                                        "Ep ${item.num} - ${item.label}$audioTag"
                                    }
                                } else {
                                    buildEpisodeLabel(fn, season)
                                }
                                episode_number = finalNum
                            }
                        }
                    }
                }

                val epDoc = Jsoup.parse(epHtml)
                val downloadLinks = epDoc.select("a[href*='/download/']")
                Log.d(TAG, "episodeListParse: found ${downloadLinks.size} download links for season $season (quality $quality)")

                if (downloadLinks.isNotEmpty()) {
                    return@parallelCatchingFlatMapBlocking downloadLinks.sortedByDescending { link ->
                        episodeNumberRegex.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    }.map { link ->
                        val fullHref = link.attr("href")
                        val filename = Uri.decode(fullHref.substringBefore("?").substringAfterLast("/"))
                        SEpisode.create().apply {
                            setUrlWithoutDomain(fullHref)
                            name = buildEpisodeLabel(filename, season)
                            episode_number = parseEpisodeNumber(filename, season)
                        }
                    }
                }

                val filenames = extractFilenames(epHtml)
                if (filenames.isNotEmpty()) {
                    Log.d(TAG, "episodeListParse: regex found ${filenames.size} filenames")
                    return@parallelCatchingFlatMapBlocking filenames.sortedByDescending { parseEpisodeNumber(it, season) }.map { filename ->
                        val encodedFilename = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
                        SEpisode.create().apply {
                            setUrlWithoutDomain("/download/$slug/$season/$encodedRes/$encodedFilename")
                            name = buildEpisodeLabel(filename, season)
                            episode_number = parseEpisodeNumber(filename, season)
                        }
                    }
                }
            }
            emptyList()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HOSTER & VIDEO LIST
    // ══════════════════════════════════════════════════════════════════════════

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val hosters = getCachedVideos(episode.url)
            .mapNotNull { video ->
                val serverName = video.videoTitle.substringAfterLast(" · ").trim()
                if (serverName !in LINK_TYPE_ENTRIES) return@mapNotNull null
                Hoster(hosterName = serverName, hosterUrl = "${episode.url}#server=$serverName")
            }
            .distinctBy { it.hosterName.lowercase(Locale.ROOT) }
        val prefLinkType = preferences.getString(PREF_LINK_TYPE_KEY, PREF_LINK_TYPE_DEFAULT) ?: PREF_LINK_TYPE_DEFAULT
        return hosters.sortedByDescending { it.hosterName.equals(prefLinkType, ignoreCase = true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val serverName = hoster.hosterName
        val episodeUrl = hoster.hosterUrl.substringBefore("#server=")
        return getCachedVideos(episodeUrl)
            .filter { it.videoTitle.substringAfterLast(" · ").trim().equals(serverName, ignoreCase = true) }
            .sortVideos()
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> = getCachedVideos(episode.url).sortVideos()

    private suspend fun extractAllVideos(episodeUrl: String): List<Video> {
        Log.d(TAG, "extractAllVideos: episodeUrl=${episodeUrl.substringBefore("?")}")

        val cleanPath = episodeUrl.substringBefore("?")
        val pathSegments = cleanPath.split("/").filter { it.isNotBlank() }
        val slug = if (pathSegments.size >= 2) pathSegments[1] else ""
        val season = if (pathSegments.size >= 3) pathSegments[2] else "1"
        val resSegment = if (pathSegments.size >= 4) pathSegments[3] else ""
        val encodedFilename = pathSegments.lastOrNull() ?: ""
        val filename = Uri.decode(encodedFilename)
        Log.d(TAG, "extractAllVideos: slug=$slug season=$season res=$resSegment filename=$filename")

        var downloadPageUrl = if (episodeUrl.startsWith("http")) episodeUrl else baseUrl + episodeUrl

        var pageHtml = try {
            fetchTextWithRetry(downloadPageUrl, headers.newBuilder().set("Referer", "$baseUrl/").build())
        } catch (e: Exception) {
            Log.e(TAG, "extractAllVideos: initial download page failed (${e.javaClass.simpleName})")
            ""
        }

        var ddlToken = extractDdlToken(pageHtml)
        var episodePageHtml: String? = null
        var episodeItem = findEpisodeItem(pageHtml, episodeUrl)
        var ddlResponse = episodeItem?.ddl?.takeIf { it.success }

        // Newer episode-list responses may carry the DDL links directly. Prefer that
        // payload; otherwise refresh the signed download URL before trying the token API.
        if (ddlResponse == null && ddlToken.isNullOrBlank() && slug.isNotBlank() && resSegment.isNotBlank()) {
            episodePageHtml = fetchEpisodePage(slug, season, resSegment)
            episodeItem = findEpisodeItem(episodePageHtml, episodeUrl)
            ddlResponse = episodeItem?.ddl?.takeIf { it.success }

            if (ddlResponse == null && episodePageHtml.isNotBlank() && !episodePageHtml.contains("Access Blocked", ignoreCase = true)) {
                val freshLink = episodeItem?.href ?: Jsoup.parse(episodePageHtml)
                    .select("a[href*='/download/']")
                    .firstOrNull {
                        normalizeEpisodePath(it.attr("href")) == normalizeEpisodePath(episodeUrl) ||
                            it.attr("href").contains(encodedFilename) ||
                            Uri.decode(it.attr("href")).contains(filename)
                    }
                    ?.attr("href")

                if (!freshLink.isNullOrBlank()) {
                    downloadPageUrl = if (freshLink.startsWith("http")) freshLink else baseUrl + freshLink
                    Log.d(TAG, "extractAllVideos: refreshed download URL from episode list")
                    pageHtml = try {
                        fetchTextWithRetry(downloadPageUrl, headers.newBuilder().set("Referer", "$baseUrl/").build())
                    } catch (e: Exception) {
                        Log.e(TAG, "extractAllVideos: fresh download page failed (${e.javaClass.simpleName})")
                        ""
                    }
                    ddlToken = extractDdlToken(pageHtml)
                }
            }
        }

        val videos = mutableListOf<Video>()
        val ext = if (filename.endsWith(".mp4", ignoreCase = true)) ".mp4" else ".mkv"
        val resLabel = Regex("""\[(\d+p)]""").find(filename)?.groupValues?.get(1) ?: prefQuality
        val audioTag = Regex("""\[(Dual|Sub|Dub)]""", RegexOption.IGNORE_CASE)
            .find(filename)?.groupValues?.get(1) ?: ""
        val audioSuffix = if (audioTag.isNotBlank()) " [$audioTag]" else ""

        if (ddlResponse == null && !ddlToken.isNullOrBlank()) {
            ddlResponse = fetchDdlResponse(encodedFilename, ddlToken, downloadPageUrl)
        }

        // If the token was present but rejected/expired, refresh the episode response
        // once. This also handles JSON episode responses that embed their own DDL data.
        if (ddlResponse == null && episodePageHtml == null && slug.isNotBlank() && resSegment.isNotBlank()) {
            episodePageHtml = fetchEpisodePage(slug, season, resSegment)
            episodeItem = findEpisodeItem(episodePageHtml, episodeUrl)
            ddlResponse = episodeItem?.ddl?.takeIf { it.success }
        }

        if (ddlResponse == null && episodePageHtml != null && episodePageHtml.isNotBlank() &&
            !episodePageHtml.contains("Access Blocked", ignoreCase = true)
        ) {
            val freshLink = episodeItem?.href ?: Jsoup.parse(episodePageHtml)
                .select("a[href*='/download/']")
                .firstOrNull {
                    normalizeEpisodePath(it.attr("href")) == normalizeEpisodePath(episodeUrl) ||
                        it.attr("href").contains(encodedFilename) ||
                        Uri.decode(it.attr("href")).contains(filename)
                }
                ?.attr("href")

            if (!freshLink.isNullOrBlank()) {
                val freshDownloadUrl = if (freshLink.startsWith("http")) freshLink else baseUrl + freshLink
                if (freshDownloadUrl != downloadPageUrl) {
                    downloadPageUrl = freshDownloadUrl
                    pageHtml = try {
                        fetchTextWithRetry(downloadPageUrl, headers.newBuilder().set("Referer", "$baseUrl/").build())
                    } catch (e: Exception) {
                        Log.e(TAG, "extractAllVideos: refreshed download page failed (${e.javaClass.simpleName})")
                        ""
                    }
                    ddlToken = extractDdlToken(pageHtml)
                    if (ddlResponse == null && !ddlToken.isNullOrBlank()) {
                        ddlResponse = fetchDdlResponse(encodedFilename, ddlToken, downloadPageUrl)
                    }
                }
            }
        }

        val ddl = ddlResponse?.takeIf { it.success }
        if (ddl != null) {
            val sizeLabel = ddl.fileSize?.let { " · $it" } ?: ""
            val qualLabel = "AV1 · $resLabel$audioSuffix$sizeLabel"
            val vHeaders = getVideoHeaders()

            val candidates = listOf(
                ddl.streamLink to "Stream",
                ddl.watchLink to "Stream",
                ddl.downloadLink to "Direct DL",
                ddl.ddl to "Direct DL",
            )
            for ((rawUrl, expectedServer) in candidates) {
                val resolvedUrl = resolveRedirect(rawUrl) ?: continue
                val video = buildVideo(resolvedUrl, expectedServer, qualLabel, ext, vHeaders) ?: continue
                if (videos.none { it.videoUrl == video.videoUrl }) videos.add(video)
            }
            // Torrent/magnet URIs are not HTTP media streams and must not be offered as playable Video URLs.
        }

        // If no videos yet, check if page has direct video player iframe or fallback links
        if (videos.isEmpty() && pageHtml.isNotBlank()) {
            val qualLabel = "AV1 · $resLabel$audioSuffix"
            val vHeaders = getVideoHeaders()

            val doc = Jsoup.parse(pageHtml)
            val iframe = doc.selectFirst("iframe#anime-video-player, iframe[src*='/r/'], iframe[src*='/watch/'], iframe[src*='/dash/'], iframe[src*='/stream/']")
            val iframeSrc = iframe?.attr("abs:src")?.ifBlank { iframe.attr("src") }
            val rawLink = if (!iframeSrc.isNullOrBlank()) {
                iframeSrc
            } else {
                Regex("""['"](/r/[a-zA-Z0-9_\-]+)['"]""").find(pageHtml)?.groupValues?.get(1)
                    ?: Regex("""['"](https?://[^'"]+/(?:stream|watch|dl|dash)/[^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
            }

            if (!rawLink.isNullOrBlank()) {
                val resolved = resolveRedirect(rawLink)
                val video = resolved?.let { buildVideo(it, "Stream", qualLabel, ext, vHeaders) }
                if (video != null) videos.add(video)
            }
        }

        Log.d(TAG, "extractAllVideos: returning ${videos.size} videos")
        return videos
    }

    private suspend fun fetchTextWithRetry(url: String, requestHeaders: Headers): String =
        retryTransientIo {
            getResponseWithRetry(client, url, requestHeaders).use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                response.bodyString()
            }
        }

    private suspend fun <T> retryTransientIo(block: suspend () -> T): T {
        var retries = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                if (retries >= MAX_NETWORK_RETRIES) throw e
                delay(RETRY_BASE_DELAY_MS * (retries + 1))
                retries++
            }
        }
    }

    private suspend fun getResponseWithRetry(
        requestClient: OkHttpClient,
        url: String,
        requestHeaders: Headers,
    ): Response {
        var retries = 0
        while (true) {
            val response = retryTransientIo {
                requestClient.newCall(GET(url, requestHeaders)).await()
            }
            if (response.code !in 500..599 || retries >= MAX_NETWORK_RETRIES) return response
            response.close()
            delay(RETRY_BASE_DELAY_MS * (retries + 1))
            retries++
        }
    }

    private fun buildVideo(
        url: String,
        expectedServer: String,
        qualLabel: String,
        ext: String,
        headers: Headers,
    ): Video? {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return null

        val path = runCatching { url.toHttpUrl().encodedPath }.getOrDefault(url.substringBefore("#").substringBefore("?"))
        if (path.endsWith(".torrent", ignoreCase = true)) return null

        val serverName = when {
            path.contains("/dash/", ignoreCase = true) || path.endsWith(".mpd", ignoreCase = true) -> "Dash"
            path.contains("/stream/", ignoreCase = true) -> "Stream"
            path.contains("/dl/", ignoreCase = true) -> "Direct DL"
            else -> expectedServer
        }
        if (serverName !in LINK_TYPE_ENTRIES) return null

        val videoUrl = when (serverName) {
            "Dash" -> sanitizeCdnDomain(url)
            "Stream" -> toStreamUrl(url, ext)
            "Direct DL" -> toDlUrl(url, ext)
            else -> return null
        }
        return Video(videoUrl = videoUrl, videoTitle = "$qualLabel · $serverName", headers = headers)
    }

    private suspend fun fetchEpisodePage(slug: String, season: String, resSegment: String): String {
        val epPageUrl = "$baseUrl/episodes/$slug/$season/$resSegment"
        Log.d(TAG, "extractAllVideos: refreshing episode data from $epPageUrl")
        return try {
            fetchTextWithRetry(epPageUrl, headers.newBuilder().set("Referer", "$baseUrl/anime/$slug").build())
        } catch (e: Exception) {
            Log.e(TAG, "extractAllVideos: episode data refresh failed (${e.javaClass.simpleName})")
            ""
        }
    }

    private suspend fun fetchDdlResponse(encodedFilename: String, token: String, referer: String): DdlResponse? {
        val ddlUrl = "$baseUrl/get_ddl/$encodedFilename"
        Log.d(TAG, "extractAllVideos: calling get_ddl (token present)")
        val ddlRaw = try {
            fetchTextWithRetry(
                ddlUrl,
                headers.newBuilder()
                    .set("Accept", "application/json")
                    .set("Referer", referer)
                    .set("Sec-Fetch-Dest", "empty")
                    .set("Sec-Fetch-Mode", "cors")
                    .set("Sec-Fetch-Site", "same-origin")
                    .set("X-DDL-Token", token)
                    .build(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "extractAllVideos: get_ddl failed (${e.javaClass.simpleName})")
            return null
        }
        return runCatching { ddlRaw.parseAs<DdlResponse>() }
            .getOrNull()
            ?.takeIf { it.success }
    }

    private fun findEpisodeItem(html: String, episodeUrl: String): EpisodeItem? {
        val json = html.trim()
        if (!json.startsWith("[")) return null
        val items = runCatching { json.parseAs<List<EpisodeItem>>() }.getOrNull() ?: return null
        val targetPath = normalizeEpisodePath(episodeUrl)
        return items.firstOrNull { normalizeEpisodePath(it.href) == targetPath }
    }

    private fun normalizeEpisodePath(url: String): String {
        val withoutFragment = url.substringBefore("#")
        val path = runCatching { java.net.URI(withoutFragment).rawPath }.getOrNull()
            ?: withoutFragment.substringBefore("?")
        return Uri.decode(path).trimEnd('/')
    }

    private suspend fun resolveRedirect(path: String?, depth: Int = 0): String? {
        if (path.isNullOrBlank() || depth > MAX_REDIRECT_DEPTH || path.startsWith("magnet:", ignoreCase = true)) return null
        val target = when {
            path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true) -> path
            path.startsWith("//") -> "${baseUrl.toHttpUrl().scheme}:$path"
            path.startsWith("/") -> "$baseUrl$path"
            else -> "$baseUrl/${path.trimStart('/')}"
        }
        return try {
            retryTransientIo {
                getResponseWithRetry(
                    noRedirectClient,
                    target,
                    headers.newBuilder()
                        .set("Referer", "$baseUrl/")
                        .set("Sec-Fetch-Site", "same-origin")
                        .build(),
                ).use { resp ->
                    val location = resp.header("Location")
                    if (!location.isNullOrBlank()) {
                        val nextUrl = resp.request.url.resolve(location)?.toString() ?: return@use null
                        return@use resolveRedirect(sanitizeCdnDomain(nextUrl), depth + 1)
                    }

                    if (!resp.isSuccessful) return@use null

                    val finalUrl = sanitizeCdnDomain(resp.request.url.toString())
                    val contentType = resp.header("Content-Type").orEmpty().substringBefore(";").lowercase(Locale.ROOT)
                    val finalPath = resp.request.url.encodedPath
                    if (isPlayableMediaResponse(contentType, finalPath)) return@use finalUrl

                    if (contentType.contains("html") || contentType.contains("xhtml")) {
                        val html = resp.body?.string().orEmpty()
                        for (mediaUrl in extractEmbeddedMediaUrls(html, finalUrl)) {
                            val resolved = resolveRedirect(mediaUrl, depth + 1)
                            if (resolved != null) return@use resolved
                        }
                    }
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveRedirect failed for ${path.substringBefore("?")} (${e.javaClass.simpleName})")
            null
        }
    }

    private fun isPlayableMediaResponse(contentType: String, path: String): Boolean {
        if (path.endsWith(".torrent", ignoreCase = true)) return false
        if (contentType.contains("text/html") || contentType.contains("application/json")) return false
        if (
            contentType.startsWith("video/") ||
            contentType in setOf(
                "application/dash+xml",
                "application/vnd.apple.mpegurl",
                "application/x-mpegurl",
                "application/mpegurl",
                "application/mp4",
                "application/octet-stream",
            )
        ) {
            return true
        }
        return Regex("""\.(?:mpd|m3u8|mp4|mkv|webm|ts)$""", RegexOption.IGNORE_CASE)
            .containsMatchIn(path)
    }

    private fun extractEmbeddedMediaUrls(html: String, pageUrl: String): List<String> {
        val selectors = listOf(
            "video[src]",
            "video[data-src]",
            "video source[src]",
            "video source[data-src]",
            "source[src]",
            "source[data-src]",
            "iframe[src*='dash']",
            "iframe[src*='stream']",
            "iframe[src*='watch']",
            "iframe[src*='player']",
            "iframe[src*='video']",
            "iframe[data-src]",
            "iframe[id*='player'][src]",
            "a[download][href]",
            "a[href*='/download/']",
            "a[href*='/dash/']",
            "a[href*='/stream/']",
            "a[href*='/dl/']",
            "a[href*='/r/']",
            "a[href$='.mp4']",
            "a[href$='.mkv']",
            "a[href$='.m3u8']",
        )
        val document = Jsoup.parse(html, pageUrl)
        return document.select(selectors.joinToString(","))
            .mapNotNull { element ->
                listOf("abs:src", "abs:data-src", "abs:href")
                    .firstNotNullOfOrNull { attribute -> element.attr(attribute).takeIf(String::isNotBlank) }
            }
            .distinct()
    }

    private fun sanitizeCdnDomain(url: String): String = url.replace("animealpha.cc", "av1encodes.com")

    private fun toStreamUrl(url: String, ext: String = ".mkv"): String =
        addContainerHint(sanitizeCdnDomain(url).replace("/watch/", "/stream/"), ext)

    private fun toDlUrl(url: String, ext: String = ".mkv"): String =
        addContainerHint(sanitizeCdnDomain(url), ext)

    private fun addContainerHint(url: String, ext: String): String {
        val path = url.substringBefore("#").substringBefore("?")
        val hasMediaExtension = Regex("""\.(?:mpd|m3u8|mp4|mkv|webm|ts)$""", RegexOption.IGNORE_CASE)
            .containsMatchIn(path)
        return if (hasMediaExtension || url.contains("#")) url else "$url#$ext"
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EXTRACTION HELPERS
    // ══════════════════════════════════════════════════════════════════════════
    private val tokenRegexes by lazy {
        listOf(
            Regex("""['"]X-DDL-Token['"]\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""['"](A{2,}[A-Za-z0-9_\-]{10,})['"]"""),
            Regex("""data-token=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""(?:token|ddlToken)\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
        )
    }

    private fun extractDdlToken(html: String): String? {
        for (regex in tokenRegexes) {
            val match = regex.find(html)?.groupValues?.get(1)
            if (!match.isNullOrBlank()) return match
        }
        return null
    }

    private fun extractImgUrl(img: Element?): String? {
        if (img == null) return null
        return img.attr("abs:data-src").ifBlank { null }
            ?: img.attr("abs:data-lazy-src").ifBlank { null }
            ?: img.attr("abs:src").ifBlank { null }
    }

    private val filenameRegex by lazy { Regex("""([a-zA-Z0-9_ \-\[\]().%]+?\.(?:mkv|mp4))""", RegexOption.IGNORE_CASE) }

    private fun extractFilenames(html: String): List<String> {
        val filenames = mutableSetOf<String>()
        val addDecoded = { fn: String ->
            val clean = Uri.decode(fn.trim())
            if (clean.isNotBlank() && !clean.contains("/")) filenames.add(clean)
        }
        Jsoup.parse(html).select("a[href*='/download/']").forEach {
            addDecoded(it.attr("href").substringAfterLast("/").substringBefore("?"))
        }
        filenameRegex
            .findAll(html).forEach { addDecoded(it.groupValues[1]) }
        return filenames.toList()
    }

    private val episodeNameRegex by lazy { Regex("""\[(?:S\d+-)?E(\d+)]\s*(.+?)\s*\[""") }
    private val subdubRegex by lazy { Regex("""\[(Dual|Sub|Dub|English Dub)]""", RegexOption.IGNORE_CASE) }
    private val qualityRegex by lazy { Regex("""\[\d{3,4}p].*""") }

    private fun buildEpisodeLabel(filename: String, season: String): String {
        val epMatch = episodeNameRegex.find(filename)
        return if (epMatch != null) {
            val e = epMatch.groupValues[1]
            val titlePart = epMatch.groupValues[2].trim()
            val audioTag = subdubRegex
                .find(filename)?.groupValues?.get(1) ?: ""
            val audioSuffix = if (audioTag.isNotBlank()) " [$audioTag]" else ""
            if (season != "1" && season.isNotBlank()) "Season $season Ep $e - $titlePart$audioSuffix" else "Ep $e - $titlePart$audioSuffix"
        } else {
            val cleanName = filename.replace(qualityRegex, "")
                .substringBeforeLast(".").trim()
            if (season != "1" && season.isNotBlank()) "Season $season - $cleanName" else cleanName
        }
    }

    private val episodeSNumberRegex by lazy { Regex("""\[(?:S\d+-)?E(\d+)]""") }
    private fun parseEpisodeNumber(filename: String, season: String = "1"): Float {
        val epNum = episodeSNumberRegex.find(filename)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
        val s = season.toIntOrNull() ?: 1
        return if (s > 1) {
            ((s - 1) * 100 + epNum)
        } else {
            epNum
        }
    }

    private val cleanTitleRegex1 by lazy { Regex("""\s*·\s*\d+\s*downloads?.*""", RegexOption.IGNORE_CASE) }
    private val cleanTitleRegex2 by lazy { Regex("""^\[[a-zA-Z0-9_\-]+]\s*""") }
    private val cleanTitleRegex3 by lazy { Regex("""\s*\[\d{3,4}p].*""", RegexOption.IGNORE_CASE) }
    private val cleanTitleRegex4 by lazy { Regex("""\.(mkv|mp4)$""", RegexOption.IGNORE_CASE) }

    private fun extractCleanTitle(raw: String): String {
        var cleaned = raw.replace(cleanTitleRegex1, "")
        cleaned = cleaned.replace(cleanTitleRegex2, "")
        cleaned = cleaned.replace(cleanTitleRegex3, "")
        cleaned = cleaned.replace(cleanTitleRegex4, "")
        return cleaned.trim()
    }

    private fun getListImageUrl(anchor: Element): String? {
        val img = anchor.selectFirst("img")
        if (img != null) {
            val url = img.attr("abs:data-src").ifBlank { img.attr("abs:data-lazy-src") }
                .ifBlank { img.attr("abs:src") }
            if (url.isNotBlank()) return url
        }
        return extractBg(anchor) ?: anchor.allElements.firstNotNullOfOrNull { extractBg(it) }
    }

    private val backgroundUrlRegex by lazy { Regex("""url\(['"](.*?)['"]\)""") }

    private fun extractBg(el: Element): String? {
        val style = el.attr("style")
        if (!style.contains("background", ignoreCase = true)) return null
        val match = backgroundUrlRegex.find(style) ?: return null
        val url = match.groupValues[1].ifBlank { return null }
        return if (url.startsWith("http")) url else "$baseUrl/${url.removePrefix("/")}"
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FILTERS
    // ══════════════════════════════════════════════════════════════════════════

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Note: Genre overrides Sort and Type"),
        SortFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    // ══════════════════════════════════════════════════════════════════════════
    // PREFERENCES
    // ══════════════════════════════════════════════════════════════════════════

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        buildPreferenceScreen(screen)
    }

    override fun List<Video>.sortVideos(): List<Video> = sortByPreferredQuality(preferences)

    // ══════════════════════════════════════════════════════════════════════════
    // CONSTANTS
    // ══════════════════════════════════════════════════════════════════════════

    companion object {
        private const val TAG = "AV1Encodes"
        private const val MAX_NETWORK_RETRIES = 2
        private const val RETRY_BASE_DELAY_MS = 300L
        private const val MAX_REDIRECT_DEPTH = 3
        private const val VIDEO_CACHE_TTL_MS = 30_000L
        private const val MAX_VIDEO_CACHE_SIZE = 48
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
