package com.steelextractor

import com.steelextractor.extractors.BiomeHashes
import com.steelextractor.extractors.ChunkStageHashes
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ChunkLevel
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.Ticket
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.status.ChunkStatus
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import kotlin.system.measureTimeMillis

/**
 * Seed extraction service: generates every chunk of a configured rectangle (in tiles) for the
 * world's seed and writes chunk stage hashes and biome hashes. Configured via env vars, see
 * README.md and scripts/extract.sh.
 */
object SteelExtractor : ModInitializer {
    private val logger = LoggerFactory.getLogger("steel-extractor")

    private const val AREA_ENV = "STEEL_EXTRACTOR_AREA"
    private const val TILE_SIZE_ENV = "STEEL_EXTRACTOR_TILE_SIZE"
    private const val DIMENSIONS_ENV = "STEEL_EXTRACTOR_DIMENSIONS"
    private const val OUTPUT_DIRECTORY_ENV = "STEEL_EXTRACTOR_OUTPUT_DIRECTORY"
    private const val EXIT_ON_COMPLETE_ENV = "STEEL_EXTRACTOR_EXIT_ON_COMPLETE"

    private const val DEFAULT_TILE_SIZE = 10
    private const val LIGHT_CAPTURE_READY_PASSES = 2
    private const val MAX_LIGHT_CAPTURE_WAIT_PASSES = 200
    private const val MAX_LIGHT_CAPTURE_PROBE_SAMPLES = 8
    private val CHUNK_POSITION_ORDER: Comparator<ChunkPos> = compareBy({ it.x }, { it.z })
    const val LIGHT_DEPENDENCY_RADIUS: Int = 1

    private val ALL_DIMENSIONS = listOf(
        "minecraft:overworld" to Level.OVERWORLD,
        "minecraft:the_nether" to Level.NETHER,
        "minecraft:the_end" to Level.END
    )

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun envFlag(name: String, defaultValue: Boolean = false): Boolean {
        val value = env(name) ?: return defaultValue
        return when {
            value == "1" || value.equals("true", ignoreCase = true) || value.equals("yes", ignoreCase = true) -> true
            value == "0" || value.equals("false", ignoreCase = true) || value.equals("no", ignoreCase = true) -> false
            else -> error("$name must be one of 1, 0, true, false, yes, or no; got '$value'")
        }
    }

    private fun configuredArea(): ChunkArea {
        val tileSize = env(TILE_SIZE_ENV)?.let { it.toIntOrNull() ?: error("$TILE_SIZE_ENV is not an integer: '$it'") }
            ?: DEFAULT_TILE_SIZE
        val area = env(AREA_ENV)
            ?: error("$AREA_ENV is not set. Use scripts/extract.sh, or set it to 'minX,minZ,maxX,maxZ' (chunk coordinates, inclusive).")
        return ChunkArea.parse(area, tileSize)
    }

    private fun configuredDimensions(): List<Pair<String, ResourceKey<Level>>> {
        val value = env(DIMENSIONS_ENV) ?: return ALL_DIMENSIONS
        val names = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return names.map { name ->
            ALL_DIMENSIONS.firstOrNull { it.first == name }
                ?: error("$DIMENSIONS_ENV entry '$name' must be one of ${ALL_DIMENSIONS.joinToString { it.first }}")
        }
    }

