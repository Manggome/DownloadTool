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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 실제 다운로드를 수행하는 포그라운드 서비스.
 *
 * - 동시 실행 개수는 설정값(기본 2)을 따른다. 플랫폼이 요청 수에 민감해
 *   무턱대고 올리면 오히려 차단에 빨리 걸린다.
 * - 일시적 실패(요청 제한, 연결 오류 등)는 백오프를 두고 스스로 재시도한다.
 * - 화면에서 미리 조회를 끝낸 경우([EXTRA_TITLE] 이 들어온 경우) 조회를 건너뛴다.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicInteger(0)

    /** 설정값은 서비스가 처음 일할 때 한 번 읽는다 */
    private val gate by lazy {
        Semaphore(Prefs.get(this).maxConcurrentDownloads.coerceIn(1, 3))
    }

    /** runTask 의 결과. 재시도 여부를 호출자가 판단할 수 있게 실패 사유를 함께 돌려준다 */
    private sealed interface Outcome {
        data object Success : Outcome
        data object Canceled : Outcome
        data class Failed(
            val message: String,
            val raw: String?,
            val transient: Boolean
        ) : Outcome
    }

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
                runWithRetry(taskId, rawUrl, quality, playlistItems, knownTitle != null)
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

    /**
     * 일시적 실패는 백오프를 두고 다시 시도한다.
     * 대기 중에는 세마포어를 놓아 다른 작업이 진행할 수 있게 한다.
     */
    private suspend fun runWithRetry(
        taskId: String,
        rawUrl: String,
        quality: Quality,
        playlistItems: String?,
        alreadyProbed: Boolean
    ) {
        val autoRetry = Prefs.get(this).autoRetry
        var attempt = 0

        while (true) {
            val outcome = gate.withPermit {
                runTask(taskId, rawUrl, quality, playlistItems, alreadyProbed)
            }

            when (outcome) {
                is Outcome.Success -> return

                is Outcome.Canceled -> {
                    DownloadRepo.update(taskId) {
                        it.copy(
                            state = TaskState.CANCELED,
                            statusLine = "취소됨",
                            finishedAt = System.currentTimeMillis()
                        )
                    }
                    return
                }

                is Outcome.Failed -> {
                    val canRetry = autoRetry && outcome.transient && attempt < BACKOFF_MS.size
                    if (!canRetry) {
                        fail(taskId, outcome.message, outcome.raw)
                        return
                    }
                    attempt++
                    if (!waitForRetry(taskId, attempt, BACKOFF_MS[attempt - 1], outcome.message)) {
                        // 대기 중 취소됨
                        DownloadRepo.update(taskId) {
                            it.copy(
                                state = TaskState.CANCELED,
                                statusLine = "취소됨",
                                finishedAt = System.currentTimeMillis()
                            )
                        }
                        return
                    }
                }
            }
        }
    }

    /** @return 계속 진행해도 되면 true, 사용자가 취소했으면 false */
    private suspend fun waitForRetry(
        taskId: String,
        attempt: Int,
        totalMs: Long,
        reason: String
    ): Boolean {
        var remaining = totalMs
        while (remaining > 0) {
            if (DownloadRepo.get(taskId)?.state == TaskState.CANCELED) return false

            val seconds = (remaining / 1000).toInt()
            DownloadRepo.update(taskId) {
                it.copy(
                    state = TaskState.RETRY_WAIT,
                    retryCount = attempt,
                    progress = 0f,
                    etaSec = -1,
                    error = reason,
                    statusLine = "재시도 대기 " + attempt + "/" + BACKOFF_MS.size +
                        " · " + seconds + "초 후"
                )
            }
            promote("재시도 대기 중", seconds.toString() + "초 후 다시 시도합니다", 0, true)

            delay(1000)
            remaining -= 1000
        }
        return DownloadRepo.get(taskId)?.state != TaskState.CANCELED
    }

    private suspend fun runTask(
        taskId: String,
        rawUrl: String,
        quality: Quality,
        playlistItems: String?,
        alreadyProbed: Boolean
    ): Outcome {
        val workDir = File(cacheDir, "dl/$taskId")
        workDir.mkdirs()

        try {
            var url = rawUrl

            if (!alreadyProbed) {
                // 1) 링크 정규화 (t.co / instagram.com/share 리다이렉트 해제)
                DownloadRepo.update(taskId) {
                    it.copy(state = TaskState.FETCHING, statusLine = "링크 확인 중…", error = null)
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
                it.copy(
                    state = TaskState.DOWNLOADING,
                    progress = 0f,
                    statusLine = "다운로드 중…",
                    error = null
                )
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
                return Outcome.Failed(
                    message = "받을 수 있는 파일이 없습니다. 비공개 게시물이거나 링크가 잘못됐을 수 있습니다.",
                    raw = buildString {
                        appendLine("yt-dlp 가 파일을 하나도 만들지 않았습니다.")
                        appendLine("url=" + url)
                        appendLine("quality=" + quality.name)
                        appendLine("items=" + (playlistItems ?: "(전체)"))
                        appendLine("마지막 상태: " + (DownloadRepo.get(taskId)?.statusLine ?: ""))
                    },
                    transient = false
                )
            }

            // 4) 갤러리로 이동
            DownloadRepo.update(taskId) {
                it.copy(state = TaskState.SAVING, progress = 1f, statusLine = "갤러리에 저장 중…")
            }
            promote(titleForNoti, "갤러리에 저장 중…", 100, false)

            val uris = MediaImporter.importAll(this, files, DownloadRepo.get(taskId)?.title)

            if (uris.isEmpty()) {
                return Outcome.Failed(
                    message = "갤러리에 저장하지 못했습니다. 저장 공간을 확인해 주세요.",
                    raw = "파일 " + files.size + "개를 MediaStore 로 옮기지 못했습니다.",
                    transient = false
                )
            }

            DownloadRepo.update(taskId) {
                it.copy(
                    state = TaskState.DONE,
                    progress = 1f,
                    savedCount = uris.size,
                    savedUris = uris,
                    statusLine = "${uris.size}개 저장 완료",
                    error = null,
                    rawError = null,
                    finishedAt = System.currentTimeMillis()
                )
            }
            Notifications.showResult(
                context = this,
                key = taskId.hashCode(),
                title = "다운로드 완료",
                text = "${titleForNoti.take(40)} · ${uris.size}개 저장됨\n${MediaImporter.savedLocationText(this)}",
                success = true
            )
            return Outcome.Success
        } catch (t: Throwable) {
            val canceled = t.javaClass.simpleName.contains("Canceled", true) ||
                DownloadRepo.get(taskId)?.state == TaskState.CANCELED
            if (canceled) return Outcome.Canceled

            Log.e(TAG, "download failed", t)
            val raw = rawOf(t)
            return Outcome.Failed(
                message = YtDlp.humanizeError(t.message),
                raw = raw,
                transient = YtDlp.isTransient(raw)
            )
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
                statusLine = "실패",
                finishedAt = System.currentTimeMillis()
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

        // 여러 개가 동시에 돌 때는 개별 제목보다 개수가 더 유용하다
        val active = running.get()
        val shownTitle = if (active > 1) "다운로드 " + active + "개 진행 중" else title

        val notification = Notifications.progress(this, shownTitle, text, percent, indeterminate)
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

        /** 재시도 대기 시간. 길이가 곧 최대 재시도 횟수 */
        private val BACKOFF_MS = longArrayOf(15_000L, 60_000L, 180_000L)

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
