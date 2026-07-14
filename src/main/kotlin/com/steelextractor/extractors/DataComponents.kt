package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer

class DataComponents : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/data_components.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val components = JsonArray()

        for (component in BuiltInRegistries.DATA_COMPONENT_TYPE) {
            val key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component)
                ?: error("Built-in data component type has no key: $component")
            val componentJson = JsonObject()
            componentJson.addProperty(
                "id",
                BuiltInRegistries.DATA_COMPONENT_TYPE.getId(component)
            )
            componentJson.addProperty("key", key.toString())
            componentJson.addProperty("persistent", !component.isTransient)
            componentJson.addProperty(
                "ignore_swap_animation",
                component.ignoreSwapAnimation()
            )
            components.add(componentJson)
        }

        val output = JsonObject()
        output.add("components", components)
        return output
    }
}
