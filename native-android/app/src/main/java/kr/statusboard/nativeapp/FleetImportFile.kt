package kr.statusboard.nativeapp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import kr.statusboard.core.FleetImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipInputStream

object FleetImportFile {
    private fun java.io.InputStream.bounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (out.size() <= limit) { val n = read(buffer, 0, minOf(buffer.size, limit + 1 - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
        return out.toByteArray()
    }
    suspend fun read(context: Context, uri: Uri): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "" }.orEmpty()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.bounded(10_000_000); require(bytes.size <= 10_000_000) { "파일은 10MB 이내로 나눠주세요." }
            val rows = if (name.endsWith(".xlsx", true) || bytes.take(2) == listOf(80.toByte(), 75.toByte())) xlsx(bytes)
            else {
                val utf8 = bytes.toString(Charsets.UTF_8)
                FleetImport.csv(if ('\uFFFD' in utf8) bytes.toString(java.nio.charset.Charset.forName("MS949")) else utf8)
            }
            FleetImport.vehicles(rows)
        } ?: error("파일을 열 수 없습니다.")
    }
    private fun xlsx(bytes: ByteArray): List<List<String>> {
        val xml = linkedMapOf<String, String>(); var total = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break; require(++count <= 3000) { "파일 항목이 너무 많습니다." }
                if (entry.name == "xl/sharedStrings.xml" || entry.name.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml"))) {
                    val content = zip.bounded(2_000_000); total += content.size
                    require(content.size <= 2_000_000 && total <= 10_000_000) { "엑셀 내용이 너무 큽니다. 나눠서 등록해주세요." }
                    xml[entry.name] = content.toString(Charsets.UTF_8)
                } else {
                    val ignored = zip.bounded(10_000_000 - total); total += ignored.size
                    require(total <= 10_000_000) { "압축 해제한 파일이 너무 큽니다." }
                }
                zip.closeEntry()
            }
        }
        fun parse(text: String, visit: (XmlPullParser) -> Unit) {
            require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "지원하지 않는 XML 문서입니다." }
            val parser = Xml.newPullParser(); parser.setInput(text.reader())
            while (parser.next() != XmlPullParser.END_DOCUMENT) visit(parser)
        }
        val shared = mutableListOf<String>(); var text = StringBuilder(); var inText = false
        parse(xml["xl/sharedStrings.xml"].orEmpty().ifBlank { "<sst/>" }) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) { "si" -> text = StringBuilder(); "t" -> inText = true }
                XmlPullParser.TEXT -> if (inText) text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) { "si" -> shared += text.toString(); "t" -> inText = false }
            }
        }
        val rows = mutableListOf<List<String>>()
        xml.keys.filter { it.startsWith("xl/worksheets/") }.sorted().forEach { file ->
            var row = mutableListOf<String>(); var type = ""; var column = 0; var value = StringBuilder(); var content = false
            parse(xml.getValue(file)) { p ->
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "row" -> row = mutableListOf()
                        "c" -> { type = p.getAttributeValue(null, "t").orEmpty(); value = StringBuilder()
                            val letters = p.getAttributeValue(null, "r").orEmpty().takeWhile(Char::isLetter)
                            column = if (letters.isBlank()) row.size else letters.fold(0) { n, c -> n * 26 + (c.uppercaseChar() - 'A' + 1) } - 1
                            require(column in 0..199) { "엑셀 열이 너무 많습니다." } }
                        "v", "t" -> content = true
                    }
                    XmlPullParser.TEXT -> if (content) value.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name) {
                        "v", "t" -> content = false
                        "c" -> { while (row.size <= column) row += ""; row[column] = if (type == "s") shared.getOrNull(value.toString().toIntOrNull() ?: -1).orEmpty() else value.toString() }
                        "row" -> { if (row.any(String::isNotBlank)) rows += row.toList(); require(rows.size <= 2000) { "행이 너무 많습니다." } }
                    }
                }
            }
        }
        return rows
    }
}
