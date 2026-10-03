package kr.statusboard.core

import java.time.Instant

enum class BotConnection(val label: String) { OFFLINE("미연결"), CONNECTED("연결됨"), LOGIN_FAILED("로그인 실패") }
object FleetBotHealth {
    fun state(lastSeen: String?, login: String?, now: Instant): BotConnection {
        val seen = try { Instant.parse(lastSeen) } catch (_: Exception) { return BotConnection.OFFLINE }
        val age = now.toEpochMilli() - seen.toEpochMilli()
        if (age < -5000 || age >= 45000) return BotConnection.OFFLINE
        return if (login == "ok") BotConnection.CONNECTED else BotConnection.LOGIN_FAILED
    }
}
