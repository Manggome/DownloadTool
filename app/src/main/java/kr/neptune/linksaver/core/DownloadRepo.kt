package kr.neptune.linksaver.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 화면과 서비스가 공유하는 다운로드 목록 (프로세스 수명 동안 메모리에 유지) */
object DownloadRepo {

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    fun add(task: DownloadTask) {
        _tasks.value = listOf(task) + _tasks.value
    }

    fun get(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    fun update(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
    }

    fun remove(id: String) {
        _tasks.value = _tasks.value.filterNot { it.id == id }
    }

    fun clearFinished() {
        _tasks.value = _tasks.value.filterNot { it.isFinished }
    }

    fun hasActive(): Boolean = _tasks.value.any { !it.isFinished }
}
