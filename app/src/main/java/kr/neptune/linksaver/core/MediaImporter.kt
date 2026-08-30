package kr.neptune.linksaver.core

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import android.util.Log
import java.io.File

/**
 * 앱 캐시에 받아둔 원본 파일을 갤러리(MediaStore)로 옮긴다.
 *
 * yt-dlp 는 공용 저장소에 직접 쓸 수 없으므로(Scoped Storage),
 * "캐시에 다운로드 → MediaStore 로 복사 → 캐시 삭제" 흐름을 쓴다.
 */
object MediaImporter {

    private const val TAG = "MediaImporter"
    const val ALBUM = "LinkSaver"

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif")
    private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "ts")
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "opus", "ogg", "wav", "flac")

    private enum class Kind { IMAGE, VIDEO, AUDIO }

    private fun kindOf(file: File): Kind? = when (file.extension.lowercase()) {
        in IMAGE_EXT -> Kind.IMAGE
        in VIDEO_EXT -> Kind.VIDEO
        in AUDIO_EXT -> Kind.AUDIO
        else -> null
    }

    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic", "heif" -> "image/heif"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "ts" -> "video/mp2t"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }

    /**
     * [files] 를 갤러리로 옮기고, 성공한 항목의 Uri 를 돌려준다.
     * 옮겨진 원본 파일은 삭제한다.
     */
    fun importAll(context: Context, files: List<File>, displayPrefix: String? = null): List<Uri> {
        val result = mutableListOf<Uri>()
        files.forEachIndexed { index, file ->
            val uri = runCatching { importOne(context, file, displayPrefix, index) }
                .onFailure { Log.e(TAG, "import failed: ${file.name}", it) }
                .getOrNull()
            if (uri != null) {
                result += uri
                file.delete()
            }
        }
        return result
    }

    private fun importOne(context: Context, file: File, prefix: String?, index: Int): Uri? {
        val kind = kindOf(file) ?: run {
            Log.w(TAG, "지원하지 않는 확장자: ${file.name}")
            return null
        }
        val displayName = buildDisplayName(file, prefix, index)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insertScoped(context, file, kind, displayName)
        } else {
            insertLegacy(context, file, kind, displayName)
        }
    }

    private fun buildDisplayName(file: File, prefix: String?, index: Int): String {
        val safePrefix = prefix
            ?.replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_")
            ?.trim()
            ?.take(60)
            ?.ifBlank { null }
        val base = if (safePrefix != null) {
            if (index == 0) safePrefix else "${safePrefix}_${index + 1}"
        } else {
            file.nameWithoutExtension
        }
        return "$base.${file.extension}"
    }

    // ------------------------------------------------------------ Android 10+

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertScoped(context: Context, file: File, kind: Kind, displayName: String): Uri? {
        val collection = when (kind) {
            Kind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Kind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Kind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relative = when (kind) {
            Kind.IMAGE -> "${Environment.DIRECTORY_PICTURES}/$ALBUM"
            Kind.VIDEO -> "${Environment.DIRECTORY_MOVIES}/$ALBUM"
            Kind.AUDIO -> "${Environment.DIRECTORY_MUSIC}/$ALBUM"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(file))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
            } ?: throw IllegalStateException("출력 스트림을 열 수 없습니다")
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    // ------------------------------------------------------------ Android 9 이하

    private fun insertLegacy(context: Context, file: File, kind: Kind, displayName: String): Uri? {
        val publicDir = when (kind) {
            Kind.IMAGE -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            Kind.VIDEO -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            Kind.AUDIO -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        }
        val albumDir = File(publicDir, ALBUM).apply { mkdirs() }
        val target = uniqueFile(albumDir, displayName)

        file.inputStream().use { input ->
            target.outputStream().use { out -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
        }

        var scanned: Uri? = null
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(mimeOf(target))
        ) { _, uri -> scanned = uri }

        return scanned ?: Uri.fromFile(target)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = candidate.nameWithoutExtension
        val ext = candidate.extension
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_$n.$ext")
            n++
        }
        return candidate
    }

    /** 저장 위치를 사용자에게 보여줄 문자열 */
    fun savedLocationText(): String =
        "사진 → Pictures/$ALBUM · 영상 → Movies/$ALBUM · 오디오 → Music/$ALBUM"
}
