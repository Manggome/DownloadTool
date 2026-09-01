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
import java.util.Collections
import kotlin.coroutines.resume

/**
 * 화면에 보이지 않는 WebView 로 페이지를 열어 미디어 주소를 알아낸다.
 *
 * 왜 필요한가: 틱톡 등은 요청에 JS 로 계산한 서명값(X-Bogus, msToken 등)을
 * 요구하고, 없으면 403 을 준다. 그 계산을 우리가 구현하는 대신
 * 진짜 브라우저에게 맡기고 결과 주소만 가져온다.
 *
 * 두 가지를 동시에 본다:
 *  1) 네트워크 가로채기 — 페이지가 실제로 요청한 미디어 URL
 *  2) DOM 읽기 — video 태그의 currentSrc / src
 *
 * blob: 로 나오면 조각 스트리밍(MSE)이라는 뜻이고 이 방법으로는 받을 수 없다.
 * 그 사실도 진단에 남겨 다음 판단에 쓴다.
 */
object WebViewExtractor {

    private const val TAG = "WebViewExtractor"

    data class Result(
        /** 좋은 것부터 정렬된 후보 주소 */
        val candidates: List<String>,
        /** 실제로 열었던 페이지 (Referer 로 쓴다) */
        val pageUrl: String,
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
     * @param timeoutMs 이 시간까지 기다린다. 미디어를 찾으면 더 빨리 끝난다.
     */
    suspend fun extract(
        context: Context,
        url: String,
        timeoutMs: Long = 25_000L
    ): Result {
        val diag = mutableListOf<String>()
        val pageUrl = pageFor(url, diag)
        val network = Collections.synchronizedSet(LinkedHashSet<String>())
        val dom = Collections.synchronizedSet(LinkedHashSet<String>())
        var blobSeen = false

        withContext(Dispatchers.Main) {
            val web = create(context) { requested ->
                if (requested.startsWith("blob:")) {
                    blobSeen = true
                } else if (isMedia(requested)) {
                    network.add(requested)
                }
            }

            try {
                web.loadUrl(pageUrl)

                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    delay(1200)

                    readVideoSources(web).forEach { src ->
                        if (src.startsWith("blob:")) blobSeen = true else dom.add(src)
                    }

                    // 바로 받을 수 있는 형태(mp4 등)를 잡았으면 그만 기다린다
                    val direct = (network + dom).any { it.startsWith("http") && !it.contains(".m3u8") }
                    if (direct) break
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

        val all = (dom + network).filter { it.startsWith("http") }
        // 조각 스트리밍(m3u8)보다 단일 파일(mp4 등)을 앞세운다
        val ranked = all.sortedBy { if (it.contains(".m3u8")) 1 else 0 }

        diag += "페이지: " + pageUrl
        diag += "네트워크 후보 " + network.size + "개 / DOM 후보 " + dom.size + "개"
        if (blobSeen) {
            diag += "blob: 감지 — 조각 스트리밍(MSE)이라 이 방법으로는 받을 수 없습니다"
        }
        if (ranked.isEmpty()) {
            diag += "미디어 주소를 찾지 못했습니다"
        } else {
            diag += "선택: " + ranked.first().take(120)
        }

        return Result(ranked, pageUrl, diag)
    }

    /**
     * 열어야 할 페이지를 고른다.
     * 틱톡은 영상 페이지가 403 이지만 제3자 삽입용 임베드 페이지는 열린다.
     */
    private fun pageFor(url: String, diag: MutableList<String>): String {
        if (UrlUtil.platformOf(url) != Platform.TIKTOK) return url

        val resolved = runCatching {
            if (url.contains("/t/") || url.contains("vm.tiktok") || url.contains("vt.tiktok")) {
                UrlUtil.resolveRedirect(url)
            } else {
                url
            }
        }.getOrDefault(url)

        val id = Regex("/video/(\\d+)").find(resolved)?.groupValues?.getOrNull(1)
        return if (id != null) {
            diag += "틱톡 임베드 페이지 사용 (영상 페이지는 403)"
            "https://www.tiktok.com/embed/v2/" + id
        } else {
            resolved
        }
    }

    private fun isMedia(url: String): Boolean {
        val lower = url.lowercase()
        if (NOT_MEDIA.any { lower.contains(it) }) return false
        return MEDIA_EXT.any { lower.contains(it) } || MEDIA_HOST.any { lower.contains(it) }
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

        // evaluateJavascript 는 JSON 문자열을 다시 문자열로 감싸서 돌려준다
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
}
