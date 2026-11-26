package claims.modules.claim

import claims.core.ClaimsContext
import claims.core.command.CommandRegistry
import claims.core.command.SubcommandTabCompleter
import claims.core.module.FeatureModule
import claims.modules.claim.ui.ClaimDialogs
import claims.modules.claim.ui.ClaimMenus
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Listener

class ClaimModule(private val ctx: ClaimsContext) : FeatureModule {
    override val id: String = "claim"
    override val description: String = "Land claims, trust matrix, subclaims, and territory protection"

    val repository = ClaimRepository(ctx.database)
    val claimManager = ClaimManager(ctx.scheduler, repository) { ctx.config }
    val boundaryVisualizer = BoundaryVisualizer(ctx.scheduler) { ctx.config }
    val sessions = ClaimSessionStore()
    val toolService = ClaimToolService(
        { ctx.config },
        ctx.messages,
        claimManager,
        boundaryVisualizer,
        ctx.actionBar,
        ctx.bossBar,
        sessions,
    )

    lateinit var menus: ClaimMenus
        private set
    lateinit var dialogs: ClaimDialogs
        private set

    init {
        dialogs = ClaimDialogs({ ctx.config }, ctx.messages, claimManager, ctx.dialogs) { menus }
        menus = ClaimMenus(
            { ctx.config },
            ctx.messages,
            claimManager,
            boundaryVisualizer,
            ctx.actionBar,
            ctx.bossBar,
            sessions,
            ctx.dialogs,
            { dialogs },
        )
    }

    override fun onEnable() {
        claimManager.loadAll()
        claimManager.startAccrualTask()

        // Register claim tool & territory HUD providers into reusable ActionBarService
        ctx.actionBar.registerHudProvider("claim_tool", priority = 50) { player ->
            renderShovelHud(player)
        }
        ctx.actionBar.registerHudProvider("claim_territory", priority = 10) { player ->
            renderTerritoryHud(player)
        }
    }

    override fun onDisable() {
        ctx.actionBar.unregisterHudProvider("claim_tool")
        ctx.actionBar.unregisterHudProvider("claim_territory")
        boundaryVisualizer.clearAll()
        claimManager.stopAccrualTask()
    }

    override fun listeners(): List<Listener> = listOf(
        ProtectionListener(
            ctx.scheduler,
            { ctx.config },
            ctx.messages,
            claimManager,
            toolService,
            boundaryVisualizer,
            ctx.actionBar,
            ctx.bossBar,
            sessions,
        )
    )

    override fun registerSubcommands(registry: CommandRegistry) {
        val playerSuggest = SubcommandTabCompleter { _, args ->
            if (args.size <= 1) {
                val prefix = args.firstOrNull().orEmpty()
                Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(prefix, ignoreCase = true) }
            } else emptyList()
        }

        val trustCompleter = SubcommandTabCompleter { _, args ->
            when (args.size) {
                0, 1 -> {
                    val prefix = args.firstOrNull().orEmpty()
                    (Bukkit.getOnlinePlayers().map { it.name } + "public").filter {
                        it.startsWith(prefix, ignoreCase = true)
                    }
                }

                2 -> listOf("access", "container", "build", "manage").filter {
                    it.startsWith(args[1], ignoreCase = true)
                }

                else -> emptyList()
            }
        }

        registry.registerSubcommand(
            name = "claim",
            description = "Create a land claim centered on your position",
            permission = "claims.claim",
        ) { sender, args ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val cfg = ctx.config
            val radius = args.firstOrNull()?.toIntOrNull()?.coerceIn(2, 128) ?: cfg.autoClaimRadius
            val loc = player.location
            val bounds = BoundingBox.ofCorners(
                loc.world.name,
                loc.blockX - radius,
                loc.world.minHeight,
                loc.blockZ - radius,
                loc.blockX + radius,
                loc.world.maxHeight - 1,
                loc.blockZ + radius
            )
            val bypass = sessions.state(player.uniqueId).adminBypass && player.hasPermission("claims.admin")
            when (val result = claimManager.createClaim(player.uniqueId, bounds, isAdminBypass = bypass)) {
                is ClaimResult.Success -> {
                    boundaryVisualizer.showClaim(player, result.claim)
                    ctx.messages.send(
                        player,
                        "claim-created",
                        mapOf(
                            "w" to result.claim.bounds.width,
                            "l" to result.claim.bounds.length,
                            "area" to result.claim.bounds.area
                        )
                    )
                }

                is ClaimResult.Failure -> reportClaimFailure(player, result)
            }
            true
        }

