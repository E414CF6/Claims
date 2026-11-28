package claims.modules.claim.ui

import claims.core.config.ClaimsConfig
import claims.core.locale.MessageService
import claims.core.ui.DialogKit
import claims.modules.claim.Claim
import claims.modules.claim.ClaimManager
import org.bukkit.entity.Player

/**
 * Dialog facade for claim management, routing to native Paper Dialogs on MC 1.21.6+
 * or falling back cleanly to [ClaimMenus] / [DialogKit] Inventory GUIs on older versions.
 */
class ClaimDialogs(
    private val configProvider: () -> ClaimsConfig,
    private val messages: MessageService,
    private val claimManager: ClaimManager,
    private val dialogKit: DialogKit,
    private val menusProvider: () -> ClaimMenus,
) {
    private val paperBackend: PaperClaimDialogs? by lazy {
        if (dialogKit.supportsNativeDialogs) {
            PaperClaimDialogs(configProvider, messages, claimManager) { player, claim ->
                menusProvider().openClaimDetail(player, claim)
            }
        } else {
            null
        }
    }

    fun openClaimSettingsDialog(player: Player, claim: Claim) {
        val backend = paperBackend
        if (backend != null) {
            backend.openClaimSettingsDialog(player, claim)
        } else {
            menusProvider().openClaimFlagsMenu(player, claim)
        }
    }

    fun openTrustAddDialog(player: Player, claim: Claim) {
        val backend = paperBackend
        if (backend != null) {
            backend.openTrustAddDialog(player, claim)
        } else {
            menusProvider().openTrustSelectPlayerMenu(player, claim)
        }
    }

    fun openTransferClaimDialog(player: Player, claim: Claim) {
        val backend = paperBackend
        if (backend != null) {
            backend.openTransferClaimDialog(player, claim)
        } else {
            menusProvider().openTransferSelectPlayerMenu(player, claim)
        }
    }

    fun openSubclaimSettingsDialog(player: Player, subclaim: Claim) {
        val backend = paperBackend
        if (backend != null) {
            backend.openSubclaimSettingsDialog(player, subclaim)
            return
        }
        val next = !subclaim.inheritNothing
        dialogKit.openConfirmationDialog(
            player,
            "<light_purple><bold>Toggle Subclaim Isolation</bold></light_purple>",
            "<gray>Set <white>${subclaim.name}</white> isolation (inheritNothing) to <white>${if (next) "ON" else "OFF"}</white>?</gray>",
            "<green>Toggle</green>"
        ) { p ->
            claimManager.updateMetadata(subclaim, subclaim.name, subclaim.greeting, next)
            messages.send(p, "subclaim-restricted", mapOf("state" to if (next) "Isolated" else "Inheriting"))
        }
    }

    fun openAdminClaimBlocksDialog(player: Player) {
        val backend = paperBackend
        if (backend != null) {
            backend.openAdminClaimBlocksDialog(player)
        } else {
            menusProvider().openAdminClaimBlocksMenu(player)
        }
    }
}
