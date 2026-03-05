package com.github.gameringop.features.impl.dev

import com.github.gameringop.SoTerm
import com.github.gameringop.SoTerm.mc
import com.github.gameringop.features.Feature
import com.github.gameringop.ui.clickgui.components.getValue
import com.github.gameringop.ui.clickgui.components.impl.ButtonSetting
import com.github.gameringop.ui.clickgui.components.impl.TextInputSetting
import com.github.gameringop.ui.clickgui.components.provideDelegate
import com.github.gameringop.ui.clickgui.components.withDescription
import com.github.gameringop.utils.ChatUtils
import com.github.gameringop.utils.dungeons.DungeonListener
import com.google.gson.Gson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object HypixelAPI : Feature("Hypixel API Integration") {
    
    private val apiKey by TextInputSetting("API Key", "")
        .withDescription("Get your API key from https://developer.hypixel.net/")
    
    private val testKey by ButtonSetting("Test API Key", false) {
        testApiKey()
    }
    
    private val testUsername by TextInputSetting("Test Username", "")
        .withDescription("Enter a username to check for Legendary Spirit pet")
    
    private val checkSpirit by ButtonSetting("Check Spirit Pet", false) {
        if (testUsername.value.isNotBlank()) {
            checkSpecificPlayer(testUsername.value)
        } else {
            ChatUtils.modMessage("§cPlease enter a username first!")
        }
    }
    
    private val clearCache by ButtonSetting("Clear Spirit Cache", false) {
        spiritCache.clear()
        uuidCache.clear()
        assumedSpirit.clear()
        ChatUtils.modMessage("§aSpirit pet cache cleared!")
    }
    
    private val showCache by ButtonSetting("Show Spirit Cache", false) {
        if (spiritCache.isEmpty()) {
            ChatUtils.modMessage("§eSpirit cache is empty")
            return@ButtonSetting
        }
        
        ChatUtils.modMessage("§6=== Spirit Cache ===")
        spiritCache.forEach { (username, hasSpirit) ->
            val status = when {
                hasSpirit && assumedSpirit[username] == true -> "§e⚠ (Assumed - Spirit)"
                hasSpirit -> "§a✓ (Spirit)"
                else -> "§c✗ (No Spirit)"
            }
            ChatUtils.modMessage("§f$username: $status")
        }
        ChatUtils.modMessage("§6==================")
    }
    
    val hasValidKey: Boolean
        get() = apiKey.value.isNotBlank()
    
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val uuidCache = ConcurrentHashMap<String, String>()
    private val uuidPendingRequests = ConcurrentHashMap<String, Long>()
    
    private val spiritCache = ConcurrentHashMap<String, Boolean>()
    private val spiritPendingRequests = ConcurrentHashMap<String, Long>()
    private val assumedSpirit = ConcurrentHashMap<String, Boolean>()
    
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
        
        Thread {
            try {
                val url = "https://api.hypixel.net/v2/player?name=Hypixel"
                
                if (SoTerm.debugFlags.contains("link")) {
                    ChatUtils.modMessage("§7Request URL: $url")
                    ChatUtils.modMessage("§7API Key: ${apiKey.value.take(8)}...${apiKey.value.takeLast(4)}")
                }
                
                val request = Request.Builder()
                    .url(url)
                    .header("API-Key", apiKey.value)
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    
                    if (SoTerm.debugFlags.contains("link")) {
                        ChatUtils.modMessage("§7Response code: ${response.code}")
                    }
                    
                    if (responseBody.trimStart().startsWith("<")) {
                        if (SoTerm.debugFlags.contains("link")) {
                            ChatUtils.modMessage("§cReceived HTML instead of JSON")
                            ChatUtils.modMessage("§7First 200 chars: ${responseBody.take(200)}")
                        }
                        return@use
                    }
                    
                    val jsonResponse = try {
                        gson.fromJson(responseBody, Map::class.java)
                    } catch (e: Exception) {
                        if (SoTerm.debugFlags.contains("link")) {
                            ChatUtils.modMessage("§cFailed to parse JSON response")
                            ChatUtils.modMessage("§7Raw response: ${responseBody.take(200)}")
                        }
                        return@use
                    }
                    
                    val cause = jsonResponse["cause"] as? String ?: ""
                    if (cause.contains("You have already looked up this name recently")) {
                        ChatUtils.modMessage("§aAPI key is valid! (Rate limited - key works)")
                        return@use
                    }
                    
                    if (response.isSuccessful && jsonResponse["success"] == true) {
                        ChatUtils.modMessage("§aAPI key is valid!")
                    } else {
                        ChatUtils.modMessage("§cAPI key is invalid! $cause")
                    }
                }
            } catch (e: Exception) {
                ChatUtils.modMessage("§cFailed to test API key: ${e.message}")
                if (SoTerm.debugFlags.contains("link")) {
                    e.printStackTrace()
                }
            }
        }.start()
    }
    
    private fun checkSpecificPlayer(username: String) {
        if (apiKey.value.isBlank()) {
            ChatUtils.modMessage("§cPlease enter an API key first!")
            return
        }
        
        ChatUtils.modMessage("§eChecking Spirit pet for §f$username§e...")
        
        Thread {
            try {
                val uuid = getUUIDFromUsername(username) ?: run {
                    ChatUtils.modMessage("§cFailed to get UUID for $username")
                    return@Thread
                }
                
                val url = "https://api.hypixel.net/v2/skyblock/profiles?uuid=$uuid"
                
                if (SoTerm.debugFlags.contains("link")) {
                    ChatUtils.modMessage("§7Request URL: $url")
                }
                
                val request = Request.Builder()
                    .url(url)
                    .header("API-Key", apiKey.value)
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    
                    if (!response.isSuccessful) {
                        ChatUtils.modMessage("§cAPI request failed: ${response.code}")
                        if (SoTerm.debugFlags.contains("link")) {
                            ChatUtils.modMessage("§7Response body: ${responseBody.take(500)}")
                        }
                        return@use
                    }
                    
                    val profilesResponse = try {
                        gson.fromJson(responseBody, SkyblockProfiles::class.java)
                    } catch (e: Exception) {
                        ChatUtils.modMessage("§cFailed to parse JSON response")
                        if (SoTerm.debugFlags.contains("link")) {
                            ChatUtils.modMessage("§7Raw response: ${responseBody.take(500)}")
                        }
                        return@use
                    }
                    
                    if (!profilesResponse.success) {
                        ChatUtils.modMessage("§cAPI error: ${profilesResponse.cause}")
                        return@use
                    }
                    
                    val selectedProfile = profilesResponse.profiles?.find { it.selected }
                    
                    if (selectedProfile == null) {
                        ChatUtils.modMessage("§cNo selected profile found for $username")
                        return@use
                    }
                    
                    val member = selectedProfile.members[uuid]
                    val hasSpirit = member?.pets_data?.pets?.any { it.isSpirit } ?: false
                    
                    if (hasSpirit) {
                        ChatUtils.modMessage("§a$username has a Legendary Spirit pet! §7(§6Spirit§7)")
                    } else {
                        ChatUtils.modMessage("§c$username does NOT have a Legendary Spirit pet")
                    }
                    
                    spiritCache[username] = hasSpirit
                }
            } catch (e: Exception) {
                ChatUtils.modMessage("§cFailed to check Spirit pet: ${e.message}")
                if (SoTerm.debugFlags.contains("link")) {
                    e.printStackTrace()
                }
            }
        }.start()
    }
    
    fun getUUIDFromUsername(username: String): String? {
        uuidCache[username]?.let { return it }
        
        if (uuidPendingRequests.containsKey(username)) {
            val lastRequest = uuidPendingRequests[username] ?: 0
            if (System.currentTimeMillis() - lastRequest < 60000) {
                return null
            }
        }
        
        uuidPendingRequests[username] = System.currentTimeMillis()
        
        try {
            val request = Request.Builder()
                .url("https://api.mojang.com/users/profiles/minecraft/$username")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = response.body?.string() ?: return null
                val data = gson.fromJson(json, Map::class.java)
                val uuid = data["id"] as? String
                
                if (uuid != null) {
                    uuidCache[username] = uuid
                    return uuid
                }
            }
        } catch (e: Exception) {
            if (SoTerm.debugFlags.contains("link")) {
                ChatUtils.modMessage("§7[Mojang API] Error: ${e.message}")
            }
        } finally {
            uuidPendingRequests.remove(username)
        }
        
        return null
    }
    
    fun checkSpiritPet(username: String): Boolean {
        if (apiKey.value.isBlank()) {
            if (SoTerm.debugFlags.contains("spirit")) {
                ChatUtils.modMessage("§eNo API key, assuming Spirit for $username")
            }
            spiritCache[username] = true
            return true
        }
        
        spiritCache[username]?.let { 
            if (SoTerm.debugFlags.contains("spirit")) {
                ChatUtils.modMessage("§eCache hit for $username: $it")
            }
            return it 
        }
        
        Thread {
            try {
                val uuid = getUUIDFromUsername(username) ?: run {
                    if (SoTerm.debugFlags.contains("spirit")) {
                        ChatUtils.modMessage("§eUUID fetch failed for $username, assuming Spirit")
                    }
                    spiritCache[username] = true
                    return@Thread
                }
                
                val url = "https://api.hypixel.net/v2/skyblock/profiles?uuid=$uuid"
                
                if (SoTerm.debugFlags.contains("link")) {
                    ChatUtils.modMessage("§7Request URL: $url")
                }
                
                val request = Request.Builder()
                    .url(url)
                    .header("API-Key", apiKey.value)
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    
                    if (!response.isSuccessful) {
                        if (SoTerm.debugFlags.contains("spirit")) {
                            ChatUtils.modMessage("§eAPI request failed (${response.code}) for $username, assuming Spirit")
                        }
                        if (SoTerm.debugFlags.contains("link")) {
                            ChatUtils.modMessage("§7Response body: ${responseBody.take(500)}")
                        }
                        spiritCache[username] = true
                        return@use
                    }
                    
                    val profilesResponse = try {
                        gson.fromJson(responseBody, SkyblockProfiles::class.java)
                    } catch (e: Exception) {
                        if (SoTerm.debugFlags.contains("spirit")) {
                            ChatUtils.modMessage("§eJSON parse failed for $username, assuming Spirit")
                        }
                        if (SoTerm.debugFlags.contains("link")) {
                            ChatUtils.modMessage("§7Raw response: ${responseBody.take(500)}")
                        }
                        spiritCache[username] = true
                        return@use
                    }
                    
                    if (!profilesResponse.success) {
                        if (SoTerm.debugFlags.contains("spirit")) {
                            ChatUtils.modMessage("§eAPI error (${profilesResponse.cause}) for $username, assuming Spirit")
                        }
                        spiritCache[username] = true
                        return@use
                    }
                    
                    val selectedProfile = profilesResponse.profiles?.find { it.selected }
                    
                    if (selectedProfile == null) {
                        if (SoTerm.debugFlags.contains("spirit")) {
                            ChatUtils.modMessage("§eNo selected profile for $username, assuming Spirit")
                        }
                        spiritCache[username] = true
                        return@use
                    }
                    
                    val member = selectedProfile.members[uuid]
                    val hasSpirit = member?.pets_data?.pets?.any { it.isSpirit } ?: false
                    
                    if (SoTerm.debugFlags.contains("spirit")) {
                        if (hasSpirit) {
                            ChatUtils.modMessage("§a$username has Legendary Spirit pet")
                        } else {
                            ChatUtils.modMessage("§c$username does NOT have Legendary Spirit pet")
                        }
                    }
                    
                    spiritCache[username] = hasSpirit
                }
            } catch (e: Exception) {
                if (SoTerm.debugFlags.contains("spirit")) {
                    ChatUtils.modMessage("§eException for $username: ${e.message}, assuming Spirit")
                }
                if (SoTerm.debugFlags.contains("link")) {
                    e.printStackTrace()
                }
                spiritCache[username] = true
            }
        }.start()
        
        return spiritCache[username] ?: false
    }
        
    fun getSpiritStatus(username: String): Boolean? = spiritCache[username]
    
    fun isSpiritLoaded(username: String): Boolean = spiritCache.containsKey(username)
    
    fun hasAssumedSpirit(username: String): Boolean = assumedSpirit[username] == true
}
