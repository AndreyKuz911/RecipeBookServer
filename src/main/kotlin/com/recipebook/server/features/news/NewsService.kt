package com.recipebook.server.features.news

import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.parsers.DocumentBuilderFactory
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

class NewsService {
    private val logger = LoggerFactory.getLogger(NewsService::class.java)
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val feeds = listOf(
        FeedSource("Gastronom", "https://www.gastronom.ru/rss"),
        FeedSource("Povarenok", "https://www.povarenok.ru/rss/"),
    )

    @Volatile
    private var cache: CacheEntry? = null
    private val cacheLock = Any()

    fun listNews(limit: Int = 30): List<NewsItemDto> {
        val now = Instant.now()
        val cached = cache
        if (cached != null && cached.expiresAt.isAfter(now)) {
            return cached.items.take(limit)
        }

        synchronized(cacheLock) {
            val insideCache = cache
            if (insideCache != null && insideCache.expiresAt.isAfter(Instant.now())) {
                return insideCache.items.take(limit)
            }

            val fresh = fetchAllNews(limit = limit.coerceIn(1, 100))
            val items = if (fresh.isNotEmpty()) fresh else insideCache?.items.orEmpty()
            cache = CacheEntry(
                items = items,
                expiresAt = now.plusSeconds(10 * 60),
            )
            return items.take(limit)
        }
    }

    private fun fetchAllNews(limit: Int): List<NewsItemDto> {
        val items = feeds.flatMap { source ->
            runCatching { fetchFromFeed(source) }
                .onFailure { error ->
                    logger.warn("Failed to load feed {}: {}", source.name, error.message ?: "unknown")
                }
                .getOrDefault(emptyList())
        }

        return items
            .sortedByDescending { it.publishedAt }
            .distinctBy { it.url }
            .take(limit)
            .map { it.toDto() }
    }

    private fun fetchFromFeed(source: FeedSource): List<NewsItem> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(source.url))
            .timeout(Duration.ofSeconds(8))
            .header("User-Agent", "RecipeBookServer/1.0 (+news-fetcher)")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            return emptyList()
        }

        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(InputSource(StringReader(response.body())))

        val nodeList = document.getElementsByTagName("item")
        val items = mutableListOf<NewsItem>()
        for (i in 0 until nodeList.length) {
            val item = nodeList.item(i) as? Element ?: continue
            val title = item.firstText("title").orEmpty().trim()
            val url = item.firstText("link").orEmpty().trim()
            if (title.isBlank() || url.isBlank()) continue

            val rawSummary = item.firstText("description").orEmpty()
            val summary = rawSummary.stripHtml().normalizeWhitespace().ifBlank { title }
            val publishedAt = parsePubDate(item.firstText("pubDate")) ?: Instant.now()
            val imageUrl = item.firstImageUrl()?.trim()

            items += NewsItem(
                title = title,
                summary = summary,
                url = url,
                imageUrl = imageUrl,
                publishedAt = publishedAt,
                source = source.name,
            )
        }
        return items
    }

    private fun parsePubDate(value: String?): Instant? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        return try {
            ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            runCatching { Instant.parse(trimmed) }.getOrNull()
        }
    }

    private fun Element.firstImageUrl(): String? {
        val enclosure = getElementsByTagName("enclosure")
        for (i in 0 until enclosure.length) {
            val node = enclosure.item(i) as? Element ?: continue
            val type = node.getAttribute("type")
            val url = node.getAttribute("url")
            if (url.isNotBlank() && type.contains("image", ignoreCase = true)) {
                return url
            }
        }

        val mediaContent = getElementsByTagName("media:content")
        for (i in 0 until mediaContent.length) {
            val node = mediaContent.item(i) as? Element ?: continue
            val url = node.getAttribute("url")
            if (url.isNotBlank()) return url
        }

        val mediaThumb = getElementsByTagName("media:thumbnail")
        for (i in 0 until mediaThumb.length) {
            val node = mediaThumb.item(i) as? Element ?: continue
            val url = node.getAttribute("url")
            if (url.isNotBlank()) return url
        }

        val desc = firstText("description").orEmpty()
        val imgRegex = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        return imgRegex.find(desc)?.groupValues?.getOrNull(1)
    }

    private fun Element.firstText(tagName: String): String? {
        val list = getElementsByTagName(tagName)
        if (list.length == 0) return null
        return list.item(0)?.textContent
    }

    private fun String.stripHtml(): String =
        replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

    private fun String.normalizeWhitespace(): String = replace(Regex("\\s+"), " ").trim()

    private data class FeedSource(
        val name: String,
        val url: String,
    )

    private data class NewsItem(
        val title: String,
        val summary: String,
        val url: String,
        val imageUrl: String?,
        val publishedAt: Instant,
        val source: String,
    ) {
        fun toDto(): NewsItemDto = NewsItemDto(
            title = title,
            summary = summary,
            url = url,
            imageUrl = imageUrl,
            publishedAt = publishedAt.toString(),
            source = source,
        )
    }

    private data class CacheEntry(
        val items: List<NewsItemDto>,
        val expiresAt: Instant,
    )
}
