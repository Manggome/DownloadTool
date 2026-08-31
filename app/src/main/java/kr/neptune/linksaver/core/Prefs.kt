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

    /**
     * 틱톡 모바일 API 를 쓰려면 기기 ID 가 필요하다.
     * 매번 바뀌면 수상해 보이므로 설치 단위로 한 번만 만들어 재사용한다.
     */
    fun tiktokDeviceId(): String {
        sp.getString("tiktok_device_id", null)?.let { return it }
        val generated = buildString {
            append((1..9).random())
            repeat(18) { append((0..9).random()) }
        }
        sp.edit().putString("tiktok_device_id", generated).apply()
        return generated
    }

    /**
     * 인스타는 로그인 세션을 쓰면 "의심스러운 로그인" 경고가 오는 경우가 있다.
     * 켜두면 먼저 익명으로 시도하고, 실패할 때만 로그인 세션을 쓴다.
     */
    var instagramAnonymousFirst: Boolean
        get() = sp.getBoolean("ig_anonymous_first", true)
        set(value) = sp.edit().putBoolean("ig_anonymous_first", value).apply()

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
