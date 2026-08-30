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

        /** python / yt-dlp / ffmpeg 압축 해제 중 */
        data object Loading : InitState

        /** 번들된 yt-dlp 가 오래됐을 때 최신판을 받는 중 */
        data object Updating : InitState

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
                } catch (t: Throwable) {
                    Log.e(TAG, "init failed", t)
                    _initState.value = InitState.Failed(t.message ?: "알 수 없는 오류")
                    return@withContext
                }

                // 앱에 번들된 yt-dlp 는 라이브러리 배포 시점 것이라 몇 달씩 뒤처진다.
                // 인스타/X 는 내부 구조를 자주 바꾸므로, 최초 실행 시(및 주기적으로)
                // 최신판을 받아둬야 갓 설치한 앱이 곧바로 실패하는 일을 막을 수 있다.
                autoUpdateIfStale(context)
                _initState.value = InitState.Ready
            }
        }
    }

    fun isReady(): Boolean = initialized

    /**
     * 번들 yt-dlp 가 오래됐으면 조용히 최신판을 받아둔다.
     * 네트워크가 없거나 실패해도 앱은 그대로 진행한다 (번들 버전으로 동작).
     */
    private fun autoUpdateIfStale(context: Context) {
        val prefs = Prefs.get(context)
        if (!prefs.autoUpdateEngine) return

        val now = System.currentTimeMillis()
        val last = prefs.engineUpdatedAt
        val stale = last == 0L || now - last > Prefs.ENGINE_UPDATE_INTERVAL_MS
        if (!stale) return

        // 오프라인 등으로 계속 실패할 때 앱 시작이 매번 느려지지 않도록 시도 간격을 둔다
        if (now - prefs.engineUpdateAttemptAt < Prefs.ENGINE_RETRY_INTERVAL_MS) return
        prefs.engineUpdateAttemptAt = now

        _initState.value = InitState.Updating
        try {
            YoutubeDL.getInstance()
                .updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            prefs.engineUpdatedAt = System.currentTimeMillis()
            Log.i(TAG, "engine auto-updated to ${YoutubeDL.getInstance().version(context)}")
        } catch (t: Throwable) {
            // 실패해도 치명적이지 않다. 다음 실행 때 다시 시도한다.
            Log.w(TAG, "engine auto-update failed: ${t.message}")
        }
    }

    suspend fun version(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching { YoutubeDL.getInstance().version(context.applicationContext) }.getOrNull()
    }

    /** yt-dlp 본체만 최신으로 갱신 (앱 재설치 불필요) */
    suspend fun update(context: Context): String = withContext(Dispatchers.IO) {
        ensureInit(context)
        try {
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            Prefs.get(context).engineUpdatedAt = System.currentTimeMillis()
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

    /**
     * X 는 기본 graphql 경로로 막히는 게시물이 있다(민감 콘텐츠, 게스트 토큰 제한 등).
     * yt-dlp 가 legacy / syndication 경로를 제공하므로 순서대로 재시도한다.
     * 로그인(쿠키) 상태면 효과가 없으므로 그때는 한 번만 시도한다.
     */
    private fun apiVariants(context: Context, url: String): List<String?> {
        val cookies = Prefs.get(context).cookiesPath
        val hasCookies = cookies != null && File(cookies).exists()
        return if (!hasCookies && UrlUtil.platformOf(url) == Platform.TWITTER) {
            listOf(null, "syndication", "legacy")
        } else {
            listOf(null)
        }
    }

    private fun YoutubeDLRequest.applyApiVariant(api: String?): YoutubeDLRequest {
        if (api != null) addOption("--extractor-args", "twitter:api=" + api)
        return this
    }

    /** 사용자가 취소한 경우는 재시도하지 않고 그대로 올려보낸다 */
    private fun isCancellation(t: Throwable): Boolean =
        t is InterruptedException || t.javaClass.simpleName.contains("Canceled", true)

    /** 모든 추출 경로가 실패했을 때, 경로별 원인을 한데 모아 올려보낸다 */
    class AllAttemptsFailedException(message: String) : Exception(message)

    /**
     * 폴백 루프에서 마지막 오류만 남기면, 사실상 폐기된 legacy 경로의 404 가
     * 진짜 원인(graphql 쪽 메시지)을 덮어버린다. 그래서 전부 합쳐서 보여준다.
     */
    private fun combineFailures(failures: List<Pair<String, String>>): Exception =
        AllAttemptsFailedException(buildString {
            appendLine("추출 경로 " + failures.size + "개를 모두 시도했지만 실패했습니다.")
            failures.forEach { entry ->
                appendLine()
                appendLine("[api=" + entry.first + "]")
                append(entry.second.trim())
            }
        })

    private fun parseAll(out: String): List<MediaMeta> =
        out.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") && it.endsWith("}") }
            .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
            .mapIndexed { position, json -> parseMeta(json, position) }
            .toList()

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
        val failures = mutableListOf<Pair<String, String>>()

        for (api in apiVariants(context, url)) {
            val label = api ?: "graphql"
            val request = YoutubeDLRequest(url).apply {
                applyCommon(context)
                applyApiVariant(api)
                addOption("--dump-json")
                addOption("--ignore-errors")
                // 인스타 사진은 formats 가 비어 있어 기본값으로는 항목 자체가 사라진다.
                // 이 옵션이 있어야 사진도 선택 목록에 나온다.
                addOption("--ignore-no-formats-error")
            }
            val metas = try {
                parseAll(YoutubeDL.getInstance().execute(request, processId, null).out)
            } catch (t: Throwable) {
                if (isCancellation(t)) throw t
                Log.w(TAG, "probe 실패 (api=" + label + "): " + t.message)
                failures += label to (t.message ?: t.javaClass.simpleName)
                emptyList()
            }
            if (metas.isNotEmpty()) return@withContext metas
        }

        if (failures.isNotEmpty()) throw combineFailures(failures)
        emptyList()
    }

    private fun parseMeta(json: JSONObject, position: Int): MediaMeta {
        val ext = json.optString("ext").ifBlank { null }
        val duration = json.optDouble("duration").let { if (it.isNaN() || it <= 0.0) null else it }
        val formatCount = json.optJSONArray("formats")?.length() ?: 0
        val isImage = (ext?.lowercase() ?: "") in setOf("jpg", "jpeg", "png", "webp", "heic") ||
            (duration == null && formatCount == 0)

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
        val failures = mutableListOf<Pair<String, String>>()

        for (api in apiVariants(context, url)) {
            val label = api ?: "graphql"
            // 재시도 전에 직전 시도의 잔여물을 지운다
            outDir.listFiles()?.forEach { it.delete() }

            val request = YoutubeDLRequest(url).apply {
                applyCommon(context)
                applyQuality(quality)
                applyApiVariant(api)
                addOption("-o", template)
                if (!playlistItems.isNullOrBlank()) {
                    addOption("--playlist-items", playlistItems)
                }
                addOption("--newline")
                addOption("--no-part")
                addOption("--ignore-errors")
            }

            try {
                YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
                    onProgress(progress, eta, line)
                }
            } catch (t: Throwable) {
                if (isCancellation(t)) throw t
                Log.w(TAG, "download 실패 (api=" + label + "): " + t.message)
                failures += label to (t.message ?: t.javaClass.simpleName)
            }

            val files = outDir.listFiles()
                ?.filter { it.isFile && it.length() > 0L && !it.name.endsWith(".part") }
                ?.sortedBy { it.name }
                .orEmpty()
            if (files.isNotEmpty()) return@withContext files
        }

        // 인스타 사진 게시물 대응.
        // 추출기가 사진을 formats 가 아니라 thumbnails 에만 넣기 때문에
        // 일반 경로로는 "No video formats found" 로 끝난다.
        // 이 경우 썸네일(=원본 이미지)을 직접 파일로 저장한다.
        val images = downloadImages(context, url, outDir, processId, playlistItems, template)
        if (images.isNotEmpty()) return@withContext images

        if (failures.isNotEmpty()) throw combineFailures(failures)
        emptyList()
    }

    /** formats 가 없는 항목(사진)을 썸네일 경로로 저장한다 */
    private fun downloadImages(
        context: Context,
        url: String,
        outDir: File,
        processId: String,
        playlistItems: String?,
        template: String
    ): List<File> {
        outDir.listFiles()?.forEach { it.delete() }

        val request = YoutubeDLRequest(url).apply {
            applyCommon(context)
            addOption("-o", template)
            if (!playlistItems.isNullOrBlank()) {
                addOption("--playlist-items", playlistItems)
            }
            addOption("--ignore-no-formats-error")
            addOption("--write-thumbnail")
            addOption("--skip-download")
            addOption("--newline")
            addOption("--ignore-errors")
        }

        try {
            YoutubeDL.getInstance().execute(request, processId, null)
        } catch (t: Throwable) {
            if (isCancellation(t)) throw t
            Log.w(TAG, "이미지 저장 실패: " + t.message)
        }

        return outDir.listFiles()
            ?.filter { it.isFile && it.length() > 0L && !it.name.endsWith(".part") }
            ?.sortedBy { it.name }
            .orEmpty()
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
