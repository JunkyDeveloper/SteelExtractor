package com.steelextractor

import com.google.gson.stream.JsonWriter
import net.minecraft.world.level.ChunkPos

/** Inclusive rectangle of chunk coordinates, extracted as square tiles of [tileSize] chunks. */
data class ChunkArea(val minX: Int, val minZ: Int, val maxX: Int, val maxZ: Int, val tileSize: Int) {
    init {
        require(minX <= maxX && minZ <= maxZ) { "Area min must be <= max, got $this" }
        require(tileSize > 0) { "Tile size must be positive, got $tileSize" }
    }

    fun contains(pos: ChunkPos): Boolean = pos.x in minX..maxX && pos.z in minZ..maxZ

    val chunkCount: Long get() = (maxX - minX + 1).toLong() * (maxZ - minZ + 1).toLong()

    val tileCount: Long
        get() = ceilDiv(maxX - minX + 1, tileSize) * ceilDiv(maxZ - minZ + 1, tileSize)

    /**
     * Tiles in x-then-z ascending order of their origin, clipped at the area edge. Chunks
     * inside a tile are in x-then-z ascending order as well. Generated lazily, so huge areas
     * don't need every position in memory.
     */
    fun tiles(): Sequence<List<ChunkPos>> = sequence {
        for (tileX in minX..maxX step tileSize) {
            for (tileZ in minZ..maxZ step tileSize) {
                val positions = ArrayList<ChunkPos>()
                for (x in tileX..minOf(tileX + tileSize - 1, maxX)) {
                    for (z in tileZ..minOf(tileZ + tileSize - 1, maxZ)) {
                        positions.add(ChunkPos(x, z))
                    }
                }
                yield(positions)
            }
        }
    }

    fun writeJson(writer: JsonWriter) {
        writer.beginObject()
        writer.name("min_x").value(minX)
        writer.name("min_z").value(minZ)
        writer.name("max_x").value(maxX)
        writer.name("max_z").value(maxZ)
        writer.endObject()
    }

    companion object {
        private fun ceilDiv(a: Int, b: Int): Long = ((a.toLong() + b - 1) / b)

        /** Parses `minX,minZ,maxX,maxZ` (chunk coordinates, inclusive). */
        fun parse(value: String, tileSize: Int): ChunkArea {
            val parts = value.split(",").map { it.trim() }
            require(parts.size == 4) { "Area must be 'minX,minZ,maxX,maxZ' in chunk coordinates, got '$value'" }
            val ints = parts.map { it.toIntOrNull() ?: error("Area coordinate is not an integer: '$it'") }
            return ChunkArea(ints[0], ints[1], ints[2], ints[3], tileSize)
        }
    }
}
