package claims.modules.claim

import org.bukkit.Bukkit
import org.bukkit.Location
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hierarchical claim trust tiers ordered from highest privilege ([OWNER]) to lowest ([ACCESS]).
 *
 * Because [isGrantedBy] compares [ordinal] values (`granted.ordinal <= this.ordinal`),
 * granting a higher tier automatically satisfies all lower-tier checks.
 */
enum class ClaimPermission(val displayName: String, val denialKey: String) {
    OWNER("Owner", "deny-manage"),
    MANAGE("Manage", "deny-manage"),
    BUILD("Build", "deny-build"),
    CONTAINER("Container", "deny-container"),
    ACCESS("Access", "deny-access");

    /** Returns `true` if [granted] meets or exceeds this required permission tier. */
    fun isGrantedBy(granted: ClaimPermission?): Boolean =
        granted != null && granted.ordinal <= this.ordinal

    /** Cycles through [TRUSTABLE_TIERS] for GUI trust-matrix clicks. */
    fun nextTrustTier(): ClaimPermission = when (this) {
        ACCESS -> CONTAINER
        CONTAINER -> BUILD
        BUILD -> MANAGE
        MANAGE -> ACCESS
        OWNER -> OWNER
    }

    companion object {
        val TRUSTABLE_TIERS = listOf(ACCESS, CONTAINER, BUILD, MANAGE)

        /** Parses a command argument or shorthand (e.g. `a`, `ct`, `tr`, `manage`) into a trust tier. */
        fun parse(raw: String): ClaimPermission? = when (raw.lowercase()) {
            "access", "a", "at" -> ACCESS
            "container", "containers", "c", "ct" -> CONTAINER
            "build", "b", "tr", "trust" -> BUILD
            "manage", "m", "mt", "permission" -> MANAGE
            else -> null
        }
    }
}

/**
 * Per-claim boolean protection flags stored in `cl_claim_flags`.
 */
enum class ClaimFlag(val key: String, val label: String) {
    PVP("pvp", "Allow PvP"),
    EXPLOSIONS("explosions", "Allow Explosions"),
    FIRE_SPREAD("fire_spread", "Allow Fire Spread"),
    MOB_GRIEFING("mob_griefing", "Allow Mob Griefing"),
    PUBLIC_ACCESS("public_access", "Public Access"),
    PISTON_CROSS_BORDER("piston_cross_border", "Cross-border Pistons"),
    ENTRY_NOTIFY("entry_notify", "Entry Notification")
}

/**
 * Active interaction mode of a player's claim tool (e.g., `GOLDEN_SHOVEL`).
 */
enum class ShovelMode(val label: String) {
    BASIC("Basic"),
    SUBDIVIDE("Subdivide"),
    ADMIN("Admin")
}

/**
 * Axis-aligned 3D integer bounding box within [worldName].
 *
 * Top-level claims span full world height and use 2D X/Z containment, while subclaims
 * honor Y bounds (`checkY = true`).
 */
data class BoundingBox(
    val worldName: String,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int
) {
    val width: Int get() = (maxX - minX) + 1
    val length: Int get() = (maxZ - minZ) + 1

    /** 2D X*Z footprint in blocks (clamped to [Int.MAX_VALUE] on overflow). */
    val area: Int get() = runCatching { Math.multiplyExact(width, length) }.getOrDefault(Int.MAX_VALUE)

    fun contains(x: Int, y: Int, z: Int, checkY: Boolean): Boolean {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return false
        return !checkY || (y in minY..maxY)
    }

    fun overlaps2D(other: BoundingBox): Boolean {
        if (worldName != other.worldName) return false
        return minX <= other.maxX && maxX >= other.minX &&
                minZ <= other.maxZ && maxZ >= other.minZ
    }

    fun contains2D(other: BoundingBox): Boolean {
        if (worldName != other.worldName) return false
        return other.minX >= minX && other.maxX <= maxX &&
                other.minZ >= minZ && other.maxZ <= maxZ
    }

    companion object {
        /** Normalizes any two opposite block corners into a valid min/max [BoundingBox]. */
        fun ofCorners(worldName: String, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): BoundingBox =
            BoundingBox(
                worldName = worldName,
                minX = minOf(x1, x2),
                minY = minOf(y1, y2),
                minZ = minOf(z1, z2),
                maxX = maxOf(x1, x2),
                maxY = maxOf(y1, y2),
                maxZ = maxOf(z1, z2)
            )
    }
}