        registry.registerSubcommand(
            name = "list",
            description = "Browse claims you own or are trusted in",
            permission = "claims.claim",
            aliases = listOf("claimslist"),
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            menus.openClaimList(player)
            true
        }

        registry.registerSubcommand(
            name = "settings",
            description = "Edit settings and flags for the current claim",
            permission = "claims.claim",
            aliases = listOf("flags", "claimsettings"),
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val claim = claimManager.getClaimAt(player.location)
            if (claim == null) {
                ctx.messages.send(player, "not-in-claim")
                return@registerSubcommand true
            }
            val adminBypass = sessions.state(player.uniqueId).adminBypass && player.hasPermission("claims.admin")
            if (!claim.hasPermission(player.uniqueId, ClaimPermission.MANAGE, adminBypass)) {
                ctx.actionBar.sendDenial(player, "deny-manage", mapOf("owner" to claim.ownerName()))
                return@registerSubcommand true
            }
            if (claim.isSubclaim) {
                dialogs.openSubclaimSettingsDialog(player, claim)
            } else {
                dialogs.openClaimSettingsDialog(player, claim)
            }
            true
        }

        registry.registerSubcommand(
            name = "trust",
            description = "Grant claim permissions to a player",
            permission = "claims.claim",
            aliases = listOf("tr"),
            tabCompleter = trustCompleter,
        ) { sender, args ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val claim = claimManager.getClaimAt(player.location)
            if (claim == null) {
                ctx.messages.send(player, "not-in-claim")
                return@registerSubcommand true
            }
            val adminBypass = sessions.state(player.uniqueId).adminBypass && player.hasPermission("claims.admin")
            if (!claim.hasPermission(player.uniqueId, ClaimPermission.MANAGE, adminBypass)) {
                ctx.actionBar.sendDenial(player, "deny-manage", mapOf("owner" to claim.ownerName()))
                return@registerSubcommand true
            }
            if (args.isEmpty()) {
                dialogs.openTrustAddDialog(player, claim)
                return@registerSubcommand true
            }
            val targetName = args[0]
            val perm = args.getOrNull(1)?.let { ClaimPermission.parse(it) } ?: ClaimPermission.BUILD
            val key = if (targetName.equals("public", ignoreCase = true)) {
                Claim.PUBLIC_KEY
            } else {
                Bukkit.getOfflinePlayer(targetName).uniqueId.toString()
            }
            claimManager.setTrust(claim, key, perm)
            ctx.messages.send(player, "trust-updated", mapOf("target" to targetName, "level" to perm.displayName))
            true
        }

        registry.registerSubcommand(
            name = "untrust",
            description = "Revoke a player's claim permissions",
            permission = "claims.claim",
            aliases = listOf("ut"),
            tabCompleter = playerSuggest,
        ) { sender, args ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val claim = claimManager.getClaimAt(player.location)
            if (claim == null) {
                ctx.messages.send(player, "not-in-claim")
                return@registerSubcommand true
            }
            val adminBypass = sessions.state(player.uniqueId).adminBypass && player.hasPermission("claims.admin")
            if (!claim.hasPermission(player.uniqueId, ClaimPermission.MANAGE, adminBypass)) {
                ctx.actionBar.sendDenial(player, "deny-manage", mapOf("owner" to claim.ownerName()))
                return@registerSubcommand true
            }
            val targetName = args.firstOrNull()
            if (targetName == null) {
                menus.openClaimTrustMenu(player, claim)
                return@registerSubcommand true
            }
            val key = if (targetName.equals("public", ignoreCase = true)) {
                Claim.PUBLIC_KEY
            } else {
                Bukkit.getOfflinePlayer(targetName).uniqueId.toString()
            }
            claimManager.removeTrust(claim, key)
            ctx.messages.send(player, "trust-removed", mapOf("target" to targetName))
            true
        }

