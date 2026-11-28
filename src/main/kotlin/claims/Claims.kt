package claims

import claims.core.ClaimsContext
import claims.core.command.RootCommandExecutor
import claims.core.compat.PlatformCompat
import claims.core.ui.MenuListener
import claims.modules.claim.ClaimModule
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

/**
 * Main Paper/Folia plugin entrypoint for **Claims**.
 *
 * Bootstraps [ClaimsContext], enables registered [claims.core.module.FeatureModule]s,
 * wires `/claims` via [RootCommandExecutor], and initializes dynamic standalone/custom
 * command aliases through [claims.core.command.CommandAliasService].
 */
class Claims : JavaPlugin() {

    /** Active plugin context; `null` before [onEnable] or after [onDisable]. */
    var context: ClaimsContext? = null
        private set

    override fun onEnable() {
        instance = this
        dataFolder.mkdirs()

        val ctx = ClaimsContext(this)
        context = ctx
        Bukkit.getServicesManager().register(ClaimsContext::class.java, ctx, this, ServicePriority.Normal)

        val claimModule = ClaimModule(ctx)

        val pm = server.pluginManager
        pm.registerEvents(MenuListener(), this)
        pm.registerEvents(ctx.commandAliases, this)
        ctx.actionBar.start()

        ctx.modules.register(claimModule)
        ctx.modules.enableAll()

        ctx.commandRegistry.clear()
        val rootExecutor = RootCommandExecutor(
            host = ctx,
            commandAliases = ctx.commandAliases,
            userPermission = "claims.use",
            openDashboard = { player -> claimModule.menus.openDashboard(player) },
            openAdminPanel = { player -> claimModule.menus.openAdminPanel(player) },
            statusReporter = { sender ->
                val topClaims = claimModule.claimManager.getAllTopLevelClaims()
                val subclaimCount = topClaims.sumOf { it.children.size }
                val uiMode = if (PlatformCompat.supportsDialogs) "Paper Dialogs" else "Legacy GUI"
                val schedMode = if (ctx.scheduler.isFolia) "Folia" else "Paper"
                ctx.messages.sendRaw(
                    sender,
                    "<aqua>Claims Status</aqua> <gray>|</gray> Claims: <white>${topClaims.size}</white> " +
                            "<gray>(Subclaims: <white>$subclaimCount</white>) |</gray> " +
                            "Modules: <white>${ctx.modules.activeModules().size}</white> <gray>|</gray> " +
                            "Aliases: <white>${ctx.commandAliases.getAllAliases().size}</white> <gray>|</gray> " +
                            "Runtime: <white>$schedMode / $uiMode</white>"
                )
            },
        )
        rootExecutor.registerCoreSubcommands()
        ctx.modules.registerAllSubcommands(ctx.commandRegistry)

        getCommand("claims")?.let {
            it.executor = rootExecutor
            it.tabCompleter = rootExecutor
        }

        // Must run after all core and module subcommands are registered.
        ctx.commandAliases.initializeFromRegistry()

        val uiMode = if (PlatformCompat.supportsDialogs) "Native Paper Dialogs" else "Legacy GUI Fallback"
        logger.info(
            "Claims enabled on MC ${PlatformCompat.serverVersionLabel()} ($uiMode)! " +
                    "Modules: ${ctx.modules.activeModules().size}, Aliases: ${ctx.commandAliases.getAllAliases().size}"
        )
    }

    override fun onDisable() {
        context?.shutdown()
        runCatching { Bukkit.getServicesManager().unregisterAll(this) }
        context = null
        instance = null
    }

    companion object {
        /** Singleton plugin instance while enabled. */
        @Volatile
        var instance: Claims? = null
            private set
    }
}
