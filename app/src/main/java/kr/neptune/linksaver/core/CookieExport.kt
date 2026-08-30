package kr.neptune.linksaver.core

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import java.io.File

/**
 * 앱 내 브라우저(WebView)에 남은 세션 쿠키를 yt-dlp 가 읽는
 * Netscape cookies.txt 형식으로 옮긴다.
 *
 * 비밀번호는 앱이 다루지 않는다. 사용자가 각 서비스의 공식 로그인 페이지에
 * 직접 입력하고, 앱은 그 결과로 생긴 쿠키만 가져간다.
 */
object CookieExport {

    private const val TAG = "CookieExport"
    private const val HEADER = "# Netscape HTTP Cookie File"
    private const val FILE_NAME = "cookies.txt"

    data class Site(
        val key: String,
        val label: String,
        val loginUrl: String,
        /** 쿠키를 읽어올 기준 URL */
        val cookieUrl: String,
        /** cookies.txt 에 기록할 도메인 */
        val domain: String,
        /** 로그인 성공 판정에 쓰는 쿠키 이름 */
        val sessionCookie: String
    )

    val X = Site(
        key = "x",
        label = "X (트위터)",
        loginUrl = "https://x.com/i/flow/login",
        cookieUrl = "https://x.com",
        domain = ".x.com",
        sessionCookie = "auth_token"
    )

    val INSTAGRAM = Site(
        key = "instagram",
        label = "인스타그램",
        loginUrl = "https://www.instagram.com/accounts/login/",
        cookieUrl = "https://www.instagram.com",
        domain = ".instagram.com",
        sessionCookie = "sessionid"
    )

    val TIKTOK = Site(
        key = "tiktok",
        label = "TikTok",
        loginUrl = "https://www.tiktok.com/login",
        cookieUrl = "https://www.tiktok.com",
        domain = ".tiktok.com",
        sessionCookie = "sessionid"
    )

    val ALL = listOf(X, INSTAGRAM, TIKTOK)

    fun byKey(key: String?): Site = ALL.firstOrNull { it.key == key } ?: X

    /** 해당 사이트에 로그인된 상태인지 (세션 쿠키 존재 여부) */
    fun isLoggedIn(site: Site): Boolean {
        val raw = runCatching { CookieManager.getInstance().getCookie(site.cookieUrl) }.getOrNull()
        return raw != null && raw.contains(site.sessionCookie + "=")
    }

    /** 저장된 cookies.txt 에 해당 도메인 항목이 들어 있는지 */
    fun isSaved(context: Context, site: Site): Boolean {
        val file = cookieFile(context)
        if (!file.exists()) return false
        return runCatching {
            file.readLines().any { it.startsWith(site.domain) && it.contains(site.sessionCookie) }
        }.getOrDefault(false)
    }

    fun cookieFile(context: Context): File = File(context.filesDir, FILE_NAME)

    /**
     * WebView 쿠키를 cookies.txt 로 옮긴다.
     * 다른 사이트의 기존 항목은 유지하고 이 사이트 항목만 갱신한다.
     *
     * @return 기록한 쿠키 개수 (0 이면 로그인되지 않은 것)
     */
    fun capture(context: Context, site: Site): Int {
        val manager = CookieManager.getInstance()
        runCatching { manager.flush() }

        val raw = manager.getCookie(site.cookieUrl)
        if (raw.isNullOrBlank()) {
            Log.w(TAG, "쿠키 없음: " + site.key)
            return 0
        }

        // 만료 시각은 WebView 가 알려주지 않으므로 넉넉히 1년 뒤로 둔다.
        // 실제 만료는 서버가 판단하므로 문제되지 않는다.
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 60 * 60

        val lines = raw.split(";").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val name = pair.substring(0, idx).trim()
            val value = pair.substring(idx + 1).trim()
            if (name.isEmpty() || value.isEmpty()) return@mapNotNull null

            // domain / includeSubdomains / path / secure / expiry / name / value
            listOf(site.domain, "TRUE", "/", "TRUE", expiry.toString(), name, value)
                .joinToString(separator = "\t")
        }

        if (lines.isEmpty()) return 0

        val file = cookieFile(context)
        val others = if (file.exists()) {
            runCatching {
                file.readLines().filter {
                    it.isNotBlank() && !it.startsWith("#") && !it.startsWith(site.domain)
                }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val body = (listOf(HEADER) + others + lines).joinToString(separator = "\n")
        file.writeText(body + "\n")
        Prefs.get(context).cookiesPath = file.absolutePath

        Log.i(TAG, "쿠키 " + lines.size + "개 저장: " + site.key)
        return lines.size
    }

    /** 특정 사이트의 로그인만 해제 (WebView 쿠키 + cookies.txt 항목) */
    fun clear(context: Context, site: Site) {
        val manager = CookieManager.getInstance()
        runCatching {
            val raw = manager.getCookie(site.cookieUrl)
            raw?.split(";")?.forEach { pair ->
                val name = pair.substringBefore('=').trim()
                if (name.isNotEmpty()) {
                    manager.setCookie(site.cookieUrl, name + "=; Max-Age=0; Path=/")
                }
            }
            manager.flush()
        }

        val file = cookieFile(context)
        if (file.exists()) {
            val others = runCatching {
                file.readLines().filter {
                    it.isNotBlank() && !it.startsWith("#") && !it.startsWith(site.domain)
                }
            }.getOrDefault(emptyList())

            if (others.isEmpty()) {
                file.delete()
                Prefs.get(context).cookiesPath = null
            } else {
                file.writeText((listOf(HEADER) + others).joinToString(separator = "\n") + "\n")
            }
        }
    }

    /** 전체 로그아웃 */
    fun clearAll(context: Context) {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        cookieFile(context).delete()
        Prefs.get(context).cookiesPath = null
    }
}
