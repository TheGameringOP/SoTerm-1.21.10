package com.github.gameringop.features.impl.dev

import com.github.gameringop.SoTerm
import com.github.gameringop.features.Feature
import com.github.gameringop.ui.clickgui.components.impl.ButtonSetting
import com.github.gameringop.ui.clickgui.components.impl.TextInputSetting
import com.github.gameringop.ui.clickgui.components.impl.ToggleSetting
import com.github.gameringop.ui.clickgui.components.section
import com.github.gameringop.ui.clickgui.components.withDescription
import com.github.gameringop.utils.ChatUtils
import com.github.gameringop.utils.ThreadUtils
import com.github.gameringop.utils.dungeons.DungeonListener
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object HypixelAPI : Feature("Hypixel API Integration") {
    
    private val apiEnabled by ToggleSetting("Enabled", false).section("Main")
    private val apiKey by TextInputSetting("API Key", "").section("Main")
        .withDescription("Get your API key from https://developer.hypixel.net/")
    
    private val testKey by ButtonSetting("Test API Key", false) {
        testApiKey()
    }.section("Main")
    
    private val clearCache by ButtonSetting("Clear Spirit Cache", false) {
        spiritCache.clear()
        ChatUtils.modMessage("§aSpirit pet cache cleared!")
    }.section("Main")
    
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
        
    private val spiritCache = ConcurrentHashMap<String, Boolean>()
    private val pendingRequests = ConcurrentHashMap<String, Long>()
    
    data class HypixelKeyResponse(
        val success: Boolean,
        val cause: String? = null,
        val record: KeyRecord? = null
    )
    
    data class KeyRecord(
        val key: String,
        val owner: String,
        val limit: Int,
        val queriesInPastMin: Int,
        val totalQueries: Int
    )
    
    data class HypixelProfile(
        val success: Boolean,
        val cause: String? = null,
        val player: PlayerData? = null
    )
    
    data class PlayerData(
        val displayname: String,
        val uuid: String
    )
    
    data class SkyblockProfiles(
        val success: Boolean,
        val cause: String? = null,
        val profiles: List<Profile>? = null
    )
    
    data class Profile(
        val profile_id: String,
        val cute_name: String,
        val selected: Boolean,
        val members: Map<String, Member>
    )
    
    data class Member(
        val pets_data: PetsData? = null
    )
    
    data class PetsData(
        val pets: List<Pet>? = null
    )
    
    data class Pet(
        val type: String,
        val tier: String,
        val heldItem: String? = null
    ) {
        val isSpirit: Boolean
            get() = type.equals("SPIRIT", ignoreCase = true) && 
                    (tier.equals("LEGENDARY", ignoreCase = true) || 
                     (tier.equals("EPIC", ignoreCase = true) && heldItem == "PET_ITEM_TIER_BOOST"))
    }
    
    private fun testApiKey() {
        if (apiKey.value.isBlank()) {
            ChatUtils.modMessage("§cPlease enter an API key first!")
            return
        }
        
        ThreadUtils.scheduledTask(0) {
            try {
                val request = Request.Builder()
                    .url("https://api.hypixel.net/key")
                    .header("API-Key", apiKey.value)
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    val keyResponse = gson.fromJson(responseBody, HypixelKeyResponse::class.java)
                    
                    if (response.isSuccessful && keyResponse.success) {
                        val record = keyResponse.record
                        ChatUtils.modMessage("§aAPI key is valid!")
                        if (record != null) {
                            ChatUtils.modMessage("§7Owner: §f${record.owner}")
                            ChatUtils.modMessage("§7Queries: §f${record.queriesInPastMin}/${record.limit} per minute")
                        }
                    } else {
                        val error = keyResponse.cause ?: "Unknown error"
                        ChatUtils.modMessage("§cAPI key is invalid! $error")
                    }
                }
            } catch (e: Exception) {
                ChatUtils.modMessage("§cFailed to test API key: ${e.message}")
            }
        }
    }
    
    fun getUUIDFromUsername(username: String): String? {
        return try {
            val request = Request.Builder()
                .url("https://api.mojang.com/users/profiles/minecraft/$username")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = response.body?.string() ?: return@use null
                val data = gson.fromJson(json, Map::class.java)
                data["id"] as? String
            }
        } catch (e: Exception) {
            null
        }
    }
    
    fun checkSpiritPet(username: String): Boolean {
        if (!apiEnabled.value || apiKey.value.isBlank()) return false
        
        // Check cache first
        spiritCache[username]?.let { return it }
        
        // Check if request is pending (avoid spam)
        if (pendingRequests.containsKey(username)) {
            val lastRequest = pendingRequests[username] ?: 0
            if (System.currentTimeMillis() - lastRequest < 60000) { // 1 minute cooldown
                return false
            }
        }
        
        pendingRequests[username] = System.currentTimeMillis()
        
        ThreadUtils.scheduledTask(0) {
            try {
                // Get UUID
                val uuid = getUUIDFromUsername(username) ?: run {
                    spiritCache[username] = false
                    if (SoTerm.debugFlags.contains("spirit")) {
                        ChatUtils.modMessage("§cFailed to get UUID for $username")
                    }
                    return@scheduledTask
                }
                
                // Get selected Skyblock profile
                val profilesRequest = Request.Builder()
                    .url("https://api.hypixel.net/skyblock/profiles?uuid=$uuid")
                    .header("API-Key", apiKey.value)
                    .build()
                
                client.newCall(profilesRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        spiritCache[username] = false
                        if (SoTerm.debugFlags.contains("spirit")) {
                            ChatUtils.modMessage("§cAPI request failed for $username: ${response.code}")
                        }
                        return@use
                    }
                    
                    val json = response.body?.string() ?: return@use
                    val profiles = gson.fromJson(json, SkyblockProfiles::class.java)
                    
                    if (!profiles.success) {
                        spiritCache[username] = false
                        if (SoTerm.debugFlags.contains("spirit")) {
                            ChatUtils.modMessage("§cAPI returned error for $username: ${profiles.cause}")
                        }
                        return@use
                    }
                    
                    // Find selected profile
                    val selectedProfile = profiles.profiles?.find { it.selected }
                    val member = selectedProfile?.members?.get(uuid)
                    
                    val hasSpirit = member?.pets_data?.pets?.any { it.isSpirit } ?: false
                    spiritCache[username] = hasSpirit
                    
                    if (SoTerm.debugFlags.contains("spirit")) {
                        ChatUtils.modMessage("§aSpirit pet check for $username: $hasSpirit")
                    }
                }
            } catch (e: Exception) {
                if (SoTerm.debugFlags.contains("spirit")) {
                    ChatUtils.modMessage("§cSpirit pet check failed for $username: ${e.message}")
                }
            } finally {
                pendingRequests.remove(username)
            }
        }
        
        return false // Return false while loading
    }
    
    fun getSpiritStatus(username: String): Boolean? = spiritCache[username]
    
    fun isSpiritLoaded(username: String): Boolean = spiritCache.containsKey(username)
    
    fun preloadTeammates() {
        if (!apiEnabled.value || apiKey.value.isBlank()) return
        
        DungeonListener.dungeonTeammatesNoSelf.forEach { teammate ->
            if (!isSpiritLoaded(teammate.name)) {
                checkSpiritPet(teammate.name)
            }
        }
    }
}
