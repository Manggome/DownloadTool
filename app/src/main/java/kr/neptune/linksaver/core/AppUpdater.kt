package kr.neptune.linksaver.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kr.neptune.linksaver.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 를 보고 새 버전이 있으면 받아서 설치까지 이어준다.
 *
 * CI 가 릴리스에 latest.json 을 함께 올리고, 앱은 그 versionCode 를
 * 자신의 것과 비교한다. 서명 키가 고정돼 있으므로 덮어쓰기 설치가 된다.
 */
object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val REPO = "Manggome/DownloadTool"
    private const val BASE = "https://github.com/$REPO/releases/latest/download"

    private const val MANIFEST_URL = "$BASE/latest.json"

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val notes: String
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val percent: Int) : State
        data class ReadyToInstall(val file: File) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val currentVersionName: String get() = BuildConfig.VERSION_NAME
    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE

    /** 기기 ABI 에 맞는 APK 파일명. 릴리스에 올라가는 이름과 맞춰야 한다 */
    private fun apkAssetName(): String {
        val abis = Build.SUPPORTED_ABIS.orEmpty()
        return when {
            abis.contains("arm64-v8a") -> "LinkSaver.apk"
            abis.contains("armeabi-v7a") -> "LinkSaver-armeabi-v7a.apk"
            abis.contains("x86_64") -> "LinkSaver-x86_64.apk"
            else -> "LinkSaver.apk"
        }
    }

    /**
     * 새 버전이 있는지 확인한다.
     * @param silent 조용히 확인할 때는 실패나 "최신" 상태를 표시하지 않는다 (앱 시작 시 사용)
     */
    suspend fun check(silent: Boolean = false): State = withContext(Dispatchers.IO) {
        if (!silent) _state.value = State.Checking

        val result = try {
            val body = fetchText(MANIFEST_URL)
            val json = JSONObject(body)
            val release = Release(
                versionCode = json.optInt("versionCode", 0),
                versionName = json.optString("versionName", "?"),
                notes = json.optString("notes", "")
            )
            if (release.versionCode > currentVersionCode) {
                State.Available(release)
            } else {
                State.UpToDate
            }
        } catch (t: Throwable) {
            Log.w(TAG, "업데이트 확인 실패: " + t.message)
            State.Failed(t.message ?: "확인 실패")
        }

        // 조용한 확인에서는 새 버전이 있을 때만 상태를 바꾼다
        if (!silent || result is State.Available) _state.value = result
        result
    }

    /** APK 를 캐시에 받는다. 성공하면 [State.ReadyToInstall] */
    suspend fun download(context: Context): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply {
            deleteRecursively()
            mkdirs()
        }
        val target = File(dir, "LinkSaver-update.apk")

        var conn: HttpURLConnection? = null
        try {
            _state.value = State.Downloading(0)
            conn = open("$BASE/${apkAssetName()}")
            if (conn.responseCode !in 200..299) {
                _state.value = State.Failed("APK 응답 " + conn.responseCode)
                return@withContext null
            }

            val total = conn.contentLengthLong
            var written = 0L
            var lastPercent = -1

            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = (written * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _state.value = State.Downloading(percent)
                            }
                        }
                    }
                }
            }

            if (target.length() <= 0L) {
                _state.value = State.Failed("받은 파일이 비어 있습니다")
                return@withContext null
            }

            _state.value = State.ReadyToInstall(target)
            target
        } catch (t: Throwable) {
            Log.w(TAG, "APK 받기 실패: " + t.message)
            _state.value = State.Failed(t.message ?: "받기 실패")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 시스템 설치 화면을 띄운다 */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun dismiss() {
        _state.value = State.Idle
    }

    // ------------------------------------------------------------------ HTTP

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LinkSaver/" + currentVersionName)
        }

    private fun fetchText(url: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = open(url)
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP " + conn.responseCode)
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
