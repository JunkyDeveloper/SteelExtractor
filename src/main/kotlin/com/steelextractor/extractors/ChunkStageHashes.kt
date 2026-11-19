package com.steelextractor.extractors

import com.google.gson.stream.JsonWriter
import com.steelextractor.ChunkArea
import com.steelextractor.ChunkStageHashStorage
import com.steelextractor.SteelExtractor
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.status.ChunkStatus
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Captures chunk stage hashes and streams them to `chunk_stage_hashes.json` tile by tile,
 * so the output size, not the heap, grows with the extraction area.
 *
 * Output: `{ <header fields>, "dimensions": { "<dim>": { "chunks": [{x, z, stages}], "chunk_count": n } } }`
 * Chunks are ordered tile by tile (tiles in x/z order), x/z ascending inside each tile.
 */
class ChunkStageHashes(
    outputDirectory: Path,
    private val area: ChunkArea
) : Closeable {
    private val logger = LoggerFactory.getLogger("steel-extractor-chunk-stage-hashes")

    val outputPath: Path = outputDirectory.resolve(FILE_NAME)
    private var writer: JsonWriter? = null
    private var dimensionChunkCount = 0

    fun open(server: MinecraftServer) {
        Files.createDirectories(outputPath.parent)
        val w = JsonWriter(Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8))
        writer = w
        w.beginObject()
        w.name("seed").value(server.overworld().seed)
        w.name("area"); area.writeJson(w)
        w.name("tile_size").value(area.tileSize)
        w.name("num_chunks").value(area.chunkCount)
        w.name("chunk_generation_order").value("tiles_x_z_ascending")
        w.name("feature_hash_capture").value("after_all_tile_features_ready")
        w.name("biome_tie_breaker").value("vanilla_thread_local_rtree")
        w.name("hashset_iteration_order").value("vanilla")
        w.name("light_hash_capture").value("after_all_tracked_light_ready_pending_tasks_drained_and_light_engine_idle")
        w.name("light_dependency_radius").value(SteelExtractor.LIGHT_DEPENDENCY_RADIUS)
        w.name("light_feature_dependency_capture").value("after_tracked_features_before_light_x_z_ascending")
        w.name("light_hash_format").value("packet_data_layers_v1")
        w.name("dimensions").beginObject()
    }

    fun beginDimension(dimension: String) {
        val w = writer ?: error("ChunkStageHashes not opened")
        dimensionChunkCount = 0
        w.name(dimension).beginObject()
        w.name("chunks").beginArray()
    }

    /** Writes the captured hashes of one finished tile and frees them from storage. */
    fun writeTile(dimension: String, positions: List<ChunkPos>) {
        val w = writer ?: error("ChunkStageHashes not opened")
        for ((pos, stages) in ChunkStageHashStorage.drainTile(dimension, positions)) {
            w.beginObject()
            w.name("x").value(pos.x)
            w.name("z").value(pos.z)
            w.name("stages").beginObject()
            for ((stage, hash) in stages) {
                w.name(stage).value(hash)
            }
            w.endObject()
            w.endObject()
            if (stages.size < 3) {
                logger.warn("Chunk (${pos.x}, ${pos.z}) in $dimension has only stages ${stages.keys}")
            }
            dimensionChunkCount++
        }
        w.flush()
    }

    fun endDimension(dimension: String) {
        val w = writer ?: error("ChunkStageHashes not opened")
        w.endArray()
        w.name("chunk_count").value(dimensionChunkCount)
        w.endObject()
        w.flush()
        logger.info("Extracted chunk stage hashes for $dimensionChunkCount chunks in $dimension")
    }

    override fun close() {
        val w = writer ?: return
        w.endObject() // dimensions
        w.endObject()
        w.close()
        writer = null
        logger.info("Wrote ${outputPath.toAbsolutePath()}")
    }

    fun captureFinalFeatureHashes(
        level: ServerLevel,
        dimension: String,
        positions: List<ChunkPos>,
        featureChunks: Map<ChunkPos, ChunkAccess>
    ) {
        var captured = 0
        for (pos in positions) {
            val chunk = featureChunks[pos] ?: level.getChunk(pos.x, pos.z, ChunkStatus.FEATURES, false)
            if (chunk == null) {
                logger.warn("Chunk $pos in $dimension is not loaded at FEATURES; skipping final feature hash")
                continue
            }
            val hash = ChunkStageHashStorage.computeBlockHash(chunk.sections.asIterable())
            ChunkStageHashStorage.storeHash(pos, dimension, ChunkStatus.FEATURES.toString(), hash)
            captured++
        }
        logger.debug("Captured final feature hashes for $captured/${positions.size} chunks in $dimension")
    }

    fun captureFinalLightHashes(
        level: ServerLevel,
        dimension: String,
        positions: List<ChunkPos>,
        lightChunks: Map<ChunkPos, ChunkAccess>
    ) {
        var captured = 0
        for (pos in positions) {
            val chunk = lightChunks[pos] ?: level.getChunk(pos.x, pos.z, ChunkStatus.LIGHT, false)
            if (chunk == null) {
                logger.warn("Chunk $pos in $dimension is not loaded at LIGHT; skipping final light hash")
                continue
            }
            val hash = ChunkStageHashStorage.computeLightHash(level.lightEngine, chunk)
            ChunkStageHashStorage.storeHash(pos, dimension, ChunkStatus.LIGHT.toString(), hash)
            captured++
        }
        logger.debug("Captured final light hashes for $captured/${positions.size} chunks in $dimension")
    }

    companion object {
        const val FILE_NAME = "steel-core/test_assets/chunk_stage_hashes.json"
    }
}
