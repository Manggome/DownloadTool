package kr.neptune.linksaver.core

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 끝난 다운로드 목록을 파일에 남긴다.
 * 앱을 껐다 켜도 무엇을 받았는지 남아 있게 하는 용도이며,
 * 진행 중인 작업은 저장하지 않는다(프로세스가 죽으면 어차피 이어받을 수 없다).
 */
object HistoryStore {

    private const val TAG = "HistoryStore"
    private const val FILE_NAME = "history.json"
    private const val MAX_ENTRIES = 300

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun load(context: Context): List<DownloadTask> {
        val f = file(context)
        if (!f.exists()) return emptyList()

        return runCatching {
            val array = JSONArray(f.readText())
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { fromJson(it) }
            }
        }.onFailure {
            Log.w(TAG, "이력 읽기 실패: " + it.message)
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, tasks: List<DownloadTask>) {
        val finished = tasks.filter { it.isFinished }.take(MAX_ENTRIES)
        runCatching {
            val array = JSONArray()
            finished.forEach { array.put(toJson(it)) }
            file(context).writeText(array.toString())
        }.onFailure {
            Log.w(TAG, "이력 저장 실패: " + it.message)
        }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    // ------------------------------------------------------------------ 변환

    private fun toJson(task: DownloadTask): JSONObject = JSONObject().apply {
        put("id", task.id)
        put("url", task.url)
        put("platform", task.platform.name)
        put("quality", task.quality.name)
        put("playlistItems", task.playlistItems ?: JSONObject.NULL)
        put("expectedCount", task.expectedCount)
        put("title", task.title)
        put("uploader", task.uploader ?: JSONObject.NULL)
        put("thumbnail", task.thumbnail ?: JSONObject.NULL)
        put("state", task.state.name)
        put("statusLine", task.statusLine)
        put("error", task.error ?: JSONObject.NULL)
        put("savedCount", task.savedCount)
        put("finishedAt", task.finishedAt)
        put("savedUris", JSONArray().apply {
            task.savedUris.forEach { put(it.toString()) }
        })
    }

    private fun fromJson(json: JSONObject): DownloadTask? = runCatching {
        val uris = json.optJSONArray("savedUris")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }?.let(Uri::parse)
            }
        }.orEmpty()

        DownloadTask(
            id = json.optString("id"),
            url = json.optString("url"),
            platform = enumOrDefault(json.optString("platform"), Platform.OTHER),
            quality = enumOrDefault(json.optString("quality"), Quality.BEST),
            playlistItems = json.optString("playlistItems").ifBlank { null },
            expectedCount = json.optInt("expectedCount", 0),
            title = json.optString("title").ifBlank { "제목 없음" },
            uploader = json.optString("uploader").ifBlank { null },
            thumbnail = json.optString("thumbnail").ifBlank { null },
            state = enumOrDefault(json.optString("state"), TaskState.DONE),
            statusLine = json.optString("statusLine"),
            error = json.optString("error").ifBlank { null },
            savedCount = json.optInt("savedCount", 0),
            savedUris = uris,
            finishedAt = json.optLong("finishedAt", 0L)
        )
    }.getOrNull()

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
}
