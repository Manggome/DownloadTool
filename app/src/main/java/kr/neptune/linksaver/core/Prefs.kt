package kr.neptune.linksaver.core

import android.content.Context

/** 아주 가벼운 설정 저장소 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("linksaver", Context.MODE_PRIVATE)

    var cookiesPath: String?
        get() = sp.getString("cookies_path", null)
        set(value) = sp.edit().putString("cookies_path", value).apply()

    var lastQuality: Quality
        get() = runCatching { Quality.valueOf(sp.getString("quality", null) ?: "") }
            .getOrDefault(Quality.BEST)
        set(value) = sp.edit().putString("quality", value.name).apply()

    var autoPasteFromClipboard: Boolean
        get() = sp.getBoolean("auto_paste", true)
        set(value) = sp.edit().putBoolean("auto_paste", value).apply()

    /** yt-dlp 를 마지막으로 최신화한 시각(epoch ms). 0 이면 한 번도 안 함 */
    var engineUpdatedAt: Long
        get() = sp.getLong("engine_updated_at", 0L)
        set(value) = sp.edit().putLong("engine_updated_at", value).apply()

    /** 최신화를 마지막으로 "시도"한 시각. 실패해도 기록해서 매 실행 재시도를 막는다 */
    var engineUpdateAttemptAt: Long
        get() = sp.getLong("engine_update_attempt_at", 0L)
        set(value) = sp.edit().putLong("engine_update_attempt_at", value).apply()

    /** 자동 최신화 사용 여부 */
    var autoUpdateEngine: Boolean
        get() = sp.getBoolean("auto_update_engine", true)
        set(value) = sp.edit().putBoolean("auto_update_engine", value).apply()

    companion object {
        /** 이 간격이 지나면 앱 시작 시 엔진을 다시 최신화한다 */
        const val ENGINE_UPDATE_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000 // 3일

        /** 최신화에 실패했을 때 다시 시도하기까지의 최소 간격 */
        const val ENGINE_RETRY_INTERVAL_MS = 60L * 60 * 1000 // 1시간

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }
    }
}
