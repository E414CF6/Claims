package claims.modules.claim

import org.bukkit.Location
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Ephemeral per-player session state for claim tool interactions, bypass mode, and HUD banners.
 */
data class ClaimPlayerSession(
    var shovelMode: ShovelMode = ShovelMode.BASIC,
    var pendingCorner1: Location? = null,
    var resizingClaim: Claim? = null,
    var subdividingParent: Claim? = null,
    var adminBypass: Boolean = false,
    var territoryBanner: Boolean = false,
    var lastClaimId: Long? = null,
)

/** Thread-safe in-memory store for active [ClaimPlayerSession] instances. */
class ClaimSessionStore {
    private val sessions = ConcurrentHashMap<UUID, ClaimPlayerSession>()

    fun state(uuid: UUID): ClaimPlayerSession =
        sessions.computeIfAbsent(uuid) { ClaimPlayerSession() }

    fun clear(uuid: UUID) {
        sessions.remove(uuid)
    }
}
