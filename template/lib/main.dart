import 'dart:async';

import 'package:fitbit_air/fitbit_air.dart';
import 'package:fitbit_air/fitbit_air_commands.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// 화면(UI) <-> 네이티브(Kotlin) 통신 채널
const _native = MethodChannel('air_relay/native');

/// 백그라운드 워커(Dart) <-> 네이티브(Kotlin) 통신 채널
const _worker = MethodChannel('air_relay/worker');

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  FitbitAir.registerLicenses();
  runApp(const AirRelayApp());
}

// ---------------------------------------------------------------------------
// 백그라운드 워커
// RelayService(포그라운드 서비스)가 별도의 Flutter 엔진을 만들어 이 함수를 실행합니다.
// Kotlin 쪽에서 "message" / "callStart" / "callEnd" / "test" 를 보내면
// Fitbit Air 에 진동 명령을 전달합니다.
// ---------------------------------------------------------------------------
@pragma('vm:entry-point')
Future<void> relayMain() async {
  WidgetsFlutterBinding.ensureInitialized();
  await _RelayWorker().run();
}

class _RelayWorker {
  final AirHapticDispatcher _haptics = AirHapticDispatcher();
  Timer? _callTimer;
  int _seq = 0;

  Future<void> run() async {
    _worker.setMethodCallHandler(_handle);
    try {
      // 명령 전송 속도를 높이기 위해 수신 전용 연결을 미리 열어 둡니다.
      await FitbitAir.keepLinkWarm(true);
    } catch (_) {}
    await _worker.invokeMethod('ready');
  }

  Future<dynamic> _handle(MethodCall call) async {
    switch (call.method) {
      case 'message':
        unawaited(_send('카카오톡', onePulse: true));
        break;
      case 'test':
        unawaited(_send('테스트', onePulse: false));
        break;
      case 'callStart':
        _startCall();
        break;
      case 'callEnd':
        _stopCall();
        break;
      case 'shutdown':
        await _shutdown();
        break;
    }
    return null;
  }

  /// 전화: 벨이 울리는 동안 약 8초 간격으로 최대 4번 진동(2회 펄스)
  void _startCall() {
    if (_callTimer != null) return;
    unawaited(_send('전화', onePulse: false));
    var count = 0;
    _callTimer = Timer.periodic(const Duration(seconds: 8), (timer) {
      count++;
      if (count > 3) {
        timer.cancel();
        _callTimer = null;
        return;
      }
      unawaited(_send('전화', onePulse: false));
    });
  }

  void _stopCall() {
    _callTimer?.cancel();
    _callTimer = null;
  }

  /// onePulse: true = 1회 짧은 진동(카톡), false = 2회 진동(전화/테스트)
  Future<void> _send(String label, {required bool onePulse}) async {
    final id = '$label-${DateTime.now().microsecondsSinceEpoch}-${_seq++}';
    final expiresAt = DateTime.now().add(const Duration(seconds: 30));
    try {
      final result = onePulse
          ? await _haptics.submit(AirHapticCommand(
              id: id,
              expiresAt: expiresAt,
              kind: AirHapticKind.tap,
            ))
          : await _haptics.submit(AirHapticCommand(
              id: id,
              expiresAt: expiresAt,
            ));
      await _report('$label → $result');
    } on FitbitAirException catch (e) {
      await _report('$label 실패 (${e.code})');
    } catch (e) {
      await _report('$label 오류: $e');
    }
  }

  Future<void> _report(String text) async {
    try {
      await _worker.invokeMethod('result', text);
    } catch (_) {}
  }

  Future<void> _shutdown() async {
    _stopCall();
    try {
      await _haptics.close();
    } catch (_) {}
    try {
      await FitbitAir.keepLinkWarm(false);
    } catch (_) {}
  }
}