        registry.registerSubcommand(
            name = "extend",
            description = "Extend or shrink the current claim in your facing direction",
            permission = "claims.claim",
            aliases = listOf("extendclaim", "expandclaim", "expand"),
        ) { sender, args ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val claim = claimManager.getClaimAt(player.location)
            if (claim == null) {
                ctx.messages.send(player, "not-in-claim")
                return@registerSubcommand true
            }
            val adminBypass = sessions.state(player.uniqueId).adminBypass && player.hasPermission("claims.admin")
            if (!claim.hasPermission(player.uniqueId, ClaimPermission.MANAGE, adminBypass)) {
                ctx.actionBar.sendDenial(player, "deny-manage", mapOf("owner" to claim.ownerName()))
                return@registerSubcommand true
            }
            val amount = args.firstOrNull()?.toIntOrNull()
            if (amount == null || amount == 0) {
                ctx.messages.sendRaw(
                    player,
                    "<yellow>Usage: /extendclaim <blocks> (expands or shrinks in the direction you face)</yellow>"
                )
                return@registerSubcommand true
            }
            val facing = cardinalFacing(player.location.yaw)
            when (val res = claimManager.extendClaimInDirection(claim, facing, amount, adminBypass)) {
                is ClaimResult.Success -> {
                    boundaryVisualizer.showClaim(player, res.claim)
                    ctx.messages.send(
                        player,
                        "claim-resized",
                        mapOf(
                            "w" to res.claim.bounds.width,
                            "l" to res.claim.bounds.length,
                            "area" to res.claim.bounds.area
                        )
                    )
                }

                is ClaimResult.Failure -> reportClaimFailure(player, res)
            }
            true
        }

        registry.registerSubcommand(
            name = "transfer",
            description = "Transfer ownership of the current claim",
            permission = "claims.claim",
            aliases = listOf("transferclaim"),
            tabCompleter = playerSuggest,
        ) { sender, args ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val claim =
                claimManager.getTopLevelClaimAt(player.world.name, player.location.blockX, player.location.blockZ)
            if (claim == null) {
                ctx.messages.send(player, "not-in-claim")
                return@registerSubcommand true
            }
            val adminBypass = sessions.state(player.uniqueId).adminBypass && player.hasPermission("claims.admin")
            if (claim.ownerId != player.uniqueId && !adminBypass) {
                ctx.actionBar.sendDenial(player, "deny-manage", mapOf("owner" to claim.ownerName()))
                return@registerSubcommand true
            }
            val targetName = args.firstOrNull()
            if (targetName == null) {
                dialogs.openTransferClaimDialog(player, claim)
                return@registerSubcommand true
            }
            val offline = Bukkit.getOfflinePlayer(targetName)
            ctx.dialogs.openConfirmationDialog(
                player,
                "<gold><bold>Confirm Ownership Transfer</bold></gold>",
                "<gray>Transfer ownership of <white>${claim.name}</white> to <white>$targetName</white>?</gray>",
                "<gold>Transfer</gold>"
            ) { p ->
                claimManager.transferClaim(claim, offline.uniqueId)
                ctx.messages.send(p, "claim-transferred", mapOf("target" to targetName))
            }
            true
        }

        registry.registerSubcommand(
            name = "restrict",
            description = "Toggle permission inheritance isolation on a subclaim",
            permission = "claims.claim",
            aliases = listOf("restrictsubclaim", "rsc"),
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val claim = claimManager.getClaimAt(player.location)
            if (claim == null || !claim.isSubclaim) {
                ctx.messages.sendRaw(player, "<yellow>You must be standing inside a subclaim.</yellow>")
                return@registerSubcommand true
            }
            val adminBypass = sessions.state(player.uniqueId).adminBypass && player.hasPermission("claims.admin")
            if (!claim.hasPermission(player.uniqueId, ClaimPermission.MANAGE, adminBypass)) {
                ctx.actionBar.sendDenial(player, "deny-manage", mapOf("owner" to claim.ownerName()))
                return@registerSubcommand true
            }
            val next = !claim.inheritNothing
            claimManager.updateMetadata(claim, claim.name, claim.greeting, next)
            ctx.messages.send(player, "subclaim-restricted", mapOf("state" to if (next) "Isolated" else "Inheriting"))
            true
        }

