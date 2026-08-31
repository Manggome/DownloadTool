package kr.neptune.linksaver

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kr.neptune.linksaver.core.DownloadRepo
import kr.neptune.linksaver.core.Notifications
import kr.neptune.linksaver.core.YtDlp

class LinkSaverApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        DownloadRepo.init(this)

        // python + yt-dlp + ffmpeg 압축 해제는 첫 실행에만 오래 걸리므로 미리 시작해 둔다
        appScope.launch { YtDlp.ensureInit(this@LinkSaverApp) }
    }
}
