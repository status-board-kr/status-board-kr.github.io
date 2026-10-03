package kr.statusboard.nativeapp

import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kr.statusboard.core.FleetDocumentCalculation as Calc
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

@Composable internal fun FleetDocuments(state: FleetUiState, model: FleetViewModel, close: () -> Unit) {
    val context = LocalContext.current; val schema = remember { FleetDocumentSchema.load(context) }
    val tabs = schema.getJSONArray("tabs").let { list -> (0 until list.length()).map(list::getJSONObject) }
    var editor by remember { mutableStateOf<JSONObject?>(null) }; var record by remember { mutableStateOf<JSONObject?>(null) }; var key by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }; var removing by remember { mutableStateOf<String?>(null) }
    var ratesOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.session?.cacheKey) { model.loadDocuments() }
    DisposableEffect(Unit) { onDispose { model.clearDocuments() } }
    WebSheet(close) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("견적·계약서", fontSize = 18.sp)
            TextButton(onClick = { ratesOpen = true }, enabled = state.session?.isAdmin == true && !state.cached) { Text("신차 렌트 계산 기준 · 가격표") }
            tabs.chunked(2).forEach { row -> Row { row.forEach { tab ->
                OutlinedButton(onClick = { editor = tab; record = null; key = model.newDocumentKey() }, enabled = state.session?.isAdmin == true && !state.sending && !state.cached, modifier = Modifier.weight(1f).padding(3.dp)) { Text(tab.getString("label"), fontSize = 11.sp) }
            } } }
            Text("저장된 문서", modifier = Modifier.padding(top = 18.dp))
            WebField("고객명 · 차량 · 연락처 검색", query, { query = it }, true)
            val data = state.documents
            if (data == null) Text("불러오는 중…", color = WebSub)
            else data.keys().asSequence().filter { !it.startsWith("_") }.mapNotNull { id -> data.optJSONObject(id)?.let { id to it } }
                .filter { (_, doc) -> query.isBlank() || "${doc.optString("name")} ${doc.optString("car")} ${doc.optString("phone")}".contains(query, true) }
                .sortedByDescending { it.second.optString("updatedAt") }.forEach { (id, doc) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Column(Modifier.weight(1f)) { Text("${doc.optString("name")} · ${doc.optString("car")}", fontSize = 13.sp); Text("${tabs.firstOrNull { it.getString("key") == doc.optString("type") }?.optString("label").orEmpty()} · ${doc.optString("date")} · ${doc.optString("amount")}", color = WebSub, fontSize = 10.sp) }
                        TextButton(onClick = { tabs.firstOrNull { it.getString("key") == doc.optString("type") }?.let { editor = it; record = doc; key = id } }) { Text("열기", fontSize = 11.sp) }
                        TextButton(onClick = { removing = id }, enabled = !state.sending && !state.cached) { Text("삭제", fontSize = 11.sp) }
                    }
                }
            if (state.message.isNotBlank()) Text(state.message, color = WebSub, fontSize = 12.sp)
        }
        OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("닫기") }
    }
    if (ratesOpen) FleetDocumentRates(state, model, schema) { ratesOpen = false }
    editor?.let { tab -> key?.let { id -> FleetDocumentEditor(state, model, schema, tab, record, id, {
        editor = null; model.loadDocuments()
    }, { draft ->
        val contract = tabs.first { it.getString("key") == "contract" }
        val oldPrefix = tab.getString("prefix"); val from = draft.optJSONObject("fields") ?: JSONObject()
        val fields = JSONObject()
        listOf("name", "phone", "birth", "biznum", "corpnum", "ceo", "addr", "addr2", "model", "carnum", "year", "color", "mileage", "fuel", "grade", "age", "mileage_limit", "own", "maint", "start", "end", "period", "price", "discount", "total", "special").forEach { field ->
            val value = from.optString("${oldPrefix}_$field"); if (value.isNotBlank()) fields.put("c_$field", value)
        }
        if (tab.getString("key") == "newcar") fields.put("c_price", from.optString("n_p1_price"))
        fields.put("c_special", from.optString("${oldPrefix}_special").ifBlank { from.optString("${oldPrefix}_note") })
        record = JSONObject().put("fields", fields).put("radios", JSONObject().put("c_id_type", draft.optJSONObject("radios")?.optString("${oldPrefix}_birth_type", "birth")?.let { if (it == "biz") "biz" else "rrn" }))
        key = model.newDocumentKey(); editor = contract
    }) } }
    removing?.let { id -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("문서 삭제") }, text = { Text("선택한 저장 문서를 삭제할까요? 삭제한 문서는 복원할 수 없습니다.") },
        confirmButton = { TextButton(onClick = { model.deleteDocument(id); removing = null }) { Text("삭제") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("취소") } }) }
}

