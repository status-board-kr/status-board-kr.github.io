package kr.statusboard.core

data class PhotoReading(val plates: List<String> = emptyList(), val km: Long? = null, val confident: Boolean = false, val reason: String = "")
data class PhotoRoute(val command: FleetCommand? = null, val chooseVehicle: Boolean = false, val candidates: List<String> = emptyList(), val otherPlates: List<String> = emptyList())
object FleetPhotoRouting {
    private fun normalize(plate: String) = plate.replace(Regex("\\s+"), "")
    fun match(plate: String, vehicles: List<FleetVehicle>): FleetVehicle? {
        val clean = normalize(plate)
        val exact = vehicles.filter { normalize(it.plate) == clean }
        if (exact.size == 1) return exact.single()
        if (!clean.matches(Regex("\\d{4}"))) return null
        return vehicles.filter { it.plate.endsWith(clean) }.singleOrNull()
    }
    fun route(caption: String, reading: PhotoReading, vehicles: List<FleetVehicle>, longBranch: String = "장기", chosenTail: String? = null): PhotoRoute {
        val plates = reading.plates.map(::normalize).distinct()
        val ours = plates.mapNotNull { match(it, vehicles) }.distinctBy { it.plate }
        val others = plates.filter { match(it, vehicles) == null }
        val commandWords = listOf("회수", "ㅎㅅ", "조완", "ㅈㅇ", "조치완료", "차고지", "입고", "보험", "서비스", "일반", longBranch, "준비중", "준비", "대기", "대기중", "운행", "운행중")
        val looksLikeCommand = caption.split(Regex("\\s+")).any { it in commandWords }
        var cmd = FleetCommands.parse(caption, longBranch)
        if (cmd == null && looksLikeCommand) {
            val tail = chosenTail ?: if (ours.size == 1 && reading.confident) ours.single().plate.takeLast(4) else null
            if (tail == null) return PhotoRoute(chooseVehicle = true, candidates = ours.map { it.plate }, otherPlates = others)
            cmd = FleetCommands.parse("$tail $caption", longBranch)
        }
        cmd ?: return PhotoRoute(otherPlates = others)
        FleetCommands.select(vehicles, cmd.plateToken)
        return PhotoRoute(command = cmd.copy(otherPlates = others, returnKm = if (cmd.recall && reading.confident) reading.km else null), otherPlates = others)
    }
}
