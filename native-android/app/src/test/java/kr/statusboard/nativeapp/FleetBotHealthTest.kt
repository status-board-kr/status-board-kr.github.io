package kr.statusboard.nativeapp
import kr.statusboard.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class FleetBotHealthTest {
    @Test fun loginSuccessStillBecomesOfflineAfterHeartbeatExpires() {
        val now = Instant.parse("2026-10-03T02:00:00Z")
        assertEquals(BotConnection.CONNECTED, FleetBotHealth.state(now.minusSeconds(30).toString(), "ok", now))
        assertEquals(BotConnection.OFFLINE, FleetBotHealth.state(now.minusSeconds(45).toString(), "ok", now))
        assertEquals(BotConnection.LOGIN_FAILED, FleetBotHealth.state(now.toString(), "failed", now))
        assertEquals(BotConnection.OFFLINE, FleetBotHealth.state("not-a-date", "ok", now))
        assertEquals(BotConnection.OFFLINE, FleetBotHealth.state(now.plusSeconds(60).toString(), "ok", now))
    }
}
