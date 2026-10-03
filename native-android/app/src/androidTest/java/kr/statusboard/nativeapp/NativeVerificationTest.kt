package kr.statusboard.nativeapp

import android.Manifest
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.android.gms.location.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeVerificationTest {
    @get:Rule val compose = createAndroidComposeRule<NativeVerificationActivity>()
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun capture(name: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val windows = automation.windows.flatMap { window ->
            fun texts(node: android.view.accessibility.AccessibilityNodeInfo?): List<String> = if (node == null) emptyList() else listOf(node.text?.toString().orEmpty()) + (0 until node.childCount).flatMap { texts(node.getChild(it)) }
            texts(window.root)
        }
        assertFalse("System ANR dialog obscures $name", windows.any { it.contains("isn't responding") || it.contains("응답하지") })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = context.getExternalFilesDir("verification")!!.apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val safeName = name.replace(Regex("\\s+"), "-")
        val file = File(dir, "$safeName.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        // AGP uninstalls the target after instrumentation. Keep proof outside its removed directory.
        for (command in listOf("mkdir -p /data/local/tmp/native-verification", "cp ${file.absolutePath} /data/local/tmp/native-verification/$safeName.png")) {
            automation.executeShellCommand(command).use { output -> android.os.ParcelFileDescriptor.AutoCloseInputStream(output).readBytes() }
        }
    }
    @Test fun filtersAndActionsPreserveWebOrder() {
        compose.onNodeWithText("예시1234").assertIsDisplayed()
        val filter = listOf("전체", "대기", "준비중", "보험", "일반", "장기")
        val boxes = filter.map { compose.onAllNodesWithText(it, useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot }
        boxes.zipWithNext().forEach { (a, b) -> assertTrue("Filter order differs from web", a.left < b.left) }
        val actions = listOf("메신저", "위치보기", "상담", "카카오톡")
        actions.map { compose.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }.zipWithNext().forEach { (a, b) -> assertTrue(a.left < b.left) }
        capture("black-board")
        compose.runOnUiThread { FleetAppearance.select(compose.activity, false) }
        capture("white-board")
    }
    @Test fun workflowScreensRenderWithoutDuplicateNavigationOrClippedFooters() {
        val titles = mapOf("login" to "차량 현황판", "chat" to "💬 직원 메신저", "schedule" to "📅 일정 관리", "vehicle" to "예시1234",
            "location" to "📍 직원 위치", "staff" to "👥 직원 관리", "registration" to "차량 추가", "settings" to "회사 설정")
        for ((screen, title) in titles) {
            compose.runOnUiThread { compose.activity.screen = screen }
            compose.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
            if (screen == "location") {
                capture("location-loading")
                try { compose.waitUntil(30_000) { compose.onAllNodesWithText("근처", substring = true).fetchSemanticsNodes().isNotEmpty() } }
                catch (failure: AssertionError) { capture("location-failure"); throw failure }
                compose.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
            }
            if (screen in setOf("chat", "schedule", "location", "staff")) compose.onAllNodesWithText("닫기").assertCountEquals(1).onFirst().assertIsDisplayed()
            capture(screen)
        }
        for (screen in listOf("payments", "documents", "inquiries")) {
            compose.runOnUiThread { compose.activity.screen = screen }
            capture(screen)
            try { compose.onNodeWithText("닫기").assertIsDisplayed() } catch (failure: AssertionError) { throw AssertionError("$screen footer is clipped", failure) }
        }
    }
    @Test fun documentTabsKeepOriginalCustomerSectionsAndLabels() {
        compose.runOnUiThread { compose.activity.screen = "documents" }
        compose.onNodeWithText("고객 정보").assertIsDisplayed()
        compose.onNodeWithText("생년월일 / 사업자번호").assertExists()
        for (title in listOf("거래명세서", "장기 견적서", "신차 렌트 견적서", "계약서", "영수증")) {
            compose.onNodeWithText(title, substring = false).performClick()
            compose.onNodeWithText("닫기").assertIsDisplayed()
            capture("document-" + title)
        }
    }
    @Test fun nativeFusedGpsReceivesInjectedJangseongLocation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("System GPS is disabled", FleetLocation.enabled(context))
        assertTrue("OS permission is missing", FleetLocation.permission(context))
        val client = LocationServices.getFusedLocationProviderClient(context)
        val latch = CountDownLatch(1)
        var fix: android.location.Location? = null
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) { result.lastLocation?.let { fix = it; latch.countDown() } }
        }
        try {
            client.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build(), callback, Looper.getMainLooper())
            assertTrue("Native fused GPS did not receive the emulator coordinate", latch.await(60, TimeUnit.SECONDS))
            assertEquals(35.3016, fix!!.latitude, .01)
            assertEquals(126.7867, fix!!.longitude, .01)
        } finally { client.removeLocationUpdates(callback) }
    }
}
