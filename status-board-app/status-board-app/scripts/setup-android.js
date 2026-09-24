// npx cap add android 직후에 한 번 실행: 위치/알림 권한, 알림 아이콘, 설정 열기 기능
const fs = require('fs'), path = require('path');
const res = path.join(__dirname, '..', 'android', 'app', 'src', 'main');
const manifestPath = path.join(res, 'AndroidManifest.xml');
if (!fs.existsSync(manifestPath)) { console.error('android 폴더가 없습니다. 먼저 npx cap add android 를 실행하세요.'); process.exit(1); }

// 1) 권한 추가
let m = fs.readFileSync(manifestPath, 'utf8');
const perms = [
  'android.permission.ACCESS_COARSE_LOCATION',
  'android.permission.ACCESS_FINE_LOCATION',
  'android.permission.FOREGROUND_SERVICE',
  'android.permission.FOREGROUND_SERVICE_LOCATION',
  'android.permission.POST_NOTIFICATIONS'
];
let added = 0;
for (const p of perms) {
  if (!m.includes('"' + p + '"')) {
    m = m.replace('</manifest>', '    <uses-permission android:name="' + p + '" />\n</manifest>');
    added++;
  }
}
fs.writeFileSync(manifestPath, m);
console.log('권한 ' + added + '개 추가');

// 2) 상단 알림 아이콘
const drawDir = path.join(res, 'res', 'drawable');
fs.mkdirSync(drawDir, { recursive: true });
fs.writeFileSync(path.join(drawDir, 'ic_tracking.xml'),
'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n  <path android:fillColor="#FFFFFFFF"\n      android:pathData="M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7zM12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z"/>\n</vector>\n');
console.log('알림 아이콘 생성');

// 3) 알림 채널 이름/아이콘/색상
const stringsPath = path.join(res, 'res', 'values', 'strings.xml');
let st = fs.readFileSync(stringsPath, 'utf8');
const entries = {
  capacitor_background_geolocation_notification_channel_name: '위치 공유',
  capacitor_background_geolocation_notification_icon: 'drawable/ic_tracking',
  capacitor_background_geolocation_notification_color: '#F5A623'
};
for (const [k, v] of Object.entries(entries)) {
  if (!st.includes('name="' + k + '"')) st = st.replace('</resources>', '    <string name="' + k + '">' + v + '</string>\n</resources>');
}
fs.writeFileSync(stringsPath, st);
console.log('알림 설정 완료');

// 4) 글자 배율 고정 + 앱에서 폰 설정 화면 열기
const javaRoot = path.join(res, 'java');
function findMain(dir){ for(const f of fs.readdirSync(dir,{withFileTypes:true})){ const p2=path.join(dir,f.name);
  if(f.isDirectory()){ const r=findMain(p2); if(r) return r; } else if(f.name==='MainActivity.java') return p2; } return null; }
const mainPath = fs.existsSync(javaRoot) ? findMain(javaRoot) : null;
if(mainPath){
  const cur = fs.readFileSync(mainPath, 'utf8');
  const pkgMatch = cur.match(/^package\s+([^;]+);/m);
  const pkg = pkgMatch ? pkgMatch[1] : 'com.jangsung.fleet';
  const java = [
    'package ' + pkg + ';',
    '',
    'import android.content.Intent;',
    'import android.net.Uri;',
    'import android.provider.Settings;',
    'import android.webkit.JavascriptInterface;',
    '',
    'import com.getcapacitor.BridgeActivity;',
    '',
    'public class MainActivity extends BridgeActivity {',
    '',
    '    @Override',
    '    public void onStart() {',
    '        super.onStart();',
    '        if (this.bridge != null && this.bridge.getWebView() != null) {',
    '            this.bridge.getWebView().getSettings().setTextZoom(100);',
    '            this.bridge.getWebView().addJavascriptInterface(new SettingsBridge(), "AndroidSettings");',
    '        }',
    '    }',
    '',
    '    public class SettingsBridge {',
    '        @JavascriptInterface',
    '        public void open(final String kind) {',
    '            runOnUiThread(new Runnable() {',
    '                @Override',
    '                public void run() {',
    '                    String pkg = getPackageName();',
    '                    try {',
    '                        Intent i;',
    '                        if ("battery".equals(kind)) {',
    '                            i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);',
    '                        } else if ("notification".equals(kind)) {',
    '                            i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);',
    '                            i.putExtra(Settings.EXTRA_APP_PACKAGE, pkg);',
    '                        } else {',
    '                            i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);',
    '                            i.setData(Uri.parse("package:" + pkg));',
    '                        }',
    '                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);',
    '                        startActivity(i);',
    '                    } catch (Exception e) {',
    '                        try {',
    '                            Intent f = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);',
    '                            f.setData(Uri.parse("package:" + pkg));',
    '                            f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);',
    '                            startActivity(f);',
    '                        } catch (Exception ignored) { }',
    '                    }',
    '                }',
    '            });',
    '        }',
    '    }',
    '}',
    ''
  ].join('\n');
  fs.writeFileSync(mainPath, java);
  console.log('글자 배율 고정 + 설정 열기 기능 적용');
}

console.log('다음: npx @capacitor/assets generate --android  ->  npx cap sync android');