        registry.registerSubcommand(
            name = "abandon",
            description = "Abandon the current claim",
            permission = "claims.claim",
            aliases = listOf("abandonclaim", "unclaim"),
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val claim = claimManager.getClaimAt(player.location)
            if (claim == null) {
                ctx.messages.send(player, "not-in-claim")
                return@registerSubcommand true
            }
            val adminBypass = sessions.state(player.uniqueId).adminBypass && player.hasPermission("claims.admin")
            if (claim.ownerId != player.uniqueId && !adminBypass) {
                ctx.actionBar.sendDenial(player, "deny-manage", mapOf("owner" to claim.ownerName()))
                return@registerSubcommand true
            }
            ctx.dialogs.openConfirmationDialog(
                player,
                "<red><bold>Abandon Claim</bold></red>",
                "<gray>Are you sure you want to abandon <white>${claim.name}</white>?</gray>",
                "<red>Abandon</red>"
            ) { p ->
                val refund = claimManager.deleteClaim(claim)
                boundaryVisualizer.clear(p)
                ctx.messages.send(p, "claim-abandoned", mapOf("refund" to refund))
            }
            true
        }

        registry.registerSubcommand(
            name = "abandonall",
            description = "Abandon all claims you own",
            permission = "claims.claim",
            aliases = listOf("abandonallclaims"),
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val owned = claimManager.getPlayerClaims(player.uniqueId)
            if (owned.isEmpty()) {
                ctx.messages.sendRaw(player, "<gray>You do not own any claims.</gray>")
                return@registerSubcommand true
            }
            ctx.dialogs.openConfirmationDialog(
                player,
                "<red><bold>Abandon All Claims</bold></red>",
                "<gray>Permanently abandon all <white>${owned.size}</white> claims you own.\nAre you sure you want to continue?</gray>",
                "<red>Abandon All</red>"
            ) { p ->
                val (count, refund) = claimManager.deleteAllPlayerClaims(p.uniqueId)
                boundaryVisualizer.clear(p)
                ctx.messages.send(p, "claims-abandoned-all", mapOf("count" to count, "refund" to refund))
            }
            true
        }

        registry.registerSubcommand(
            name = "subdivide",
            description = "Toggle subclaim shovel mode",
            permission = "claims.claim",
            aliases = listOf("subdivideclaims", "sc"),
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val st = sessions.state(player.uniqueId)
            st.shovelMode = if (st.shovelMode == ShovelMode.SUBDIVIDE) ShovelMode.BASIC else ShovelMode.SUBDIVIDE
            ctx.actionBar.tickPlayer(player)
            ctx.messages.sendRaw(
                player,
                "<aqua>Claim tool mode switched to <white>${st.shovelMode.label}</white>.</aqua>"
            )
            true
        }

        registry.registerSubcommand(
            name = "adminclaims",
            description = "Toggle admin claim shovel mode",
            permission = "claims.admin",
            aliases = listOf("ac"),
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val st = sessions.state(player.uniqueId)
            st.shovelMode = if (st.shovelMode == ShovelMode.ADMIN) ShovelMode.BASIC else ShovelMode.ADMIN
            ctx.actionBar.tickPlayer(player)
            ctx.messages.sendRaw(
                player,
                "<gold>Claim tool mode switched to <white>${st.shovelMode.label}</white>.</gold>"
            )
            true
        }

