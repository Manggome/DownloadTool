package kr.neptune.linksaver.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import kr.neptune.linksaver.core.CookieExport

/**
 * 각 서비스의 공식 로그인 페이지를 앱 안에서 띄운다.
 * 비밀번호는 앱이 만지지 않고, 로그인 결과로 생긴 세션 쿠키만 가져가 yt-dlp 에 넘긴다.
 */
class LoginActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_SITE = "site_key"

        fun intent(context: Context, siteKey: String): Intent =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_SITE, siteKey)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val site = CookieExport.byKey(intent.getStringExtra(EXTRA_SITE))

        setContent {
            LinkSaverTheme {
                LoginScreen(
                    site = site,
                    onDone = {
                        setResult(RESULT_OK)
                        finish()
                    },
                    onCancel = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginScreen(
    site: CookieExport.Site,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var progress by remember { mutableStateOf(0) }
    var loggedIn by remember { mutableStateOf(CookieExport.isLoggedIn(site)) }
    var currentUrl by remember { mutableStateOf(site.loginUrl) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val webView = remember {
        createWebView(
            context = context,
            site = site,
            onProgress = { progress = it },
            onPageChanged = { url ->
                currentUrl = url
                loggedIn = CookieExport.isLoggedIn(site)
            },
            onError = { errorText = it }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onCancel()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(site.label + " 로그인", fontWeight = FontWeight.SemiBold)
                        Text(
                            currentUrl,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "닫기")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        errorText = null
                        webView.reload()
                    }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "새로고침")
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            StatusCard(loggedIn = loggedIn, errorText = errorText)

            // WebView 는 반드시 명확한 크기를 가져야 한다.
            // LayoutParams 를 주지 않으면 높이가 0 으로 잡혀 흰 화면이 되고 터치도 어긋난다.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Button(
                onClick = {
                    val count = CookieExport.capture(context, site)
                    if (count > 0) {
                        onDone()
                    } else {
                        scope.launch {
                            snackbar.showSnackbar("아직 로그인되지 않았습니다. 로그인을 마친 뒤 다시 눌러 주세요.")
                        }
                    }
                },
                enabled = loggedIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text("  로그인 저장하고 닫기")
            }
        }
    }
}

@Composable
private fun StatusCard(loggedIn: Boolean, errorText: String?) {
    val container = when {
        errorText != null -> MaterialTheme.colorScheme.errorContainer
        loggedIn -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                when {
                    errorText != null -> "페이지를 불러오지 못했습니다"
                    loggedIn -> "로그인 확인됨 — 아래 버튼을 눌러 저장하세요"
                    else -> "공식 로그인 페이지입니다. 로그인하면 버튼이 활성화됩니다."
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                errorText
                    ?: "자동화 도구로 세션을 쓰는 것은 각 서비스 약관 위반이라 차단·정지 위험이 있습니다. 주계정 대신 별도 계정을 권장합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: Context,
    site: CookieExport.Site,
    onProgress: (Int) -> Unit,
    onPageChanged: (String) -> Unit,
    onError: (String) -> Unit
): WebView {
    CookieManager.getInstance().setAcceptCookie(true)

    return WebView(context).apply {
        // 흰 화면 / 터치 어긋남의 주원인. 부모 크기를 그대로 채우게 명시한다.
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            // 하드코딩한 UA 는 실제 엔진 버전과 어긋나 사이트가 깨진 번들을 내려줄 수 있다.
            // 기본 UA 에서 WebView 표식(; wv)만 제거해 일반 크롬처럼 보이게 한다.
            userAgentString = WebSettings.getDefaultUserAgent(context)
                .replace("; wv", "")
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgress(newProgress)
            }
        }

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                if (url != null) onPageChanged(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                onProgress(100)
                if (url != null) onPageChanged(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // 메인 프레임 오류만 사용자에게 보여준다 (광고/추적 요청 실패는 무시)
                if (request?.isForMainFrame == true) {
                    onError("오류 " + (error?.errorCode ?: 0) + ": " + (error?.description ?: ""))
                }
            }
        }

        loadUrl(site.loginUrl)
    }
}
