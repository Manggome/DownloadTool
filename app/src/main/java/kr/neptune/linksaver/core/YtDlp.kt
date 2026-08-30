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
import java.net.HttpURLConnection
import java.net.URL

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

    // 링크 하나를 받는 동안 조회가 여러 번 일어난다(화면 -> 서비스 -> 이미지 경로).
    // 인스타는 요청 수에 민감해 뒤쪽 조회가 빈 응답을 받는 일이 잦으므로 짧게 캐시한다.
    private const val PROBE_CACHE_TTL_MS = 5L * 60 * 1000

    @Volatile
    private var probeCache: Pair<String, List<MediaMeta>>? = null

    @Volatile
    private var probeCacheAt = 0L

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
        // --no-warnings 를 켜면 안 된다.
        // 틱톡은 모바일 API 실패를 경고로 처리한 뒤 웹페이지로 넘어가므로,
        // 경고를 숨기면 진짜 실패 사유가 사라지고 폴백의 403 만 보인다.
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
     * 플랫폼별 추출 경로 폴백 목록. 앞에서부터 시도하고 성공하면 멈춘다.
     * 기본 경로가 막히는 게시물이 플랫폼마다 있어서, 엔진이 제공하는
     * 대체 경로를 순서대로 시도한다.
     */
    private fun apiVariants(context: Context, url: String): List<Pair<String, String?>> {
        val prefs = Prefs.get(context)
        val cookies = prefs.cookiesPath
        val hasCookies = cookies != null && File(cookies).exists()

        return when (UrlUtil.platformOf(url)) {
            Platform.TWITTER ->
                if (hasCookies) listOf("기본" to null)
                else listOf(
                    "기본" to null,
                    "syndication" to "twitter:api=syndication",
                    "legacy" to "twitter:api=legacy"
                )

            // 틱톡 기본 경로는 웹페이지를 긁는데 403 을 자주 받는다.
            // device_id 를 주면 모바일 API 경로가 열리고, 지역에 맞는 앱 이름과
            // API 호스트를 지정하면 성공률이 크게 오른다.
            // (추출기 주석: KR/PH/TW/TH/VN = trill, 그 외 = musical_ly)
            Platform.TIKTOK -> {
                val device = prefs.tiktokDeviceId()
                listOf(
                    "기본" to null,
                    "모바일API(trill/싱가포르)" to
                        "tiktok:device_id=" + device +
                        ";api_hostname=api22-normal-c-alisg.tiktokv.com" +
                        ";app_name=trill;aid=1180",
                    "모바일API(musical_ly/미국)" to
                        "tiktok:device_id=" + device +
                        ";api_hostname=api16-normal-c-useast1a.tiktokv.com"
                )
            }

            else -> listOf("기본" to null)
        }
    }

    private fun YoutubeDLRequest.applyApiVariant(args: String?): YoutubeDLRequest {
        if (args != null) addOption("--extractor-args", args)
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
                appendLine("[" + entry.first + "]")
                append(entry.second.trim().take(900))
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

        for ((label, args) in apiVariants(context, url)) {
            val request = YoutubeDLRequest(url).apply {
                applyCommon(context)
                applyApiVariant(args)
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
            if (metas.isNotEmpty()) {
                probeCache = url to metas
                probeCacheAt = System.currentTimeMillis()
                return@withContext metas
            }
        }

        if (failures.isNotEmpty()) throw combineFailures(failures)
        emptyList()
    }

    /** 같은 링크를 짧은 시간 안에 다시 조회할 때 재요청을 피한다 */
    private fun cachedProbe(url: String): List<MediaMeta>? {
        val cache = probeCache ?: return null
        if (cache.first != url) return null
        if (System.currentTimeMillis() - probeCacheAt > PROBE_CACHE_TTL_MS) return null
        return cache.second
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
            thumbnail = bestThumbnail(json),
            ext = ext,
            durationSec = duration,
            width = json.optInt("width", 0).takeIf { it > 0 },
            height = json.optInt("height", 0).takeIf { it > 0 },
            isImage = isImage
        )
    }

    /**
     * 인스타 사진은 단일 thumbnail 필드가 없고 thumbnails 배열에만 들어온다.
     * yt-dlp 는 이 배열을 낮은 화질 -> 높은 화질 순으로 담으므로 마지막이 원본에 가장 가깝다.
     */
    private fun bestThumbnail(json: JSONObject): String? {
        json.optString("thumbnail").takeIf { it.startsWith("http") }?.let { return it }

        val arr = json.optJSONArray("thumbnails") ?: return null
        for (i in arr.length() - 1 downTo 0) {
            val u = arr.optJSONObject(i)?.optString("url").orEmpty()
            if (u.startsWith("http")) return u
        }
        return null
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

        for ((label, args) in apiVariants(context, url)) {
            // 재시도 전에 직전 시도의 잔여물을 지운다
            outDir.listFiles()?.forEach { it.delete() }

            val request = YoutubeDLRequest(url).apply {
                applyCommon(context)
                applyQuality(quality)
                applyApiVariant(args)
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
        val diag = mutableListOf<String>()
        val images = downloadImages(context, url, outDir, playlistItems, diag)
        if (images.isNotEmpty()) return@withContext images

        if (diag.isNotEmpty()) failures += "이미지 경로" to diag.joinToString(separator = " / ")
        if (failures.isNotEmpty()) throw combineFailures(failures)
        emptyList()
    }

    /**
     * 사진 게시물 대응.
     * 추출기가 사진을 formats 에 넣지 않으므로 yt-dlp 로는 받을 수 없다.
     * 대신 조회 결과의 이미지 URL 을 직접 내려받는다.
     */
    private suspend fun downloadImages(
        context: Context,
        url: String,
        outDir: File,
        playlistItems: String?,
        diag: MutableList<String>
    ): List<File> {
        val cached = cachedProbe(url)
        val metas = cached ?: runCatching { probe(context, url, null) }
            .onFailure { diag += "재조회 실패: " + (it.message ?: it.javaClass.simpleName) }
            .getOrDefault(emptyList())

        diag += "항목 " + metas.size + "개 (" + (if (cached != null) "캐시" else "재조회") + ")"

        val wanted = parseItemFilter(playlistItems)
        val targets = metas.filter { meta ->
            meta.isImage &&
                !meta.thumbnail.isNullOrBlank() &&
                (wanted == null || meta.playlistIndex in wanted)
        }

        diag += "이미지 후보 " + targets.size + "개 / 선택 " + (playlistItems ?: "전체")
        if (targets.isEmpty()) {
            val why = metas.joinToString(", ") { m ->
                "#" + m.playlistIndex + (if (m.isImage) " img" else " vid") +
                    (if (m.thumbnail.isNullOrBlank()) " no-url" else " url-ok")
            }
            if (why.isNotBlank()) diag += "항목 상태: " + why
            return emptyList()
        }

        outDir.listFiles()?.forEach { it.delete() }

        var failed = 0
        targets.forEachIndexed { index, meta ->
            val source = meta.thumbnail ?: return@forEachIndexed
            val name = "%03d_%s.%s".format(
                index + 1, meta.id.ifBlank { "image" }, extensionOf(source)
            )
            val code = fetchTo(source, File(outDir, name))
            if (code != 200) {
                failed++
                diag += name + " 실패 (HTTP " + code + ")"
            }
        }
        if (failed > 0) diag += "이미지 " + failed + "/" + targets.size + "개 실패"

        return outDir.listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    /** "1,3,7" 형식을 정수 집합으로. null 이면 전체 */
    private fun parseItemFilter(playlistItems: String?): Set<Int>? {
        if (playlistItems.isNullOrBlank()) return null
        return playlistItems.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
            .takeIf { it.isNotEmpty() }
    }

    private fun extensionOf(source: String): String {
        val path = runCatching { URL(source).path }.getOrDefault("")
        val ext = path.substringAfterLast('.', "").lowercase()
        return if (ext in setOf("jpg", "jpeg", "png", "webp", "heic")) ext else "jpg"
    }

    /**
     * 인스타 CDN 은 Referer 를 확인하므로 함께 보낸다.
     * @return HTTP 상태 코드. 성공은 200, 예외는 -1
     */
    private fun fetchTo(source: String, target: File): Int {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(source).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"
                )
                setRequestProperty("Referer", "https://www.instagram.com/")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "이미지 응답 " + code)
                return code
            }
            conn.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.length() > 0L) 200 else -1
        } catch (t: Throwable) {
            Log.w(TAG, "이미지 내려받기 실패: " + t.message)
            -1
        } finally {
            runCatching { conn?.disconnect() }
        }
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
