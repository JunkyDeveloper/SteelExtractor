package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.Registry
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.npc.villager.VillagerProfession
import net.minecraft.world.entity.npc.villager.VillagerType
import net.minecraft.world.level.saveddata.maps.MapDecorationType

private fun <T : Any> extractBuiltInRegistry(
    registry: Registry<T>,
    addFields: (T, JsonObject) -> Unit = { _, _ -> }
): JsonArray {
    val values = JsonArray()
    for (entry in registry) {
        val key = registry.getKey(entry) ?: error("Built-in registry entry has no key: $entry")
        val entryJson = JsonObject()
        entryJson.addProperty("id", registry.getId(entry))
        entryJson.addProperty("key", key.toString())
        addFields(entry, entryJson)
        values.add(entryJson)
    }
    return values
}

class ParticleTypeRegistryExtractor : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/particle_types.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        return extractBuiltInRegistry(BuiltInRegistries.PARTICLE_TYPE) { particleType: ParticleType<*>, json ->
            json.addProperty("override_limiter", particleType.overrideLimiter)
        }
    }
}

class MapDecorationTypeRegistryExtractor : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/map_decoration_types.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        return extractBuiltInRegistry(BuiltInRegistries.MAP_DECORATION_TYPE) { type: MapDecorationType, json ->
            json.addProperty("asset_id", type.assetId().toString())
            json.addProperty("show_on_item_frame", type.showOnItemFrame())
            json.addProperty("map_color", type.mapColor())
            json.addProperty("exploration_map_element", type.explorationMapElement())
            json.addProperty("track_count", type.trackCount())
        }
    }
}

class VillagerTypeRegistryExtractor : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/villager_types.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        return extractBuiltInRegistry(BuiltInRegistries.VILLAGER_TYPE) { _: VillagerType, _ -> }
    }
}

class VillagerProfessionRegistryExtractor : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/villager_professions.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        return extractBuiltInRegistry(BuiltInRegistries.VILLAGER_PROFESSION) { profession: VillagerProfession, json ->
            val workSound = profession.workSound()
            if (workSound != null) {
                json.addProperty("work_sound", soundKey(workSound))
            }
        }
    }

    private fun soundKey(sound: SoundEvent): String {
        val key = BuiltInRegistries.SOUND_EVENT.getKey(sound)
            ?: error("Villager profession work sound has no key: $sound")
        return key.toString()
    }
}
