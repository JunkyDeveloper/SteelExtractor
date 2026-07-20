package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer

class BlockEntities : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/block_entities.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonObject()

        val blockEntitiesJson = JsonArray()
        for (blockEntity in BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            val blockEntityJson = JsonObject()
            blockEntityJson.addProperty(
                "name",
                BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity)!!.path,
            )

            val validBlocksJson = JsonArray()
            for (block in BuiltInRegistries.BLOCK) {
                if (blockEntity.isValid(block.defaultBlockState())) {
                    validBlocksJson.add(BuiltInRegistries.BLOCK.getKey(block).path)
                }
            }
            blockEntityJson.add("valid_blocks", validBlocksJson)
            blockEntitiesJson.add(blockEntityJson)
        }

        topLevelJson.add("block_entity_types", blockEntitiesJson)

        return topLevelJson
    }
}
