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

    companion object {
        @Volatile private var instance: Prefs? = null
        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }
    }
}
