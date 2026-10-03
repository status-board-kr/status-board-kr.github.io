package kr.statusboard.core

object FleetImport {
    val classes = listOf("경형", "소형", "준중형", "중형", "대형", "승합", "화물", "수입", "전기")
    private val fuels = listOf("가솔린", "휘발유", "디젤", "경유", "LPG", "엘피지", "전기", "하이브리드")
    fun plate(text: String): String? = Regex("(?<![0-9])[가-힣]{0,4}[0-9]{2,3}[가-힣][0-9]{4}(?![0-9])").find(text.replace(Regex("\\s+"), ""))?.value
    fun fuel(text: String): String = fuels.firstOrNull { text.contains(it, true) }?.let { when (it) { "휘발유" -> "가솔린"; "경유" -> "디젤"; "엘피지" -> "LPG"; else -> it } }.orEmpty()
    fun csv(text: String): List<List<String>> {
        require(text.length <= 2_000_000) { "파일은 2MB 이내로 나눠주세요." }
        val rows = mutableListOf<List<String>>(); val row = mutableListOf<String>(); val cell = StringBuilder()
        var quote = false; var index = 0
        fun nextCell() { row += cell.toString().trim().trimStart('\uFEFF'); cell.setLength(0); require(row.size <= 200) { "열이 너무 많습니다." } }
        fun nextRow() { nextCell(); if (row.any(String::isNotBlank)) rows += row.toList(); row.clear(); require(rows.size <= 2000) { "행이 너무 많습니다." } }
        while (index < text.length) {
            val char = text[index]
            if (char == '"') {
                if (quote && index + 1 < text.length && text[index + 1] == '"') { cell.append('"'); index++ }
                else quote = !quote
            } else if (char == ',' && !quote) nextCell()
            else if ((char == '\n' || char == '\r') && !quote) { nextRow(); if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++ }
            else cell.append(char)
            index++
        }
        require(!quote) { "CSV의 따옴표가 닫히지 않았습니다." }
        if (cell.isNotEmpty() || row.isNotEmpty()) nextRow()
        return rows
    }
    fun vehicles(rows: List<List<String>>): List<Map<String, String>> {
        val aliases = mapOf("plate" to listOf("차량번호", "차번호", "등록번호", "번호판", "plate"), "model" to listOf("차종", "모델", "차명", "model"),
            "cls" to listOf("종별", "차급", "구분", "cls"), "fuel" to listOf("연료", "fuel"), "regDate" to listOf("최초등록일", "등록일", "regdate"))
        val headerIndex = rows.indexOfFirst { row -> row.any { cell -> aliases.getValue("plate").any { cell.replace(" ", "").equals(it, true) } } }
        val header = rows.getOrNull(headerIndex).orEmpty()
        val columns = aliases.mapValues { (_, names) -> header.indexOfFirst { cell -> names.any { cell.replace(" ", "").equals(it, true) } } }
        return rows.drop(if (headerIndex >= 0) headerIndex + 1 else 0).mapNotNull { row ->
            val joined = row.joinToString(" "); val number = row.firstNotNullOfOrNull(::plate) ?: return@mapNotNull null
            fun column(key: String) = row.getOrNull(columns[key] ?: -1).orEmpty().trim()
            val model = column("model").ifBlank { row.firstOrNull { it.isNotBlank() && it.length <= 30 && plate(it) == null && it !in classes && fuel(it) != it && Regex("[가-힣A-Za-z]").containsMatchIn(it) }.orEmpty() }
            mapOf("plate" to number, "model" to model, "cls" to column("cls").ifBlank { classes.firstOrNull(joined::contains).orEmpty() },
                "fuel" to fuel(column("fuel").ifBlank { joined }), "regDate" to column("regDate"))
        }.distinctBy { it["plate"] }.also { require(it.size <= 200) { "한 번에 최대 200대까지 등록할 수 있습니다." } }
    }
}
