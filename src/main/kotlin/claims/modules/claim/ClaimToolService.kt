package claims.modules.claim

import claims.core.config.ClaimsConfig
import claims.core.locale.MessageService
import claims.core.ui.ActionBarService
import claims.core.ui.BossBarService
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Handles interactive claim-tool (`GOLDEN_SHOVEL`) corner selection, resizing, subdivision,
 * and inspection-stick (`STICK`) queries with BossBar/ActionBar feedback and boundary visualization.
 */
class ClaimToolService(
    private val configProvider: () -> ClaimsConfig,
    private val messages: MessageService,
    private val claimManager: ClaimManager,
    private val visualizer: BoundaryVisualizer,
    private val actionBar: ActionBarService,
    private val bossBar: BossBarService,
    private val sessions: ClaimSessionStore,
) {
    companion object {
        private const val MODE_BAR_KEY = "claim_mode"
    }

    /** Inspects the claim at [target] or highlights the nearest claim within 24 blocks. */
    fun handleInspectStick(player: Player, target: Location) {
        val cfg = configProvider()
        val worldName = target.world?.name ?: return
        if (!cfg.isWorldEnabled(worldName)) {
            messages.send(player, "world-disabled")
            return
        }
        val claim = claimManager.getClaimAt(target)
        if (claim == null) {
            actionBar.flash(player, messages.render("wilderness"))
            val nearby = claimManager.getClaimsOverlapping(
                BoundingBox.ofCorners(
                    worldName,
                    target.blockX - 24,
                    target.blockY,
                    target.blockZ - 24,
                    target.blockX + 24,
                    target.blockY,
                    target.blockZ + 24
                )
            )
            if (nearby.isNotEmpty()) {
                visualizer.showClaim(player, nearby.first(), Material.DIAMOND_BLOCK)
                messages.send(
                    player,
                    "claim-nearby-highlighted",
                    mapOf("name" to nearby.first().name, "owner" to nearby.first().ownerName())
                )
            } else {
                messages.send(player, "claim-none-nearby")
            }
            return
        }

        visualizer.showClaim(player, claim)
        val perm = claim.effectivePermission(player.uniqueId)
        val permLabel = perm?.displayName ?: "None"
        messages.send(
            player,
            "claim-inspect",
            mapOf(
                "name" to claim.name,
                "owner" to claim.ownerName(),
                "w" to claim.bounds.width,
                "l" to claim.bounds.length,
                "area" to claim.bounds.area,
                "trust" to permLabel,
            )
        )
    }

    /** Processes right-click corner selection for creating, resizing, or subdividing claims. */
    fun handleShovelRightClick(player: Player, clicked: Location) {
        val cfg = configProvider()
        val worldName = clicked.world?.name ?: return
        if (!cfg.isWorldEnabled(worldName)) {
            cancelSelection(player)
            messages.send(player, "world-disabled")
            return
        }
        val state = sessions.state(player.uniqueId)
        val adminBypass = state.adminBypass && player.hasPermission("claims.admin")
        val existingAtClick = claimManager.getClaimAt(clicked)

        val corner1 = state.pendingCorner1
        if (corner1 != null && corner1.world?.name == worldName) {
            state.pendingCorner1 = null
            bossBar.hide(player, MODE_BAR_KEY)

            val resizing = state.resizingClaim
            if (resizing != null) {
                state.resizingClaim = null
                val liveClaim = claimManager.getClaimById(resizing.id)
                if (liveClaim == null) {
                    messages.send(player, "not-in-claim")
                    return
                }
                finishResize(player, liveClaim, corner1, clicked, adminBypass)
                return
            }

            val subdivParent = state.subdividingParent
            if (subdivParent != null) {
                state.subdividingParent = null
                val liveParent = claimManager.getClaimById(subdivParent.id)
                if (liveParent == null) {
                    messages.send(player, "not-in-claim")
                    return
                }
                finishSubclaim(player, liveParent, corner1, clicked)
                return
            }

            finishNewClaim(player, corner1, clicked, state.shovelMode, adminBypass)
            return
        }

        if (existingAtClick != null && state.shovelMode != ShovelMode.SUBDIVIDE) {
            if (isCorner(existingAtClick.bounds, clicked)) {
                if (!existingAtClick.hasPermission(player.uniqueId, ClaimPermission.MANAGE, adminBypass)) {
                    actionBar.sendDenial(player, "deny-manage", mapOf("owner" to existingAtClick.ownerName()))
                    return
                }
                state.resizingClaim = existingAtClick
                state.pendingCorner1 = clicked.clone()
                visualizer.showClaim(player, existingAtClick, Material.EMERALD_BLOCK)
                if (cfg.bossBarModeBanner) {
                    bossBar.showOrUpdate(
                        player,
                        MODE_BAR_KEY,
                        messages.render("bossbar-resizing"),
                        1.0f,
                        BossBar.Color.YELLOW
                    )
                }
                return
            }
        }

        if (state.shovelMode == ShovelMode.SUBDIVIDE) {
            val top = claimManager.getTopLevelClaimAt(worldName, clicked.blockX, clicked.blockZ)
            if (top == null) {
                messages.send(player, "not-in-claim")
                return
            }
            if (!top.hasPermission(player.uniqueId, ClaimPermission.MANAGE, adminBypass)) {
                actionBar.sendDenial(player, "deny-manage", mapOf("owner" to top.ownerName()))
                return
            }
            state.subdividingParent = top
            state.pendingCorner1 = clicked.clone()
            visualizer.showClaim(player, top, Material.LAPIS_BLOCK)
            if (cfg.bossBarModeBanner) {
                bossBar.showOrUpdate(
                    player,
                    MODE_BAR_KEY,
                    messages.render("bossbar-subdividing"),
                    1.0f,
                    BossBar.Color.PURPLE
                )
            }
            return
        }

        if (existingAtClick != null) {
            messages.send(player, "claim-overlap")
            visualizer.showClaim(player, existingAtClick)
            return
        }

        state.pendingCorner1 = clicked.clone()
        visualizer.showBounds(
            player,
            BoundingBox.ofCorners(
                worldName,
                clicked.blockX,
                clicked.blockY,
                clicked.blockZ,
                clicked.blockX,
                clicked.blockY,
                clicked.blockZ
            ),
            Material.DIAMOND_BLOCK,
            Material.DIAMOND_BLOCK
        )
        if (cfg.bossBarModeBanner) {
            bossBar.showOrUpdate(
                player,
                MODE_BAR_KEY,
                messages.render("bossbar-creating"),
                1.0f,
                BossBar.Color.BLUE
            )
        }
        actionBar.tickPlayer(player)
    }

    /** Clears any pending corner/resize/subdivision state and hides active visualization & mode BossBar. */
    fun cancelSelection(player: Player) {
        val state = sessions.state(player.uniqueId)
        state.pendingCorner1 = null
        state.resizingClaim = null
        state.subdividingParent = null
        bossBar.hide(player, MODE_BAR_KEY)
        visualizer.clear(player)
    }

    private fun finishNewClaim(
        player: Player, c1: Location, c2: Location, mode: ShovelMode, adminBypass: Boolean
    ) {
        val world = c1.world
        val ownerId = if (mode == ShovelMode.ADMIN && player.hasPermission("claims.admin")) null else player.uniqueId
        val bounds = BoundingBox.ofCorners(
            world.name, c1.blockX, world.minHeight, c1.blockZ, c2.blockX, world.maxHeight - 1, c2.blockZ
        )
        when (val result = claimManager.createClaim(ownerId, bounds, isAdminBypass = adminBypass || ownerId == null)) {
            is ClaimResult.Success -> {
                visualizer.showClaim(player, result.claim)
                messages.send(
                    player,
                    "claim-created",
                    mapOf(
                        "w" to result.claim.bounds.width,
                        "l" to result.claim.bounds.length,
                        "area" to result.claim.bounds.area
                    )
                )
            }

            is ClaimResult.Failure -> reportFailure(player, result)
        }
    }

    private fun finishSubclaim(player: Player, parent: Claim, c1: Location, c2: Location) {
        val bounds = BoundingBox.ofCorners(
            parent.bounds.worldName,
            c1.blockX,
            minOf(c1.blockY, c2.blockY),
            c1.blockZ,
            c2.blockX,
            maxOf(c1.blockY, c2.blockY),
            c2.blockZ
        )
        when (val result = claimManager.createSubclaim(parent, bounds)) {
            is ClaimResult.Success -> {
                visualizer.showClaim(player, result.claim, Material.IRON_BLOCK)
                messages.send(player, "subclaim-created")
            }

            is ClaimResult.Failure -> reportFailure(player, result)
        }
    }

    private fun finishResize(
        player: Player, claim: Claim, oldCorner: Location, newCorner: Location, adminBypass: Boolean
    ) {
        val b = claim.bounds
        val newMinX = if (oldCorner.blockX == b.minX) newCorner.blockX else b.minX
        val newMaxX = if (oldCorner.blockX == b.maxX && oldCorner.blockX != b.minX) newCorner.blockX else b.maxX
        val newMinZ = if (oldCorner.blockZ == b.minZ) newCorner.blockZ else b.minZ
        val newMaxZ = if (oldCorner.blockZ == b.maxZ && oldCorner.blockZ != b.minZ) newCorner.blockZ else b.maxZ

        if (newMinX > newMaxX || newMinZ > newMaxZ) {
            reportFailure(player, ClaimResult.Failure(ClaimResult.Reason.TOO_SMALL))
            return
        }

        val newBounds = BoundingBox(
            b.worldName, newMinX, b.minY, newMinZ, newMaxX, b.maxY, newMaxZ
        )
        when (val result = claimManager.resizeClaim(claim, newBounds, isAdminBypass = adminBypass)) {
            is ClaimResult.Success -> {
                visualizer.showClaim(player, result.claim)
                messages.send(
                    player,
                    "claim-resized",
                    mapOf(
                        "w" to result.claim.bounds.width,
                        "l" to result.claim.bounds.length,
                        "area" to result.claim.bounds.area
                    )
                )
            }

            is ClaimResult.Failure -> reportFailure(player, result)
        }
    }

    private fun reportFailure(player: Player, failure: ClaimResult.Failure) {
        val cfg = configProvider()
        when (failure.reason) {
            ClaimResult.Reason.TOO_SMALL -> messages.send(
                player, "claim-too-small", mapOf("min_w" to cfg.minClaimWidth, "min_area" to cfg.minClaimArea)
            )

            ClaimResult.Reason.OVERLAPS_EXISTING -> messages.send(player, "claim-overlap")
            ClaimResult.Reason.INSUFFICIENT_BLOCKS -> messages.send(
                player, "claim-insufficient-blocks", mapOf("needed" to failure.needed, "available" to failure.available)
            )

            ClaimResult.Reason.OUTSIDE_PARENT -> messages.send(player, "outside-parent")
            ClaimResult.Reason.NOT_OWNER -> messages.send(player, "deny-manage", mapOf("owner" to "-"))
            ClaimResult.Reason.WORLD_DISABLED -> messages.send(player, "world-disabled")
        }
    }

    private fun isCorner(bounds: BoundingBox, loc: Location): Boolean {
        val x = loc.blockX
        val z = loc.blockZ
        return (x == bounds.minX || x == bounds.maxX) && (z == bounds.minZ || z == bounds.maxZ)
    }
}