@Composable private fun FleetDocumentEditor(state: FleetUiState, model: FleetViewModel, schema: JSONObject, tab: JSONObject, original: JSONObject?, key: String, close: () -> Unit, toContract: (JSONObject) -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val type = tab.getString("key"); val prefix = tab.getString("prefix")
    val fields = remember(key) { mutableStateMapOf<String, String>().apply { putAll(FleetDocumentSchema.defaults(tab)); original?.optJSONObject("fields")?.let { values -> values.keys().forEach { put(it, values.optString(it)) } } } }
    val radios = remember(key) { mutableStateMapOf<String, String>().apply {
        val options = tab.getJSONObject("radios"); options.keys().forEach { name -> val list = options.getJSONArray(name); if (list.length() > 0) put(name, (0 until list.length()).map(list::getJSONObject).firstOrNull { it.optBoolean("checked") }?.optString("value") ?: list.getJSONObject(0).optString("value")) }
        original?.optJSONObject("radios")?.let { values -> values.keys().forEach { put(it, values.optString(it)) } }
    } }
    fun rows(source: JSONArray?, defaults: Map<String, String>) = (0 until (source?.length() ?: 0)).map { source!!.getJSONObject(it) }.map { value -> mutableStateMapOf<String, String>().apply { defaults.keys.forEach { put(it, value.optString(it)) } } }
    val drivers = remember(key) { mutableStateListOf<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>>().apply { addAll(rows(original?.optJSONArray("drivers"), mapOf("name" to "", "phone" to "", "license" to "", "birth" to ""))) } }
    val items = remember(key) { mutableStateListOf<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>>().apply { addAll(rows(original?.optJSONArray("items"), mapOf("name" to "", "qty" to "1", "unit" to "", "amt" to ""))) } }
    var version by remember(key) { mutableStateOf(original?.optString("updatedAt")?.takeIf(String::isNotBlank)) }
    var request by remember(key) { mutableStateOf(UUID.randomUUID().toString()) }; var lastRequestFields by remember(key) { mutableStateOf("") }
    var error by remember(key) { mutableStateOf("") }; var pdf by remember(key) { mutableStateOf<File?>(null) }; var rendering by remember { mutableStateOf(false) }
    val definitions = tab.getJSONArray("fields").let { list -> (0 until list.length()).map(list::getJSONObject) }
    val rates = jsonMap(schema.getJSONObject("newcarRates")) + jsonMap(state.documents?.optJSONObject("_newcarRates"))
    LaunchedEffect(key) { if (type == "newcar" && original == null) listOf("d2", "d3").forEachIndexed { index, value ->
        fields["n_p${index + 2}_deposit"] = rates[value]?.toString()?.takeUnless { Calc.number(it) == 0.0 }.orEmpty()
    } }
    val editable = !state.sending && !state.cached && !rendering && state.session?.isAdmin == true
    fun change(id: String, value: String) {
        fields[id] = value; error = ""
        runCatching { FleetDocumentSchema.calculated(type, fields.toMap(), id, schema, rates) }.onSuccess { fields.putAll(it) }.onFailure { error = it.message.orEmpty() }
        if (type == "statement" && id == "st_tax_type") {
            val sum = items.sumOf { Calc.number(it["amt"]) }
            fields["st_total"] = Calc.won(sum * if (value == "add") 1.1 else 1.0) + "원"
        }
    }
    fun record(): JSONObject {
        val result = JSONObject().put("type", type).put("fields", JSONObject(fields.toMap())).put("radios", JSONObject(radios.toMap()))
        if (type == "contract") result.put("drivers", JSONArray(drivers.map { JSONObject(it.toMap()) }))
        if (type == "statement") result.put("items", JSONArray(items.map { JSONObject(it.toMap()) }))
        result.put("name", fields["${prefix}_name"].orEmpty()).put("phone", fields["${prefix}_phone"].orEmpty())
            .put("car", "${fields["${prefix}_model"].orEmpty()} ${fields["${prefix}_carnum"].orEmpty()}".trim())
            .put("amount", fields["${prefix}_total"].orEmpty().ifBlank { fields["${prefix}_price"].orEmpty().ifBlank { fields["n_p1_price"].orEmpty() } })
            .put("date", fields["${prefix}_date"].orEmpty().ifBlank { fields["${prefix}_start"].orEmpty() })
        return result
    }
    @Composable fun driverRows() {
                TextButton(onClick = { drivers += mutableStateMapOf("name" to "", "phone" to "", "license" to "", "birth" to "") }, enabled = editable && drivers.size < 10) { Text("+ 운전자 추가") }
                drivers.toList().forEachIndexed { index, driver ->
                    Text("운전자 ${index + 1}", modifier = Modifier.padding(top = 14.dp))
                    listOf("name" to "이름", "phone" to "연락처", "license" to "면허번호", "birth" to "주민번호").forEach { (field, title) -> WebField(title, driver[field].orEmpty(), { driver[field] = it }, editable) }
                    TextButton(onClick = { drivers.remove(driver) }, enabled = editable) { Text("운전자 삭제") }
                }
            }
    @Composable fun itemRows() {
                TextButton(onClick = { items += mutableStateMapOf("name" to "", "qty" to "1", "unit" to "", "amt" to "") }, enabled = editable && items.size < 100) { Text("+ 품목 추가") }
                items.toList().forEachIndexed { index, item ->
                    Text("품목 ${index + 1}", modifier = Modifier.padding(top = 14.dp))
                    listOf("name" to "품목명", "qty" to "수량", "unit" to "단가 (원)").forEach { (field, title) -> WebField(title, item[field].orEmpty(), { value ->
                        item[field] = value; item["amt"] = Calc.won(Calc.number(item["qty"]) * Calc.number(item["unit"])) + "원"
                        val sum = items.sumOf { Calc.number(it["amt"]) }; fields["st_total"] = Calc.won(sum * if (fields["st_tax_type"] == "add") 1.1 else 1.0) + "원"
                    }, editable) }
                    Text(item["amt"].orEmpty(), color = WebSub, fontSize = 12.sp)
                    TextButton(onClick = { items.remove(item); val sum = items.sumOf { Calc.number(it["amt"]) }; fields["st_total"] = Calc.won(sum * if (fields["st_tax_type"] == "add") 1.1 else 1.0) + "원" }, enabled = editable) { Text("품목 삭제") }
                }
            }
    WebSheet(close) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(tab.getString("label"), fontSize = 18.sp)
            val radioDefinitions = tab.getJSONObject("radios")
            radioDefinitions.keys().forEach { name ->
                Row { val options = radioDefinitions.getJSONArray(name); (0 until options.length()).forEach { index ->
                    val option = options.getJSONObject(index); val value = option.getString("value")
                    TextButton(onClick = { radios[name] = value }, enabled = editable) { Text("${if (radios[name] == value) "✓ " else ""}${option.getString("label")}", fontSize = 12.sp) }
                } }
            }
            val business = radios[if (prefix == "c") "c_id_type" else "${prefix}_birth_type"] == "biz"
            definitions.filter { field ->
                val id = field.getString("id")
                !(id.endsWith("_birth") && business) && !((id.endsWith("_biznum") || id.endsWith("_corpnum") || id.endsWith("_ceo")) && !business)
            }.forEach { field ->
                val id = field.getString("id"); val label = field.getString("label"); val options = field.getJSONArray("options")
                if (id == "c_model") driverRows()
                if (id == "st_tax_type") itemRows()
                if (id == "n_maker") {
                    var catalog by remember { mutableStateOf(false) }
                    val cars = rates["cars"]?.toString().orEmpty().lines().mapNotNull { line ->
                        val parts = line.trim().split(Regex("\\s+")); if (parts.size < 5) null else {
                            val price = parts.last().replace(",", "").toDoubleOrNull()?.times(10000) ?: 0.0
                            if (price !in 5_000_000.0..300_000_000.0) null else parts to price
                        }
                    }
                    Box { OutlinedButton(onClick = { catalog = true }, enabled = editable) { Text("가격표에서 고르기") }
                        DropdownMenu(catalog, { catalog = false }) { cars.forEach { (parts, price) -> DropdownMenuItem(text = { Text("${parts.dropLast(1).joinToString(" ")} · ${Calc.won(price / 10000)}만원") }, onClick = {
                            fields["n_maker"] = parts[0]; fields["n_model"] = parts[1] + when {
                                parts[2] == "하이브리드" -> " 하이브리드"
                                parts[2] == "전기" && !Regex("^(ev|아이오닉)", RegexOption.IGNORE_CASE).containsMatchIn(parts[1]) -> if (parts[1] == "레이") " EV" else " 일렉트릭"
                                else -> ""
                            }
                            fields["n_fuel"] = parts[2]; fields["n_trim"] = parts.subList(3, parts.lastIndex).joinToString(" ")
                            change("n_carprice", Calc.won(price)); catalog = false
                        }) } }
                    }
                }
                if (options.length() > 0) {
                    var expanded by remember(key, id) { mutableStateOf(false) }
                    val selected = (0 until options.length()).map(options::getJSONObject).firstOrNull { it.optString("value") == fields[id] }
                    Text(label, color = WebSub, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                    Box { OutlinedButton(onClick = { expanded = true }, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text(selected?.optString("label") ?: fields[id].orEmpty(), fontSize = 12.sp) }
                        DropdownMenu(expanded, { expanded = false }) { (0 until options.length()).forEach { index -> val option = options.getJSONObject(index); DropdownMenuItem(text = { Text(option.getString("label")) }, onClick = { change(id, option.getString("value")); expanded = false }) } }
                    }
                } else WebField(label, fields[id].orEmpty(), { change(id, it) }, editable && !field.optBoolean("readonly"))
                if (type == "newcar" && id.matches(Regex("n_p[123]_price"))) {
                    val option = id.substring(3, 4); val base = "n_p${option}_"
                    if (Calc.number(fields["n_carprice"]) > 0 && Calc.number(fields["n_period"]) > 0) {
                        val cost = runCatching { Calc.newcarCost(Calc.number(fields["n_carprice"]), Calc.number(fields["n_period"]), Calc.number(fields[base + "deposit"]), Calc.number(fields[base + "prepay"]), fields["n_age"] == "21", rates) }.getOrNull()
                        if (cost != null && fields[id]?.isNotBlank() == true) Text("원가 ${Calc.won(cost)}원 · 마진 ${Calc.won(Calc.number(fields[id]) - cost)}원${if (Calc.number(fields[id]) < cost) " · 원가보다 낮습니다" else ""}", color = WebSub, fontSize = 11.sp)
                    }
                    if (fields[base + "manual"] == "1") TextButton(onClick = { fields[base + "manual"] = ""; change("n_period", fields["n_period"].orEmpty()) }, enabled = editable) { Text("직접 입력 → 자동 계산으로") }
                }
            }
            if (type in setOf("simple", "quote", "newcar")) TextButton(onClick = { toContract(record()) }, enabled = editable) { Text("이 견적으로 계약서 만들기") }
            if (error.isNotBlank()) Text(error, color = WebSub, fontSize = 12.sp)
            if (state.message.isNotBlank()) Text(state.message, color = WebSub, fontSize = 12.sp)
            Text("문서 미리보기·PDF에서 주민번호 뒷자리는 가려집니다.", color = WebSub, fontSize = 11.sp)
        }
        Row(Modifier.padding(16.dp)) { OutlinedButton(onClick = close, modifier = Modifier.weight(1f)) { Text("닫기") }
            Button(onClick = {
                val required = when (type) {
                    "contract" -> listOf("c_name", "c_phone", "c_model", "c_price", "c_start", "c_end")
                    "quote" -> listOf("q_name", "q_phone", "q_model", "q_price")
                    "newcar" -> listOf("n_name", "n_phone", "n_model", "n_carprice")
                    "receipt" -> listOf("r_name", "r_price")
                    else -> listOf("${prefix}_name")
                }
                if (required.any { fields[it].isNullOrBlank() }) error = "필수 항목을 입력해주세요."
                else if (type == "statement" && items.isEmpty()) error = "품목을 1개 이상 입력해주세요."
                else {
                    val draft = record(); val body = draft.toString()
                    if (lastRequestFields != body) { request = UUID.randomUUID().toString(); lastRequestFields = body }
                    model.saveDocument(key, request, jsonMap(draft), version) { saved -> if (saved) {
                        rendering = true
                        scope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) { FleetDocumentPdf.create(context, schema, tab, draft, state.companyName) { model.state.value.session?.cacheKey == state.session?.cacheKey && model.state.value.session?.isAdmin == true } }
                                if (model.state.value.session?.cacheKey == state.session?.cacheKey && model.state.value.session?.isAdmin == true) { pdf = file; model.loadDocuments() } else file.delete()
                            }
                            catch (failure: Exception) { if (failure is CancellationException) throw failure; error = "저장했지만 PDF를 만들지 못했습니다. 다시 시도해주세요." }
                            finally { rendering = false }
                        }
                    } }
                }
            }, enabled = editable, modifier = Modifier.weight(1f)) { Text(if (rendering) "문서 만드는 중…" else "저장 · 미리보기") } }
    }
    LaunchedEffect(state.documents?.optJSONObject(key)?.optString("updatedAt")) {
        val saved = state.documents?.optJSONObject(key)
        if (saved?.optString("_nativeSaveId") == request) version = saved.optString("updatedAt").takeIf(String::isNotBlank)
    }
    pdf?.let { file -> FleetDocumentPreview(file, tab.getString("label"), { model.state.value.session?.cacheKey == state.session?.cacheKey && model.state.value.session?.isAdmin == true }) { pdf = null } }
}

