package kr.statusboard.core

/** Access is established before any company cache is exposed to a screen. */
data class FleetSession(val uid: String, val companyId: String, val role: String) {
    init {
        require(uid.isNotBlank() && companyId.isNotBlank())
        require(companyId.none { it in ".#$[]/" })
    }
    val isAdmin: Boolean get() = role == "owner"
    val cacheKey: String get() = "$uid:$companyId"
    fun path(child: String): String = "companies/$companyId/$child"
}

interface MembershipGateway {
    suspend fun companyFor(uid: String): String?
    suspend fun roleFor(companyId: String, uid: String): String?
}

class FleetAccessResolver(private val gateway: MembershipGateway) {
    suspend fun resolve(uid: String): FleetSession? {
        val company = gateway.companyFor(uid) ?: return null
        val role = gateway.roleFor(company, uid) ?: return null
        return FleetSession(uid, company, role.ifBlank { "staff" })
    }
}
