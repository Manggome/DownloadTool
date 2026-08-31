package kr.neptune.linksaver.core

import android.net.Uri

enum class Platform(val label: String, val short: String) {
    INSTAGRAM("Instagram", "IG"),
    TWITTER("X (Twitter)", "X"),
    TIKTOK("TikTok", "TT"),
    YOUTUBE("YouTube", "YT"),
    OTHER("기타", "WEB")
}

/** 엔진이 알려준 실제 포맷 하나 */
data class MediaFormat(
    val formatId: String,
    val ext: String?,
    val width: Int?,
    val height: Int?,
    val filesizeBytes: Long?,
    val tbrKbps: Double?,
    val vcodec: String?,
    val acodec: String?,
    val note: String?
) {
    val hasVideo: Boolean get() = vcodec != null && vcodec != "none"
    val hasAudio: Boolean get() = acodec != null && acodec != "none"

    /** 목록에 보여줄 한 줄 */
    val label: String
        get() = buildString {
            when {
                hasVideo && height != null -> append(height).append("p")
                hasVideo -> append("영상")
                hasAudio -> append("오디오")
                else -> append(formatId)
            }
            ext?.let { append(" · ").append(it) }
            if (hasVideo && !hasAudio) append(" · 영상만")
            tbrKbps?.let { append(" · ").append(it.toInt()).append("kbps") }
            filesizeBytes?.let { append(" · ").append(it / 1024 / 1024).append("MB") }
            note?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
}

/** 화질/형식 프리셋 */
enum class Quality(val label: String, val short: String) {
    BEST("최고 화질", "최고"),
    HD720("일반 (720p)", "720p"),
    AUDIO("오디오만 (MP3)", "MP3"),

    /** 조회 후 실제 포맷 목록에서 직접 고른다 */
    CUSTOM("직접 선택", "직접")
}

/** yt-dlp --dump-json 한 줄에서 뽑아낸 미리보기 정보 */
data class MediaMeta(
    /** 게시물 안에서의 순번(1부터). --playlist-items 에 그대로 넘긴다 */
    val playlistIndex: Int,
    val id: String,
    val title: String,
    val uploader: String?,
    val thumbnail: String?,
    val ext: String?,
    val durationSec: Double?,
    val width: Int?,
    val height: Int?,
    val isImage: Boolean,
    /** 고를 수 있는 실제 포맷 목록 (좋은 것부터) */
    val formats: List<MediaFormat> = emptyList()
) {
    /** 격자 칸에 표시할 짧은 설명 (예: "동영상 0:12", "사진 1080×1350") */
    val badge: String
        get() = when {
            isImage -> {
                val size = if (width != null && height != null) " ${width}×${height}" else ""
                "사진$size"
            }
            durationSec != null -> {
                val total = durationSec.toInt()
                "동영상 %d:%02d".format(total / 60, total % 60)
            }
            else -> "동영상"
        }
}

enum class TaskState {
    QUEUED, FETCHING, DOWNLOADING, SAVING,

    /** 일시적 실패 후 백오프 대기 중 */
    RETRY_WAIT,

    DONE, FAILED, CANCELED
}

data class DownloadTask(
    val id: String,
    val url: String,
    val platform: Platform,
    val quality: Quality,
    /** 선택 다운로드용 yt-dlp --playlist-items 값 (예: "1,3,7"). null 이면 전부 */
    val playlistItems: String? = null,
    /** 받기로 한 미디어 개수 (0 이면 미상) */
    val expectedCount: Int = 0,
    val title: String = "정보 가져오는 중…",
    val uploader: String? = null,
    val thumbnail: String? = null,
    val state: TaskState = TaskState.QUEUED,
    val progress: Float = 0f,
    val etaSec: Long = -1,
    val statusLine: String = "",
    /** 사용자에게 보여줄 한국어 요약 */
    val error: String? = null,
    /** yt-dlp 가 실제로 뱉은 원문 (진단용, "자세히"에서 노출) */
    val rawError: String? = null,
    val savedCount: Int = 0,
    val savedUris: List<Uri> = emptyList(),
    /** 끝난 시각(epoch ms). 이력 정렬에 쓴다. 0 이면 아직 진행 중 */
    val finishedAt: Long = 0L,
    /** 지금까지 자동 재시도한 횟수 */
    val retryCount: Int = 0,
    /** 사용자가 직접 고른 포맷 (Quality.CUSTOM 일 때) */
    val formatId: String? = null
) {
    val isFinished: Boolean
        get() = state == TaskState.DONE || state == TaskState.FAILED || state == TaskState.CANCELED
}
