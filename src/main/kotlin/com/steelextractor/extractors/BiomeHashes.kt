package com.steelextractor.extractors

import com.google.gson.stream.JsonWriter
import com.steelextractor.ChunkArea
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext
import net.minecraft.resources.ResourceKey
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Streams per-chunk biome hashes for every chunk in [area] to `biome_hashes.json`, using the
 * world seed. Output: `{seed, area, tile_size, num_chunks, "<dim>": {min_section_y, max_section_y,
 * hashes: [[x, z, md5], ...]}}`, chunks in the same tile order as chunk_stage_hashes.json.
 */
class BiomeHashes(
    private val area: ChunkArea,
    private val dimensions: List<Pair<String, ResourceKey<Level>>>
) {
    private val logger = LoggerFactory.getLogger("steel-extractor-biome-hashes")

    private data class BiomeKey(val sectionY: Int, val x: Int, val y: Int, val z: Int)

    fun extract(server: MinecraftServer, outputDirectory: Path) {
        val outputPath = outputDirectory.resolve(FILE_NAME)
        Files.createDirectories(outputPath.parent)
        val seed = server.overworld().seed

        JsonWriter(Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)).use { w ->
            w.beginObject()
            w.name("seed").value(seed)
            w.name("area"); area.writeJson(w)
            w.name("tile_size").value(area.tileSize)
            w.name("num_chunks").value(area.chunkCount)

            for ((dimId, key) in dimensions) {
                val level = server.getLevel(key)
                if (level == null) {
                    logger.warn("Dimension $dimId not available, skipping")
                    continue
                }
                w.name(dimId.removePrefix("minecraft:"))
                extractDimension(server, level, seed, w)
                logger.info("Extracted biome hashes for $dimId (${area.chunkCount} chunks, seed=$seed)")
            }
            w.endObject()
        }
        logger.info("Wrote ${outputPath.toAbsolutePath()}")
    }

    private fun extractDimension(server: MinecraftServer, level: ServerLevel, seed: Long, w: JsonWriter) {
        val minSectionY = level.minSectionY
        val maxSectionY = level.maxSectionY

        w.beginObject()
        w.name("min_section_y").value(minSectionY)
        w.name("max_section_y").value(maxSectionY)

        val chunkGenerator = level.chunkSource.generator
        val biomeSource = chunkGenerator.biomeSource
        val noiseRegistry = server.registryAccess().lookupOrThrow(Registries.NOISE)

        val randomState = if (chunkGenerator is NoiseBasedChunkGenerator) {
            RandomState.create(noiseRegistry, seed, chunkGenerator.generatorSettings().value())
        } else {
            logger.warn("Chunk generator for ${level.dimension()} is not NoiseBasedChunkGenerator, using level's RandomState")
            level.chunkSource.randomState()
        }
        val climateSampler = randomState.createClimateSampler(SamplerContext.EMPTY_UNCACHED)

        w.name("hashes").beginArray()
        for (tile in area.tiles()) {
            for (pos in tile) {
                val hash = chunkBiomeHash(climateSampler, biomeSource, pos.x, pos.z, minSectionY, maxSectionY)
                w.beginArray()
                w.value(pos.x)
                w.value(pos.z)
                w.value(hash)
                w.endArray()
            }
        }
        w.endArray()
        w.endObject()
    }

    companion object {
        const val FILE_NAME = "steel-core/test_assets/biome_hashes.json"
    }

    /**
     * Computes a biome MD5 hash for a chunk.
     *
     * Samples biomes using vanilla's generation iteration order (X,Y,Z) for cache
     * tie-breaking, then hashes in deterministic Y,Z,X order with section_y markers.
     */
    private fun chunkBiomeHash(
        climateSampler: net.minecraft.world.level.biome.Climate.Sampler,
        biomeSource: net.minecraft.world.level.biome.BiomeSource,
        chunkX: Int,
        chunkZ: Int,
        minSectionY: Int,
        maxSectionY: Int
    ): String {
        val biomeResolver = biomeSource.createResolverForChunk(
            climateSampler,
            chunkX * 4,
            minSectionY * 4,
            chunkZ * 4,
            4,
            (maxSectionY - minSectionY + 1) * 4,
            4
        )

        // Step 1: Sample biomes in generation order (X outer, Y middle, Z inner)
        // to match vanilla/Steel world generation cache behavior.
        val biomes = HashMap<BiomeKey, String>()

        for (sectionY in minSectionY..maxSectionY) {
            for (x in 0 until 4) {
                for (y in 0 until 4) {
                    for (z in 0 until 4) {
                        val quartX = chunkX * 4 + x
                        val quartY = sectionY * 4 + y
                        val quartZ = chunkZ * 4 + z

                        val biome = biomeResolver.getNoiseBiome(quartX, quartY, quartZ)
                        val biomeName = biome.unwrapKey()
                            .map { it.identifier().toString() }
                            .orElse("unknown")

                        biomes[BiomeKey(sectionY, x, y, z)] = biomeName
                    }
                }
            }
        }

        // Step 2: Hash in deterministic Y,Z,X order with section markers.
        val md = MessageDigest.getInstance("MD5")

        for (sectionY in minSectionY..maxSectionY) {
            md.update(sectionY.toByte())
            for (y in 0 until 4) {
                for (z in 0 until 4) {
                    for (x in 0 until 4) {
                        val biome = biomes[BiomeKey(sectionY, x, y, z)]!!
                        // Strip "minecraft:" prefix if present
                        val name = if (biome.startsWith("minecraft:")) {
                            biome.substring("minecraft:".length)
                        } else {
                            biome
                        }
                        md.update(name.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }

        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
