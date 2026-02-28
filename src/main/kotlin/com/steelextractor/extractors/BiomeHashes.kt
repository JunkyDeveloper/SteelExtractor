package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
import net.minecraft.world.level.levelgen.RandomState
import org.slf4j.LoggerFactory
import java.security.MessageDigest

class BiomeHashes : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-biome-hashes")

    companion object {
        const val SEED: Long = 13579
        const val RADIUS: Int = 5
        const val MIN_SECTION_Y: Int = -4
        const val MAX_SECTION_Y: Int = 20
    }

    override fun fileName(): String {
        return "biome_hashes.json"
    }

    private data class BiomeKey(val sectionY: Int, val x: Int, val y: Int, val z: Int)

    override fun extract(server: MinecraftServer): JsonElement {
        val json = JsonObject()
        json.addProperty("seed", SEED)
        json.addProperty("radius", RADIUS)

        // Get the overworld dimension
        val overworld = server.overworld()
        val chunkGenerator = overworld.chunkSource.generator

        // Get the biome source
        val biomeSource = chunkGenerator.biomeSource

        // Create RandomState with the test seed
        val noiseRegistry = server.registryAccess().lookupOrThrow(Registries.NOISE)

        val randomState = if (chunkGenerator is NoiseBasedChunkGenerator) {
            RandomState.create(chunkGenerator.generatorSettings().value(), noiseRegistry, SEED)
        } else {
            logger.warn("Chunk generator is not NoiseBasedChunkGenerator, using world's RandomState")
            overworld.chunkSource.randomState()
        }

        val climateSampler = randomState.sampler()

        val hashesArray = JsonArray()

        for (chunkX in -RADIUS..RADIUS) {
            for (chunkZ in -RADIUS..RADIUS) {
                val hash = chunkBiomeHash(climateSampler, biomeSource, chunkX, chunkZ)

                val entry = JsonArray()
                entry.add(chunkX)
                entry.add(chunkZ)
                entry.add(hash)
                hashesArray.add(entry)
            }
        }

        json.add("hashes", hashesArray)

        logger.info("Extracted biome hashes for ${hashesArray.size()} chunks (seed=$SEED, radius=$RADIUS)")
        return json
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
        chunkZ: Int
    ): String {
        // Step 1: Sample biomes in generation order (X outer, Y middle, Z inner)
        // to match vanilla/Steel world generation cache behavior.
        val biomes = HashMap<BiomeKey, String>()

        for (sectionY in MIN_SECTION_Y until MAX_SECTION_Y) {
            for (x in 0 until 4) {
                for (y in 0 until 4) {
                    for (z in 0 until 4) {
                        val quartX = chunkX * 4 + x
                        val quartY = sectionY * 4 + y
                        val quartZ = chunkZ * 4 + z

                        val biome = biomeSource.getNoiseBiome(quartX, quartY, quartZ, climateSampler)
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

        for (sectionY in MIN_SECTION_Y until MAX_SECTION_Y) {
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
