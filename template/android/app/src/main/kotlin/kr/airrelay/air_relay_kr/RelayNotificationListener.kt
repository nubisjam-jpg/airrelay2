package kr.airrelay.air_relay_kr

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 카카오톡 알림을 감지합니다. (설정 > 알림 접근 에서 사용자가 허용해야 동작)
 *
 * 알림 제목·내용은 읽지 않고, "카카오톡에서 새 알림이 올라왔다"는 사실만 사용합니다.
 * 릴레이 서비스가 실행 중일 때만 동작하며, 서비스를 새로 띄우지는 않습니다.
 */
class RelayNotificationListener : NotificationListenerService() {

    companion object {
        private const val KAKAO_PACKAGE = "com.kakao.talk"
        private const val MAX_TRACKED = 256
    }

    // 알림 key -> 마지막으로 처리한 postTime (같은 게시물을 두 번 처리하지 않기 위함)
    private val seen = HashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != KAKAO_PACKAGE) return
        val service = RelayService.instance ?: return
        if (!Prefs.getBool(this, Prefs.KAKAO, true)) return

        val flags = sbn.notification.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return // 묶음 요약은 무시
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return // 상시 알림은 무시

        val last = seen[sbn.key]
        if (last != null && sbn.postTime <= last) return
        if (seen.size >= MAX_TRACKED) seen.clear()
        seen[sbn.key] = sbn.postTime

        service.onKakao()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // 시스템이 연결을 끊은 경우 다시 연결 요청
        try {
            requestRebind(ComponentName(this, RelayNotificationListener::class.java))
        } catch (_: Exception) {
        }
    }
}
