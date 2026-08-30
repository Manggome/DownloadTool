package kr.neptune.linksaver.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 실제 다운로드를 수행하는 포그라운드 서비스.
 * 한 번에 하나씩 순차 처리하고, 모두 끝나면 스스로 종료한다.
 *
 * 화면에서 미리 목록을 조회하고 사용자가 항목을 고른 경우
 * ([EXTRA_PLAYLIST_ITEMS] / [EXTRA_TITLE] 이 함께 들어온 경우)
 * 서비스는 조회를 건너뛰고 바로 다운로드부터 시작한다.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(1)
    private val running = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 포그라운드 승격은 무조건 즉시 (ForegroundServiceDidNotStartInTime 방지)
        promote("준비 중…", "", 0, true)

        when (intent?.action) {
            ACTION_CANCEL -> {
                intent.getStringExtra(EXTRA_TASK_ID)?.let { cancelTask(it) }
                if (running.get() == 0) stopSelf()
                return START_NOT_STICKY
            }

            ACTION_ENQUEUE -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (url.isNullOrBlank()) {
                    if (running.get() == 0) stopSelf()
                    return START_NOT_STICKY
                }
                val quality = runCatching {
                    Quality.valueOf(intent.getStringExtra(EXTRA_QUALITY) ?: Quality.BEST.name)
                }.getOrDefault(Quality.BEST)

                enqueue(
                    rawUrl = url,
                    quality = quality,
                    playlistItems = intent.getStringExtra(EXTRA_PLAYLIST_ITEMS),
                    expectedCount = intent.getIntExtra(EXTRA_EXPECTED_COUNT, 0),
                    knownTitle = intent.getStringExtra(EXTRA_TITLE),
                    knownUploader = intent.getStringExtra(EXTRA_UPLOADER),
                    knownThumbnail = intent.getStringExtra(EXTRA_THUMBNAIL)
                )
            }

            else -> if (running.get() == 0) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 작업

    private fun enqueue(
        rawUrl: String,
        quality: Quality,
        playlistItems: String?,
        expectedCount: Int,
        knownTitle: String?,
        knownUploader: String?,
        knownThumbnail: String?
    ) {
        val taskId = UUID.randomUUID().toString()
        DownloadRepo.add(
            DownloadTask(
                id = taskId,
                url = rawUrl,
                platform = UrlUtil.platformOf(rawUrl),
                quality = quality,
                playlistItems = playlistItems,
                expectedCount = expectedCount,
                title = knownTitle ?: "정보 가져오는 중…",
                uploader = knownUploader,
                thumbnail = knownThumbnail,
                state = TaskState.QUEUED
            )
        )
        running.incrementAndGet()

        scope.launch {
            try {
                gate.withPermit {
                    runTask(taskId, rawUrl, quality, playlistItems, alreadyProbed = knownTitle != null)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "task crashed", t)
                fail(taskId, YtDlp.humanizeError(t.message), raw = rawOf(t))
            } finally {
                if (running.decrementAndGet() == 0) {
                    ServiceCompat.stopForeground(
                        this@DownloadService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE
                    )
                    stopSelf()
                }
            }
        }
    }

    private suspend fun runTask(
        taskId: String,
        rawUrl: String,
        quality: Quality,
        playlistItems: String?,
        alreadyProbed: Boolean
    ) {
        val workDir = File(cacheDir, "dl/$taskId")
        workDir.mkdirs()

        try {
            var url = rawUrl

            if (!alreadyProbed) {
                // 1) 링크 정규화 (t.co / instagram.com/share 리다이렉트 해제)
                DownloadRepo.update(taskId) {
                    it.copy(state = TaskState.FETCHING, statusLine = "링크 확인 중…")
                }
                promote("링크 확인 중…", rawUrl, 0, true)

                url = runCatching { UrlUtil.canonicalize(rawUrl) }.getOrDefault(rawUrl)
                DownloadRepo.update(taskId) {
                    it.copy(url = url, platform = UrlUtil.platformOf(url))
                }

                // 2) 메타데이터 (실패해도 다운로드는 시도한다)
                val metas = runCatching { YtDlp.probe(this, url) }.getOrDefault(emptyList())
                val first = metas.firstOrNull()
                if (first != null) {
                    DownloadRepo.update(taskId) {
                        it.copy(
                            title = first.title,
                            uploader = first.uploader,
                            thumbnail = first.thumbnail,
                            expectedCount = metas.size
                        )
                    }
                } else {
                    DownloadRepo.update(taskId) { it.copy(title = "제목 확인 불가") }
                }
            }

            // 3) 다운로드
            DownloadRepo.update(taskId) {
                it.copy(state = TaskState.DOWNLOADING, progress = 0f, statusLine = "다운로드 중…")
            }

            val titleForNoti = DownloadRepo.get(taskId)?.title.orEmpty().ifBlank { "다운로드" }
            val files = YtDlp.download(
                context = this,
                url = url,
                quality = quality,
                outDir = workDir,
                processId = taskId,
                playlistItems = playlistItems
            ) { progress, eta, line ->
                DownloadRepo.update(taskId) {
                    it.copy(
                        state = TaskState.DOWNLOADING,
                        progress = (progress / 100f).coerceIn(0f, 1f),
                        etaSec = eta,
                        statusLine = line.trim().take(120)
                    )
                }
                promote(
                    title = titleForNoti,
                    text = etaText(eta),
                    percent = progress.toInt(),
                    indeterminate = progress < 0f,
                    force = false
                )
            }

            if (files.isEmpty()) {
                fail(
                    taskId,
                    "받을 수 있는 파일이 없습니다. 비공개 게시물이거나 링크가 잘못됐을 수 있습니다.",
                    raw = buildString {
                        appendLine("yt-dlp 가 파일을 하나도 만들지 않았습니다.")
                        appendLine("url=" + url)
                        appendLine("quality=" + quality.name)
                        appendLine("items=" + (playlistItems ?: "(전체)"))
                        appendLine("마지막 상태: " + (DownloadRepo.get(taskId)?.statusLine ?: ""))
                    }
                )
                return
            }

            // 4) 갤러리로 이동
            DownloadRepo.update(taskId) {
                it.copy(state = TaskState.SAVING, progress = 1f, statusLine = "갤러리에 저장 중…")
            }
            promote(titleForNoti, "갤러리에 저장 중…", 100, false)

            val uris = MediaImporter.importAll(this, files, DownloadRepo.get(taskId)?.title)

            if (uris.isEmpty()) {
                fail(taskId, "갤러리에 저장하지 못했습니다. 저장 공간을 확인해 주세요.")
                return
            }

            DownloadRepo.update(taskId) {
                it.copy(
                    state = TaskState.DONE,
                    progress = 1f,
                    savedCount = uris.size,
                    savedUris = uris,
                    statusLine = "${uris.size}개 저장 완료",
                    error = null
                )
            }
            Notifications.showResult(
                context = this,
                key = taskId.hashCode(),
                title = "다운로드 완료",
                text = "${titleForNoti.take(40)} · ${uris.size}개 저장됨\n${MediaImporter.savedLocationText()}",
                success = true
            )
        } catch (t: Throwable) {
            val canceled = t.javaClass.simpleName.contains("Canceled", true) ||
                DownloadRepo.get(taskId)?.state == TaskState.CANCELED
            if (canceled) {
                DownloadRepo.update(taskId) {
                    it.copy(state = TaskState.CANCELED, statusLine = "취소됨")
                }
            } else {
                Log.e(TAG, "download failed", t)
                fail(taskId, YtDlp.humanizeError(t.message), raw = rawOf(t))
            }
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    private fun fail(taskId: String, message: String, raw: String? = null) {
        DownloadRepo.update(taskId) {
            it.copy(
                state = TaskState.FAILED,
                error = message,
                rawError = raw?.takeIf { s -> s.isNotBlank() },
                statusLine = "실패"
            )
        }
        Notifications.showResult(this, taskId.hashCode(), "다운로드 실패", message, success = false)
    }

    /** 예외 사슬 전체를 진단용 문자열로 펼친다 (yt-dlp stderr 가 여기 담긴다) */
    private fun rawOf(t: Throwable): String = buildString {
        var cur: Throwable? = t
        var depth = 0
        while (depth < 5) {
            val c = cur ?: break
            if (depth > 0) {
                appendLine()
                appendLine("--- caused by ---")
            }
            append(c.javaClass.simpleName)
            append(": ")
            append(c.message ?: "(메시지 없음)")
            cur = c.cause
            depth++
        }
    }.take(4000)

    private fun cancelTask(taskId: String) {
        DownloadRepo.update(taskId) { it.copy(state = TaskState.CANCELED, statusLine = "취소 중…") }
        YtDlp.cancel(taskId)
    }

    // ------------------------------------------------------------------ 알림

    @Volatile
    private var lastNotifyAt = 0L

    @Volatile
    private var lastPercent = -1

    /**
     * 포그라운드 알림 갱신.
     * yt-dlp 진행 콜백은 초당 수십 번 오므로 400ms / 1% 단위로 솎아낸다.
     * (최초 승격 등 [force] 인 경우는 반드시 즉시 호출한다)
     */
    private fun promote(
        title: String,
        text: String,
        percent: Int,
        indeterminate: Boolean,
        force: Boolean = true
    ) {
        val now = System.currentTimeMillis()
        if (!force && percent == lastPercent && now - lastNotifyAt < 400L) return
        lastPercent = percent
        lastNotifyAt = now

        val notification = Notifications.progress(this, title, text, percent, indeterminate)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                Notifications.FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ServiceCompat.startForeground(this, Notifications.FOREGROUND_ID, notification, 0)
        }
    }

    private fun etaText(eta: Long): String =
        if (eta <= 0) "남은 시간 계산 중…" else "남은 시간 약 ${eta}초"

    companion object {
        private const val TAG = "DownloadService"

        const val ACTION_ENQUEUE = "kr.neptune.linksaver.ENQUEUE"
        const val ACTION_CANCEL = "kr.neptune.linksaver.CANCEL"

        const val EXTRA_URL = "url"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_PLAYLIST_ITEMS = "playlist_items"
        const val EXTRA_EXPECTED_COUNT = "expected_count"
        const val EXTRA_TITLE = "title"
        const val EXTRA_UPLOADER = "uploader"
        const val EXTRA_THUMBNAIL = "thumbnail"

        /**
         * @param playlistItems "1,3,7" 형식. null 이면 게시물 전체
         * @param knownTitle 화면에서 이미 조회를 끝냈다면 넘긴다 (서비스가 조회를 건너뛴다)
         */
        fun start(
            context: Context,
            url: String,
            quality: Quality,
            playlistItems: String? = null,
            expectedCount: Int = 0,
            knownTitle: String? = null,
            knownUploader: String? = null,
            knownThumbnail: String? = null
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_ENQUEUE
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_QUALITY, quality.name)
                putExtra(EXTRA_PLAYLIST_ITEMS, playlistItems)
                putExtra(EXTRA_EXPECTED_COUNT, expectedCount)
                putExtra(EXTRA_TITLE, knownTitle)
                putExtra(EXTRA_UPLOADER, knownUploader)
                putExtra(EXTRA_THUMBNAIL, knownThumbnail)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_TASK_ID, taskId)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