@Composable private fun FleetDocumentPreview(file: File, title: String, allowed: () -> Boolean, close: () -> Unit) {
    val context = LocalContext.current; var pages by remember(file) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope(); var exporting by remember(file) { mutableStateOf(false) }
    var error by remember(file) { mutableStateOf("") }
    LaunchedEffect(file) { try { pages = withContext(Dispatchers.IO) { FleetDocumentPdf.pages(file) } } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = "문서 미리보기를 열지 못했습니다." } }
    WebSheet(close) {
        Row(Modifier.padding(12.dp)) { Text(title, modifier = Modifier.weight(1f)); TextButton(onClick = { FleetDocumentPdf.print(context, file, title) }) { Text("인쇄") }
            TextButton(onClick = {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "PDF 공유"))
            }) { Text("PDF 공유") }
        }
        TextButton(onClick = {
            if (!exporting) { exporting = true; scope.launch {
                try {
                    val images = withContext(Dispatchers.IO) { FleetDocumentPdf.images(file, allowed) }
                    if (allowed()) {
                        val uris = ArrayList(images.map { FileProvider.getUriForFile(context, "${context.packageName}.photos", it) })
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "JPG 저장 · 공유"))
                    } else images.forEach { it.delete() }
                } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = "JPG를 만들지 못했습니다. 다시 시도해주세요." }
                finally { exporting = false }
            } }
        }, enabled = !exporting) { Text(if (exporting) "이미지 만드는 중…" else "JPG 저장 · 공유") }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (error.isNotBlank()) item { Text(error, color = WebSub) }
            items((0 until pages).toList(), key = { it }) { index ->
                var image by remember(file, index) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(file, index) { try { image = withContext(Dispatchers.IO) { FleetDocumentPdf.page(file, index) } } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = "${index + 1}쪽을 표시하지 못했습니다." } }
                image?.let { Image(it.asImageBitmap(), "${index + 1}쪽", modifier = Modifier.fillMaxWidth().padding(8.dp)) }
            }
        }
        OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("닫기") }
    }
}
