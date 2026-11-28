package claims.core.config

import claims.core.ui.ActionBarSettings
import claims.modules.claim.ClaimFlag
import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.util.*

/**
 * Strongly typed, validated snapshot of `config.yml`.
 *
 * All numeric bounds are sanitized in [load] so malformed admin edits cannot crash
 * claim sizing, refund calculations, or scheduler intervals.
 */
data class ClaimsConfig(
    val language: String = "en",
    val registerStandaloneAliases: Boolean = true,
    val moduleEnabledMap: Map<String, Boolean> = emptyMap(),
    val databaseFile: String = "claims.db",
    val databasePoolSize: Int = 4,
    val enabledWorlds: Set<String> = emptySet(),
    val claimTool: Material = Material.GOLDEN_SHOVEL,
    val inspectTool: Material = Material.STICK,
    val initialClaimBlocks: Int = 400,
    val accrualBlocksPerHour: Int = 120,
    val maxAccruedBlocks: Int = 80000,
    val minClaimWidth: Int = 5,
    val minClaimArea: Int = 25,
    val autoClaimRadius: Int = 7,
    val abandonRefundRatio: Double = 1.0,
    val visualizationDurationSeconds: Int = 30,
    val limitTreeGrowth: Boolean = true,
    val preventNonPlayerPortals: Boolean = true,
    val unusedCleanupEnabled: Boolean = false,
    val unusedCleanupInactiveDays: Int = 60,
    val unusedCleanupMaxArea: Int = 2500,
    val defaultFlags: Map<ClaimFlag, Boolean> = mapOf(ClaimFlag.ENTRY_NOTIFY to true),
    val actionBarEnabled: Boolean = true,
    val actionBarRefreshTicks: Long = 10L,
    val denialCooldownMs: Long = 1500L,
    val bossBarEnabled: Boolean = true,
    val bossBarModeBanner: Boolean = true,
    val bossBarTerritoryBanner: Boolean = false,
) {
    /** Returns `true` if claiming/protection is active in [worldName] (empty set = all worlds). */
    fun isWorldEnabled(worldName: String): Boolean =
        enabledWorlds.isEmpty() || enabledWorlds.contains(worldName)

    /** Returns whether module [id] is enabled in `config.yml`, falling back to [defaultEnabled]. */
    fun isModuleEnabled(id: String, defaultEnabled: Boolean = true): Boolean =
        moduleEnabledMap[id.lowercase(Locale.ROOT)] ?: defaultEnabled

    /** Projects ActionBar-specific settings for [ActionBarSettings] consumers. */
    fun actionBarSettings(): ActionBarSettings = ActionBarSettings(
        enabled = actionBarEnabled,
        refreshTicks = actionBarRefreshTicks,
        denialCooldownMs = denialCooldownMs,
    )

    companion object {
        /**
         * Ensures default `config.yml` exists, reloads it from disk, and parses all fields
         * with safe fallbacks and clamped ranges.
         */
        fun load(plugin: JavaPlugin): ClaimsConfig {
            plugin.saveDefaultConfig()
            plugin.reloadConfig()
            val cfg: FileConfiguration = plugin.config

            val moduleFlags = HashMap<String, Boolean>()
            val modSec = cfg.getConfigurationSection("modules")
            if (modSec != null) {
                for (key in modSec.getKeys(false)) {
                    moduleFlags[key.lowercase(Locale.ROOT)] = modSec.getBoolean("$key.enabled", true)
                }
            }

            val flags = mapOf(
                ClaimFlag.PVP to cfg.getBoolean("claims.default-flags.pvp", false),
                ClaimFlag.EXPLOSIONS to cfg.getBoolean("claims.default-flags.explosions", false),
                ClaimFlag.FIRE_SPREAD to cfg.getBoolean("claims.default-flags.fire-spread", false),
                ClaimFlag.MOB_GRIEFING to cfg.getBoolean("claims.default-flags.mob-griefing", false),
                ClaimFlag.PUBLIC_ACCESS to cfg.getBoolean("claims.default-flags.public-access", false),
                ClaimFlag.PISTON_CROSS_BORDER to cfg.getBoolean("claims.default-flags.piston-cross-border", false),
                ClaimFlag.ENTRY_NOTIFY to cfg.getBoolean("claims.default-flags.entry-notify", true),
            )

            val rawClaimTool = cfg.getString("claims.claim-tool") ?: "GOLDEN_SHOVEL"
            val claimTool = Material.matchMaterial(rawClaimTool) ?: run {
                plugin.logger.warning("Invalid claims.claim-tool '$rawClaimTool'; falling back to GOLDEN_SHOVEL.")
                Material.GOLDEN_SHOVEL
            }
            val rawInspectTool = cfg.getString("claims.inspect-tool") ?: "STICK"
            val inspectTool = Material.matchMaterial(rawInspectTool) ?: run {
                plugin.logger.warning("Invalid claims.inspect-tool '$rawInspectTool'; falling back to STICK.")
                Material.STICK
            }

            return ClaimsConfig(
                language = cfg.getString("language", "en")?.trim()?.ifEmpty { "en" } ?: "en",
                registerStandaloneAliases = cfg.getBoolean("command-aliases.register-standalone-aliases", true),
                moduleEnabledMap = moduleFlags,
                databaseFile = cfg.getString("database.file", "claims.db")?.trim()?.ifEmpty { "claims.db" }
                    ?: "claims.db",
                databasePoolSize = cfg.getInt("database.pool-size", 4).coerceAtLeast(1),
                enabledWorlds = cfg.getStringList("claims.enabled-worlds").map { it.trim() }.filter { it.isNotEmpty() }
                    .toSet(),
                claimTool = claimTool,
                inspectTool = inspectTool,
                initialClaimBlocks = cfg.getInt("claims.initial-claim-blocks", 400).coerceAtLeast(0),
                accrualBlocksPerHour = cfg.getInt("claims.accrual-blocks-per-hour", 120).coerceAtLeast(0),
                maxAccruedBlocks = cfg.getInt("claims.max-accrued-blocks", 80000).coerceAtLeast(100),
                minClaimWidth = cfg.getInt("claims.min-claim-width", 5).coerceAtLeast(1),
                minClaimArea = cfg.getInt("claims.min-claim-area", 25).coerceAtLeast(1),
                autoClaimRadius = cfg.getInt("claims.auto-claim-radius", 7).coerceAtLeast(2),
                abandonRefundRatio = cfg.getDouble("claims.abandon-refund-ratio", 1.0).coerceIn(0.0, 1.0),
                visualizationDurationSeconds = cfg.getInt("claims.visualization-duration-seconds", 30).coerceAtLeast(5),
                limitTreeGrowth = cfg.getBoolean("claims.limit-tree-growth", true),
                preventNonPlayerPortals = cfg.getBoolean("claims.prevent-non-player-portals", true),
                unusedCleanupEnabled = cfg.getBoolean("claims.unused-cleanup.enabled", false),
                unusedCleanupInactiveDays = cfg.getInt("claims.unused-cleanup.inactive-days", 60).coerceAtLeast(1),
                unusedCleanupMaxArea = cfg.getInt("claims.unused-cleanup.max-area-to-expire", 2500).coerceAtLeast(1),
                defaultFlags = flags,
                actionBarEnabled = cfg.getBoolean("ui.actionbar.enabled", true),
                actionBarRefreshTicks = cfg.getLong("ui.actionbar.refresh-ticks", 10L).coerceAtLeast(2L),
                denialCooldownMs = cfg.getLong("ui.actionbar.denial-cooldown-ms", 1500L).coerceAtLeast(200L),
                bossBarEnabled = cfg.getBoolean("ui.bossbar.enabled", true),
                bossBarModeBanner = cfg.getBoolean("ui.bossbar.show-mode-banner", true),
                bossBarTerritoryBanner = cfg.getBoolean("ui.bossbar.show-territory-banner", false),
            )
        }
    }
}
