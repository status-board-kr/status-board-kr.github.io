package kr.statusboard.nativeapp

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.*
import android.print.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import kr.statusboard.core.FleetDocumentCalculation
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Native Canvas/PDF renderer. No WebView, browser engine or remote document upload. */
object FleetDocumentPdf {
    fun create(context: Context, schema: JSONObject, tab: JSONObject, record: JSONObject, company: String, stillAllowed: () -> Boolean = { true }): File {
        val pdf = PdfDocument(); val fields = record.optJSONObject("fields") ?: JSONObject()
        val radios = record.optJSONObject("radios") ?: JSONObject(); val prefix = tab.getString("prefix")
        val assets = JSONObject(context.assets.open("documents-brand.json").bufferedReader().use { it.readText() })
        fun image(key: String): Bitmap? = runCatching { val data = Base64.decode(assets.getString(key).substringAfter(','), Base64.DEFAULT); BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull()
        val logo = image("logoImg"); val stamp = image("stampImg")
        var page: PdfDocument.Page? = null; var y = 0f; var count = 0
        fun start() {
            page?.let(pdf::finishPage)
            page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, ++count).create()); y = 42f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(3, 105, 161); textSize = 22f; typeface = Typeface.DEFAULT_BOLD }
            page!!.canvas.drawText(tab.getString("label"), 36f, y, paint); y += 20f
            paint.textSize = 11f; paint.color = Color.DKGRAY; page!!.canvas.drawText(company, 36f, y, paint)
            logo?.let { page!!.canvas.drawBitmap(it, null, RectF(469f, 27f, 559f, 64f), null) }
            y += 13f; paint.color = Color.rgb(3, 105, 161); paint.strokeWidth = 2f; page!!.canvas.drawLine(36f, y, 559f, y, paint); y += 12f
            paint.textSize = 9f; paint.color = Color.GRAY; page!!.canvas.drawText("$count", 548f, 822f, paint)
        }
        fun paragraph(text: String, bold: Boolean = false, size: Float = 11f) {
            val masked = FleetDocumentCalculation.maskIdentity(text)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 41, 59); textSize = size; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
            masked.split('\n').forEach { line ->
                val value = line.ifBlank { " " }
                val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, 523).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(3f, 1f).build()
                for (index in 0 until layout.lineCount) {
                    val top = layout.getLineTop(index); val bottom = layout.getLineBottom(index)
                    if (y + bottom - top > 787) start()
                    val canvas = page!!.canvas
                    canvas.save(); canvas.clipRect(36f, y, 559f, y + bottom - top); canvas.translate(36f, y - top)
                    layout.draw(canvas); canvas.restore(); y += bottom - top
                }
                y += 6f
            }
        }
        start()
        val kind = radios.optString(if (prefix == "c") "c_id_type" else "${prefix}_birth_type")
        val list = tab.getJSONArray("fields")
        for (index in 0 until list.length()) {
            val field = list.getJSONObject(index); val id = field.getString("id"); val raw = fields.optString(id)
            if (raw.isBlank()) continue
            if (id.endsWith("_birth") && kind == "biz") continue
            if ((id.endsWith("_biznum") || id.endsWith("_corpnum") || id.endsWith("_ceo")) && kind != "biz") continue
            val options = field.optJSONArray("options"); var value = raw
            if (options != null) for (option in 0 until options.length()) {
                if (options.getJSONObject(option).optString("value") == raw) value = options.getJSONObject(option).optString("label")
            }
            paragraph("${field.getString("label").replace("*", "").trim()}  |  $value", id.endsWith("_total") || id.endsWith("_price"))
        }
        record.optJSONArray("drivers")?.let { drivers -> for (index in 0 until drivers.length()) {
            val driver = drivers.getJSONObject(index)
            paragraph("운전자 ${index + 1}  |  ${driver.optString("name")} · ${driver.optString("phone")}\n면허번호 ${driver.optString("license")} · 주민번호 ${driver.optString("birth")}")
        } }
        record.optJSONArray("items")?.let { items -> for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            paragraph("품목 ${index + 1}  |  ${item.optString("name")}\n수량 ${item.optString("qty")} × 단가 ${item.optString("unit")}원 = ${item.optString("amt")}")
        } }
        if (tab.getString("key") == "contract") {
            paragraph("임대인 (갑): $company\n임차인 (을): ${fields.optString("c_name")}  서명: __________________\n서명일: ____년 ____월 ____일", true)
            stamp?.let { if (y + 64 > 787) start(); page!!.canvas.drawBitmap(it, null, RectF(460f, y, 518f, y + 58), null); y += 66 }
            val terms = schema.getJSONObject("terms")
            val limit = fields.optString("c_mileage_limit")
            val definition = (0 until list.length()).map(list::getJSONObject).first { it.getString("id") == "c_mileage_limit" }.getJSONArray("options")
            val km = (0 until definition.length()).map(definition::getJSONObject).firstOrNull { it.optString("value") == limit }?.optString("label")?.substringBefore(" (").orEmpty()
            listOf("special", "privacy", "rental").forEach { key -> start(); paragraph(terms.getString(key).replace("{{km}}", km), size = 10f) }
        } else paragraph(if (tab.getString("key") == "receipt") "위 금액을 수령하였습니다." else "본 견적서는 영수증으로 사용할 수 없습니다.", true)
        page?.let(pdf::finishPage)
        val directory = File(context.cacheDir, "documents").apply { mkdirs() }
        val file = File(directory, "document-${UUID.randomUUID()}.pdf")
        try { file.outputStream().use(pdf::writeTo) } finally { pdf.close(); logo?.recycle(); stamp?.recycle() }
        if (!stillAllowed()) { file.delete(); error("문서 접근 권한이 변경되었습니다.") }
        return file
    }
    fun print(context: Context, file: File, title: String) {
        val total = pages(file)
        context.getSystemService(PrintManager::class.java).print(title, object : PrintDocumentAdapter() {
            override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
                if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
                callback.onLayoutFinished(PrintDocumentInfo.Builder("$title.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(total).build(), true)
            }
            override fun onWrite(pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?, callback: WriteResultCallback) {
                val ranges = pages ?: arrayOf(PageRange.ALL_PAGES)
                Thread({
                    val main = Handler(Looper.getMainLooper())
                    try {
                        val selected = (0 until total).filter { index -> ranges.any { index in it.start..it.end } }
                        val result = PdfDocument()
                        try {
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { renderer ->
                                selected.forEach { index ->
                                    if (cancellationSignal?.isCanceled == true) throw java.util.concurrent.CancellationException()
                                    renderer.openPage(index).use { source ->
                                        val bitmap = Bitmap.createBitmap(source.width * 2, source.height * 2, Bitmap.Config.ARGB_8888)
                                        try {
                                            bitmap.eraseColor(Color.WHITE); source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                            val target = result.startPage(PdfDocument.PageInfo.Builder(source.width, source.height, index + 1).create())
                                            target.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()), null); result.finishPage(target)
                                        } finally { bitmap.recycle() }
                                    }
                                }
                            } }
                            if (cancellationSignal?.isCanceled == true) throw java.util.concurrent.CancellationException()
                            java.io.FileOutputStream(destination.fileDescriptor).use(result::writeTo)
                            main.post { callback.onWriteFinished(selected.map { PageRange(it, it) }.toTypedArray()) }
                        } finally { result.close() }
                    } catch (_: java.util.concurrent.CancellationException) { main.post { callback.onWriteCancelled() } }
                    catch (_: Exception) { main.post { callback.onWriteFailed("문서를 인쇄하지 못했습니다.") } }
                }, "fleet-document-print").start()
            }
        }, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
    }
    fun page(file: File, index: Int): Bitmap = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer -> renderer.openPage(index).use { page ->
            Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE); page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        } }
    }
    fun pages(file: File): Int = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).use { renderer -> renderer.pageCount } }
    fun images(file: File, stillAllowed: () -> Boolean): List<File> {
        val output = mutableListOf<File>()
        try {
            for (index in 0 until pages(file)) {
                check(stillAllowed()) { "문서 접근 권한이 변경되었습니다." }
                val image = page(file, index)
                val target = File(file.parentFile, "document-${UUID.randomUUID()}-${index + 1}.jpg")
                output += target
                try { target.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }
                finally { image.recycle() }
            }
            check(stillAllowed()) { "문서 접근 권한이 변경되었습니다." }
            return output
        } catch (error: Exception) { output.forEach { it.delete() }; throw error }
    }
}
