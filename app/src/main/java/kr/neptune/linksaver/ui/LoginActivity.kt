package kr.neptune.linksaver.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import kr.neptune.linksaver.core.CookieExport

// 기본 WebView UA 는 X·인스타가 거부하는 경우가 있어 최신 크롬 모바일로 맞춘다
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Mobile Safari/537.36"

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

    @OptIn(ExperimentalMaterial3Api::class)
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

    var loading by remember { mutableStateOf(true) }
    var loggedIn by remember { mutableStateOf(CookieExport.isLoggedIn(site)) }

    val webView = remember { createWebView(context, site) { finished ->
        loading = !finished
        if (finished) loggedIn = CookieExport.isLoggedIn(site)
    } }

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
                    Text(site.label + " 로그인", fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "닫기")
                    }
                }
            )
        }
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {

            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (loggedIn) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        if (loggedIn) "로그인 확인됨 — 아래 버튼을 눌러 저장하세요"
                        else "공식 로그인 페이지입니다. 로그인하면 버튼이 활성화됩니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "자동화 도구로 세션을 쓰는 것은 각 서비스 약관 위반이라 차단·정지 위험이 있습니다. " +
                            "주계정 대신 별도 계정을 권장합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )

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
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  로그인 저장하고 닫기")
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: Context,
    site: CookieExport.Site,
    onLoadingChanged: (finished: Boolean) -> Unit
): WebView {
    CookieManager.getInstance().setAcceptCookie(true)

    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.userAgentString = USER_AGENT
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true

        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                onLoadingChanged(false)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                onLoadingChanged(true)
            }
        }

        loadUrl(site.loginUrl)
    }
}
