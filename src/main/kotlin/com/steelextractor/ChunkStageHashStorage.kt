package com.steelextractor

import net.minecraft.core.SectionPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.lighting.LevelLightEngine
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class DimChunkPos(val pos: ChunkPos, val dimension: String)

/**
 * Holds stage hashes until their tile is written. Hashes are drained to the output writer
 * once a tile is captured, so memory stays bounded no matter how large the area is.
 */
object ChunkStageHashStorage {
    private val hashes = ConcurrentHashMap<Pair<DimChunkPos, String>, String>()

    /** Set by SteelExtractor before generating each dimension's chunks. Read by the mixin. */
    @Volatile
    var currentDimension: String = ""

    /** The extraction area, set once at startup. */
    @Volatile
    var area: ChunkArea? = null

    /**
     * Every chunk inside the area is tracked, not just the current tile: a tile's light
     * dependency ring pulls chunks of the next tiles through TERRAIN early, and TERRAIN is
     * order-independent, so it is hashed whenever it happens.
     */
    fun isTracking(pos: ChunkPos, dimension: String): Boolean {
        return dimension.isNotEmpty() && area?.contains(pos) == true
    }

    fun storeHash(pos: ChunkPos, dimension: String, stageName: String, hash: String) {
        hashes[Pair(DimChunkPos(pos, dimension), stageName)] = hash
    }

    /**
     * Removes the given chunks from tracking and returns their stage hashes
     * (stage name -> hash, sorted by stage name), in the order of [positions].
     */
    fun drainTile(dimension: String, positions: List<ChunkPos>): List<Pair<ChunkPos, Map<String, String>>> {
        val keys = positions.map { DimChunkPos(it, dimension) }.toSet()
        val byChunk = HashMap<DimChunkPos, MutableMap<String, String>>()
        val iterator = hashes.entries.iterator()
        while (iterator.hasNext()) {
            val (key, hash) = iterator.next()
            if (key.first in keys) {
                byChunk.getOrPut(key.first) { sortedMapOf() }[key.second] = hash
                iterator.remove()
            }
        }
        return positions.map { pos -> pos to (byChunk[DimChunkPos(pos, dimension)] ?: emptyMap()) }
    }

    private fun MessageDigest.updateInt(value: Int) {
        update((value shr 24).toByte())
        update((value shr 16).toByte())
        update((value shr 8).toByte())
        update(value.toByte())
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

    fun computeLightHash(lightEngine: LevelLightEngine, chunk: ChunkAccess): String {
        val pos = chunk.pos
        val md = MessageDigest.getInstance("MD5")
        val minSection = lightEngine.minLightSection
        val sectionCount = lightEngine.lightSectionCount

        md.updateInt(minSection)
        md.updateInt(sectionCount)
        for (layer in listOf(LightLayer.SKY, LightLayer.BLOCK)) {
            md.update(if (layer == LightLayer.SKY) 0.toByte() else 1.toByte())
            val listener = lightEngine.getLayerListener(layer)
            for (sectionIndex in 0 until sectionCount) {
                val data = listener.getDataLayerData(SectionPos.of(pos, minSection + sectionIndex))
                if (data == null) {
                    md.update(0.toByte())
                } else if (data.isEmpty) {
                    md.update(1.toByte())
                } else {
                    md.update(2.toByte())
                    md.update(data.copy().data)
                }
            }
        }
        return md.hex()
    }

    fun computeBlockHash(sections: Iterable<LevelChunkSection>): String {
        val md = MessageDigest.getInstance("MD5")
        for (section in sections) {
            if (section.hasOnlyAir()) {
                md.update(0.toByte())
            } else {
                val states = section.states
                for (y in 0 until 16) {
                    for (z in 0 until 16) {
                        for (x in 0 until 16) {
                            md.updateInt(net.minecraft.world.level.block.Block.getId(states.get(x, y, z)))
                        }
                    }
                }
            }
        }
        return md.hex()
    }
}
