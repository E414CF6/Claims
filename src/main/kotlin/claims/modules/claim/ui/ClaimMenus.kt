package claims.modules.claim.ui

import claims.core.config.ClaimsConfig
import claims.core.locale.MessageService
import claims.core.ui.ActionBarService
import claims.core.ui.BossBarService
import claims.core.ui.DialogKit
import claims.core.ui.MenuItems
import claims.core.ui.MenuWindow
import claims.modules.claim.BoundaryVisualizer
import claims.modules.claim.Claim
import claims.modules.claim.ClaimFlag
import claims.modules.claim.ClaimManager
import claims.modules.claim.ClaimPermission
import claims.modules.claim.ClaimSessionStore
import claims.modules.claim.ShovelMode
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import java.util.UUID

class ClaimMenus(
    private val configProvider: () -> ClaimsConfig,
    private val messages: MessageService,
    private val claimManager: ClaimManager,
    private val visualizer: BoundaryVisualizer,
    private val actionBar: ActionBarService,
    private val bossBar: BossBarService,
    private val sessions: ClaimSessionStore,
    private val dialogKit: DialogKit,
    private val claimDialogsProvider: () -> ClaimDialogs,
) {
    fun openDashboard(player: Player) {
        val window =
            MenuWindow(messages.renderRaw("<gradient:#38bdf8:#818cf8><bold>Claims Dashboard</bold></gradient>"), 4)
        window.fillBorder(Material.GRAY_STAINED_GLASS_PANE)

        val state = sessions.state(player.uniqueId)
        val available = claimManager.getAvailableClaimBlocks(player.uniqueId)
        val used = claimManager.getUsedClaimBlocks(player.uniqueId)
        val myClaims = claimManager.getPlayerClaims(player.uniqueId)
        val currentClaim = claimManager.getClaimAt(player.location)

        window.set(
            4,
            MenuItems.item(
                Material.SHIELD,
                "<aqua><bold>Claims Overview</bold></aqua>",
                listOf(
                    "<gray>Owned Claims:</gray> <white>${myClaims.size}</white>",
                    "<gray>Used Blocks:</gray> <white>$used</white>",
                    "<gray>Available Blocks:</gray> <green>$available</green>",
                    "<gray>Current Location:</gray> <white>${currentClaim?.name ?: "Wilderness"}</white>"
                ),
                messages,
            )
        )

        window.set(
            11,
            MenuItems.item(
                Material.GRASS_BLOCK,
                "<green><bold>My Claims</bold></green>",
                listOf(
                    "<gray>Browse claims you own or are trusted in,",
                    "<gray>and teleport, visualize, or configure them."
                ),
                messages,
            )
        ) { click ->
            openClaimList(click.player, 0)
        }

        val currentDesc = if (currentClaim != null) {
            listOf(
                "<gray>Standing in:</gray> <white>${currentClaim.name}</white>",
                "<yellow>Click to manage settings & trusts</yellow>"
            )
        } else {
            listOf("<red>You are not inside a claim.</red>", "<gray>Click to create a claim around you.</gray>")
        }
        window.set(
            13,
            MenuItems.item(
                Material.COMPARATOR,
                "<aqua><bold>Manage Current Claim</bold></aqua>",
                currentDesc,
                messages,
            )
        ) { click ->
            val current = claimManager.getClaimAt(click.player.location)
            if (current != null) {
                openClaimDetail(click.player, current)
            } else {
                click.player.closeInventory()
                click.player.performCommand("claims claim")
            }
        }

        window.set(
            15,
            MenuItems.item(
                configProvider().claimTool,
                "<gold><bold>Claim Tool & Mode</bold></gold>",
                listOf(
                    "<gray>Current Mode:</gray> <aqua>${state.shovelMode.label}</aqua>",
                    "<yellow>Left-click: Get tool | Right-click: Cycle mode</yellow>"
                ),
                messages,
            )
        ) { click ->
            if (click.clickType == ClickType.RIGHT) {
                state.shovelMode = when (state.shovelMode) {
                    ShovelMode.BASIC -> ShovelMode.SUBDIVIDE
                    ShovelMode.SUBDIVIDE -> if (click.player.hasPermission("claims.admin")) ShovelMode.ADMIN else ShovelMode.BASIC
                    ShovelMode.ADMIN -> ShovelMode.BASIC
                }
                openDashboard(click.player)
            } else {
                val tool = configProvider().claimTool
                if (!click.player.inventory.contains(tool)) {
                    click.player.inventory.addItem(ItemStack(tool))
                }
                click.player.closeInventory()
                actionBar.flash(
                    click.player,
                    messages.renderRaw("<aqua>⛏ Claim tool (${tool.name}) ready! Right-click two corners.</aqua>")
                )
            }
        }

        window.set(
            21,
            MenuItems.item(
                if (state.territoryBanner) Material.BEACON else Material.LANTERN,
                if (state.territoryBanner) "<aqua>BossBar Territory Banner: ON</aqua>" else "<gray>BossBar Territory Banner: OFF</gray>",
                listOf("<yellow>Display claim status on top BossBar</yellow>"),
                messages,
            )
        ) { click ->
            state.territoryBanner = !state.territoryBanner
            if (!state.territoryBanner) bossBar.hide(click.player, "territory_banner")
            actionBar.tickPlayer(click.player)
            openDashboard(click.player)
        }

        val hudOn = actionBar.isHudEnabled(player.uniqueId)
        window.set(
            23,
            MenuItems.item(
                if (hudOn) Material.BELL else Material.BARRIER,
                if (hudOn) "<aqua>ActionBar HUD: ON</aqua>" else "<gray>ActionBar HUD: OFF</gray>",
                listOf("<yellow>Click to toggle persistent ActionBar HUD</yellow>"),
                messages,
            )
        ) { click ->
            val next = !actionBar.isHudEnabled(click.player.uniqueId)
            actionBar.setHudEnabled(click.player.uniqueId, next)
            if (!next) click.player.sendActionBar(Component.empty())
            openDashboard(click.player)
        }

        if (player.hasPermission("claims.admin")) {
            window.set(
                30,
                MenuItems.item(
                    Material.COMMAND_BLOCK,
                    "<red><bold>Admin Control Panel</bold></red>",
                    listOf("<gray>Adjust claim blocks and inspect loaded claims."),
                    messages,
                )
            ) { click ->
                openAdminPanel(click.player)
            }
            window.set(
                32,
                MenuItems.item(
                    if (state.adminBypass) Material.NETHER_STAR else Material.IRON_INGOT,
                    if (state.adminBypass) "<red><bold>Admin Bypass: ON</bold></red>" else "<gray>Admin Bypass: OFF</gray>",
                    listOf("<yellow>Temporarily bypass all claim restrictions.</yellow>"),
                    messages,
                )
            ) { click ->
                if (click.player.hasPermission("claims.admin")) {
                    state.adminBypass = !state.adminBypass
                    openDashboard(click.player)
                }
            }
        }

        window.open(player)
    }

    fun openAdminPanel(player: Player) {
        if (!player.hasPermission("claims.admin")) return
        val window = MenuWindow(messages.renderRaw("<red><bold>Claims Admin Panel</bold></red>"), 3)
        window.fillBorder(Material.RED_STAINED_GLASS_PANE)

        window.set(
            11,
            MenuItems.item(
                Material.GOLD_INGOT,
                "<gold><bold>Adjust Claim Blocks (Dialog)</bold></gold>",
                listOf("<gray>Add or subtract bonus claim blocks."),
                messages,
            )
        ) { click ->
            click.player.closeInventory()
            claimDialogsProvider().openAdminClaimBlocksDialog(click.player)
        }

        window.set(
            15,
            MenuItems.item(
                Material.REDSTONE_TORCH,
                "<aqua><bold>Claims System Status</bold></aqua>",
                listOf(
                    "<gray>Loaded Top-level Claims:</gray> <white>${claimManager.getAllTopLevelClaims().size}</white>",
                    "<gray>Online Players:</gray> <white>${Bukkit.getOnlinePlayers().size}</white>"
                ),
                messages,
            )
        )

        window.set(
            22,
            MenuItems.item(Material.OAK_DOOR, "<gray>Back to Dashboard</gray>", emptyList(), messages)
        ) { click ->
            openDashboard(click.player)
        }
        window.open(player)
    }

    fun openClaimList(player: Player, page: Int = 0) {
        val owned = claimManager.getPlayerClaims(player.uniqueId)
        val trusted = claimManager.getTrustedClaims(player.uniqueId)
        val all = (owned + trusted).distinctBy { it.id }

        val pageSize = 28
        val totalPages = maxOf(1, (all.size + pageSize - 1) / pageSize)
        val safePage = page.coerceIn(0, totalPages - 1)

        val window =
            MenuWindow(messages.renderRaw("<aqua><bold>My Claims (${safePage + 1}/$totalPages)</bold></aqua>"), 6)
        window.fillBorder(Material.BLACK_STAINED_GLASS_PANE)

        val slice = all.drop(safePage * pageSize).take(pageSize)
        slice.forEachIndexed { idx, claim ->
            val slot = MenuWindow.CONTENT_SLOTS[idx]
            val isOwner = claim.ownerId == player.uniqueId
            val mat =
                if (claim.isAdminClaim) Material.BEACON else if (isOwner) Material.EMERALD_BLOCK else Material.GOLD_BLOCK
            window.set(
                slot,
                MenuItems.item(
                    mat,
                    "<aqua>${claim.name}</aqua> <dark_gray>#${claim.id}</dark_gray>",
                    listOf(
                        "<gray>Owner:</gray> <white>${claim.ownerName()}</white>",
                        "<gray>World:</gray> <white>${claim.bounds.worldName}</white>",
                        "<gray>Bounds:</gray> <white>(${claim.bounds.minX}, ${claim.bounds.minZ}) ~ (${claim.bounds.maxX}, ${claim.bounds.maxZ})</white>",
                        "<gray>Size:</gray> <white>${claim.bounds.width}×${claim.bounds.length} (${claim.bounds.area} blocks)</white>",
                        "<gray>Subclaims:</gray> <white>${claim.children.size}</white>",
                        "",
                        "<yellow>Left-click: Manage details & trusts</yellow>",
                        "<aqua>Right-click: Visualize & Teleport</aqua>"
                    ),
                    messages,
                )
            ) { click ->
                if (click.clickType == ClickType.RIGHT) {
                    click.player.closeInventory()
                    visualizer.showClaim(click.player, claim)
                    teleportToClaimCenter(click.player, claim)
                } else {
                    openClaimDetail(click.player, claim)
                }
            }
        }

        if (safePage > 0) {
            window.set(
                45,
                MenuItems.item(Material.ARROW, "<yellow>Previous Page</yellow>", emptyList(), messages)
            ) { click ->
                openClaimList(click.player, safePage - 1)
            }
        }
        window.set(
            49,
            MenuItems.item(Material.OAK_DOOR, "<gray>Back to Dashboard</gray>", emptyList(), messages)
        ) { click ->
            openDashboard(click.player)
        }
        if (safePage + 1 < totalPages) {
            window.set(
                53,
                MenuItems.item(Material.ARROW, "<yellow>Next Page</yellow>", emptyList(), messages)
            ) { click ->
                openClaimList(click.player, safePage + 1)
            }
        }
        window.open(player)
    }

    fun openClaimDetail(player: Player, claim: Claim) {
        val window = MenuWindow(messages.renderRaw("<aqua><bold>Claim: ${claim.name}</bold></aqua>"), 3)
        window.fillBorder(Material.GRAY_STAINED_GLASS_PANE)

        val adminBypass = sessions.state(player.uniqueId).adminBypass && player.hasPermission("claims.admin")
        val canManage = claim.hasPermission(player.uniqueId, ClaimPermission.MANAGE, adminBypass)
        val isOwner = claim.ownerId == player.uniqueId || adminBypass

        window.set(
            10,
            MenuItems.item(
                Material.PLAYER_HEAD,
                "<green><bold>Trusted Players & Permissions</bold></green>",
                listOf(
                    "<gray>Trusted Entries:</gray> <white>${claim.trusts.size}</white>",
                    "<yellow>Click to open trust matrix</yellow>"
                ),
                messages,
            )
        ) { click ->
            openClaimTrustMenu(click.player, claim, 0)
        }

        if (canManage) {
            window.set(
                11,
                MenuItems.item(
                    Material.WRITABLE_BOOK,
                    "<aqua><bold>Claim Name & Flags (Dialog)</bold></aqua>",
                    listOf(
                        "<gray>Edit PvP, explosions, fire spread, mob griefing,",
                        "<gray>and greeting message via Paper Dialog."
                    ),
                    messages,
                )
            ) { click ->
                click.player.closeInventory()
                claimDialogsProvider().openClaimSettingsDialog(click.player, claim)
            }
        }

        if (!claim.isSubclaim) {
            window.set(
                13,
                MenuItems.item(
                    Material.IRON_BARS,
                    "<light_purple><bold>Subclaims</bold></light_purple>",
                    listOf(
                        "<gray>Subclaims:</gray> <white>${claim.children.size}</white>",
                        "<yellow>Click to list & configure subclaims</yellow>"
                    ),
                    messages,
                )
            ) { click ->
                openSubclaimList(click.player, claim)
            }
        }

        window.set(
            14,
            MenuItems.item(
                Material.GLOWSTONE,
                "<yellow><bold>Visualize & Teleport</bold></yellow>",
                listOf("<gray>Left-click: Show boundary", "<aqua>Right-click: Teleport to center</aqua>"),
                messages,
            )
        ) { click ->
            click.player.closeInventory()
            visualizer.showClaim(click.player, claim)
            if (click.clickType == ClickType.RIGHT) {
                teleportToClaimCenter(click.player, claim)
            }
        }

        if (isOwner && !claim.isSubclaim) {
            window.set(
                15,
                MenuItems.item(
                    Material.NAME_TAG,
                    "<gold><bold>Transfer Ownership (Dialog)</bold></gold>",
                    listOf("<gray>Transfer this claim to another player."),
                    messages,
                )
            ) { click ->
                click.player.closeInventory()
                claimDialogsProvider().openTransferClaimDialog(click.player, claim)
            }
        }

        if (isOwner) {
            window.set(
                16,
                MenuItems.item(
                    Material.TNT,
                    "<red><bold>Abandon Claim</bold></red>",
                    listOf("<gray>Opens confirmation Dialog before deleting."),
                    messages,
                )
            ) { click ->
                click.player.closeInventory()
                dialogKit.openConfirmationDialog(
                    click.player,
                    "<red><bold>Abandon Claim</bold></red>",
                    "<gray>Are you sure you want to abandon <white>${claim.name}</white>?\nAll trust entries and flags for this claim will be deleted.</gray>",
                    "<red>Abandon</red>"
                ) { p ->
                    val refund = claimManager.deleteClaim(claim)
                    visualizer.clear(p)
                    messages.send(p, "claim-abandoned", mapOf("refund" to refund))
                }
            }
        }

        window.set(
            22,
            MenuItems.item(Material.OAK_DOOR, "<gray>Back to Claims</gray>", emptyList(), messages)
        ) { click ->
            openClaimList(click.player, 0)
        }
        window.open(player)
    }

    fun openSubclaimList(player: Player, parent: Claim) {
        val window =
            MenuWindow(messages.renderRaw("<light_purple><bold>Subclaims: ${parent.name}</bold></light_purple>"), 6)
        window.fillBorder(Material.PURPLE_STAINED_GLASS_PANE)

        parent.children.take(28).forEachIndexed { idx, sub ->
            val slot = MenuWindow.CONTENT_SLOTS[idx]
            window.set(
                slot,
                MenuItems.item(
                    if (sub.inheritNothing) Material.IRON_DOOR else Material.OAK_FENCE_GATE,
                    "<light_purple>${sub.name}</light_purple> <dark_gray>#${sub.id}</dark_gray>",
                    listOf(
                        "<gray>Isolated (inheritNothing):</gray> ${if (sub.inheritNothing) "<red>ON</red>" else "<green>OFF</green>"}",
                        "<gray>Bounds:</gray> <white>(${sub.bounds.minX}, ${sub.bounds.minY}, ${sub.bounds.minZ}) ~ (${sub.bounds.maxX}, ${sub.bounds.maxY}, ${sub.bounds.maxZ})</white>",
                        "<yellow>Left-click: Settings Dialog (Name / Isolation)</yellow>",
                        "<aqua>Right-click: Visualize boundary</aqua>"
                    ),
                    messages,
                )
            ) { click ->
                if (click.clickType == ClickType.RIGHT) {
                    click.player.closeInventory()
                    visualizer.showClaim(click.player, sub, Material.IRON_BLOCK)
                } else {
                    click.player.closeInventory()
                    claimDialogsProvider().openSubclaimSettingsDialog(click.player, sub)
                }
            }
        }

        window.set(
            4,
            MenuItems.item(
                Material.GOLDEN_SHOVEL,
                "<aqua>How to Create a Subclaim</aqua>",
                listOf(
                    "<gray>Run /claims subdivide and right-click two corners",
                    "<gray>inside the parent claim with the claim tool."
                ),
                messages,
            )
        )
        window.set(
            49,
            MenuItems.item(Material.OAK_DOOR, "<gray>Back to Parent Claim</gray>", emptyList(), messages)
        ) { click ->
            openClaimDetail(click.player, parent)
        }
        window.open(player)
    }

    fun openClaimTrustMenu(player: Player, claim: Claim, page: Int = 0) {
        val entries = claim.trusts.entries.sortedBy { it.key }
        val pageSize = 28
        val totalPages = maxOf(1, (entries.size + pageSize - 1) / pageSize)
        val safePage = page.coerceIn(0, totalPages - 1)

        val window = MenuWindow(messages.renderRaw("<green><bold>Trust Matrix: ${claim.name}</bold></green>"), 6)
        window.fillBorder(Material.GRAY_STAINED_GLASS_PANE)

        entries.drop(safePage * pageSize).take(pageSize).forEachIndexed { idx, (key, level) ->
            val slot = MenuWindow.CONTENT_SLOTS[idx]
            val displayName = if (key == Claim.PUBLIC_KEY) {
                "Public (Everyone)"
            } else {
                runCatching { Bukkit.getOfflinePlayer(UUID.fromString(key)).name ?: key.take(8) }.getOrDefault(key)
            }
            val lore = listOf(
                "<gray>Current Tier:</gray> <green>${level.displayName} (${level.name})</green>",
                "",
                "<yellow>Left-click: Cycle next tier</yellow>",
                "<red>Right-click: Remove trust</red>"
            )
            val item = if (key == Claim.PUBLIC_KEY) {
                MenuItems.item(Material.ENDER_PEARL, "<aqua>$displayName</aqua>", lore, messages)
            } else {
                MenuItems.playerHead(
                    Bukkit.getOfflinePlayer(UUID.fromString(key)),
                    "<aqua>$displayName</aqua>",
                    lore,
                    messages
                )
            }
            window.set(slot, item) { click ->
                val adminBypass =
                    sessions.state(click.player.uniqueId).adminBypass && click.player.hasPermission("claims.admin")
                if (!claim.hasPermission(click.player.uniqueId, ClaimPermission.MANAGE, adminBypass)) {
                    actionBar.sendDenial(click.player, "deny-manage", mapOf("owner" to claim.ownerName()))
                    return@set
                }
                if (click.clickType == ClickType.RIGHT) {
                    click.player.closeInventory()
                    dialogKit.openConfirmationDialog(
                        click.player,
                        "<red><bold>Remove Claim Trust</bold></red>",
                        "<gray>Remove this player's trust in <white>${claim.name}</white>?</gray>",
                        "<red>Remove</red>"
                    ) { p ->
                        claimManager.removeTrust(claim, key)
                        messages.send(p, "trust-removed", mapOf("target" to displayName))
                        openClaimTrustMenu(p, claim, safePage)
                    }
                } else {
                    val current = claim.trusts[key] ?: ClaimPermission.ACCESS
                    claimManager.setTrust(claim, key, current.nextTrustTier())
                    openClaimTrustMenu(click.player, claim, safePage)
                }
            }
        }

        window.set(
            4,
            MenuItems.item(
                Material.EMERALD,
                "<green><bold>+ Add Trusted Player (Dialog)</bold></green>",
                listOf("<gray>Open input & tier selector Dialog."),
                messages,
            )
        ) { click ->
            click.player.closeInventory()
            claimDialogsProvider().openTrustAddDialog(click.player, claim)
        }
        window.set(
            49,
            MenuItems.item(Material.OAK_DOOR, "<gray>Back to Claim Details</gray>", emptyList(), messages)
        ) { click ->
            openClaimDetail(click.player, claim)
        }
        window.open(player)
    }

    // --- Legacy (< 1.21.6) GUI Fallbacks ---

    fun openClaimFlagsMenu(player: Player, claim: Claim) {
        val cfg = configProvider()
        val window = MenuWindow(messages.renderRaw("<aqua><bold>Claim Flags: ${claim.name}</bold></aqua>"), 4)
        window.fillBorder(Material.GRAY_STAINED_GLASS_PANE)

        val slots = listOf(10, 11, 12, 13, 14, 15, 16)
        ClaimFlag.entries.forEachIndexed { idx, flag ->
            val enabled = claim.flag(flag, cfg.defaultFlags)
            window.set(
                slots[idx],
                MenuItems.item(
                    if (enabled) Material.LIME_DYE else Material.GRAY_DYE,
                    "<aqua>${flag.label}</aqua>",
                    listOf(
                        "<gray>Current:</gray> ${if (enabled) "<green>ENABLED</green>" else "<red>DISABLED</red>"}",
                        "<yellow>Click to toggle</yellow>"
                    ),
                    messages,
                )
            ) { click ->
                val next = !claim.flag(flag, configProvider().defaultFlags)
                claimManager.setFlag(claim, flag, next)
                openClaimFlagsMenu(click.player, claim)
            }
        }
        window.set(
            31,
            MenuItems.item(Material.OAK_DOOR, "<gray>Back to Claim Details</gray>", emptyList(), messages)
        ) { click ->
            openClaimDetail(click.player, claim)
        }
        window.open(player)
    }

    fun openTrustSelectPlayerMenu(player: Player, claim: Claim) {
        val window = MenuWindow(messages.renderRaw("<green><bold>Select Player to Trust</bold></green>"), 6)
        window.fillBorder(Material.GRAY_STAINED_GLASS_PANE)

        window.set(
            4,
            MenuItems.item(
                Material.ENDER_PEARL,
                "<aqua>Trust Public (Everyone)</aqua>",
                listOf("<yellow>Click to grant Access trust to everyone</yellow>"),
                messages,
            )
        ) { click ->
            claimManager.setTrust(claim, Claim.PUBLIC_KEY, ClaimPermission.ACCESS)
            openClaimTrustMenu(click.player, claim, 0)
        }

        Bukkit.getOnlinePlayers().filter { it.uniqueId != claim.ownerId }.take(28).forEachIndexed { idx, online ->
            window.set(
                MenuWindow.CONTENT_SLOTS[idx],
                MenuItems.playerHead(
                    online,
                    "<aqua>${online.name}</aqua>",
                    listOf("<yellow>Click to grant Build trust</yellow>"),
                    messages,
                )
            ) { click ->
                claimManager.setTrust(claim, online.uniqueId.toString(), ClaimPermission.BUILD)
                openClaimTrustMenu(click.player, claim, 0)
            }
        }
        window.set(
            49,
            MenuItems.item(Material.OAK_DOOR, "<gray>Back to Trust Matrix</gray>", emptyList(), messages)
        ) { click ->
            openClaimTrustMenu(click.player, claim, 0)
        }
        window.open(player)
    }

    fun openTransferSelectPlayerMenu(player: Player, claim: Claim) {
        val window = MenuWindow(messages.renderRaw("<gold><bold>Select New Claim Owner</bold></gold>"), 6)
        window.fillBorder(Material.GRAY_STAINED_GLASS_PANE)

        Bukkit.getOnlinePlayers().filter { it.uniqueId != claim.ownerId }.take(28).forEachIndexed { idx, online ->
            window.set(
                MenuWindow.CONTENT_SLOTS[idx],
                MenuItems.playerHead(
                    online,
                    "<gold>${online.name}</gold>",
                    listOf("<yellow>Click to transfer ownership</yellow>"),
                    messages,
                )
            ) { click ->
                dialogKit.openConfirmationMenu(
                    click.player,
                    "<gold><bold>Confirm Ownership Transfer</bold></gold>",
                    "Transfer ${claim.name} to ${online.name}?"
                ) { p ->
                    claimManager.transferClaim(claim, online.uniqueId)
                    messages.send(p, "claim-transferred", mapOf("target" to online.name))
                }
            }
        }
        window.set(
            49,
            MenuItems.item(Material.OAK_DOOR, "<gray>Back to Claim Details</gray>", emptyList(), messages)
        ) { click ->
            openClaimDetail(click.player, claim)
        }
        window.open(player)
    }

    fun openAdminClaimBlocksMenu(player: Player) {
        val window = MenuWindow(messages.renderRaw("<red><bold>Adjust Bonus Claim Blocks (+500)</bold></red>"), 6)
        window.fillBorder(Material.RED_STAINED_GLASS_PANE)

        Bukkit.getOnlinePlayers().take(28).forEachIndexed { idx, online ->
            val avail = claimManager.getAvailableClaimBlocks(online.uniqueId)
            window.set(
                MenuWindow.CONTENT_SLOTS[idx],
                MenuItems.playerHead(
                    online,
                    "<aqua>${online.name}</aqua>",
                    listOf(
                        "<gray>Available:</gray> <green>$avail</green>",
                        "<yellow>Left-click: +500 blocks | Right-click: -500 blocks</yellow>"
                    ),
                    messages,
                )
            ) { click ->
                if (!click.player.hasPermission("claims.admin")) return@set
                val delta = if (click.clickType == ClickType.RIGHT) -500 else 500
                claimManager.addBonusBlocks(online.uniqueId, delta)
                openAdminClaimBlocksMenu(click.player)
            }
        }
        window.set(
            49,
            MenuItems.item(Material.OAK_DOOR, "<gray>Back to Admin Panel</gray>", emptyList(), messages)
        ) { click ->
            openAdminPanel(click.player)
        }
        window.open(player)
    }

    private fun teleportToClaimCenter(player: Player, claim: Claim) {
        val world = Bukkit.getWorld(claim.bounds.worldName) ?: return
        val cx = (claim.bounds.minX + claim.bounds.maxX) / 2
        val cz = (claim.bounds.minZ + claim.bounds.maxZ) / 2
        val cy = world.getHighestBlockYAt(cx, cz) + 1
        player.teleport(Location(world, cx + 0.5, cy.toDouble(), cz + 0.5))
    }
}
