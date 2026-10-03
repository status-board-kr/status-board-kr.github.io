// Reuse the approved production widget templates without altering the production app.
const fs = require('fs'), path = require('path'), os = require('os');
const { spawnSync } = require('child_process');
const project = path.resolve(__dirname, '..');
const source = path.resolve(project, '../status-board-app/status-board-app/scripts');
const staging = fs.mkdtempSync(path.join(os.tmpdir(), 'fleet-native-widgets-'));
const scripts = path.join(staging, 'scripts');
const main = path.join(staging, 'android/app/src/main');
const java = path.join(main, 'java/kr/statusboard/nativeapp');
fs.mkdirSync(scripts, { recursive: true }); fs.mkdirSync(java, { recursive: true });
for (const name of ['setup-widget.js', 'widget-schedule-popup.java']) fs.copyFileSync(path.join(source, name), path.join(scripts, name));
fs.writeFileSync(path.join(java, 'MainActivity.java'), 'package kr.statusboard.nativeapp;\npublic class MainActivity { class SettingsBridge {} }');
fs.writeFileSync(path.join(main, 'AndroidManifest.xml'), '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application></application></manifest>');
const result = spawnSync(process.execPath, [path.join(scripts, 'setup-widget.js')], { encoding: 'utf8' });
if (result.status !== 0) throw new Error(result.stderr || result.stdout);
const target = path.join(project, 'app/src/main');
for (const name of ['FleetWidgetUtil.java', 'FleetWidgetScheduleActivity.java', 'FleetCalendarWidget.java', 'FleetCompactWidget.java', 'FleetWidgetWeather.java']) {
  let content = fs.readFileSync(path.join(java, name), 'utf8');
  if (name === 'FleetWidgetUtil.java') content = content.replace('public static JSONObject load(Context ctx) {\n        try {', `public static JSONObject load(Context ctx) {
        try {
            com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
            String owner = ctx.getSharedPreferences("fleet_widget", Context.MODE_PRIVATE).getString("owner", "");
            if (user == null || !owner.startsWith(user.getUid()+":")) return null;
            long verified = ctx.getSharedPreferences("fleet_widget", Context.MODE_PRIVATE).getLong("verifiedAt", 0);
            if (System.currentTimeMillis() - verified > 20 * 60_000L) return null;`);
  if (name === 'FleetCalendarWidget.java' || name === 'FleetCompactWidget.java') {
    content = content.replace(/(FleetWidgetUtil\.update\(ctx, m, ids, FleetWidgetUtil\.[A-Z]+\);)/, '$1\n        FleetWidgetRefresh.enqueue(ctx);');
    content = content.replace(/}\s*$/, '    @Override public void onDisabled(Context ctx) { FleetWidgetRefresh.enqueue(ctx); }\n}\n');
  }
  if (name === 'FleetWidgetScheduleActivity.java') {
    content = content.replace('public class FleetWidgetScheduleActivity extends Activity {', `public class FleetWidgetScheduleActivity extends Activity {
    kotlinx.coroutines.Job refreshing;
    void refresh(){if(refreshing!=null)refreshing.cancel(null);refreshing=FleetWidgetRefresh.refreshPopup(this,()->showDate(getIntent()));}`);
    content = content.replace('setFinishOnTouchOutside(true);showDate(getIntent());', 'setFinishOnTouchOutside(true);showDate(getIntent());refresh();');
    content = content.replace('setIntent(intent);showDate(intent);}', 'setIntent(intent);showDate(intent);refresh();}\n    @Override public void onDestroy(){if(refreshing!=null)refreshing.cancel(null);super.onDestroy();}');
  }
  fs.writeFileSync(path.join(target, 'java/kr/statusboard/nativeapp', name), content);
}
for (const dir of ['drawable', 'layout', 'xml']) {
  fs.mkdirSync(path.join(target, 'res', dir), { recursive: true });
  for (const name of fs.readdirSync(path.join(main, 'res', dir))) {
    // Only the two approved widget variants are exposed in the native app.
    if (/^widget_(small|medium)/.test(name)) continue;
    fs.copyFileSync(path.join(main, 'res', dir, name), path.join(target, 'res', dir, name));
  }
}
console.log('Prepared existing and compact native widget templates.');