        registry.registerSubcommand(
            name = "adjustblocks",
            description = "Adjust bonus claim blocks for a player",
            permission = "claims.admin",
            aliases = listOf("adjustclaimblocks", "acb"),
            tabCompleter = playerSuggest,
        ) { sender, args ->
            if (args.size < 2) {
                val player = sender as? Player
                if (player != null) {
                    dialogs.openAdminClaimBlocksDialog(player)
                } else {
                    ctx.messages.sendRaw(sender, "<yellow>Usage: /adjustclaimblocks <player> <amount></yellow>")
                }
                return@registerSubcommand true
            }
            val targetName = args[0]
            val delta = args[1].toIntOrNull()
            if (delta == null) {
                ctx.messages.sendRaw(sender, "<red>Amount must be a valid integer.</red>")
                return@registerSubcommand true
            }
            val offline = Bukkit.getOfflinePlayer(targetName)
            claimManager.addBonusBlocks(offline.uniqueId, delta)
            val data = claimManager.getOrCreatePlayerData(offline.uniqueId)
            val avail = claimManager.getAvailableClaimBlocks(offline.uniqueId)
            ctx.messages.send(
                sender,
                "claim-blocks-adjusted",
                mapOf("target" to targetName, "bonus" to data.bonusBlocks, "available" to avail)
            )
            true
        }

        registry.registerSubcommand(
            name = "trapped",
            description = "Escape to nearby wilderness if trapped inside a claim",
            permission = "claims.claim",
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val claim =
                claimManager.getTopLevelClaimAt(player.world.name, player.location.blockX, player.location.blockZ)
            if (claim == null || claim.hasPermission(player.uniqueId, ClaimPermission.BUILD, false)) {
                ctx.messages.send(player, "trapped-not-needed")
                return@registerSubcommand true
            }
            val b = claim.bounds
            val loc = player.location
            val candidates = listOf(
                Location(player.world, (b.minX - 2).toDouble(), loc.y, loc.z),
                Location(player.world, (b.maxX + 2).toDouble(), loc.y, loc.z),
                Location(player.world, loc.x, loc.y, (b.minZ - 2).toDouble()),
                Location(player.world, loc.x, loc.y, (b.maxZ + 2).toDouble())
            )
            val best =
                candidates.filter { claimManager.getTopLevelClaimAt(it.world.name, it.blockX, it.blockZ) == null }
                    .minByOrNull { it.distanceSquared(loc) }
            if (best == null) {
                ctx.messages.sendRaw(player, "<red>Could not find a nearby safe wilderness spot.</red>")
                return@registerSubcommand true
            }
            val safeY = player.world.getHighestBlockYAt(best.blockX, best.blockZ) + 1
            best.x = best.blockX + 0.5
            best.y = safeY.toDouble()
            best.z = best.blockZ + 0.5
            player.teleport(best)
            ctx.messages.send(player, "trapped-success")
            true
        }

