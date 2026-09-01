package kr.neptune.linksaver.core

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Collections
import kotlin.coroutines.resume

/**
 * 화면에 보이지 않는 WebView 로 페이지를 열어 미디어 주소를 알아낸다.
 *
 * 왜 필요한가: 틱톡 등은 요청에 JS 로 계산한 서명값(X-Bogus, msToken 등)을
 * 요구하고, 없으면 403 을 준다. 그 계산을 우리가 구현하는 대신
 * 진짜 브라우저에게 맡기고 결과 주소만 가져온다.
 *
 * 주소를 얻는 경로가 셋이고, 워터마크 유무 때문에 우선순위가 중요하다:
 *  1) 페이지 JSON 의 playAddr  — 워터마크 없음. 가장 좋다
 *  2) DOM 의 video.currentSrc  — 보통 재생용이라 워터마크 없음
 *  3) 네트워크 가로채기         — 임베드 스트림이면 워터마크가 박혀 있을 수 있다
 *  4) downloadAddr             — 틱톡 "저장" 용이라 워터마크가 박혀 있다
 *
 * blob: 으로 나오면 조각 스트리밍(MSE)이라 이 방법으로는 받을 수 없다.
 * 그 사실도 진단에 남겨 다음 판단에 쓴다.
 */
object WebViewExtractor {

    private const val TAG = "WebViewExtractor"

    /** 낮을수록 좋은 후보 */
    private const val RANK_PLAY_ADDR = 0
    private const val RANK_DOM = 1
    private const val RANK_NETWORK = 2
    private const val RANK_DOWNLOAD_ADDR = 3
    private const val RANK_HLS = 4

    private data class Found(val url: String, val source: String, val rank: Int)

    data class Result(
        /** 좋은 것부터 정렬된 후보 주소 */
        val candidates: List<String>,
        /** 실제로 열었던 페이지 (Referer 로 쓴다) */
        val pageUrl: String,
        /** 고른 후보의 출처 (진단용) */
        val bestSource: String?,
        val diag: List<String>
    ) {
        val best: String? get() = candidates.firstOrNull()
    }

