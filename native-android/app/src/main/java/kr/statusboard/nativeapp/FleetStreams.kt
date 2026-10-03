package kr.statusboard.nativeapp

import com.google.firebase.database.*
import kr.statusboard.core.FleetSession
import org.json.JSONArray
import org.json.JSONObject

/** Attach only after server membership verification; remove every listener on logout/company change. */
class FleetStreams {
    private val active = mutableListOf<Pair<Query, ValueEventListener>>()
    fun bind(session: FleetSession, receive: (String, Any?) -> Unit, denied: () -> Unit) {
        close()
        val root = FirebaseDatabase.getInstance().getReference(session.path(""))
        listOf("vehicles", "schedules", "profile", "members", "wookyJobs", "locations", "locationSettings", "paymentSettings", "paymentOverrides", "generalSales", "quickApps", "quickApp", "inquiries").forEach { name ->
            listen(root.child(name), name, receive, denied)
        }
        listen(root.child("chat").orderByChild("at").limitToLast(50), "chat", receive, denied)
        listen(root.child("paymentSendLog").orderByKey().limitToLast(100), "paymentSendLog", receive, denied)
        listen(FirebaseDatabase.getInstance().getReference(".info/connected"), "connected", receive, denied)
    }
    private fun listen(query: Query, name: String, receive: (String, Any?) -> Unit, denied: () -> Unit) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) { receive(name, json(snapshot.value)) }
            override fun onCancelled(error: DatabaseError) {
                if (error.code == DatabaseError.PERMISSION_DENIED) denied() else receive("error", "실시간 연결을 확인해주세요.")
            }
        }
        active += query to listener
        query.addValueEventListener(listener)
    }
    fun close() { active.forEach { (query, listener) -> query.removeEventListener(listener) }; active.clear() }
    private fun json(value: Any?): Any? = when (value) {
        is Map<*, *> -> JSONObject(value)
        is List<*> -> JSONArray(value)
        else -> value
    }
}
