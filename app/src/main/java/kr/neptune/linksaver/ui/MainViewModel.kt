package kr.neptune.linksaver.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.neptune.linksaver.core.AppUpdater
import kr.neptune.linksaver.core.CookieExport
import kr.neptune.linksaver.core.DownloadRepo
import kr.neptune.linksaver.core.DownloadService
import kr.neptune.linksaver.core.MediaMeta
import kr.neptune.linksaver.core.Prefs
import kr.neptune.linksaver.core.Quality
import kr.neptune.linksaver.core.UrlUtil
import kr.neptune.linksaver.core.YtDlp
import java.io.File
import java.util.UUID

/** 게시물에 미디어가 여러 개일 때 무엇을 받을지 고르는 단계 */
sealed interface PickerState {
    data object Hidden : PickerState

    /** 목록 조회 중 */
    data object Probing : PickerState

    /** 조회 완료 — 사용자가 고를 차례 */
    data class Choosing(
        val url: String,
        val items: List<MediaMeta>,
        /** 선택된 playlistIndex 집합 */
        val selected: Set<Int>
    ) : PickerState {
        val allSelected: Boolean get() = selected.size == items.size
        val canConfirm: Boolean get() = selected.isNotEmpty()
    }
}

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs.get(app)

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    private val _quality = MutableStateFlow(prefs.lastQuality)
    val quality: StateFlow<Quality> = _quality.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _picker = MutableStateFlow<PickerState>(PickerState.Hidden)
    val picker: StateFlow<PickerState> = _picker.asStateFlow()

    private val _ytdlpVersion = MutableStateFlow<String?>(null)
    val ytdlpVersion: StateFlow<String?> = _ytdlpVersion.asStateFlow()

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()

    private val _cookiesName = MutableStateFlow(cookiesLabel())
    val cookiesName: StateFlow<String?> = _cookiesName.asStateFlow()

    /** 로그인이 저장된 사이트 key 집합 */
    private val _loggedInSites = MutableStateFlow(currentLoggedInSites())
    val loggedInSites: StateFlow<Set<String>> = _loggedInSites.asStateFlow()

    val autoPaste: Boolean get() = prefs.autoPasteFromClipboard

    private var probeJob: Job? = null
    private var probeProcessId: String? = null

    /** 앱 자체 업데이트 상태 */
    val updateState: StateFlow<AppUpdater.State> = AppUpdater.state

    val appVersionName: String get() = AppUpdater.currentVersionName

    init {
        viewModelScope.launch {
            YtDlp.ensureInit(app)
            _ytdlpVersion.value = YtDlp.version(app)
        }
        // 새 버전이 있을 때만 조용히 알린다 (실패나 "최신" 상태는 표시하지 않음)
        viewModelScope.launch { AppUpdater.check(silent = true) }
    }

    fun checkAppUpdate() {
        viewModelScope.launch { AppUpdater.check() }
    }

    fun downloadAppUpdate() {
        viewModelScope.launch { AppUpdater.download(app) }
    }

    fun installAppUpdate(context: Context, apk: File) {
        runCatching { AppUpdater.install(context, apk) }
            .onFailure {
                _toast.value = "설치 화면을 열 수 없습니다. 이 앱에 앱 설치 권한을 허용해 주세요."
            }
    }

    fun dismissAppUpdate() = AppUpdater.dismiss()

    fun setUrl(value: String) {
        _url.value = value
    }

    fun setQuality(value: Quality) {
        _quality.value = value
        prefs.lastQuality = value
    }

    fun consumeToast() {
        _toast.value = null
    }

    fun showToast(message: String) {
        _toast.value = message
    }

    /** 공유 인텐트 / 링크 열기로 들어온 텍스트 처리 */
    fun onIncomingText(text: String?, autoStart: Boolean) {
        val found = UrlUtil.extractFirstUrl(text) ?: return
        _url.value = found
        if (autoStart && UrlUtil.isSupported(found)) {
            submit()
        }
    }

    fun pasteFromClipboard(context: Context, silent: Boolean = false) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()

        val found = UrlUtil.extractFirstUrl(text)
        when {
            found == null -> if (!silent) _toast.value = "클립보드에 링크가 없습니다"
            silent && !UrlUtil.isSupported(found) -> Unit
            silent && _url.value.isNotBlank() -> Unit
            else -> _url.value = found
        }
    }

    // ------------------------------------------------------------ 조회 → 선택 → 다운로드

    fun submit() {
        if (_picker.value is PickerState.Probing) return

        val raw = _url.value.trim()
        val found = UrlUtil.extractFirstUrl(raw) ?: raw

        if (found.isBlank()) {
            _toast.value = "링크를 입력해 주세요"
            return
        }
        if (!found.startsWith("http")) {
            _toast.value = "http 로 시작하는 링크를 넣어 주세요"
            return
        }
        val processId = "probe-${UUID.randomUUID()}"
        probeProcessId = processId

        probeJob = viewModelScope.launch {
            _picker.value = PickerState.Probing

            val canonical = withContext(Dispatchers.IO) {
                runCatching { UrlUtil.canonicalize(found) }.getOrDefault(found)
            }
            val items = runCatching { YtDlp.probe(app, canonical, processId) }
                .getOrElse { emptyList() }

            probeProcessId = null

            when {
                // 조회 실패 — 그래도 다운로드는 시도해 본다 (서비스가 다시 조회)
                items.isEmpty() -> {
                    _picker.value = PickerState.Hidden
                    DownloadService.start(app, canonical, _quality.value)
                    _url.value = ""
                    _toast.value = "다운로드를 시작했습니다"
                }

                // 1개뿐이면 고를 것이 없다
                items.size == 1 -> {
                    _picker.value = PickerState.Hidden
                    startDownload(canonical, items, items.map { it.playlistIndex }.toSet())
                }

                // 여러 개 — 사용자가 고른다 (기본은 전체 선택)
                else -> {
                    _picker.value = PickerState.Choosing(
                        url = canonical,
                        items = items,
                        selected = items.map { it.playlistIndex }.toSet()
                    )
                }
            }
        }
    }

    fun cancelProbe() {
        probeProcessId?.let { YtDlp.cancel(it) }
        probeProcessId = null
        probeJob?.cancel()
        probeJob = null
        _picker.value = PickerState.Hidden
    }

    fun toggleSelection(playlistIndex: Int) {
        val current = _picker.value as? PickerState.Choosing ?: return
        val next = current.selected.toMutableSet().apply {
            if (!add(playlistIndex)) remove(playlistIndex)
        }
        _picker.value = current.copy(selected = next)
    }

    fun selectAll() {
        val current = _picker.value as? PickerState.Choosing ?: return
        _picker.value = current.copy(selected = current.items.map { it.playlistIndex }.toSet())
    }

    fun clearSelection() {
        val current = _picker.value as? PickerState.Choosing ?: return
        _picker.value = current.copy(selected = emptySet())
    }

    fun confirmSelection() {
        val current = _picker.value as? PickerState.Choosing ?: return
        if (current.selected.isEmpty()) {
            _toast.value = "받을 항목을 하나 이상 선택해 주세요"
            return
        }
        _picker.value = PickerState.Hidden
        startDownload(current.url, current.items, current.selected)
    }

    fun dismissPicker() {
        _picker.value = PickerState.Hidden
    }

    private fun startDownload(url: String, items: List<MediaMeta>, selected: Set<Int>) {
        val head = items.firstOrNull()
        // 전부 고른 경우엔 --playlist-items 를 붙이지 않는다 (yt-dlp 기본 동작이 더 안전)
        val playlistItems =
            if (items.size > 1 && selected.size < items.size) selected.sorted().joinToString(",")
            else null

        DownloadService.start(
            context = app,
            url = url,
            quality = _quality.value,
            playlistItems = playlistItems,
            expectedCount = selected.size,
            knownTitle = head?.title,
            knownUploader = head?.uploader,
            knownThumbnail = items.firstOrNull { it.playlistIndex in selected }?.thumbnail
                ?: head?.thumbnail
        )
        _url.value = ""
        _toast.value = "${selected.size}개 다운로드를 시작했습니다"
    }

    fun retry(url: String, quality: Quality, playlistItems: String?) {
        DownloadService.start(app, url, quality, playlistItems = playlistItems)
    }

    fun cancel(taskId: String) {
        DownloadService.cancel(app, taskId)
    }

    fun removeTask(taskId: String) = DownloadRepo.remove(taskId)

    fun clearFinished() = DownloadRepo.clearFinished()

    // -------------------------------------------------------------- 설정

    fun updateYtDlp() {
        if (_updating.value) return
        viewModelScope.launch {
            _updating.value = true
            val result = YtDlp.update(app)
            _ytdlpVersion.value = YtDlp.version(app)
            _updating.value = false
            _toast.value = result
        }
    }

    private fun currentLoggedInSites(): Set<String> =
        CookieExport.ALL.filter { CookieExport.isSaved(app, it) }.map { it.key }.toSet()

    /** 로그인 화면에서 돌아왔을 때 호출 */
    fun refreshLoginState() {
        _loggedInSites.value = currentLoggedInSites()
        _cookiesName.value = cookiesLabel()
    }

    fun logout(site: CookieExport.Site) {
        CookieExport.clear(app, site)
        refreshLoginState()
        _toast.value = site.label + " 로그아웃했습니다"
    }

    fun setAutoPaste(enabled: Boolean) {
        prefs.autoPasteFromClipboard = enabled
    }

    val instagramAnonymousFirst: Boolean get() = prefs.instagramAnonymousFirst

    val albumName: String get() = prefs.albumName

    val maxConcurrent: Int get() = prefs.maxConcurrentDownloads

    fun setMaxConcurrent(value: Int) {
        prefs.maxConcurrentDownloads = value
        _toast.value = "동시 다운로드를 " + prefs.maxConcurrentDownloads + "개로 바꿨습니다"
    }

    val autoRetry: Boolean get() = prefs.autoRetry

    fun setAutoRetry(enabled: Boolean) {
        prefs.autoRetry = enabled
    }

    fun setAlbumName(value: String) {
        prefs.albumName = value
        _toast.value = "저장 폴더를 " + prefs.albumName + " 로 바꿨습니다"
    }

    fun setInstagramAnonymousFirst(enabled: Boolean) {
        prefs.instagramAnonymousFirst = enabled
    }

    /** 사용자가 고른 cookies.txt 를 앱 내부로 복사해 둔다 */
    fun importCookies(context: Context, uri: android.net.Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(context.filesDir, "cookies.txt")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching false
                    prefs.cookiesPath = target.absolutePath
                    true
                }.getOrDefault(false)
            }
            _cookiesName.value = cookiesLabel()
            _toast.value = if (ok) "cookies.txt 를 등록했습니다" else "cookies.txt 등록에 실패했습니다"
        }
    }

    fun clearCookies() {
        prefs.cookiesPath?.let { runCatching { File(it).delete() } }
        prefs.cookiesPath = null
        _cookiesName.value = null
        _toast.value = "cookies.txt 를 해제했습니다"
    }

    private fun cookiesLabel(): String? {
        val path = prefs.cookiesPath ?: return null
        val file = File(path)
        return if (file.exists()) "등록됨 (${file.length() / 1024}KB)" else null
    }
}