// ---------------------------------------------------------------------------
// 화면
// ---------------------------------------------------------------------------
class AirRelayApp extends StatelessWidget {
  const AirRelayApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Air 릴레이',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorSchemeSeed: const Color(0xFF1A73E8),
        useMaterial3: true,
      ),
      darkTheme: ThemeData(
        colorSchemeSeed: const Color(0xFF1A73E8),
        brightness: Brightness.dark,
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  Map<String, dynamic> _s = const {};
  bool _paired = false;
  String _note = '';
  Timer? _poll;

  bool _b(String key) => _s[key] == true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
    _poll = Timer.periodic(const Duration(seconds: 2), (_) => _refresh());
  }

  @override
  void dispose() {
    _poll?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    try {
      final s = await _native.invokeMapMethod<String, dynamic>('status');
      var paired = _paired;
      try {
        paired = await FitbitAir.isAirPaired();
      } catch (_) {}
      if (!mounted) return;
      setState(() {
        _s = s ?? const {};
        _paired = paired;
      });
    } catch (_) {}
  }

  Future<void> _call(String method, [Object? args]) async {
    try {
      await _native.invokeMethod(method, args);
    } on PlatformException catch (e) {
      if (mounted) setState(() => _note = e.message ?? e.code);
    }
    await _refresh();
  }

  Future<void> _setOption(String key, bool value) =>
      _call('setOption', {'key': key, 'value': value});

  Future<void> _test() async {
    final handled = await _native.invokeMethod<bool>('test') ?? false;
    if (handled) {
      setState(() => _note = '실행 중인 릴레이로 테스트 진동을 요청했습니다.');
      return;
    }
    setState(() => _note = '테스트 진동 전송 중... (몇 초 걸립니다)');
    try {
      await FitbitAir.vibrate();
      if (mounted) setState(() => _note = '테스트 진동을 보냈습니다.');
    } on FitbitAirException catch (e) {
      if (mounted) setState(() => _note = '테스트 실패: ${e.code}');
    } catch (e) {
      if (mounted) setState(() => _note = '테스트 오류: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final running = _b('running');
    final canStart = _b('bluetooth') && _paired;
    final last = (_s['last'] as String?) ?? '';

    return Scaffold(
      appBar: AppBar(title: const Text('Air 릴레이')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _section('1. 준비', [
            _check('Fitbit Air 페어링 (Google Health 앱)', _paired),
            _check('근처 기기(블루투스) 권한', _b('bluetooth')),
            _check('알림 표시 권한', _b('notifications')),
            _check('전화 상태 권한 (전화 진동용)', _b('phone')),
            _check('알림 접근 권한 (카카오톡 진동용)', _b('listener')),
            const SizedBox(height: 8),
            Wrap(spacing: 8, runSpacing: 8, children: [
              FilledButton.tonal(
                onPressed: () => _call('requestPermissions'),
                child: const Text('권한 허용'),
              ),
              FilledButton.tonal(
                onPressed: () => _call('openListenerSettings'),
                child: const Text('알림 접근 설정 열기'),
              ),
              OutlinedButton(
                onPressed: () => _call('openBatterySettings'),
                child: const Text('배터리 최적화 설정'),
              ),
            ]),
          ]),
          _section('2. 진동으로 받을 알림', [
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('카카오톡 메시지'),
              subtitle: const Text('짧은 진동 1회'),
              value: _b('kakao'),
              onChanged: (v) => _setOption('kakao', v),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('전화 수신'),
              subtitle: const Text('벨이 울리는 동안 진동 2회씩 반복'),
              value: _b('call'),
              onChanged: (v) => _setOption('call', v),
            ),
          ]),
          _section('3. 실행', [
            Row(children: [
              Expanded(
                child: FilledButton(
                  onPressed: running
                      ? () => _call('stopRelay')
                      : (canStart ? () => _call('startRelay') : null),
                  child: Text(running ? '릴레이 중지' : '릴레이 시작'),
                ),
              ),
              const SizedBox(width: 8),
              OutlinedButton(
                onPressed: _paired ? _test : null,
                child: const Text('테스트 진동'),
              ),
            ]),
            const SizedBox(height: 8),
            Text(running ? '● 실행 중' : '○ 중지됨'),
            if (!canStart && !running)
              const Padding(
                padding: EdgeInsets.only(top: 4),
                child: Text('Air 페어링과 근처 기기 권한이 필요합니다.'),
              ),
            if (running && _b('kakao') && !_b('listener'))
              const Padding(
                padding: EdgeInsets.only(top: 4),
                child: Text('카카오톡 진동을 쓰려면 알림 접근 권한을 켜 주세요.'),
              ),
          ]),
          if (last.isNotEmpty || _note.isNotEmpty)
            _section('상태', [
              if (_note.isNotEmpty) Text(_note),
              if (last.isNotEmpty) Text('마지막 전달: $last'),
            ]),
          const Padding(
            padding: EdgeInsets.all(8),
            child: Text(
              '비공식 프로젝트입니다. Air 펌웨어가 업데이트되면 동작하지 않을 수 있습니다. '
              '릴레이는 재부팅·강제 종료 후 수동으로 다시 시작해야 합니다.',
              style: TextStyle(fontSize: 12),
            ),
          ),
        ],
      ),
    );
  }

  Widget _section(String title, List<Widget> children) {
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            ...children,
          ],
        ),
      ),
    );
  }

  Widget _check(String label, bool ok) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(children: [
        Icon(ok ? Icons.check_circle : Icons.radio_button_unchecked,
            size: 20, color: ok ? Colors.green : Colors.grey),
        const SizedBox(width: 8),
        Expanded(child: Text(label)),
      ]),
    );
  }
}