/**
 * In-memory representation of a land claim or subclaim.
 *
 * When [ownerId] is `null`, the claim is an administrative claim ([isAdminClaim]).
 * When [parentId] is non-null, this is a subclaim ([isSubclaim]) that inherits its parent's
 * trusts and flags unless [inheritNothing] is `true`.
 */
class Claim(
    val id: Long,
    var parentId: Long? = null,
    var ownerId: UUID?,
    var bounds: BoundingBox,
    var inheritNothing: Boolean = false,
    var name: String = "Claim #$id",
    var greeting: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = createdAt
) {
    val trusts: ConcurrentHashMap<String, ClaimPermission> = ConcurrentHashMap()
    val flags: ConcurrentHashMap<ClaimFlag, Boolean> = ConcurrentHashMap()
    val children: CopyOnWriteArrayList<Claim> = CopyOnWriteArrayList()

    @Volatile
    var parent: Claim? = null

    val isAdminClaim: Boolean get() = ownerId == null
    val isSubclaim: Boolean get() = parentId != null

    fun ownerName(): String {
        val id = ownerId ?: return "Administrator"
        val shortId = id.toString().substring(0, 8)
        return runCatching { Bukkit.getOfflinePlayer(id).name }.getOrNull() ?: shortId
    }

    fun flag(flag: ClaimFlag, defaults: Map<ClaimFlag, Boolean> = emptyMap()): Boolean =
        flags[flag] ?: parent?.flags?.get(flag) ?: defaults[flag] ?: (flag == ClaimFlag.ENTRY_NOTIFY)

    fun contains(loc: Location): Boolean {
        val world = loc.world ?: return false
        if (world.name != bounds.worldName) return false
        return bounds.contains(loc.blockX, loc.blockY, loc.blockZ, checkY = isSubclaim)
    }

    fun effectivePermission(playerId: UUID): ClaimPermission? {
        if (ownerId == playerId) return ClaimPermission.OWNER
        val direct = trusts[playerId.toString()]
        val publicTrust =
            trusts[PUBLIC_KEY] ?: if (flags[ClaimFlag.PUBLIC_ACCESS] == true) ClaimPermission.ACCESS else null

        val localBest = listOfNotNull(direct, publicTrust).minByOrNull { it.ordinal }
        if (!isSubclaim || inheritNothing) {
            return localBest
        }
        val parentPerm = parent?.effectivePermission(playerId)
        return listOfNotNull(localBest, parentPerm).minByOrNull { it.ordinal }
    }

    fun hasPermission(playerId: UUID, required: ClaimPermission, adminBypass: Boolean = false): Boolean {
        if (adminBypass) return true
        return required.isGrantedBy(effectivePermission(playerId))
    }

    companion object {
        const val PUBLIC_KEY = "public"
    }
}

data class PlayerClaimData(
    val uuid: UUID,
    var accruedBlocks: Int,
    var bonusBlocks: Int,
    var lastActiveAt: Long = System.currentTimeMillis()
) {
    fun totalBlocks(maxAccrued: Int): Int = accruedBlocks.coerceAtMost(maxAccrued) + bonusBlocks
}

/**
 * Resolves an online or previously cached [org.bukkit.OfflinePlayer] by exact username
 * without performing blocking Mojang HTTP lookups on the calling thread.
 */
fun resolveKnownPlayer(name: String): org.bukkit.OfflinePlayer? =
    Bukkit.getPlayerExact(name) ?: runCatching { Bukkit.getOfflinePlayerIfCached(name) }.getOrNull()
