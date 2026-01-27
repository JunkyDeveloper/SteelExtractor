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
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityAttachment
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import org.slf4j.LoggerFactory
import java.lang.reflect.Field
import java.util.UUID
import com.mojang.authlib.GameProfile

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
                // Default dimensions from EntityType
                val defaultDimensions = entityType.dimensions
                entityTypeJson.addProperty("width", defaultDimensions.width())
                entityTypeJson.addProperty("height", defaultDimensions.height())
                entityTypeJson.addProperty("eye_height", defaultDimensions.eyeHeight())
                entityTypeJson.addProperty("fixed", defaultDimensions.fixed())

                // Extract default attachments
                entityTypeJson.add("attachments", extractAttachments(defaultDimensions))

                // Try to create entity instance to get pose-specific dimensions and other data
                val entity = try {
                    @Suppress("UNCHECKED_CAST")
                    (entityType as EntityType<Entity>).create(world, net.minecraft.world.entity.EntitySpawnReason.LOAD)
                } catch (e: Exception) {
                    logger.warn("Failed to create entity instance for $name: ${e.message}")
                    null
                }

                // Extract pose-specific dimensions (for entities that have different sizes per pose)
                entityTypeJson.add("pose_dimensions", extractPoseDimensions(entity, entityType, world))

                // Extract baby dimensions for ageable entities
                if (entity is LivingEntity) {
                    entityTypeJson.add("baby_dimensions", extractBabyDimensions(entity))
                }

                // Extract multi-part hitboxes (e.g., EnderDragon)
                if (entity is EnderDragon) {
                    logger.info("Extracting dragon parts for ender_dragon")
                    entityTypeJson.add("parts", extractDragonParts(entity))
                }

                entity?.discard()

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
                // Entity can't be instantiated (e.g., Player)
                // For Player, create a fake ServerPlayer to get all synched data
                if (entityType == EntityType.PLAYER) {
                    val fakePlayer = createFakeServerPlayer(server = world.server)
                    if (fakePlayer != null) {
                        val entityData = entityDataField.get(fakePlayer) as SynchedEntityData
                        val itemsById = itemsByIdField.get(entityData) as Array<*>
                        val accessorNames = getAccessorNames(fakePlayer::class.java)

                        for (dataItem in itemsById) {
                            if (dataItem == null) continue

                            val dataItemJson = JsonObject()
                            val accessor =
                                accessorField.get(dataItem) as net.minecraft.network.syncher.EntityDataAccessor<*>
                            val serializerId = EntityDataSerializers.getSerializedId(accessor.serializer())
                            val index = accessor.id()

                            dataItemJson.addProperty("index", index)
                            dataItemJson.addProperty("name", accessorNames[index]?.second ?: "unknown")
                            dataItemJson.addProperty("serializer", serializerNames[serializerId] ?: "unknown")

                            val defaultValue = initialValueField.get(dataItem)
                            dataItemJson.add("default_value", serializeDefaultValue(defaultValue))

                            synchedDataArray.add(dataItemJson)
                        }

                        fakePlayer.discard()
                    }
                } else {
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

    private fun createFakeServerPlayer(server: MinecraftServer): ServerPlayer? {
        return try {
            val world = server.overworld()
            val profile = GameProfile(UUID.randomUUID(), "FakePlayer")
            ServerPlayer(server, world, profile, ClientInformation.createDefault())
        } catch (e: Exception) {
            logger.warn("Failed to create fake ServerPlayer: ${e.message}")
            null
        }
    }

    private fun extractAttachments(dimensions: net.minecraft.world.entity.EntityDimensions): JsonObject {
        val attachmentsJson = JsonObject()
        val attachmentsObj = dimensions.attachments()

        for (attachmentType in EntityAttachment.entries) {
            val pointsArray = JsonArray()
            var index = 0
            while (true) {
                val point = attachmentsObj.getNullable(attachmentType, index, 0f)
                if (point == null) break
                val pointJson = JsonObject()
                pointJson.addProperty("x", point.x)
                pointJson.addProperty("y", point.y)
                pointJson.addProperty("z", point.z)
                pointsArray.add(pointJson)
                index++
            }
            if (pointsArray.size() > 0) {
                attachmentsJson.add(attachmentType.name.lowercase(), pointsArray)
            }
        }

        return attachmentsJson
    }

    private fun extractPoseDimensions(
        entity: Entity?,
        entityType: EntityType<*>,
        world: net.minecraft.server.level.ServerLevel
    ): JsonObject {
        val poseDimensionsJson = JsonObject()
        val defaultDimensions = entityType.dimensions

        // For player, create a fake player to get dimensions
        val effectiveEntity = entity ?: if (entityType == EntityType.PLAYER) {
            createFakeServerPlayer(world.server)
        } else {
            null
        }

        if (effectiveEntity != null) {
            for (pose in Pose.entries) {
                try {
                    val poseDimensions = effectiveEntity.getDimensions(pose)

                    // Only include if different from default
                    if (poseDimensions.width() != defaultDimensions.width() ||
                        poseDimensions.height() != defaultDimensions.height() ||
                        poseDimensions.eyeHeight() != defaultDimensions.eyeHeight()
                    ) {
                        val poseJson = JsonObject()
                        poseJson.addProperty("width", poseDimensions.width())
                        poseJson.addProperty("height", poseDimensions.height())
                        poseJson.addProperty("eye_height", poseDimensions.eyeHeight())
                        poseDimensionsJson.add(pose.name.lowercase(), poseJson)
                    }
                } catch (_: Exception) {
                    // Some poses may not be valid for certain entities
                }
            }

            // Clean up fake player if we created one
            if (entity == null && effectiveEntity is ServerPlayer) {
                effectiveEntity.discard()
            }
        }

        return poseDimensionsJson
    }

    private fun extractBabyDimensions(entity: LivingEntity): JsonObject? {
        try {
            // Get the age scale method via reflection to check if this entity supports babies
            val getAgeScaleMethod = LivingEntity::class.java.getDeclaredMethod("getAgeScale")
            getAgeScaleMethod.isAccessible = true
            val currentAgeScale = getAgeScaleMethod.invoke(entity) as Float

            // If age scale is 1.0, the entity is adult - try to get baby scale
            // Most baby mobs have 0.5 scale
            if (currentAgeScale == 1.0f) {
                // Check if entity has setBaby method (AgeableMob)
                val setBabyMethod = try {
                    entity::class.java.getMethod("setBaby", Boolean::class.java)
                } catch (_: NoSuchMethodException) {
                    null
                }

                if (setBabyMethod != null) {
                    // Set to baby, get dimensions, then restore
                    setBabyMethod.invoke(entity, true)
                    entity.refreshDimensions()

                    val babyDimensions = entity.getDimensions(Pose.STANDING)
                    val babyJson = JsonObject()
                    babyJson.addProperty("width", babyDimensions.width())
                    babyJson.addProperty("height", babyDimensions.height())
                    babyJson.addProperty("eye_height", babyDimensions.eyeHeight())

                    // Restore to adult
                    setBabyMethod.invoke(entity, false)
                    entity.refreshDimensions()

                    return babyJson
                }
            }
        } catch (_: Exception) {
            // Entity doesn't support baby dimensions
        }
        return null
    }

    private fun extractDragonParts(dragon: EnderDragon): JsonArray {
        val partsArray = JsonArray()
        val subEntities = dragon.getSubEntities()
        logger.info("Dragon has ${subEntities.size} sub-entities")

        for (part in subEntities) {
            val partJson = JsonObject()
            partJson.addProperty("name", part.name)
            val partDimensions = part.getDimensions(Pose.STANDING)
            partJson.addProperty("width", partDimensions.width())
            partJson.addProperty("height", partDimensions.height())
            partsArray.add(partJson)
        }

        return partsArray
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
