package kr.neptune.linksaver.core

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object UrlUtil {

    private val URL_REGEX = Regex("""https?://[^\s<>"'\)\]]+""")

    private val INSTAGRAM_HOSTS = setOf(
        "instagram.com", "www.instagram.com", "m.instagram.com", "instagr.am", "ddinstagram.com"
    )
    private val TWITTER_HOSTS = setOf(
        "x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com",
        "vxtwitter.com", "fxtwitter.com", "fixupx.com", "twittpr.com", "nitter.net"
    )
    private val REDIRECT_HOSTS = setOf("t.co", "instagr.am", "bit.ly", "buff.ly")

    /** 공유 인텐트로 들어온 텍스트에서 첫 번째 URL 을 뽑아냄 */
    fun extractFirstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return URL_REGEX.find(text)?.value?.trimEnd('.', ',', ')', ']')
    }

    fun hostOf(url: String): String = runCatching {
        URI(url).host?.lowercase().orEmpty()
    }.getOrDefault("")

    fun platformOf(url: String): Platform {
        val host = hostOf(url)
        return when {
            host in INSTAGRAM_HOSTS -> Platform.INSTAGRAM
            host in TWITTER_HOSTS -> Platform.TWITTER
            else -> Platform.OTHER
        }
    }

    fun isSupported(url: String): Boolean = platformOf(url) != Platform.OTHER

    /** 리다이렉트가 필요한 단축/공유 링크인지 */
    fun needsRedirectResolve(url: String): Boolean {
        val host = hostOf(url)
        if (host in REDIRECT_HOSTS) return true
        // 인스타 신형 공유 링크: instagram.com/share/xxxx
        return host in INSTAGRAM_HOSTS && URI(url).path.orEmpty().startsWith("/share")
    }

    /** HEAD 요청으로 최종 URL 을 따라감 (백그라운드 스레드에서 호출할 것) */
    fun resolveRedirect(url: String, maxHops: Int = 5): String {
        var current = url
        repeat(maxHops) {
            val conn = runCatching {
                (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "HEAD"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
                    )
                }
            }.getOrNull() ?: return current

            val next = runCatching {
                val code = conn.responseCode
                if (code in 300..399) conn.getHeaderField("Location") else null
            }.getOrNull()
            runCatching { conn.disconnect() }

            if (next.isNullOrBlank()) return current
            current = runCatching { URI(current).resolve(next).toString() }.getOrDefault(next)
        }
        return current
    }

    /**
     * 추적 파라미터(?igsh=, ?t=, ?s=) 제거 + 미러 호스트를 원본으로 정규화.
     * yt-dlp 가 인식하는 형태로 맞춰준다.
     */
    fun normalize(rawUrl: String): String {
        val url = rawUrl.trim()
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase() ?: return url
        val path = uri.path.orEmpty().trimEnd('/')

        return when {
            host in INSTAGRAM_HOSTS -> "https://www.instagram.com$path/"
            host in TWITTER_HOSTS -> {
                // /i/status/123, /user/status/123 형태만 유지
                "https://x.com$path"
            }
            else -> url
        }
    }

    /** 정규화 + (필요시) 리다이렉트 해제. 네트워크를 타므로 IO 스레드에서 호출. */
    fun canonicalize(rawUrl: String): String {
        var url = extractFirstUrl(rawUrl) ?: rawUrl.trim()
        if (needsRedirectResolve(url)) {
            url = resolveRedirect(url)
        }
        return normalize(url)
    }
}
