package com.steelextractor.extractors

import com.mojang.authlib.GameProfile
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.util.UUID

class MenuTypes : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-menutypes")

    override fun fileName(): String {
        return "steel-registry/build_assets/menutypes.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val menusJson = JsonArray()
        val player = ServerPlayer(
            server,
            server.overworld(),
            GameProfile(UUID.randomUUID(), "MenuTypeExtractor"),
            ClientInformation.createDefault()
        )

        for (menuType in BuiltInRegistries.MENU) {
            val menuJson = JsonObject()
            val menu = menuType.create(0, player.inventory)
            menuJson.addProperty("name", BuiltInRegistries.MENU.getKey(menuType)!!.path)
            menuJson.addProperty("slot_count", menu.slots.size)
            menusJson.add(menuJson)
        }

        return menusJson
    }
}