        registry.registerSubcommand(
            name = "cancel",
            description = "Cancel active claim tool selection and visualization",
            permission = "claims.use",
            standaloneByDefault = false,
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            toolService.cancelSelection(player)
            ctx.messages.sendRaw(player, "<gray>Cleared active claim selection.</gray>")
            true
        }
    }

    private fun renderShovelHud(player: Player): Component? {
        val cfg = ctx.config
        if (player.inventory.itemInMainHand.type != cfg.claimTool) return null
        val st = sessions.state(player.uniqueId)
        val available = claimManager.getAvailableClaimBlocks(player.uniqueId)
        val corner = st.pendingCorner1
        if (corner != null && corner.world?.name == player.world.name) {
            val target = player.location
            val w = kotlin.math.abs(target.blockX - corner.blockX) + 1
            val l = kotlin.math.abs(target.blockZ - corner.blockZ) + 1
            val area = w * l
            return ctx.messages.render(
                "shovel-corner-hud",
                mapOf(
                    "x" to corner.blockX,
                    "z" to corner.blockZ,
                    "w" to w,
                    "l" to l,
                    "area" to area,
                    "available" to available,
                )
            )
        }
        return ctx.messages.render(
            "shovel-idle-hud",
            mapOf("mode" to st.shovelMode.label, "available" to available)
        )
    }

    private fun renderTerritoryHud(player: Player): Component? {
        val cfg = ctx.config
        val st = sessions.state(player.uniqueId)
        val claim = claimManager.getClaimAt(player.location)
        val showBanner = cfg.bossBarTerritoryBanner || st.territoryBanner
        if (claim == null) {
            st.lastClaimId = null
            ctx.bossBar.hide(player, "territory_banner")
            return ctx.messages.render("wilderness")
        }

        if (st.lastClaimId != claim.id) {
            st.lastClaimId = claim.id
            val greeting = claim.greeting
            if (!greeting.isNullOrBlank() && claim.flag(ClaimFlag.ENTRY_NOTIFY, cfg.defaultFlags)) {
                val greetingComp = ctx.messages.renderRaw("<aqua>⚑ $greeting</aqua>")
                ctx.actionBar.flash(player, greetingComp, 2500L)
                return greetingComp
            }
        }

        val displayTitle = if (claim.isSubclaim && claim.parent != null) {
            val lockTag = if (claim.inheritNothing) " <red>[Isolated]</red>" else ""
            "${claim.parent!!.name} › ${claim.name}$lockTag"
        } else {
            claim.name
        }

        if (showBanner) {
            val pvpTag =
                if (claim.flag(ClaimFlag.PVP, cfg.defaultFlags)) "<red>⚔ PvP</red>" else "<green>🛡 Safe</green>"
            ctx.bossBar.showOrUpdate(
                player,
                "territory_banner",
                ctx.messages.renderRaw("<aqua>${claim.ownerName()}</aqua> <gray>·</gray> <white>$displayTitle</white> <gray>|</gray> $pvpTag"),
                1.0f,
                if (claim.isAdminClaim) BossBar.Color.YELLOW else BossBar.Color.GREEN,
            )
        } else {
            ctx.bossBar.hide(player, "territory_banner")
        }

        if (claim.isAdminClaim) {
            return ctx.messages.render("claim-hud-admin", mapOf("name" to displayTitle))
        }
        val perm = claim.effectivePermission(player.uniqueId)
        val badge = when {
            perm == ClaimPermission.OWNER -> "<green>[Owner]</green>"
            perm != null -> "<aqua>[${perm.displayName}]</aqua>"
            claim.flag(ClaimFlag.PVP, cfg.defaultFlags) -> "<red>[⚔ PvP]</red>"
            else -> "<gray>[Protected]</gray>"
        }
        return ctx.messages.render(
            "claim-hud",
            mapOf("owner" to claim.ownerName(), "name" to displayTitle, "badge" to badge)
        )
    }

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender is Player) return sender
        ctx.messages.send(sender, "player-only")
        return null
    }

    private fun reportClaimFailure(player: Player, failure: ClaimResult.Failure) {
        val cfg = ctx.config
        when (failure.reason) {
            ClaimResult.Reason.OVERLAPS_EXISTING -> ctx.messages.send(player, "claim-overlap")
            ClaimResult.Reason.TOO_SMALL -> ctx.messages.send(
                player, "claim-too-small", mapOf("min_w" to cfg.minClaimWidth, "min_area" to cfg.minClaimArea)
            )

            ClaimResult.Reason.INSUFFICIENT_BLOCKS -> ctx.messages.send(
                player, "claim-insufficient-blocks", mapOf("needed" to failure.needed, "available" to failure.available)
            )

            ClaimResult.Reason.OUTSIDE_PARENT -> ctx.messages.sendRaw(
                player, "<red>Subclaim cannot extend outside its parent claim.</red>"
            )

            else -> ctx.messages.sendRaw(player, "<red>Unable to create or update claim.</red>")
        }
    }

    private fun cardinalFacing(yaw: Float): BlockFace {
        val norm = ((yaw % 360f) + 360f) % 360f
        return when {
            norm < 45f || norm >= 315f -> BlockFace.SOUTH
            norm < 135f -> BlockFace.WEST
            norm < 225f -> BlockFace.NORTH
            else -> BlockFace.EAST
        }
    }
}
