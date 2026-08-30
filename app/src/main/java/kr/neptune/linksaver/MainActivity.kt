package kr.neptune.linksaver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import kr.neptune.linksaver.ui.HomeScreen
import kr.neptune.linksaver.ui.LinkSaverTheme
import kr.neptune.linksaver.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 결과는 무시 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LinkSaverTheme {
                HomeScreen(viewModel)
            }
        }

        askNotificationPermission()
        handleIntent(intent, autoStart = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, autoStart = true)
    }

    override fun onResume() {
        super.onResume()
        // 안드로이드 10+ 는 포그라운드일 때만 클립보드를 읽을 수 있다
        if (viewModel.autoPaste) {
            viewModel.pasteFromClipboard(this, silent = true)
        }
    }

    /** 공유 인텐트 / 링크 열기로 들어온 URL 처리 */
    private fun handleIntent(intent: Intent?, autoStart: Boolean) {
        if (intent == null) return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return

        viewModel.onIncomingText(text, autoStart)

        // 같은 인텐트를 회전 등으로 다시 처리하지 않도록 비운다
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.data = null
        intent.action = Intent.ACTION_MAIN
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
