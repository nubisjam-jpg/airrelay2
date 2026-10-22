package kr.airrelay.air_relay_kr

import android.content.Context

/** 앱 설정 저장소 (기기 안에만 저장, 알림 내용은 저장하지 않음) */
object Prefs {
    const val KAKAO = "kakao"
    const val CALL = "call"
    const val LAST = "last"

    private fun sp(c: Context) =
        c.getSharedPreferences("air_relay", Context.MODE_PRIVATE)

    fun getBool(c: Context, key: String, default: Boolean): Boolean =
        sp(c).getBoolean(key, default)

    fun setBool(c: Context, key: String, value: Boolean) {
        sp(c).edit().putBoolean(key, value).apply()
    }

    fun getStr(c: Context, key: String): String =
        sp(c).getString(key, "") ?: ""

    fun setStr(c: Context, key: String, value: String) {
        sp(c).edit().putString(key, value).apply()
    }
}
