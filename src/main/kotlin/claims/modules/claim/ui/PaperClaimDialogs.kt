package claims.modules.claim.ui

import claims.core.config.ClaimsConfig
import claims.core.locale.MessageService
import claims.modules.claim.Claim
import claims.modules.claim.ClaimFlag
import claims.modules.claim.ClaimManager
import claims.modules.claim.ClaimPermission
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * Isolated Paper 1.21.6+ / 26.x Dialog definitions for the Claim module.
 */
internal class PaperClaimDialogs(
    private val configProvider: () -> ClaimsConfig,
    private val messages: MessageService,
    private val claimManager: ClaimManager,
    private val onReopenClaimDetail: (Player, Claim) -> Unit,
) {
    private fun oneShotOptions(): ClickCallback.Options =
        ClickCallback.Options.builder().uses(1).build()

    fun openClaimSettingsDialog(player: Player, claim: Claim) {
        val cfg = configProvider()
        val inputs = listOf(
            DialogInput.text("name", messages.renderRaw("<aqua>Claim Name</aqua>"))
                .width(260)
                .initial(claim.name)
                .maxLength(48)
                .build(),
            DialogInput.text("greeting", messages.renderRaw("<aqua>Entry Greeting (leave blank for none)</aqua>"))
                .width(260)
                .initial(claim.greeting ?: "")
                .maxLength(100)
                .build(),
            DialogInput.bool("pvp", messages.renderRaw("<red>Allow PvP Inside Claim</red>"))
                .initial(claim.flag(ClaimFlag.PVP, cfg.defaultFlags))
                .onTrue("true")
                .onFalse("false")
                .build(),
            DialogInput.bool("explosions", messages.renderRaw("<gold>Allow Explosion Damage</gold>"))
                .initial(claim.flag(ClaimFlag.EXPLOSIONS, cfg.defaultFlags))
                .onTrue("true")
                .onFalse("false")
                .build(),
            DialogInput.bool("fire_spread", messages.renderRaw("<yellow>Allow Fire Spread</yellow>"))
                .initial(claim.flag(ClaimFlag.FIRE_SPREAD, cfg.defaultFlags))
                .onTrue("true")
                .onFalse("false")
                .build(),
            DialogInput.bool("mob_griefing", messages.renderRaw("<green>Allow Mob Griefing</green>"))
                .initial(claim.flag(ClaimFlag.MOB_GRIEFING, cfg.defaultFlags))
                .onTrue("true")
                .onFalse("false")
                .build(),
            DialogInput.bool("public_access", messages.renderRaw("<aqua>Allow Public Door/Button Access</aqua>"))
                .initial(claim.flag(ClaimFlag.PUBLIC_ACCESS, cfg.defaultFlags))
                .onTrue("true")
                .onFalse("false")
                .build(),
            DialogInput.bool("entry_notify", messages.renderRaw("<light_purple>Show Entry Notification</light_purple>"))
                .initial(claim.flag(ClaimFlag.ENTRY_NOTIFY, cfg.defaultFlags))
                .onTrue("true")
                .onFalse("false")
                .build(),
        )

        val saveBtn = ActionButton.builder(messages.renderRaw("<green>✔ Save Settings</green>"))
            .width(130)
            .action(DialogAction.customClick({ response, audience ->
                val p = audience as? Player ?: return@customClick
                val newName = response.getText("name")?.trim()?.takeIf { it.isNotEmpty() } ?: claim.name
                val newGreeting = response.getText("greeting")?.trim()
                claimManager.updateMetadata(claim, newName, newGreeting)

                response.getBoolean("pvp")?.let { claimManager.setFlag(claim, ClaimFlag.PVP, it) }
                response.getBoolean("explosions")?.let { claimManager.setFlag(claim, ClaimFlag.EXPLOSIONS, it) }
                response.getBoolean("fire_spread")?.let { claimManager.setFlag(claim, ClaimFlag.FIRE_SPREAD, it) }
                response.getBoolean("mob_griefing")?.let { claimManager.setFlag(claim, ClaimFlag.MOB_GRIEFING, it) }
                response.getBoolean("public_access")?.let { claimManager.setFlag(claim, ClaimFlag.PUBLIC_ACCESS, it) }
                response.getBoolean("entry_notify")?.let { claimManager.setFlag(claim, ClaimFlag.ENTRY_NOTIFY, it) }

                messages.sendRaw(p, "<green>Saved settings for claim [<white>${claim.name}</white>].</green>")
                onReopenClaimDetail(p, claim)
            }, oneShotOptions()))
            .build()

        val cancelBtn = ActionButton.builder(messages.renderRaw("<gray>Close</gray>")).width(110).build()

        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(messages.renderRaw("<gradient:#38bdf8:#818cf8><bold>Claim Settings</bold></gradient>"))
                        .canCloseWithEscape(true)
                        .body(
                            listOf(
                                DialogBody.plainMessage(
                                    messages.renderRaw(
                                        "<gray>Claim ID:</gray> <white>#${claim.id}</white>  <gray>| Size:</gray> <white>${claim.bounds.width}×${claim.bounds.length} (${claim.bounds.area} blocks)</white>"
                                    )
                                )
                            )
                        )
                        .inputs(inputs)
                        .build()
                )
                .type(DialogType.confirmation(saveBtn, cancelBtn))
        }
        player.showDialog(dialog)
    }

    fun openTrustAddDialog(player: Player, claim: Claim) {
        val tierOptions = listOf(
            SingleOptionDialogInput.OptionEntry.create(
                "ACCESS",
                messages.renderRaw("<gray>Access (Doors / Buttons / Levers)</gray>"),
                false
            ),
            SingleOptionDialogInput.OptionEntry.create(
                "CONTAINER",
                messages.renderRaw("<gold>Container (Chests / Animals / Crops)</gold>"),
                false
            ),
            SingleOptionDialogInput.OptionEntry.create(
                "BUILD",
                messages.renderRaw("<green>Build (Place / Break Blocks)</green>"),
                true
            ),
            SingleOptionDialogInput.OptionEntry.create(
                "MANAGE",
                messages.renderRaw("<aqua>Manage (Edit Trusts & Settings)</aqua>"),
                false
            )
        )

        val inputs = listOf(
            DialogInput.text("target", messages.renderRaw("<aqua>Player Name (or 'public' for everyone)</aqua>"))
                .width(240)
                .initial("")
                .maxLength(32)
                .build(),
            DialogInput.singleOption("tier", messages.renderRaw("<aqua>Trust Level</aqua>"), tierOptions)
                .width(240)
                .build()
        )

        val confirmBtn = ActionButton.builder(messages.renderRaw("<green>✔ Grant Trust</green>"))
            .width(130)
            .action(DialogAction.customClick({ response, audience ->
                val p = audience as? Player ?: return@customClick
                val rawTarget = response.getText("target")?.trim().orEmpty()
                if (rawTarget.isEmpty()) {
                    messages.sendRaw(p, "<red>Please enter a target player name.</red>")
                    return@customClick
                }
                val tierName = response.getText("tier") ?: "BUILD"
                val perm = runCatching { ClaimPermission.valueOf(tierName) }.getOrDefault(ClaimPermission.BUILD)
                val key = if (rawTarget.equals("public", ignoreCase = true)) {
                    Claim.PUBLIC_KEY
                } else {
                    Bukkit.getOfflinePlayer(rawTarget).uniqueId.toString()
                }
                claimManager.setTrust(claim, key, perm)
                messages.send(p, "trust-updated", mapOf("target" to rawTarget, "level" to perm.displayName))
            }, oneShotOptions()))
            .build()

        val cancelBtn = ActionButton.builder(messages.renderRaw("<gray>Cancel</gray>")).width(100).build()

        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(messages.renderRaw("<aqua><bold>Add Claim Trust</bold></aqua>"))
                        .canCloseWithEscape(true)
                        .body(
                            listOf(
                                DialogBody.plainMessage(
                                    messages.renderRaw("<gray>Target Claim:</gray> <white>${claim.name}</white>")
                                )
                            )
                        )
                        .inputs(inputs)
                        .build()
                )
                .type(DialogType.confirmation(confirmBtn, cancelBtn))
        }
        player.showDialog(dialog)
    }

    fun openTransferClaimDialog(player: Player, claim: Claim) {
        val input = DialogInput.text("target", messages.renderRaw("<aqua>New Owner Player Name</aqua>"))
            .width(240)
            .initial("")
            .maxLength(32)
            .build()

        val confirmBtn = ActionButton.builder(messages.renderRaw("<gold>✔ Transfer Ownership</gold>"))
            .width(150)
            .action(DialogAction.customClick({ response, audience ->
                val p = audience as? Player ?: return@customClick
                val targetName = response.getText("target")?.trim().orEmpty()
                if (targetName.isEmpty()) {
                    messages.sendRaw(p, "<red>Please enter a target player name.</red>")
                    return@customClick
                }
                val offline = Bukkit.getOfflinePlayer(targetName)
                claimManager.transferClaim(claim, offline.uniqueId)
                messages.send(p, "claim-transferred", mapOf("target" to targetName))
            }, oneShotOptions()))
            .build()

        val cancelBtn = ActionButton.builder(messages.renderRaw("<gray>Cancel</gray>")).width(100).build()

        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(messages.renderRaw("<gold><bold>Transfer Claim Ownership</bold></gold>"))
                        .canCloseWithEscape(true)
                        .body(
                            listOf(
                                DialogBody.plainMessage(
                                    messages.renderRaw("<yellow>Warning:</yellow> <gray>Ownership of <white>${claim.name}</white> and all its subclaims will be transferred.</gray>")
                                )
                            )
                        )
                        .inputs(listOf(input))
                        .build()
                )
                .type(DialogType.confirmation(confirmBtn, cancelBtn))
        }
        player.showDialog(dialog)
    }

    fun openSubclaimSettingsDialog(player: Player, subclaim: Claim) {
        val inputs = listOf(
            DialogInput.text("name", messages.renderRaw("<aqua>Subclaim Name</aqua>"))
                .width(240)
                .initial(subclaim.name)
                .maxLength(48)
                .build(),
            DialogInput.bool("inheritNothing", messages.renderRaw("<red>Block Parent Inheritance (Isolated)</red>"))
                .initial(subclaim.inheritNothing)
                .onTrue("true")
                .onFalse("false")
                .build()
        )

        val saveBtn = ActionButton.builder(messages.renderRaw("<green>✔ Save</green>"))
            .width(120)
            .action(DialogAction.customClick({ response, audience ->
                val p = audience as? Player ?: return@customClick
                val newName = response.getText("name")?.trim()?.takeIf { it.isNotEmpty() } ?: subclaim.name
                val isolate = response.getBoolean("inheritNothing") ?: subclaim.inheritNothing
                claimManager.updateMetadata(subclaim, newName, subclaim.greeting, isolate)
                messages.send(p, "subclaim-restricted", mapOf("state" to if (isolate) "Isolated" else "Inheriting"))
            }, oneShotOptions()))
            .build()

        val cancelBtn = ActionButton.builder(messages.renderRaw("<gray>Cancel</gray>")).width(100).build()

        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(messages.renderRaw("<light_purple><bold>Subclaim Settings</bold></light_purple>"))
                        .canCloseWithEscape(true)
                        .body(
                            listOf(
                                DialogBody.plainMessage(
                                    messages.renderRaw("<gray>Parent Claim:</gray> <white>${subclaim.parent?.name ?: "-"}</white>")
                                )
                            )
                        )
                        .inputs(inputs)
                        .build()
                )
                .type(DialogType.confirmation(saveBtn, cancelBtn))
        }
        player.showDialog(dialog)
    }

    fun openAdminClaimBlocksDialog(player: Player) {
        val inputs = listOf(
            DialogInput.text("target", messages.renderRaw("<aqua>Target Player Name</aqua>"))
                .width(240)
                .initial(player.name)
                .maxLength(32)
                .build(),
            DialogInput.numberRange(
                "amount",
                messages.renderRaw("<aqua>Bonus Claim Blocks Delta</aqua>"),
                -5000f,
                10000f
            )
                .width(240)
                .labelFormat("%s: %s")
                .initial(500f)
                .step(100f)
                .build()
        )

        val applyBtn = ActionButton.builder(messages.renderRaw("<green>✔ Apply Adjustment</green>"))
            .width(150)
            .action(DialogAction.customClick({ response, audience ->
                val p = audience as? Player ?: return@customClick
                val targetName = response.getText("target")?.trim().orEmpty()
                if (targetName.isEmpty()) return@customClick
                val delta = response.getFloat("amount")?.toInt() ?: 0
                val offline = Bukkit.getOfflinePlayer(targetName)
                claimManager.addBonusBlocks(offline.uniqueId, delta)
                val data = claimManager.getOrCreatePlayerData(offline.uniqueId)
                val avail = claimManager.getAvailableClaimBlocks(offline.uniqueId)
                messages.send(
                    p,
                    "claim-blocks-adjusted",
                    mapOf("target" to targetName, "bonus" to data.bonusBlocks, "available" to avail)
                )
            }, oneShotOptions()))
            .build()

        val cancelBtn = ActionButton.builder(messages.renderRaw("<gray>Close</gray>")).width(100).build()

        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(messages.renderRaw("<red><bold>Admin: Adjust Claim Blocks</bold></red>"))
                        .canCloseWithEscape(true)
                        .body(
                            listOf(
                                DialogBody.plainMessage(
                                    messages.renderRaw("<gray>Add or subtract bonus claim blocks for a player.</gray>")
                                )
                            )
                        )
                        .inputs(inputs)
                        .build()
                )
                .type(DialogType.confirmation(applyBtn, cancelBtn))
        }
        player.showDialog(dialog)
    }
}
