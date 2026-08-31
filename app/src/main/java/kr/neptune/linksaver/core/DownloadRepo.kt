package kr.neptune.linksaver.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 화면과 서비스가 공유하는 다운로드 목록.
 * 끝난 작업은 파일에 남겨 앱을 다시 켜도 보이게 한다.
 */
object DownloadRepo {

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    /** 앱 시작 시 한 번 호출. 저장된 이력을 불러온다 */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        _tasks.value = HistoryStore.load(context)
            .sortedByDescending { it.finishedAt }
    }

    fun add(task: DownloadTask) {
        _tasks.value = listOf(task) + _tasks.value
    }

    fun get(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    fun update(id: String, transform: (DownloadTask) -> DownloadTask) {
        var becameFinished = false
        _tasks.value = _tasks.value.map { task ->
            if (task.id != id) return@map task
            val updated = transform(task)
            if (!task.isFinished && updated.isFinished) becameFinished = true
            updated
        }
        // 진행률은 초당 수십 번 바뀌므로, 끝나는 순간에만 기록한다
        if (becameFinished) persist()
    }

    fun remove(id: String) {
        _tasks.value = _tasks.value.filterNot { it.id == id }
        persist()
    }

    fun clearFinished() {
        _tasks.value = _tasks.value.filterNot { it.isFinished }
        persist()
    }

    fun hasActive(): Boolean = _tasks.value.any { !it.isFinished }

    private fun persist() {
        val context = appContext ?: return
        HistoryStore.save(context, _tasks.value)
    }
}
