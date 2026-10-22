package kr.airrelay.air_relay_kr

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 사용자가 [릴레이 시작]을 누르면 실행되는 포그라운드 서비스.
 *  - 별도의 Flutter 엔진에서 Dart 워커(relayMain)를 실행해 Fitbit Air 로 진동 명령을 보냅니다.
 *  - 전화 수신 상태(TelephonyCallback)를 감지합니다.
 *  - 카카오톡 알림은 RelayNotificationListener 가 감지해 onKakao() 를 호출합니다.
 */
class RelayService : Service() {

    companion object {
        @Volatile
        var instance: RelayService? = null

        private const val ACTION_STOP = "kr.airrelay.air_relay_kr.STOP"
        private const val CHANNEL_ID = "air_relay_status"
        private const val NOTIFICATION_ID = 4101
        private const val MIN_GAP_MS = 2000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RelayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var engine: FlutterEngine? = null
    private var channel: MethodChannel? = null
    private var ready = false
    private val pending = ArrayList<Pair<String, Any?>>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastKakaoAt = 0L

    private var telephonyCallback: TelephonyCallback? = null
    private var phoneListener: PhoneStateListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startEngine()
        registerPhoneState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 프로세스가 죽으면 자동 재시작하지 않음 (앱에서 다시 시작해야 함)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        unregisterPhoneState()
        // 진행 중인 진동 명령이 끝날 시간을 잠깐 주고 엔진 종료
        val ch = channel
        val e = engine
        ch?.invokeMethod("shutdown", null)
        main.postDelayed({ e?.destroy() }, 4000)
        engine = null
        channel = null
        ready = false
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Flutter 워커

    private fun startEngine() {
        val appContext = applicationContext
        val loader = FlutterInjector.instance().flutterLoader()
        loader.startInitialization(appContext)
        loader.ensureInitializationComplete(appContext, null)

        val e = FlutterEngine(appContext)
        val ch = MethodChannel(e.dartExecutor.binaryMessenger, "air_relay/worker")
        ch.setMethodCallHandler { call, result ->
            when (call.method) {
                "ready" -> {
                    ready = true
                    for ((method, args) in pending) ch.invokeMethod(method, args)
                    pending.clear()
                    result.success(null)
                }

                "result" -> {
                    val text = call.arguments as? String ?: ""
                    val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
                    Prefs.setStr(this, Prefs.LAST, "$time  $text")
                    result.success(null)
                }

                else -> result.notImplemented()
            }
        }
        e.dartExecutor.executeDartEntrypoint(
            DartExecutor.DartEntrypoint(loader.findAppBundlePath(), "relayMain")
        )
        engine = e
        channel = ch
    }

    private fun send(method: String, args: Any? = null) {
        acquireWakeLock()
        main.post {
            val ch = channel ?: return@post
            if (ready) ch.invokeMethod(method, args) else pending.add(method to args)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airrelay:work")
            .also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        lock.acquire(60_000L)
    }

    // ---------------------------------------------------------------- 외부에서 호출

    /** 카카오톡 알림이 새로 올라왔을 때 (RelayNotificationListener) */
    fun onKakao() {
        if (!Prefs.getBool(this, Prefs.KAKAO, true)) return
        val now = System.currentTimeMillis()
        if (now - lastKakaoAt < MIN_GAP_MS) return
        lastKakaoAt = now
        send("message")
    }

    /** 화면의 [테스트 진동] */
    fun test() = send("test")

    // ---------------------------------------------------------------- 전화 상태

    private fun handleCallState(state: Int) {
        if (!Prefs.getBool(this, Prefs.CALL, true)) return
        if (state == TelephonyManager.CALL_STATE_RINGING) send("callStart") else send("callEnd")
    }

    private fun registerPhoneState() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = handleCallState(state)
                }
                tm.registerTelephonyCallback(mainExecutor, cb)
                telephonyCallback = cb
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) =
                        handleCallState(state)
                }
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                phoneListener = l
            }
        } catch (_: SecurityException) {
            // 권한이 없으면 전화 진동만 비활성화
        }
    }

    private fun unregisterPhoneState() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= 31) {
            telephonyCallback?.let { tm.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            phoneListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
        }
        telephonyCallback = null
        phoneListener = null
    }

    // ---------------------------------------------------------------- 상태 알림

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Air 릴레이 상태", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Air 릴레이 실행 중")
            .setContentText("카카오톡·전화 알림을 Fitbit Air 진동으로 전달합니다")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(0, "중지", stop).build())
            .build()
    }
}
