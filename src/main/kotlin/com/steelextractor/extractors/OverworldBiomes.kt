package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonNull
import com.steelextractor.SteelExtractor
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Climate.Parameter
import net.minecraft.world.level.biome.OverworldBiomeBuilder
import org.slf4j.LoggerFactory
import java.lang.reflect.Field

class OverworldBiomes : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-overworld-biomes")

    override fun fileName(): String {
        return "overworld_biome_builder.json"
    }

    /**
     * Gets a private field value from an object using reflection.
     */
    private inline fun <reified T> getField(obj: Any, fieldName: String): T? {
        return try {
            val field: Field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(obj) as? T
        } catch (e: Exception) {
            logger.error("Failed to get field '$fieldName': ${e.message}")
            null
        }
    }

    /**
     * Converts a Parameter (climate range) to a JSON array [min, max].
     */
    private fun parameterToJson(param: Parameter): JsonArray {
        val arr = JsonArray()
        // Parameter stores values as longs that need to be converted back to floats
        // The quantize function multiplies by 10000, so we divide to get back floats
        arr.add(param.min().toFloat() / 10000f)
        arr.add(param.max().toFloat() / 10000f)
        return arr
    }

    /**
     * Converts an array of Parameters to a JSON array of [min, max] pairs.
     */
    private fun parameterArrayToJson(params: Array<Parameter>): JsonArray {
        val arr = JsonArray()
        for (param in params) {
            arr.add(parameterToJson(param))
        }
        return arr
    }

    /**
     * Converts a biome ResourceKey to its string name.
     */
    private fun biomeToString(biome: ResourceKey<Biome>?): JsonElement {
        return if (biome == null) {
            JsonNull.INSTANCE
        } else {
            // ResourceKey.toString() format is "ResourceKey[registry / location]"
            // We just need the location part (e.g., "minecraft:plains")
            val str = biome.toString()
            // Extract from format like "ResourceKey[minecraft:worldgen/biome / minecraft:plains]"
            val match = Regex(".* / (.+)]$").find(str)
            val location = match?.groupValues?.get(1) ?: str
            com.google.gson.JsonPrimitive(location)
        }
    }

    /**
     * Converts a 2D biome array to JSON.
     */
    private fun biomeTableToJson(table: Array<Array<ResourceKey<Biome>?>>): JsonArray {
        val arr = JsonArray()
        for (row in table) {
            val rowArr = JsonArray()
            for (biome in row) {
                rowArr.add(biomeToString(biome))
            }
            arr.add(rowArr)
        }
        return arr
    }

    @Suppress("UNCHECKED_CAST")
    override fun extract(server: MinecraftServer): JsonElement {
        val builder = OverworldBiomeBuilder()
        val json = JsonObject()

        // Extract parameter ranges
        val temperatures = getField<Array<Parameter>>(builder, "temperatures")
        if (temperatures != null) {
            json.add("temperatures", parameterArrayToJson(temperatures))
        }

        val humidities = getField<Array<Parameter>>(builder, "humidities")
        if (humidities != null) {
            json.add("humidities", parameterArrayToJson(humidities))
        }

        val erosions = getField<Array<Parameter>>(builder, "erosions")
        if (erosions != null) {
            json.add("erosions", parameterArrayToJson(erosions))
        }

        // Extract continentalness ranges
        val continentalnessJson = JsonObject()

        val mushroomFieldsContinentalness = getField<Parameter>(builder, "mushroomFieldsContinentalness")
        if (mushroomFieldsContinentalness != null) {
            continentalnessJson.add("mushroom_fields", parameterToJson(mushroomFieldsContinentalness))
        }

        val deepOceanContinentalness = getField<Parameter>(builder, "deepOceanContinentalness")
        if (deepOceanContinentalness != null) {
            continentalnessJson.add("deep_ocean", parameterToJson(deepOceanContinentalness))
        }

        val oceanContinentalness = getField<Parameter>(builder, "oceanContinentalness")
        if (oceanContinentalness != null) {
            continentalnessJson.add("ocean", parameterToJson(oceanContinentalness))
        }

        val coastContinentalness = getField<Parameter>(builder, "coastContinentalness")
        if (coastContinentalness != null) {
            continentalnessJson.add("coast", parameterToJson(coastContinentalness))
        }

        val inlandContinentalness = getField<Parameter>(builder, "inlandContinentalness")
        if (inlandContinentalness != null) {
            continentalnessJson.add("inland", parameterToJson(inlandContinentalness))
        }

        val nearInlandContinentalness = getField<Parameter>(builder, "nearInlandContinentalness")
        if (nearInlandContinentalness != null) {
            continentalnessJson.add("near_inland", parameterToJson(nearInlandContinentalness))
        }

        val midInlandContinentalness = getField<Parameter>(builder, "midInlandContinentalness")
        if (midInlandContinentalness != null) {
            continentalnessJson.add("mid_inland", parameterToJson(midInlandContinentalness))
        }

        val farInlandContinentalness = getField<Parameter>(builder, "farInlandContinentalness")
        if (farInlandContinentalness != null) {
            continentalnessJson.add("far_inland", parameterToJson(farInlandContinentalness))
        }

        json.add("continentalness", continentalnessJson)

        // Extract biome tables
        val oceans = getField<Array<Array<ResourceKey<Biome>>>>(builder, "OCEANS")
        if (oceans != null) {
            json.add("oceans", biomeTableToJson(oceans as Array<Array<ResourceKey<Biome>?>>))
        }

        val middleBiomes = getField<Array<Array<ResourceKey<Biome>>>>(builder, "MIDDLE_BIOMES")
        if (middleBiomes != null) {
            json.add("middle_biomes", biomeTableToJson(middleBiomes as Array<Array<ResourceKey<Biome>?>>))
        }

        val middleBiomesVariant = getField<Array<Array<ResourceKey<Biome>?>>>(builder, "MIDDLE_BIOMES_VARIANT")
        if (middleBiomesVariant != null) {
            json.add("middle_biomes_variant", biomeTableToJson(middleBiomesVariant))
        }

        val plateauBiomes = getField<Array<Array<ResourceKey<Biome>>>>(builder, "PLATEAU_BIOMES")
        if (plateauBiomes != null) {
            json.add("plateau_biomes", biomeTableToJson(plateauBiomes as Array<Array<ResourceKey<Biome>?>>))
        }

        val plateauBiomesVariant = getField<Array<Array<ResourceKey<Biome>?>>>(builder, "PLATEAU_BIOMES_VARIANT")
        if (plateauBiomesVariant != null) {
            json.add("plateau_biomes_variant", biomeTableToJson(plateauBiomesVariant))
        }

        val shatteredBiomes = getField<Array<Array<ResourceKey<Biome>?>>>(builder, "SHATTERED_BIOMES")
        if (shatteredBiomes != null) {
            json.add("shattered_biomes", biomeTableToJson(shatteredBiomes))
        }

        logger.info("Extracted OverworldBiomeBuilder data")
        return json
    }
}
