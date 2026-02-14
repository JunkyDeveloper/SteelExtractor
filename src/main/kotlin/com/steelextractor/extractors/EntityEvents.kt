package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.EntityEvent
import kotlin.reflect.full.staticProperties

class EntityEvents : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "entity_events.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonArray()
        for (event in EntityEvent::class.staticProperties) {
            val eventJson = JsonObject()
            eventJson.addProperty("name", event.name.lowercase())
            eventJson.addProperty("value", event.get() as Byte)
            topLevelJson.add(eventJson)
        }
        return topLevelJson
    }
}
