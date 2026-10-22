package kr.airrelay.air_relay_kr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "air_relay/native")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "status" -> result.success(status())

                    "requestPermissions" -> {
                        requestMissingPermissions()
                        result.success(null)
                    }

                    "openListenerSettings" -> {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        result.success(null)
                    }

                    "openBatterySettings" -> {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        result.success(null)
                    }

                    "setOption" -> {
                        val key = call.argument<String>("key")
                        val value = call.argument<Boolean>("value")
                        if (key != null && value != null) Prefs.setBool(this, key, value)
                        result.success(null)
                    }

                    "startRelay" -> {
                        if (!hasBluetoothPermission()) {
                            result.error("PERMISSION", "근처 기기 권한이 필요합니다.", null)
                        } else {
                            RelayService.start(this)
                            result.success(null)
                        }
                    }

                    "stopRelay" -> {
                        RelayService.stop(this)
                        result.success(null)
                    }

                    // 실행 중인 워커가 있으면 true, 없으면 false (이 경우 Dart UI가 직접 진동)
                    "test" -> {
                        val svc = RelayService.instance
                        if (svc != null) svc.test()
                        result.success(svc != null)
                    }

                    else -> result.notImplemented()
                }
            }
    }

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)

    private fun status(): Map<String, Any> {
        val enabledListeners =
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return mapOf(
            "bluetooth" to hasBluetoothPermission(),
            "notifications" to (Build.VERSION.SDK_INT < 33 ||
                granted(Manifest.permission.POST_NOTIFICATIONS)),
            "phone" to granted(Manifest.permission.READ_PHONE_STATE),
            "listener" to enabledListeners.contains(packageName),
            "running" to (RelayService.instance != null),
            "kakao" to Prefs.getBool(this, Prefs.KAKAO, true),
            "call" to Prefs.getBool(this, Prefs.CALL, true),
            "last" to Prefs.getStr(this, Prefs.LAST),
        )
    }

    private fun requestMissingPermissions() {
        val wanted = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            wanted.add(Manifest.permission.BLUETOOTH_CONNECT)
            wanted.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        wanted.add(Manifest.permission.READ_PHONE_STATE)
        val missing = wanted.filter { !granted(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 100)
    }
}
