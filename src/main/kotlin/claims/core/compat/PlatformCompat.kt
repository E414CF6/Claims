package claims.core.compat

import net.kyori.adventure.audience.Audience
import org.bukkit.Bukkit

/**
 * Runtime capability probes for Paper Dialogs (MC 1.21.6+) and Folia regionized schedulers.
 *
 * Keeping reflective class/method checks here ensures older Paper builds (1.20.4 - 1.21.5)
 * never eagerly load missing Paper Dialog classes and fall back cleanly to Inventory GUIs.
 */
object PlatformCompat {
    /** `true` when the Paper Dialog API and `Audience.showDialog` are available at runtime. */
    val supportsDialogs: Boolean by lazy {
        hasClass("io.papermc.paper.dialog.Dialog") &&
                hasClass("io.papermc.paper.registry.data.dialog.DialogBase") &&
                runCatching {
                    Audience::class.java.methods.any { it.name == "showDialog" }
                }.getOrDefault(false)
    }

    /** `true` when Folia's `GlobalRegionScheduler` API is present on the server. */
    val supportsFoliaSchedulers: Boolean by lazy {
        hasClass("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler") &&
                runCatching {
                    Bukkit::class.java.methods.any { it.name == "getGlobalRegionScheduler" }
                }.getOrDefault(false)
    }

    /** Returns the running Minecraft version string (e.g. `1.21.4`) or `"unknown"` in headless tests. */
    fun serverVersionLabel(): String =
        runCatching { Bukkit.getMinecraftVersion() }.getOrElse {
            runCatching { Bukkit.getBukkitVersion() }.getOrDefault("unknown")
        }

    /** Checks whether class [name] exists without running its static initializers. */
    fun hasClass(name: String): Boolean =
        runCatching {
            Class.forName(name, false, PlatformCompat::class.java.classLoader)
            true
        }.getOrDefault(false)
}