    private val MEDIA_EXT = listOf(".mp4", ".m3u8", ".webm", ".mov")
    private val MEDIA_HOST = listOf(
        "tiktokcdn", "muscdn", "byteicdn",
        "cdninstagram", "fbcdn",
        "video.twimg", "akamaized"
    )
    private val NOT_MEDIA = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".heic", ".gif",
        ".css", ".js", ".woff", ".svg", ".ico",
        "avatar", "/img/", "thumbnail", "sprite"
    )

    /**
     * @param timeoutMs 페이지 하나당 이 시간까지 기다린다. 좋은 후보를 찾으면 더 빨리 끝난다.
     */
    suspend fun extract(
        context: Context,
        url: String,
        timeoutMs: Long = 20_000L
    ): Result {
        val diag = mutableListOf<String>()
        val pages = pagesFor(url, diag)
        var last: Result? = null

        for ((index, page) in pages.withIndex()) {
            val result = extractFrom(context, page, timeoutMs, diag)
            last = result

            // 워터마크 없는 주소를 얻었으면 더 볼 필요가 없다
            if (result.bestSource == "playAddr" || result.bestSource == "DOM") {
                return result
            }
            if (result.best != null && index == pages.lastIndex) {
                return result
            }
            if (result.best != null) {
                diag += "워터마크 없는 주소를 못 찾아 다음 페이지를 시도합니다"
            }
        }

        return last ?: Result(emptyList(), pages.firstOrNull() ?: url, null, diag)
    }

    private suspend fun extractFrom(
        context: Context,
        pageUrl: String,
        timeoutMs: Long,
        diag: MutableList<String>
    ): Result {
        val network = Collections.synchronizedSet(LinkedHashSet<String>())
        val found = mutableListOf<Found>()
        var blobSeen = false

        withContext(Dispatchers.Main) {
            val web = create(context) { requested ->
                if (requested.startsWith("blob:")) blobSeen = true
                else if (isMedia(requested)) network.add(requested)
            }

            try {
                web.loadUrl(pageUrl)

                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    delay(1200)

                    // 1) 페이지 JSON 에서 playAddr / downloadAddr
                    parsePageAddrs(evaluate(web, JS_PAGE_ADDRS)).forEach { found.add(it) }

                    // 2) DOM 의 video 태그
                    readVideoSources(web).forEach { src ->
                        if (src.startsWith("blob:")) blobSeen = true
                        else found.add(Found(src, "DOM", RANK_DOM))
                    }

                    // 워터마크 없는 걸 찾았으면 그만 기다린다
                    if (found.any { it.rank <= RANK_DOM }) break
                }
            } catch (t: Throwable) {
                Log.w(TAG, "추출 실패: " + t.message)
                diag += "웹뷰 오류: " + (t.message ?: t.javaClass.simpleName)
            } finally {
                runCatching {
                    web.stopLoading()
                    web.destroy()
                }
            }
        }

        network.forEach { u ->
            val rank = if (u.contains(".m3u8")) RANK_HLS else RANK_NETWORK
            found.add(Found(u, "네트워크", rank))
        }

        val ranked = found
            .filter { it.url.startsWith("http") }
            .distinctBy { it.url }
            .sortedBy { it.rank }

        diag += "페이지: " + pageUrl
        diag += "후보 " + ranked.size + "개" +
            (if (ranked.isEmpty()) "" else " (" + ranked.take(4).joinToString(", ") { it.source } + ")")
        if (blobSeen) {
            diag += "blob: 감지 — 조각 스트리밍(MSE)이라 이 방법으로는 받을 수 없습니다"
        }
        ranked.firstOrNull()?.let {
            diag += "선택: [" + it.source + "] " + it.url.take(110)
            if (it.rank >= RANK_NETWORK) {
                diag += "주의: 재생용 주소를 못 찾아 워터마크가 있을 수 있습니다"
            }
        }

        return Result(
            candidates = ranked.map { it.url },
            pageUrl = pageUrl,
            bestSource = ranked.firstOrNull()?.source,
            diag = diag
        )
    }

    /**
     * 열어야 할 페이지 목록 (앞에서부터 시도).
     *
     * 틱톡은 영상 페이지가 일반 HTTP 요청엔 403 이지만, 브라우저는 서명값을
     * 스스로 만들기 때문에 열린다. 영상 페이지에는 playAddr(워터마크 없음)이
     * 들어 있으므로 이쪽을 먼저 본다. 안 되면 임베드 페이지로 내려간다.
     */
    private fun pagesFor(url: String, diag: MutableList<String>): List<String> {
        if (UrlUtil.platformOf(url) != Platform.TIKTOK) return listOf(url)

        val resolved = runCatching {
            if (url.contains("/t/") || url.contains("vm.tiktok") || url.contains("vt.tiktok")) {
                UrlUtil.resolveRedirect(url)
            } else {
                url
            }
        }.getOrDefault(url)

        val id = Regex("/video/(\\d+)").find(resolved)?.groupValues?.getOrNull(1)
        return if (id != null) {
            diag += "틱톡: 영상 페이지 -> 임베드 페이지 순으로 시도"
            listOf(resolved, "https://www.tiktok.com/embed/v2/" + id)
        } else {
            listOf(resolved)
        }
    }

    private fun isMedia(url: String): Boolean {
        val lower = url.lowercase()
        if (NOT_MEDIA.any { lower.contains(it) }) return false
        return MEDIA_EXT.any { lower.contains(it) } || MEDIA_HOST.any { lower.contains(it) }
    }

    /** JS 가 돌려준 [[키, URL], ...] 을 후보로 바꾼다 */
    private fun parsePageAddrs(raw: String): List<Found> {
        if (raw.isBlank()) return emptyList()

        // evaluateJavascript 는 JSON 문자열을 다시 문자열로 감싸 돌려준다
        val unwrapped = raw.trim().removeSurrounding("\"")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")

        return runCatching {
            val arr = JSONArray(unwrapped)
            (0 until arr.length()).mapNotNull { i ->
                val pair = arr.optJSONArray(i) ?: return@mapNotNull null
                val key = pair.optString(0)
                val value = pair.optString(1)
                if (!value.startsWith("http")) return@mapNotNull null

                val isDownloadAddr = key.contains("download", ignoreCase = true)
                Found(
                    url = value,
                    source = if (isDownloadAddr) "downloadAddr" else "playAddr",
                    rank = if (isDownloadAddr) RANK_DOWNLOAD_ADDR else RANK_PLAY_ADDR
                )
            }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(context: Context, onRequest: (String) -> Unit): WebView {
        CookieManager.getInstance().setAcceptCookie(true)

        return WebView(context.applicationContext).apply {
            // 화면에 붙이지 않으므로 크기를 직접 준다.
            // 크기가 0 이면 플레이어가 재생을 시작하지 않아 미디어 요청이 안 나간다.
            layoutParams = ViewGroup.LayoutParams(1080, 1920)
            layout(0, 0, 1080, 1920)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = WebSettings.getDefaultUserAgent(context).replace("; wv", "")
            }

            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    request?.url?.toString()?.let(onRequest)
                    // null 을 돌려주면 요청은 그대로 진행된다 (가로채기만 하고 막지 않는다)
                    return null
                }
            }
        }
    }

    /** video 태그의 재생 주소를 읽고, 필요하면 재생을 눌러준다 */
    private suspend fun readVideoSources(web: WebView): List<String> {
        val json = evaluate(web, JS_READ_SOURCES)
        if (json.isBlank()) return emptyList()

        return Regex("https?://[^\"\\\\ ]+|blob:[^\"\\\\ ]+")
            .findAll(json)
            .map { it.value }
            .distinct()
            .toList()
    }

    private suspend fun evaluate(web: WebView, script: String): String =
        suspendCancellableCoroutine { cont ->
            runCatching {
                web.evaluateJavascript(script) { value ->
                    if (cont.isActive) cont.resume(value ?: "")
                }
            }.onFailure {
                if (cont.isActive) cont.resume("")
            }
        }

    private val JS_READ_SOURCES = """
        (function () {
          var out = [];
          var vids = document.querySelectorAll('video');
          for (var i = 0; i < vids.length; i++) {
            var v = vids[i];
            if (v.currentSrc) out.push(v.currentSrc);
            if (v.src) out.push(v.src);
            var srcs = v.querySelectorAll('source');
            for (var j = 0; j < srcs.length; j++) {
              if (srcs[j].src) out.push(srcs[j].src);
            }
            try { v.muted = true; v.play(); } catch (e) {}
          }
          return JSON.stringify(out);
        })();
    """.trimIndent()

    /**
     * 페이지가 심어둔 JSON 데이터에서 재생 주소를 찾는다.
     * 틱톡은 playAddr(워터마크 없음)와 downloadAddr(워터마크 있음)을 함께 담는다.
     */
    private val JS_PAGE_ADDRS = """
        (function () {
          var keys = ['playAddr', 'play_addr', 'downloadAddr', 'download_addr'];
          var out = [];

          function walk(node, depth) {
            if (!node || depth > 10 || typeof node !== 'object') return;
            for (var k in node) {
              var v = node[k];
              if (keys.indexOf(k) >= 0) {
                if (typeof v === 'string' && v.indexOf('http') === 0) {
                  out.push([k, v]);
                } else if (v && typeof v === 'object') {
                  if (typeof v.url_list !== 'undefined' && v.url_list.length) {
                    out.push([k, v.url_list[0]]);
                  }
                  walk(v, depth + 1);
                }
              } else if (v && typeof v === 'object') {
                walk(v, depth + 1);
              }
            }
          }

          var ids = ['__UNIVERSAL_DATA_FOR_REHYDRATION__', 'SIGI_STATE', 'sigi-persisted-data'];
          for (var i = 0; i < ids.length; i++) {
            var el = document.getElementById(ids[i]);
            if (!el) continue;
            try { walk(JSON.parse(el.textContent), 0); } catch (e) {}
          }
          return JSON.stringify(out);
        })();
    """.trimIndent()
}
