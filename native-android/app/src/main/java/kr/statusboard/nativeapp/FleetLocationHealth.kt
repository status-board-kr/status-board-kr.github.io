package kr.statusboard.nativeapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kr.statusboard.core.FleetLocationReason

internal object FleetLocationHealth {
    var owner by mutableStateOf("")
        private set
    var message by mutableStateOf("")
        private set
    fun report(key: String, text: String) { owner = key; message = text }
    fun label(reason: FleetLocationReason): String = when (reason) {
        FleetLocationReason.ADMIN -> "관리자는 위치 수집 대상이 아닙니다. 직원 위치는 아래에서 확인할 수 있습니다."
        FleetLocationReason.WEEKEND -> "토요일·일요일에는 위치를 공유하지 않습니다."
        FleetLocationReason.HOLIDAY -> "공휴일에는 위치를 공유하지 않습니다."
        FleetLocationReason.OUTSIDE_HOURS -> "근무시간 밖이라 위치 공유가 중지되어 있습니다."
        FleetLocationReason.UNKNOWN_CALENDAR -> "공휴일 정보가 없는 연도라 위치 공유를 중지했습니다."
        FleetLocationReason.INVALID_HOURS -> "회사 설정의 근무시간을 확인해주세요."
        FleetLocationReason.CONSENT -> "위치 공유에 동의하면 근무시간에 공유가 시작됩니다."
        FleetLocationReason.PERMISSION -> "휴대전화의 위치 권한을 허용해주세요."
        FleetLocationReason.ACTIVE -> "위치 공유 시작을 확인하는 중입니다."
    }
}
