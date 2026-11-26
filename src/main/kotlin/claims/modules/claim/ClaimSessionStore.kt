package claims.modules.claim

import org.bukkit.Location
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ClaimPlayerSession(
    var shovelMode: ShovelMode = ShovelMode.BASIC,
    var pendingCorner1: Location? = null,
    var resizingClaim: Claim? = null,
    var subdividingParent: Claim? = null,
    var adminBypass: Boolean = false,
    var territoryBanner: Boolean = false,
    var lastClaimId: Long? = null,
)

class ClaimSessionStore {
    private val sessions = ConcurrentHashMap<UUID, ClaimPlayerSession>()

    fun state(uuid: UUID): ClaimPlayerSession =
        sessions.computeIfAbsent(uuid) { ClaimPlayerSession() }

    fun clear(uuid: UUID) {
        sessions.remove(uuid)
    }
}
