package claims.modules.claim

import claims.core.config.ClaimsConfig
import claims.core.scheduler.PluginScheduler
import claims.core.scheduler.TaskHandle
import org.bukkit.*
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Renders temporary client-side perimeter marker blocks (`sendBlockChange`) and corner
 * particles for claims without modifying real world blocks.
 */
class BoundaryVisualizer(
    private val scheduler: PluginScheduler,
    private val configProvider: () -> ClaimsConfig
) {
    private data class ActiveVisualization(
        val locations: List<Location>,
        val task: TaskHandle?
    )

    private val active = ConcurrentHashMap<UUID, ActiveVisualization>()

    fun showClaim(player: Player, claim: Claim, accentMaterial: Material = Material.GOLD_BLOCK) {
        showBounds(player, claim.bounds, accentMaterial, Material.GLOWSTONE)
    }

    fun showBounds(
        player: Player,
        bounds: BoundingBox,
        cornerMaterial: Material = Material.GOLD_BLOCK,
        edgeMaterial: Material = Material.GLOWSTONE
    ) {
        clear(player)
        val world = Bukkit.getWorld(bounds.worldName) ?: return
        val points = collectPerimeterPoints(world, bounds, player.location.blockY)
        if (points.isEmpty()) return

        val changed = ArrayList<Location>(points.size)
        for ((loc, isCorner) in points) {
            val mat = if (isCorner) cornerMaterial else edgeMaterial
            runCatching {
                player.sendBlockChange(loc, mat.createBlockData())
                changed.add(loc)
            }
        }

        val corners = listOf(
            Location(world, bounds.minX + 0.5, player.location.y + 1.0, bounds.minZ + 0.5),
            Location(world, bounds.maxX + 0.5, player.location.y + 1.0, bounds.minZ + 0.5),
            Location(world, bounds.minX + 0.5, player.location.y + 1.0, bounds.maxZ + 0.5),
            Location(world, bounds.maxX + 0.5, player.location.y + 1.0, bounds.maxZ + 0.5)
        )
        for (c in corners) {
            runCatching { player.spawnParticle(Particle.END_ROD, c, 8, 0.1, 1.2, 0.1, 0.01) }
        }

        val durationTicks = configProvider().visualizationDurationSeconds * 20L
        val task = scheduler.runForEntityDelayed(player, durationTicks) {
            clear(player)
        }
        active[player.uniqueId] = ActiveVisualization(changed, task)
    }

    fun clear(player: Player) {
        val prev = active.remove(player.uniqueId) ?: return
        prev.task?.cancel()
        if (!player.isOnline) return
        for (loc in prev.locations) {
            runCatching {
                player.sendBlockChange(loc, loc.block.blockData)
            }
        }
    }

    fun clearAll() {
        for (uuid in active.keys.toList()) {
            Bukkit.getPlayer(uuid)?.let { clear(it) } ?: active.remove(uuid)?.task?.cancel()
        }
    }

    private fun collectPerimeterPoints(
        world: World,
        bounds: BoundingBox,
        refY: Int
    ): List<Pair<Location, Boolean>> {
        val points = ArrayList<Pair<Location, Boolean>>()
        val step = maxOf(1, maxOf(bounds.width, bounds.length) / 24)

        fun surfaceAt(x: Int, z: Int): Location {
            // Guarded with runCatching so Folia cross-region perimeter lookups safely fall back to refY.
            val y = runCatching {
                world.getHighestBlockYAt(x, z).coerceIn(world.minHeight, world.maxHeight - 1)
            }.getOrElse {
                refY.coerceIn(world.minHeight, world.maxHeight - 1)
            }
            val chosenY = if (kotlin.math.abs(y - refY) > 16) refY.coerceIn(world.minHeight, world.maxHeight - 1) else y
            return Location(world, x.toDouble(), chosenY.toDouble(), z.toDouble())
        }

        val corners = setOf(
            bounds.minX to bounds.minZ,
            bounds.maxX to bounds.minZ,
            bounds.minX to bounds.maxZ,
            bounds.maxX to bounds.maxZ
        )

        var x = bounds.minX
        while (x <= bounds.maxX) {
            points.add(surfaceAt(x, bounds.minZ) to ((x to bounds.minZ) in corners))
            if (bounds.maxZ != bounds.minZ) {
                points.add(surfaceAt(x, bounds.maxZ) to ((x to bounds.maxZ) in corners))
            }
            x += step
        }
        var z = bounds.minZ + step
        while (z < bounds.maxZ) {
            points.add(surfaceAt(bounds.minX, z) to false)
            if (bounds.maxX != bounds.minX) {
                points.add(surfaceAt(bounds.maxX, z) to false)
            }
            z += step
        }
        for ((cx, cz) in corners) {
            points.add(surfaceAt(cx, cz) to true)
        }
        return points.distinctBy { "${it.first.blockX},${it.first.blockZ}" }
    }
}
