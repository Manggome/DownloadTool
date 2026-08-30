package kr.neptune.linksaver.core

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * yt-dlp(파이썬 번들) 래퍼.
 * - init: 앱 첫 실행 시 python / yt-dlp / ffmpeg 를 내부 저장소에 풀어놓음 (수 초 소요)
 * - probe: --dump-json 으로 메타데이터만 조회
 * - download: 실제 다운로드. 앱 캐시 디렉터리에 받은 뒤 MediaStore 로 옮긴다.
 */
object YtDlp {

    private const val TAG = "YtDlp"

    sealed interface InitState {
        data object Idle : InitState
        data object Loading : InitState
        data object Ready : InitState
        data class Failed(val message: String) : InitState
    }

    private val _initState = MutableStateFlow<InitState>(InitState.Idle)
    val initState: StateFlow<InitState> = _initState.asStateFlow()

    private val initMutex = Mutex()

    @Volatile
    private var initialized = false

    suspend fun ensureInit(context: Context) {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            _initState.value = InitState.Loading
            withContext(Dispatchers.IO) {
                try {
                    YoutubeDL.getInstance().init(context.applicationContext)
                    FFmpeg.getInstance().init(context.applicationContext)
                    initialized = true
                    _initState.value = InitState.Ready
                } catch (t: Throwable) {
                    Log.e(TAG, "init failed", t)
                    _initState.value = InitState.Failed(t.message ?: "알 수 없는 오류")
                }
            }
        }
    }

    fun isReady(): Boolean = initialized

    suspend fun version(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching { YoutubeDL.getInstance().version(context.applicationContext) }.getOrNull()
    }

    /** yt-dlp 본체만 최신으로 갱신 (앱 재설치 불필요) */
    suspend fun update(context: Context): String = withContext(Dispatchers.IO) {
        ensureInit(context)
        try {
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            val v = version(context) ?: "?"
            when (status?.name) {
                "DONE" -> "업데이트 완료 ($v)"
                "ALREADY_UP_TO_DATE" -> "이미 최신입니다 ($v)"
                else -> "업데이트 상태: ${status?.name ?: "알 수 없음"}"
            }
        } catch (t: Throwable) {
            "업데이트 실패: ${t.message}"
        }
    }

    // ---------------------------------------------------------------- 공통 옵션

    private fun YoutubeDLRequest.applyCommon(context: Context): YoutubeDLRequest {
        addOption("--no-warnings")
        addOption("--no-mtime")
        addOption("--socket-timeout", "30")
        addOption("--retries", "3")

        // 사용자가 직접 내보낸 cookies.txt 가 있으면 사용 (인스타 성공률 상승)
        val path = Prefs.get(context).cookiesPath
        if (path != null) {
            val f = File(path)
            if (f.exists() && f.canRead()) addOption("--cookies", f.absolutePath)
        }
        return this
    }

    private fun YoutubeDLRequest.applyQuality(quality: Quality): YoutubeDLRequest {
        when (quality) {
            Quality.BEST -> {
                addOption("-f", "bv*+ba/b")
                addOption("--merge-output-format", "mp4")
            }

            Quality.HD720 -> {
                addOption("-f", "bv*[height<=?720]+ba/b[height<=?720]/b")
                addOption("--merge-output-format", "mp4")
            }

            Quality.AUDIO -> {
                addOption("-f", "ba/b")
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")
            }
        }
        return this
    }

    // ---------------------------------------------------------------- 메타데이터

    /**
     * 링크의 미디어 목록을 조회한다.
     * 인스타 캐러셀(여러 장)이면 항목 수만큼 여러 줄의 JSON 이 나온다.
     */
    suspend fun probe(
        context: Context,
        url: String,
        processId: String? = null
    ): List<MediaMeta> = withContext(Dispatchers.IO) {
        ensureInit(context)
        val request = YoutubeDLRequest(url).apply {
            applyCommon(context)
            addOption("--dump-json")
            addOption("--ignore-errors")
        }
        val response = YoutubeDL.getInstance().execute(request, processId, null)
        response.out
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") && it.endsWith("}") }
            .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
            .mapIndexed { position, json -> parseMeta(json, position) }
            .toList()
    }

    private fun parseMeta(json: JSONObject, position: Int): MediaMeta {
        val ext = json.optString("ext").ifBlank { null }
        val duration = json.optDouble("duration").let { if (it.isNaN() || it <= 0.0) null else it }
        val isImage = (ext?.lowercase() ?: "") in setOf("jpg", "jpeg", "png", "webp", "heic")

        val title = json.optString("title").ifBlank {
            json.optString("description").take(60).ifBlank { "제목 없음" }
        }
        val uploader = json.optString("uploader").ifBlank {
            json.optString("channel").ifBlank { json.optString("uploader_id").ifBlank { null } }
        }

        return MediaMeta(
            // 캐러셀이면 yt-dlp 가 playlist_index 를 준다. 없으면 출력 순서를 쓴다.
            playlistIndex = json.optInt("playlist_index", 0).takeIf { it > 0 } ?: (position + 1),
            id = json.optString("id"),
            title = title,
            uploader = uploader,
            thumbnail = json.optString("thumbnail").ifBlank { null },
            ext = ext,
            durationSec = duration,
            width = json.optInt("width", 0).takeIf { it > 0 },
            height = json.optInt("height", 0).takeIf { it > 0 },
            isImage = isImage
        )
    }

    // ---------------------------------------------------------------- 다운로드

    /**
     * [outDir] 에 원본 파일들을 내려받는다. 캐러셀/스레드면 여러 파일이 생긴다.
     * @return 생성된 파일 목록
     */
    suspend fun download(
        context: Context,
        url: String,
        quality: Quality,
        outDir: File,
        processId: String,
        /** "1,3,7" 처럼 받을 항목만 지정. null 이면 게시물 전체 */
        playlistItems: String? = null,
        onProgress: (progress: Float, etaSec: Long, line: String) -> Unit
    ): List<File> = withContext(Dispatchers.IO) {
        ensureInit(context)
        outDir.mkdirs()

        val template = File(outDir, "%(autonumber)03d_%(id)s.%(ext)s").absolutePath
        val request = YoutubeDLRequest(url).apply {
            applyCommon(context)
            applyQuality(quality)
            addOption("-o", template)
            if (!playlistItems.isNullOrBlank()) {
                addOption("--playlist-items", playlistItems)
            }
            addOption("--newline")
            addOption("--no-part")
            addOption("--ignore-errors")
        }

        YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
            onProgress(progress, eta, line)
        }

        outDir.listFiles()
            ?.filter { it.isFile && it.length() > 0L && !it.name.endsWith(".part") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun cancel(processId: String): Boolean =
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }.getOrDefault(false)

    /** yt-dlp 의 장황한 stderr 를 사람이 읽을 수 있는 한국어로 바꿔준다 */
    fun humanizeError(raw: String?): String {
        val msg = raw.orEmpty()
        return when {
            msg.contains("login required", true) ||
                msg.contains("requested content is not available", true) ||
                msg.contains("rate-limit", true) ->
                "인스타그램이 로그인을 요구했습니다. 잠시 후 다시 시도하거나, 설정에서 cookies.txt 를 등록해 보세요."

            msg.contains("private", true) ->
                "비공개 계정이거나 삭제된 게시물입니다."

            msg.contains("Unsupported URL", true) ->
                "지원하지 않는 링크입니다. 게시물 / 릴스 / 트윗 주소가 맞는지 확인해 주세요."

            msg.contains("No video formats", true) || msg.contains("No media found", true) ->
                "이 게시물에서 받을 수 있는 미디어를 찾지 못했습니다."

            msg.contains("HTTP Error 4", true) || msg.contains("Unable to download", true) ->
                "서버가 요청을 거부했습니다. 잠시 후 다시 시도해 주세요."

            msg.isBlank() -> "알 수 없는 오류가 발생했습니다."

            else -> msg.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .lastOrNull()
                ?.take(200)
                ?: "알 수 없는 오류가 발생했습니다."
        }
    }
}
