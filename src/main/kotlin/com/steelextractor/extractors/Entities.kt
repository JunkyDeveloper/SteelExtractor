package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import org.slf4j.LoggerFactory
import java.lang.reflect.Field

class Entities : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-entities")

    // Cache reflection fields
    private val entityDataField: Field = Entity::class.java.getDeclaredField("entityData").apply { isAccessible = true }
    private val itemsByIdField: Field =
        SynchedEntityData::class.java.getDeclaredField("itemsById").apply { isAccessible = true }
    private val dataItemClass = Class.forName("net.minecraft.network.syncher.SynchedEntityData\$DataItem")
    private val accessorField: Field = dataItemClass.getDeclaredField("accessor").apply { isAccessible = true }
    private val initialValueField: Field = dataItemClass.getDeclaredField("initialValue").apply { isAccessible = true }

    // Build serializer name lookup
    private val serializerNames: Map<Int, String> by lazy {
        val names = mutableMapOf<Int, String>()
        for (field in EntityDataSerializers::class.java.declaredFields) {
            if (net.minecraft.network.syncher.EntityDataSerializer::class.java.isAssignableFrom(field.type)) {
                field.isAccessible = true
                val serializer = field.get(null) as? net.minecraft.network.syncher.EntityDataSerializer<*>
                if (serializer != null) {
                    val id = EntityDataSerializers.getSerializedId(serializer)
                    if (id != -1) {
                        names[id] = field.name.lowercase()
                    }
                }
            }
        }
        names
    }

    override fun fileName(): String {
        return "entities.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val entityTypesArray = JsonArray()
        val world = server.overworld()

        for (entityType in BuiltInRegistries.ENTITY_TYPE) {
            val key = BuiltInRegistries.ENTITY_TYPE.getKey(entityType)
            val name = key?.path ?: "unknown"

            val entityTypeJson = JsonObject()
            val id = BuiltInRegistries.ENTITY_TYPE.getId(entityType)

            entityTypeJson.addProperty("id", id)
            entityTypeJson.addProperty("name", name)

            try {
                // Dimensions
                entityTypeJson.addProperty("width", entityType.width)
                entityTypeJson.addProperty("height", entityType.height)

                // Get eye height from dimensions
                val dimensions = entityType.dimensions
                entityTypeJson.addProperty("eye_height", dimensions.eyeHeight())

                // Category
                entityTypeJson.addProperty("mob_category", entityType.category.name)

                // Tracking info
                entityTypeJson.addProperty("client_tracking_range", entityType.clientTrackingRange())
                entityTypeJson.addProperty("update_interval", entityType.updateInterval())

                // Flags
                entityTypeJson.addProperty("fire_immune", entityType.fireImmune())
                entityTypeJson.addProperty("summonable", entityType.canSummon())
                entityTypeJson.addProperty("can_spawn_far_from_player", entityType.canSpawnFarFromPlayer())

                // Synched data
                entityTypeJson.add("synched_data", extractSynchedData(entityType, world))

            } catch (e: Exception) {
                logger.warn("Failed to get info for ${key?.path}: ${e.message}")
            }

            entityTypesArray.add(entityTypeJson)
        }

        return entityTypesArray
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractSynchedData(
        entityType: EntityType<*>,
        world: net.minecraft.server.level.ServerLevel
    ): JsonArray {
        val synchedDataArray = JsonArray()

        try {
            val entity =
                (entityType as EntityType<Entity>).create(world, net.minecraft.world.entity.EntitySpawnReason.LOAD)

            if (entity != null) {
                val entityData = entityDataField.get(entity) as SynchedEntityData
                val itemsById = itemsByIdField.get(entityData) as Array<*>

                // Build a map of accessor ID -> field name by scanning the entity's class hierarchy
                val accessorNames = getAccessorNames(entity::class.java)

                for (dataItem in itemsById) {
                    if (dataItem == null) continue

                    val dataItemJson = JsonObject()
                    val accessor = accessorField.get(dataItem) as net.minecraft.network.syncher.EntityDataAccessor<*>
                    val serializerId = EntityDataSerializers.getSerializedId(accessor.serializer())
                    val index = accessor.id()

                    dataItemJson.addProperty("index", index)
                    dataItemJson.addProperty("name", accessorNames[index]?.second ?: "unknown")
                    dataItemJson.addProperty("serializer", serializerNames[serializerId] ?: "unknown")

                    val defaultValue = initialValueField.get(dataItem)
                    dataItemJson.add("default_value", serializeDefaultValue(defaultValue))

                    synchedDataArray.add(dataItemJson)
                }

                entity.discard()
            } else {
                // Entity can't be instantiated (e.g., Player is abstract)
                // Fall back to static extraction of EntityDataAccessor fields
                val entityClass = entityType.baseClass
                val accessorNames = getAccessorNames(entityClass)

                for ((index, pair) in accessorNames.toSortedMap()) {
                    val (accessor, name) = pair
                    val dataItemJson = JsonObject()
                    val serializerId = EntityDataSerializers.getSerializedId(accessor.serializer())

                    dataItemJson.addProperty("index", index)
                    dataItemJson.addProperty("name", name)
                    dataItemJson.addProperty("serializer", serializerNames[serializerId] ?: "unknown")

                    synchedDataArray.add(dataItemJson)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract synched data: ${e.message}")
        }

        return synchedDataArray
    }

    private fun getAccessorNames(entityClass: Class<*>): Map<Int, Pair<net.minecraft.network.syncher.EntityDataAccessor<*>, String>> {
        val accessors = mutableMapOf<Int, Pair<net.minecraft.network.syncher.EntityDataAccessor<*>, String>>()
        var clazz: Class<*>? = entityClass
        while (clazz != null && Entity::class.java.isAssignableFrom(clazz)) {
            for (field in clazz.declaredFields) {
                if (net.minecraft.network.syncher.EntityDataAccessor::class.java.isAssignableFrom(field.type)) {
                    try {
                        field.isAccessible = true
                        val accessor = field.get(null) as? net.minecraft.network.syncher.EntityDataAccessor<*>
                        if (accessor != null) {
                            val name = field.name.lowercase().removePrefix("data_")
                            accessors[accessor.id()] = Pair(accessor, name)
                        }
                    } catch (_: Exception) {
                        // Skip non-static or inaccessible fields
                    }
                }
            }
            clazz = clazz.superclass
        }
        return accessors
    }

    private fun serializeDefaultValue(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull.INSTANCE
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is java.util.Optional<*> -> {
                val obj = JsonObject()
                obj.addProperty("present", value.isPresent)
                if (value.isPresent) {
                    obj.add("value", serializeDefaultValue(value.get()))
                }
                obj
            }

            is java.util.OptionalInt -> {
                val obj = JsonObject()
                obj.addProperty("present", value.isPresent)
                if (value.isPresent) {
                    obj.addProperty("value", value.asInt)
                }
                obj
            }

            is net.minecraft.world.entity.Pose -> JsonPrimitive(value.name)
            is net.minecraft.core.Direction -> JsonPrimitive(value.name)
            is net.minecraft.world.item.ItemStack -> {
                if (value.isEmpty) {
                    JsonPrimitive("empty")
                } else {
                    val obj = JsonObject()
                    val itemKey = BuiltInRegistries.ITEM.getKey(value.item)
                    obj.addProperty("item", itemKey?.toString() ?: "unknown")
                    obj.addProperty("count", value.count)
                    obj
                }
            }

            is net.minecraft.core.BlockPos -> {
                val obj = JsonObject()
                obj.addProperty("x", value.x)
                obj.addProperty("y", value.y)
                obj.addProperty("z", value.z)
                obj
            }

            is net.minecraft.world.level.block.state.BlockState -> {
                val blockKey = BuiltInRegistries.BLOCK.getKey(value.block)
                JsonPrimitive(blockKey?.toString() ?: "unknown")
            }

            is net.minecraft.core.Rotations -> {
                val obj = JsonObject()
                obj.addProperty("x", value.x)
                obj.addProperty("y", value.y)
                obj.addProperty("z", value.z)
                obj
            }

            is net.minecraft.network.chat.Component -> {
                JsonPrimitive(value.string)
            }

            is net.minecraft.core.Holder<*> -> {
                val key = value.unwrapKey()
                if (key.isPresent) {
                    JsonPrimitive(key.get().identifier().toString())
                } else {
                    JsonPrimitive("unknown_holder")
                }
            }

            is List<*> -> {
                val arr = JsonArray()
                for (item in value) {
                    arr.add(serializeDefaultValue(item))
                }
                arr
            }

            is org.joml.Vector3f -> {
                val obj = JsonObject()
                obj.addProperty("x", value.x)
                obj.addProperty("y", value.y)
                obj.addProperty("z", value.z)
                obj
            }

            is org.joml.Quaternionf -> {
                val obj = JsonObject()
                obj.addProperty("x", value.x)
                obj.addProperty("y", value.y)
                obj.addProperty("z", value.z)
                obj.addProperty("w", value.w)
                obj
            }

            is Enum<*> -> JsonPrimitive(value.name)
            else -> JsonPrimitive(value::class.java.simpleName)
        }
    }
}
