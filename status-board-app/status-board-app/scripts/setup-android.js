// npx cap add android 직후에 한 번 실행: 권한, 알림, 뒤로가기, 설정·앱 열기
const fs = require('fs'), path = require('path');
const res = path.join(__dirname, '..', 'android', 'app', 'src', 'main');
const manifestPath = path.join(res, 'AndroidManifest.xml');
if (!fs.existsSync(manifestPath)) { console.error('android 폴더가 없습니다.'); process.exit(1); }

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
console.log('perm ' + added);

if (!m.includes('<queries>')) {
  m = m.replace('</manifest>', '    <queries>\n        <intent>\n            <action android:name="android.intent.action.MAIN" />\n        </intent>\n        <intent>\n            <action android:name="android.intent.action.VIEW" />\n            <data android:scheme="https" />\n        </intent>\n    </queries>\n</manifest>');
  console.log('queries added');
}

if (/android:enableOnBackInvokedCallback="true"/.test(m)) {
  m = m.replace(/android:enableOnBackInvokedCallback="true"/g, 'android:enableOnBackInvokedCallback="false"');
  console.log('back callback off');
} else if (!/enableOnBackInvokedCallback/.test(m)) {
  m = m.replace('<application', '<application\n        android:enableOnBackInvokedCallback="false"');
  console.log('back callback attr added');
}
fs.writeFileSync(manifestPath, m);

const drawDir = path.join(res, 'res', 'drawable');
fs.mkdirSync(drawDir, { recursive: true });
fs.writeFileSync(path.join(drawDir, 'ic_tracking.xml'),
'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n  <path android:fillColor="#FFFFFFFF"\n      android:pathData="M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7zM12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z"/>\n</vector>\n');

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

// 서명키 위치를 앱 설정에 못 박기 (안 하면 빌드마다 키가 새로 만들어져 덮어쓰기 설치가 안 됨)
const gradlePath = path.join(__dirname, '..', 'android', 'app', 'build.gradle');
if (fs.existsSync(gradlePath)) {
  let g = fs.readFileSync(gradlePath, 'utf8');
  if (!g.includes('debug.keystore')) {
    const block = [
      '    signingConfigs {',
      '        debug {',
      "            storeFile file('debug.keystore')",
      "            storePassword 'android'",
      "            keyAlias 'androiddebugkey'",
      "            keyPassword 'android'",
      '        }',
      '    }',
      ''
    ].join('\n');
    g = g.replace(/android\s*\{/, (m) => m + '\n' + block);
    fs.writeFileSync(gradlePath, g);
    console.log('signing config fixed');
  } else {
    console.log('signing config already fixed');
  }
}

const javaRoot = path.join(res, 'java');
function findMain(dir){ for(const f of fs.readdirSync(dir,{withFileTypes:true})){ const p2=path.join(dir,f.name);
  if(f.isDirectory()){ const r=findMain(p2); if(r) return r; } else if(f.name==='MainActivity.java') return p2; } return null; }
const mainPath = fs.existsSync(javaRoot) ? findMain(javaRoot) : null;
if(mainPath){
  const cur = fs.readFileSync(mainPath, 'utf8');
  const pkgMatch = cur.match(/^package\s+([^;]+);/m);
  const pkg = pkgMatch ? pkgMatch[1] : 'com.jangsung.fleet';
  const L = [];
  L.push('package ' + pkg + ';', '');
  L.push('import android.content.Intent;', 'import android.net.Uri;', 'import android.provider.Settings;', 'import android.webkit.JavascriptInterface;', '');
  L.push('import com.getcapacitor.BridgeActivity;', '');
  L.push('public class MainActivity extends BridgeActivity {', '');
  L.push('    @Override', '    public void onStart() {', '        super.onStart();');
  L.push('        if (this.bridge != null && this.bridge.getWebView() != null) {');
  L.push('            this.bridge.getWebView().getSettings().setTextZoom(100);');
  L.push('            this.bridge.getWebView().addJavascriptInterface(new SettingsBridge(), \"AndroidSettings\");');
  L.push('        }', '    }', '');
  L.push('    public class SettingsBridge {');
  L.push('        @JavascriptInterface');
  L.push('        public void open(final String kind) {');
  L.push('            runOnUiThread(new Runnable() { @Override public void run() {');
  L.push('                String pkg = getPackageName();');
  L.push('                try {');
  L.push('                    Intent i;');
  L.push('                    if (\"battery\".equals(kind)) {');
  L.push('                        i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);');
  L.push('                    } else if (\"notification\".equals(kind)) {');
  L.push('                        i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);');
  L.push('                        i.putExtra(Settings.EXTRA_APP_PACKAGE, pkg);');
  L.push('                    } else {');
  L.push('                        i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);');
  L.push('                        i.setData(Uri.parse(\"package:\" + pkg));');
  L.push('                    }');
  L.push('                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);');
  L.push('                    startActivity(i);');
  L.push('                } catch (Exception ignored) { }');
  L.push('            } });');
  L.push('        }', '');
  L.push('        @JavascriptInterface');
  L.push('        public void openApp(final String pkgName, final String fallbackUrl) {');
  L.push('            runOnUiThread(new Runnable() { @Override public void run() {');
  L.push('                try {');
  L.push('                    Intent i = getPackageManager().getLaunchIntentForPackage(pkgName);');
  L.push('                    if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return; }');
  L.push('                } catch (Exception ignored) { }');
  L.push('                try {');
  L.push('                    Intent w = new Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl));');
  L.push('                    w.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(w);');
  L.push('                } catch (Exception ignored) { }');
  L.push('            } });');
  L.push('        }', '');
  L.push('        @JavascriptInterface');
  L.push('        public void openUrl(final String url) {');
  L.push('            runOnUiThread(new Runnable() { @Override public void run() {');
  L.push('                try {');
  L.push('                    Intent w = new Intent(Intent.ACTION_VIEW, Uri.parse(url));');
  L.push('                    w.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(w);');
  L.push('                } catch (Exception ignored) { }');
  L.push('            } });');
  L.push('        }');
  L.push('    }');
  L.push('}', '');
  fs.writeFileSync(mainPath, L.join('\n'));
  console.log('MainActivity updated');
}

console.log('next: npx @capacitor/assets generate --android');