    private fun expandPositions(positions: List<ChunkPos>, radius: Int): List<ChunkPos> {
        val expanded = mutableSetOf<ChunkPos>()
        for (pos in positions) {
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    expanded.add(ChunkPos(pos.x + dx, pos.z + dz))
                }
            }
        }
        return expanded.sortedWith(CHUNK_POSITION_ORDER)
    }

    private fun lightDependencyPositions(positions: List<ChunkPos>): List<ChunkPos> {
        return expandPositions(positions, LIGHT_DEPENDENCY_RADIUS)
    }

    private fun lightFeatureDependencyPositions(positions: List<ChunkPos>): List<ChunkPos> {
        return expandPositions(lightDependencyPositions(positions), 1)
    }

    private fun exceptPositions(positions: List<ChunkPos>, excluded: List<ChunkPos>): List<ChunkPos> {
        val excludedSet = excluded.toSet()
        return positions.filter { !excludedSet.contains(it) }.sortedWith(CHUNK_POSITION_ORDER)
    }

    private fun stopWhenComplete(server: MinecraftServer) {
        if (!envFlag(EXIT_ON_COMPLETE_ENV)) return

        logger.info("Exiting because $EXIT_ON_COMPLETE_ENV is enabled")
        server.halt(false)
    }

    private data class LightCaptureProbe(
        val missingChunks: Int,
        val samples: List<String>
    ) {
        val isReady: Boolean
            get() = missingChunks == 0
    }

    private fun probeLightCaptureReadiness(
        level: ServerLevel,
        positions: List<ChunkPos>,
        lightChunks: Map<ChunkPos, ChunkAccess>
    ): LightCaptureProbe {
        var missingChunks = 0
        val samples = mutableListOf<String>()

        for (pos in positions) {
            val chunk = lightChunks[pos] ?: level.getChunk(pos.x, pos.z, ChunkStatus.LIGHT, false)
            if (chunk == null) {
                missingChunks++
                if (samples.size < MAX_LIGHT_CAPTURE_PROBE_SAMPLES) {
                    samples.add("missing LIGHT chunk (${pos.x}, ${pos.z})")
                }
            }
        }

        return LightCaptureProbe(missingChunks, samples)
    }

    private fun retainLightChunks(level: ServerLevel, positions: List<ChunkPos>) {
        val ticket = Ticket(TicketType.UNKNOWN, ChunkLevel.byStatus(ChunkStatus.LIGHT))
        for (pos in positions) {
            level.chunkSource.addTicket(ticket, pos)
        }
    }

    /**
     * One tile, processed like the old fixture clusters: terrain, features (then capture
     * feature hashes), features for the light-dependency ring, light (then capture light hashes).
     */
    private class TileWork(val positions: List<ChunkPos>) {
        val lightPositions = lightDependencyPositions(positions)
        val lightFeaturePositions = lightFeatureDependencyPositions(positions)
        val terrainQueue = ArrayDeque(positions)
        val featureQueue = ArrayDeque(positions)
        val lightFeatureQueue = ArrayDeque(exceptPositions(lightFeaturePositions, positions))
        val lightQueue = ArrayDeque(lightPositions)
        val featureChunks: MutableMap<ChunkPos, ChunkAccess> = mutableMapOf()
        val lightChunks: MutableMap<ChunkPos, ChunkAccess> = mutableMapOf()
        var featureHashesCaptured = false
        var lightHashesCaptured = false
        var pendingLightCaptureBarrier: CompletableFuture<Void>? = null
        var lightCaptureReadyPasses = 0
        var lightCaptureWaitPasses = 0

        val queuesEmpty: Boolean
            get() = terrainQueue.isEmpty() && featureQueue.isEmpty() && lightFeatureQueue.isEmpty() && lightQueue.isEmpty()
    }

    private class DimensionWork(
        val dimensionKey: ResourceKey<Level>,
        val dimId: String,
        val tiles: Iterator<List<ChunkPos>>
    ) {
        var current: TileWork? = null
        var started = false
        var tilesDone = 0L
    }

    override fun onInitialize() {
        val area = configuredArea()
        val dimensions = configuredDimensions()
        val chunksPerTick = area.tileSize * area.tileSize

        val outputDirectory: Path = Files.createDirectories(Paths.get(env(OUTPUT_DIRECTORY_ENV) ?: "steel_extractor_output"))
        val chunkStageHashes = ChunkStageHashes(outputDirectory, area)
        ChunkStageHashStorage.area = area

        logger.info(
            "Seed extraction: area x=${area.minX}..${area.maxX} z=${area.minZ}..${area.maxZ} " +
                "(${area.chunkCount} chunks, ${area.tileCount} tiles of ${area.tileSize}x${area.tileSize}) " +
                "in ${dimensions.joinToString { it.first }} -> ${outputDirectory.toAbsolutePath()}"
        )

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            val millis = measureTimeMillis {
                BiomeHashes(area, dimensions).extract(server, outputDirectory)
            }
            logger.info("Biome hashes done, took ${millis}ms")
            chunkStageHashes.open(server)
        }

        val dimWork = dimensions.map { (dimId, key) -> DimensionWork(key, dimId, area.tiles().iterator()) }
        var currentDimIdx = 0
        var done = false
        var startedAtMillis = 0L

        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (done) return@register
            if (startedAtMillis == 0L) {
                startedAtMillis = System.currentTimeMillis()
                logger.info("Forcing deterministic generation of ${area.chunkCount} chunks per dimension ($chunksPerTick chunks/tick, tiles in x/z order)...")
            }

            if (currentDimIdx >= dimWork.size) {
                chunkStageHashes.close()
                done = true
                logger.info("All extractors complete! Took ${(System.currentTimeMillis() - startedAtMillis) / 1000}s")
                stopWhenComplete(server)
                return@register
            }

            val dim = dimWork[currentDimIdx]
            ChunkStageHashStorage.currentDimension = dim.dimId
            val level: ServerLevel = server.getLevel(dim.dimensionKey) ?: run {
                logger.warn("Could not get level for ${dim.dimId}, skipping")
                currentDimIdx++
                return@register
            }
            if (!dim.started) {
                dim.started = true
                chunkStageHashes.beginDimension(dim.dimId)
            }

            val tile = dim.current ?: run {
                if (!dim.tiles.hasNext()) {
                    chunkStageHashes.endDimension(dim.dimId)
                    logger.info("Finished generating chunks for ${dim.dimId}")
                    currentDimIdx++
                    return@register
                }
                val next = TileWork(dim.tiles.next())
                dim.current = next
                next
            }

            val (queue, status) = when {
                tile.terrainQueue.isNotEmpty() -> tile.terrainQueue to ChunkStatus.TERRAIN
                tile.featureQueue.isNotEmpty() -> tile.featureQueue to ChunkStatus.FEATURES
                tile.lightFeatureQueue.isNotEmpty() -> tile.lightFeatureQueue to ChunkStatus.FEATURES
                else -> tile.lightQueue to ChunkStatus.LIGHT
            }
            val isMainFeatureQueue = queue === tile.featureQueue

            var generatedThisTick = 0
            while (queue.isNotEmpty() && generatedThisTick < chunksPerTick) {
                val pos = queue.removeFirst()
                val chunk = level.getChunk(pos.x, pos.z, status, true)
                if (chunk != null) {
                    if (status == ChunkStatus.FEATURES && isMainFeatureQueue) {
                        tile.featureChunks[pos] = chunk
                    } else if (status == ChunkStatus.LIGHT) {
                        tile.lightChunks[pos] = chunk
                    }
                }
                generatedThisTick++
            }

            if (tile.terrainQueue.isEmpty() && tile.featureQueue.isEmpty() && !tile.featureHashesCaptured) {
                chunkStageHashes.captureFinalFeatureHashes(level, dim.dimId, tile.positions, tile.featureChunks)
                tile.featureHashesCaptured = true
            }

            if (!tile.queuesEmpty) return@register

            if (!tile.lightHashesCaptured) {
                // getChunk's vanilla UNKNOWN ticket expires after one server tick. Refresh it
                // while the asynchronous light updates settle so unloading cannot erase the
                // visible DataLayer map before this tile is captured.
                retainLightChunks(level, tile.lightPositions)

                val barrier = tile.pendingLightCaptureBarrier
                if (barrier == null) {
                    val lightEngine = level.chunkSource.lightEngine
                    val pendingTasks = tile.lightPositions
                        .map { pos -> lightEngine.waitForPendingTasks(pos.x, pos.z) }
                        .toTypedArray()
                    tile.pendingLightCaptureBarrier = CompletableFuture.allOf(*pendingTasks)
                    return@register
                }
                if (!barrier.isDone) return@register
                barrier.join()
                tile.pendingLightCaptureBarrier = null
                if (level.chunkSource.lightEngine.hasLightWork()) {
                    tile.lightCaptureReadyPasses = 0
                    return@register
                }

                val probe = probeLightCaptureReadiness(level, tile.lightPositions, tile.lightChunks)
                if (!probe.isReady) {
                    tile.lightCaptureReadyPasses = 0
                    tile.lightCaptureWaitPasses++
                    if (tile.lightCaptureWaitPasses > MAX_LIGHT_CAPTURE_WAIT_PASSES) {
                        error(
                            "Light capture for ${dim.dimId} did not stabilize after $MAX_LIGHT_CAPTURE_WAIT_PASSES passes: " +
                                "missing_chunks=${probe.missingChunks}, samples=${probe.samples}"
                        )
                    }
                    logger.info(
                        "Waiting for light chunk availability before light hash capture (${dim.dimId}): " +
                            "missing_chunks=${probe.missingChunks}, samples=${probe.samples}"
                    )
                    return@register
                }

                tile.lightCaptureReadyPasses++
                if (tile.lightCaptureReadyPasses < LIGHT_CAPTURE_READY_PASSES) return@register
                tile.lightCaptureWaitPasses = 0

                chunkStageHashes.captureFinalLightHashes(level, dim.dimId, tile.positions, tile.lightChunks)
                tile.lightHashesCaptured = true
            }

            chunkStageHashes.writeTile(dim.dimId, tile.positions)
            dim.current = null
            dim.tilesDone++

            val elapsedSeconds = (System.currentTimeMillis() - startedAtMillis) / 1000.0
            val totalTiles = area.tileCount * dimWork.size
            val tilesDoneOverall = currentDimIdx * area.tileCount + dim.tilesDone
            val etaSeconds = if (tilesDoneOverall > 0) elapsedSeconds / tilesDoneOverall * (totalTiles - tilesDoneOverall) else 0.0
            logger.info(
                "Tile ${dim.tilesDone}/${area.tileCount} done in ${dim.dimId} " +
                    "(overall $tilesDoneOverall/$totalTiles, elapsed ${elapsedSeconds.toLong()}s, ETA ${etaSeconds.toLong()}s)"
            )
        }
    }
}
