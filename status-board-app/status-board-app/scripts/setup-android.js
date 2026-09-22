// npx cap add android 직후에 한 번 실행: 위치/알림 권한, 알림 아이콘 설정
// (여러 번 실행해도 중복으로 들어가지 않게 만들어져 있습니다)
const fs = require('fs'), path = require('path');
const res = path.join(__dirname, '..', 'android', 'app', 'src', 'main');
const manifestPath = path.join(res, 'AndroidManifest.xml');
if (!fs.existsSync(manifestPath)) { console.error('❌ android 폴더가 없어요. 먼저 "npx cap add android"를 실행하세요.'); process.exit(1); }

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
  if (!m.includes(`"${p}"`)) {
    m = m.replace('</manifest>', `    <uses-permission android:name="${p}" />\n</manifest>`);
    added++;
  }
}
fs.writeFileSync(manifestPath, m);
console.log(`✅ 권한 ${added}개 추가 (이미 있던 건 건너뜀)`);

// 2) 상단 알림 아이콘 (벡터 아이콘이어야 알림이 제대로 고정됨)
const drawDir = path.join(res, 'res', 'drawable');
fs.mkdirSync(drawDir, { recursive: true });
fs.writeFileSync(path.join(drawDir, 'ic_tracking.xml'),
`<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
  <path android:fillColor="#FFFFFFFF"
      android:pathData="M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7zM12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z"/>
</vector>
`);
console.log('✅ 알림 아이콘(ic_tracking) 생성');

// 3) 알림 채널 이름/아이콘/색상
const stringsPath = path.join(res, 'res', 'values', 'strings.xml');
let st = fs.readFileSync(stringsPath, 'utf8');
const entries = {
  capacitor_background_geolocation_notification_channel_name: '위치 공유',
  capacitor_background_geolocation_notification_icon: 'drawable/ic_tracking',
  capacitor_background_geolocation_notification_color: '#F5A623'
};
for (const [k, v] of Object.entries(entries)) {
  if (!st.includes(`name="${k}"`)) st = st.replace('</resources>', `    <string name="${k}">${v}</string>\n</resources>`);
}
fs.writeFileSync(stringsPath, st);
console.log('✅ 알림 이름/아이콘/색상 설정');

// 4) 폰 설정의 '글자 크기'를 크게 해둔 기종에서 화면이 깨지지 않도록 앱 안 글자 배율 고정
const javaRoot = path.join(res, 'java');
function findMain(dir){ for(const f of fs.readdirSync(dir,{withFileTypes:true})){ const p=path.join(dir,f.name);
  if(f.isDirectory()){ const r=findMain(p); if(r) return r; } else if(f.name==='MainActivity.java') return p; } return null; }
const mainPath = fs.existsSync(javaRoot) ? findMain(javaRoot) : null;
if(mainPath){
  let j = fs.readFileSync(mainPath,'utf8');
  if(!j.includes('setTextZoom')){
    j = j.replace(/public class MainActivity extends BridgeActivity \{\s*\}/,
`public class MainActivity extends BridgeActivity {
    @Override
    public void onStart() {
        super.onStart();
        // 기종·글자크기 설정과 상관없이 화면이 같게 보이도록 (폰 글자크기 설정 무시)
        if (this.bridge != null && this.bridge.getWebView() != null) {
            this.bridge.getWebView().getSettings().setTextZoom(100);
        }
    }
}`);
    fs.writeFileSync(mainPath, j);
    console.log(j.includes('setTextZoom') ? '✅ 글자 배율 고정(MainActivity)' : '⚠️ MainActivity 형식이 달라 글자 배율 고정은 건너뜀');
  } else console.log('✅ 글자 배율 고정 이미 적용됨');
}

console.log('\n다음: npx @capacitor/assets generate --android  →  npx cap sync android');
