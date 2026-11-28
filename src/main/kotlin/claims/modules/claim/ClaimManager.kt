package claims.modules.claim

import claims.core.config.ClaimsConfig
import claims.core.scheduler.PluginScheduler
import claims.core.scheduler.TaskHandle
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.BlockFace
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Outcome of a claim creation, subclaim creation, or resize operation. */
sealed interface ClaimResult {
    data class Success(val claim: Claim, val refundedBlocks: Int = 0) : ClaimResult
    data class Failure(val reason: Reason, val needed: Int = 0, val available: Int = 0) : ClaimResult

    enum class Reason {
        TOO_SMALL, OVERLAPS_EXISTING, INSUFFICIENT_BLOCKS, OUTSIDE_PARENT, NOT_OWNER, WORLD_DISABLED
    }
}

/**
 * Core spatial index, economy manager, and lifecycle coordinator for claims and subclaims.
 *
 * Top-level claims are indexed in [topLevelByChunk] by `(worldName, chunkX, chunkZ)` for
 * $O(1)$ lookup on hot protection events, while SQLite mutations are delegated to [repository].
 */
class ClaimManager(
    private val scheduler: PluginScheduler?,
    private val repository: ClaimRepository,
    private val configProvider: () -> ClaimsConfig
) {
    private data class ChunkKey(val worldName: String, val chunkX: Int, val chunkZ: Int)

    private val claimsById = ConcurrentHashMap<Long, Claim>()
    private val topLevelByChunk = ConcurrentHashMap<ChunkKey, CopyOnWriteArrayList<Claim>>()
    private val playerDataMap = ConcurrentHashMap<UUID, PlayerClaimData>()
    private val dirtyPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val lastAfkPositions = ConcurrentHashMap<UUID, Location>()
    private var accrualTask: TaskHandle? = null
    private var flushTask: TaskHandle? = null
    private var cleanupTask: TaskHandle? = null

    /** Loads all claims, subclaims, trusts, flags, and player balances from SQLite into memory. */
    fun loadAll() {
        claimsById.clear()
        topLevelByChunk.clear()
        playerDataMap.clear()

        val loadedClaims = repository.loadAllClaims()
        for (claim in loadedClaims) {
            claimsById[claim.id] = claim
            if (!claim.isSubclaim) {
                indexClaim(claim)
            }
        }
        playerDataMap.putAll(repository.loadAllPlayerData())
    }

    /**
     * Starts periodic playtime accrual (every 5 minutes) and, if enabled, inactive-claim cleanup.
     *
     * On Folia, player locations are read via [PluginScheduler.runForEntity] so each check runs on
     * the player's owning region thread.
     */
    fun startAccrualTask() {
        val sched = scheduler ?: return
        stopAccrualTask()
        accrualTask = sched.runGlobalTimer(6000L, 6000L) {
            val cfg = configProvider()
            if (cfg.accrualBlocksPerHour <= 0) return@runGlobalTimer
            val increment = maxOf(1, cfg.accrualBlocksPerHour / 12)
            for (player in Bukkit.getOnlinePlayers()) {
                sched.runForEntity(player) {
                    if (!player.isOnline) return@runForEntity
                    val loc = player.location
                    val prev = lastAfkPositions.put(player.uniqueId, loc.clone())
                    val moved = prev == null || prev.world?.name != loc.world?.name || prev.distanceSquared(loc) >= 9.0
                    if (moved) {
                        val data = getOrCreatePlayerData(player.uniqueId)
                        data.lastActiveAt = System.currentTimeMillis()
                        if (data.accruedBlocks < cfg.maxAccruedBlocks) {
                            data.accruedBlocks = (data.accruedBlocks + increment).coerceAtMost(cfg.maxAccruedBlocks)
                        }
                        dirtyPlayers.add(player.uniqueId)
                    }
                }
            }
        }

        // Flush dirty balances asynchronously shortly after each accrual cycle.
        flushTask = sched.runGlobalTimer(6200L, 6000L) {
            sched.runAsync { flushDirtyPlayerData() }
        }

        val cfg = configProvider()
        if (cfg.unusedCleanupEnabled) {
            cleanupTask = sched.runGlobalTimer(36000L, 36000L) {
                cleanupUnusedClaims()
            }
        }
    }

    /** Stops background accrual/cleanup timers and flushes any pending player data to SQLite. */
    fun stopAccrualTask() {
        accrualTask?.cancel()
        accrualTask = null
        flushTask?.cancel()
        flushTask = null
        cleanupTask?.cancel()
        cleanupTask = null
        flushDirtyPlayerData()
    }

    /** Clears ephemeral AFK tracking state for a disconnecting player. */
    fun clearTracking(uuid: UUID) {
        lastAfkPositions.remove(uuid)
    }

    /** Marks [uuid] as recently active so inactive-claim cleanup will not expire their claims. */
    fun touchPlayerActivity(uuid: UUID) {
        val data = getOrCreatePlayerData(uuid)
        data.lastActiveAt = System.currentTimeMillis()
        dirtyPlayers.add(uuid)
    }

    fun flushDirtyPlayerData() {
        if (dirtyPlayers.isEmpty()) return
        val snapshot = ArrayList<PlayerClaimData>()
        val iter = dirtyPlayers.iterator()
        while (iter.hasNext()) {
            val uuid = iter.next()
            iter.remove()
            playerDataMap[uuid]?.let { snapshot.add(it) }
        }
        if (snapshot.isNotEmpty()) {
            repository.savePlayerDataBatch(snapshot)
        }
    }

    fun getOrCreatePlayerData(uuid: UUID): PlayerClaimData = playerDataMap.computeIfAbsent(uuid) {
        val created = PlayerClaimData(uuid, configProvider().initialClaimBlocks, 0)
        repository.savePlayerData(created)
        created
    }

    /** Returns total 2D block area consumed across all top-level claims owned by [uuid]. */
    fun getUsedClaimBlocks(uuid: UUID): Int =
        claimsById.values.asSequence()
            .filter { !it.isSubclaim && it.ownerId == uuid }
            .sumOf { it.bounds.area.toLong() }
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    fun getAvailableClaimBlocks(uuid: UUID): Int {
        val cfg = configProvider()
        val total = getOrCreatePlayerData(uuid).totalBlocks(cfg.maxAccruedBlocks)
        return (total - getUsedClaimBlocks(uuid)).coerceAtLeast(0)
    }

    fun addBonusBlocks(uuid: UUID, amount: Int) {
        val data = getOrCreatePlayerData(uuid)
        data.bonusBlocks = (data.bonusBlocks + amount).coerceAtLeast(0)
        repository.savePlayerData(data)
    }

    fun setAccruedBlocks(uuid: UUID, amount: Int) {
        val cfg = configProvider()
        val data = getOrCreatePlayerData(uuid)
        data.accruedBlocks = amount.coerceIn(0, cfg.maxAccruedBlocks)
        repository.savePlayerData(data)
    }

    /**
     * Extends (positive [amount]) or shrinks (negative [amount]) [claim] along [facing].
     * Rejects shrinks that would invert past the opposite edge instead of flipping across space.
     */
    fun extendClaimInDirection(
        claim: Claim, facing: BlockFace, amount: Int, isAdminBypass: Boolean = false
    ): ClaimResult {
        val b = claim.bounds
        val newBounds = when (facing) {
            BlockFace.NORTH -> b.copy(minZ = b.minZ - amount)
            BlockFace.SOUTH -> b.copy(maxZ = b.maxZ + amount)
            BlockFace.WEST -> b.copy(minX = b.minX - amount)
            BlockFace.EAST -> b.copy(maxX = b.maxX + amount)
            else -> return ClaimResult.Failure(ClaimResult.Reason.TOO_SMALL)
        }
        if (newBounds.minX > newBounds.maxX || newBounds.minZ > newBounds.maxZ) {
            return ClaimResult.Failure(ClaimResult.Reason.TOO_SMALL)
        }
        return resizeClaim(claim, newBounds, isAdminBypass)
    }

    fun transferClaim(claim: Claim, newOwnerId: UUID?) {
        claim.ownerId = newOwnerId
        if (newOwnerId != null) {
            removeTrust(claim, newOwnerId.toString())
        }
        for (child in claim.children) {
            child.ownerId = newOwnerId
            if (newOwnerId != null) {
                removeTrust(child, newOwnerId.toString())
            }
            repository.updateClaimMetadata(child)
        }
        repository.updateClaimMetadata(claim)
    }

    fun deleteAllPlayerClaims(ownerId: UUID): Pair<Int, Int> {
        val owned = getPlayerClaims(ownerId)
        var totalRefund = 0
        for (c in owned) {
            totalRefund += deleteClaim(c)
        }
        return owned.size to totalRefund
    }

    fun cleanupUnusedClaims(nowMs: Long = System.currentTimeMillis()): Int {
        val cfg = configProvider()
        val cutoffMs = nowMs - (cfg.unusedCleanupInactiveDays.toLong() * 86_400_000L)
        var removed = 0
        for (claim in getAllTopLevelClaims()) {
            val owner = claim.ownerId ?: continue
            if (claim.bounds.area > cfg.unusedCleanupMaxArea) continue
            val data = playerDataMap[owner] ?: continue
            if (data.lastActiveAt < cutoffMs) {
                deleteClaim(claim)
                removed++
            }
        }
        return removed
    }

    fun getClaimById(id: Long): Claim? = claimsById[id]

    fun getAllTopLevelClaims(): List<Claim> = claimsById.values.filter { !it.isSubclaim }.sortedBy { it.id }

    fun getPlayerClaims(uuid: UUID): List<Claim> =
        claimsById.values.filter { !it.isSubclaim && it.ownerId == uuid }.sortedBy { it.id }

    fun getTrustedClaims(uuid: UUID): List<Claim> =
        claimsById.values.filter { !it.isSubclaim && it.ownerId != uuid && it.effectivePermission(uuid) != null }
            .sortedBy { it.id }

    fun getClaimAt(loc: Location): Claim? {
        val world = loc.world ?: return null
        val top = getTopLevelClaimAt(world.name, loc.blockX, loc.blockZ) ?: return null
        val child = top.children.firstOrNull {
            it.bounds.contains(loc.blockX, loc.blockY, loc.blockZ, checkY = true)
        }
        return child ?: top
    }

    fun getTopLevelClaimAt(worldName: String, x: Int, z: Int): Claim? {
        val key = ChunkKey(worldName, x shr 4, z shr 4)
        val bucket = topLevelByChunk[key] ?: return null
        return bucket.firstOrNull { it.bounds.contains(x, 0, z, checkY = false) }
    }

    fun getClaimsOverlapping(bounds: BoundingBox, excludeClaimId: Long? = null): List<Claim> {
        val minChunkX = bounds.minX shr 4
        val maxChunkX = bounds.maxX shr 4
        val minChunkZ = bounds.minZ shr 4
        val maxChunkZ = bounds.maxZ shr 4

        val seen = HashSet<Long>()
        val result = ArrayList<Claim>()
        for (cx in minChunkX..maxChunkX) {
            for (cz in minChunkZ..maxChunkZ) {
                val bucket = topLevelByChunk[ChunkKey(bounds.worldName, cx, cz)] ?: continue
                for (claim in bucket) {
                    if (claim.id == excludeClaimId) continue
                    if (seen.add(claim.id) && claim.bounds.overlaps2D(bounds)) {
                        result.add(claim)
                    }
                }
            }
        }
        return result
    }

    fun createClaim(
        ownerId: UUID?, bounds: BoundingBox, isAdminBypass: Boolean = false, name: String? = null
    ): ClaimResult {
        val cfg = configProvider()
        if (!cfg.isWorldEnabled(bounds.worldName)) {
            return ClaimResult.Failure(ClaimResult.Reason.WORLD_DISABLED)
        }
        if (bounds.width < cfg.minClaimWidth || bounds.length < cfg.minClaimWidth || bounds.area < cfg.minClaimArea) {
            if (!isAdminBypass) {
                return ClaimResult.Failure(ClaimResult.Reason.TOO_SMALL)
            }
        }
        if (getClaimsOverlapping(bounds).isNotEmpty()) {
            return ClaimResult.Failure(ClaimResult.Reason.OVERLAPS_EXISTING)
        }
        if (ownerId != null && !isAdminBypass) {
            val available = getAvailableClaimBlocks(ownerId)
            if (bounds.area > available) {
                return ClaimResult.Failure(ClaimResult.Reason.INSUFFICIENT_BLOCKS, bounds.area, available)
            }
        }

        val claim = repository.insertClaim(
            parentId = null,
            ownerId = ownerId,
            bounds = bounds,
            inheritNothing = false,
            name = name,
            greeting = null,
            defaultFlags = cfg.defaultFlags
        )
        claimsById[claim.id] = claim
        indexClaim(claim)
        return ClaimResult.Success(claim)
    }

    fun createSubclaim(
        parent: Claim, bounds: BoundingBox, inheritNothing: Boolean = false, name: String? = null
    ): ClaimResult {
        if (!parent.bounds.contains2D(bounds)) {
            return ClaimResult.Failure(ClaimResult.Reason.OUTSIDE_PARENT)
        }
        if (parent.children.any { it.bounds.overlaps2D(bounds) }) {
            return ClaimResult.Failure(ClaimResult.Reason.OVERLAPS_EXISTING)
        }

        val sub = repository.insertClaim(
            parentId = parent.id,
            ownerId = parent.ownerId,
            bounds = bounds,
            inheritNothing = inheritNothing,
            name = name ?: "${parent.name} Subclaim",
            greeting = null,
            defaultFlags = emptyMap()
        )
        sub.parent = parent
        parent.children.add(sub)
        claimsById[sub.id] = sub
        return ClaimResult.Success(sub)
    }

    /**
     * Resizes a top-level claim or subclaim to [newBounds], validating minimum dimensions,
     * world consistency, overlap with sibling/foreign claims, child containment, and block balance.
     */
    fun resizeClaim(claim: Claim, newBounds: BoundingBox, isAdminBypass: Boolean = false): ClaimResult {
        val cfg = configProvider()
        if (newBounds.worldName != claim.bounds.worldName) {
            return ClaimResult.Failure(ClaimResult.Reason.WORLD_DISABLED)
        }
        if (claim.isSubclaim) {
            val parent = claim.parent ?: return ClaimResult.Failure(ClaimResult.Reason.OUTSIDE_PARENT)
            if (!parent.bounds.contains2D(newBounds)) {
                return ClaimResult.Failure(ClaimResult.Reason.OUTSIDE_PARENT)
            }
            if (parent.children.any { it.id != claim.id && it.bounds.overlaps2D(newBounds) }) {
                return ClaimResult.Failure(ClaimResult.Reason.OVERLAPS_EXISTING)
            }
            claim.bounds = newBounds
            repository.updateClaimBounds(claim)
            return ClaimResult.Success(claim)
        }

        if (!isAdminBypass && (newBounds.width < cfg.minClaimWidth || newBounds.length < cfg.minClaimWidth || newBounds.area < cfg.minClaimArea)) {
            return ClaimResult.Failure(ClaimResult.Reason.TOO_SMALL)
        }
        if (getClaimsOverlapping(newBounds, excludeClaimId = claim.id).isNotEmpty()) {
            return ClaimResult.Failure(ClaimResult.Reason.OVERLAPS_EXISTING)
        }
        if (claim.children.any { !newBounds.contains2D(it.bounds) }) {
            return ClaimResult.Failure(ClaimResult.Reason.OUTSIDE_PARENT)
        }

        val delta = newBounds.area - claim.bounds.area
        val owner = claim.ownerId
        if (owner != null && delta > 0 && !isAdminBypass) {
            val available = getAvailableClaimBlocks(owner)
            if (delta > available) {
                return ClaimResult.Failure(ClaimResult.Reason.INSUFFICIENT_BLOCKS, delta, available)
            }
        }

        deindexClaim(claim)
        claim.bounds = newBounds
        indexClaim(claim)
        repository.updateClaimBounds(claim)
        return ClaimResult.Success(claim)
    }

    /**
     * Deletes [claim] (and its child subclaims if top-level) from memory and SQLite.
     *
     * When `abandon-refund-ratio < 1.0`, the non-refunded block penalty `(area - refund)` is
     * deducted from the owner's accrued/bonus pool so available claim blocks reflect the ratio.
     */
    fun deleteClaim(claim: Claim): Int {
        val cfg = configProvider()
        val area = claim.bounds.area
        val refund = if (!claim.isSubclaim && !claim.isAdminClaim) {
            (area * cfg.abandonRefundRatio).toInt().coerceIn(0, area)
        } else 0

        if (claim.isSubclaim) {
            claim.parent?.children?.remove(claim)
        } else {
            deindexClaim(claim)
            for (child in claim.children) {
                claimsById.remove(child.id)
            }
        }
        claimsById.remove(claim.id)
        repository.deleteClaim(claim.id)

        val owner = claim.ownerId
        val penalty = area - refund
        if (owner != null && !claim.isSubclaim && penalty > 0) {
            val data = getOrCreatePlayerData(owner)
            val fromAccrued = minOf(data.accruedBlocks, penalty)
            data.accruedBlocks -= fromAccrued
            val remainingPenalty = penalty - fromAccrued
            if (remainingPenalty > 0) {
                data.bonusBlocks = (data.bonusBlocks - remainingPenalty).coerceAtLeast(0)
            }
            repository.savePlayerData(data)
        }
        return refund
    }

    fun setTrust(claim: Claim, targetKey: String, permission: ClaimPermission) {
        claim.trusts[targetKey] = permission
        repository.upsertTrust(claim.id, targetKey, permission)
    }

    fun removeTrust(claim: Claim, targetKey: String) {
        claim.trusts.remove(targetKey)
        repository.removeTrust(claim.id, targetKey)
    }

    /** Revokes all direct and public trust entries on [claim]. */
    fun removeAllTrusts(claim: Claim) {
        claim.trusts.clear()
        repository.deleteAllTrusts(claim.id)
    }

    fun setFlag(claim: Claim, flag: ClaimFlag, value: Boolean) {
        claim.flags[flag] = value
        repository.upsertFlag(claim.id, flag, value)
    }

    fun updateMetadata(claim: Claim, name: String, greeting: String?, inheritNothing: Boolean = claim.inheritNothing) {
        claim.name = name
        claim.greeting = greeting?.takeIf { it.isNotBlank() }
        claim.inheritNothing = inheritNothing
        repository.updateClaimMetadata(claim)
    }

    private fun indexClaim(claim: Claim) {
        val minChunkX = claim.bounds.minX shr 4
        val maxChunkX = claim.bounds.maxX shr 4
        val minChunkZ = claim.bounds.minZ shr 4
        val maxChunkZ = claim.bounds.maxZ shr 4
        for (cx in minChunkX..maxChunkX) {
            for (cz in minChunkZ..maxChunkZ) {
                topLevelByChunk.computeIfAbsent(ChunkKey(claim.bounds.worldName, cx, cz)) {
                    CopyOnWriteArrayList()
                }.add(claim)
            }
        }
    }

    private fun deindexClaim(claim: Claim) {
        val minChunkX = claim.bounds.minX shr 4
        val maxChunkX = claim.bounds.maxX shr 4
        val minChunkZ = claim.bounds.minZ shr 4
        val maxChunkZ = claim.bounds.maxZ shr 4
        for (cx in minChunkX..maxChunkX) {
            for (cz in minChunkZ..maxChunkZ) {
                val key = ChunkKey(claim.bounds.worldName, cx, cz)
                topLevelByChunk[key]?.remove(claim)
            }
        }
    }
}
