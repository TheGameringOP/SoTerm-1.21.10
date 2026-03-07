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
import com.google.gson.Gson
import okhttp3.*
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
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
        .build()
    
    private val uuidCache = ConcurrentHashMap<String, String>()
    private val uuidPendingRequests = ConcurrentHashMap<String, Long>()
    private val spiritCache = ConcurrentHashMap<String, Boolean>()
    private val assumedSpirit = ConcurrentHashMap<String, Boolean>()
    
    data class SkyblockProfiles(val success: Boolean, val cause: String?, val profiles: List<Profile>?)
    data class Profile(val profile_id: String, val selected: Boolean, val members: Map<String, Member>)
    data class Member(val pets_data: PetsData?)
    data class PetsData(val pets: List<Pet>?)
    data class Pet(val type: String, val tier: String, val heldItem: String? = null) {
        val isSpirit: Boolean get() = type.equals("SPIRIT", ignoreCase = true) && 
            (tier.equals("LEGENDARY", ignoreCase = true) || (tier.equals("EPIC", ignoreCase = true) && heldItem == "PET_ITEM_TIER_BOOST"))
    }

    private fun testApiKey() {
        if (apiKey.value.isBlank()) {
            ChatUtils.modMessage("§cPlease enter an API key first!")
            return
        }
        Thread {
            try {
                val request = Request.Builder()
                    .url("https://api.hypixel.net/v2/player?name=Hypixel")
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .addHeader("API-Key", apiKey.value)
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val json = try { gson.fromJson(body, Map::class.java) } catch (e: Exception) { null }
                    val cause = json?.get("cause") as? String ?: ""
                    
                    if (response.isSuccessful || cause.contains("recently")) {
                        ChatUtils.modMessage("§aAPI key is valid!")
                    } else {
                        ChatUtils.modMessage("§cAPI key invalid! (Code: ${response.code}) $cause")
                    }
                }
            } catch (e: Exception) {
                ChatUtils.modMessage("§cTest failed: ${e.message}")
            }
        }.start()
    }

    private fun checkSpecificPlayer(username: String) {
        ChatUtils.modMessage("§eChecking §f$username§e...")
        checkSpiritPetAsync(username) { hasSpirit ->
            if (hasSpirit) ChatUtils.modMessage("§a$username has a Legendary Spirit pet!")
            else ChatUtils.modMessage("§c$username does NOT have a Legendary Spirit pet")
        }
    }

    fun checkSpiritPetAsync(username: String, callback: (Boolean) -> Unit) {
        if (apiKey.value.isBlank()) {
            spiritCache[username] = true
            assumedSpirit[username] = true
            callback(true)
            return
        }

        spiritCache[username]?.let {
            callback(it)
            return
        }

        Thread {
            val uuid = getUUIDFromUsername(username) ?: run {
                spiritCache[username] = true
                assumedSpirit[username] = true
                callback(true)
                return@Thread
            }

            val request = Request.Builder()
                .url("https://api.hypixel.net/v2/skyblock/profiles?uuid=$uuid")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Accept", "application/json")
                .addHeader("API-Key", apiKey.value)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    spiritCache[username] = true
                    assumedSpirit[username] = true
                    callback(true)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            spiritCache[username] = true
                            assumedSpirit[username] = true
                            callback(true)
                            return
                        }
                        val body = response.body?.string() ?: ""
                        val data = try { gson.fromJson(body, SkyblockProfiles::class.java) } catch (e: Exception) { null }
                        val hasSpirit = data?.profiles?.find { it.selected }?.members?.get(uuid)?.pets_data?.pets?.any { it.isSpirit } ?: false
                        
                        spiritCache[username] = hasSpirit
                        callback(hasSpirit)
                    }
                }
            })
        }.start()
    }

    fun getUUIDFromUsername(username: String): String? {
        uuidCache[username]?.let { return it }
        if (uuidPendingRequests.containsKey(username)) return null
        uuidPendingRequests[username] = System.currentTimeMillis()
        
        return try {
            val request = Request.Builder()
                .url("https://api.mojang.com/users/profiles/minecraft/$username")
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val data = gson.fromJson(response.body?.string(), Map::class.java)
                val uuid = data["id"] as? String
                if (uuid != null) uuidCache[username] = uuid
                uuid
            }
        } catch (e: Exception) { null }
        finally { uuidPendingRequests.remove(username) }
    }

    fun getSpiritStatus(username: String): Boolean? = spiritCache[username]
    fun isSpiritLoaded(username: String): Boolean = spiritCache.containsKey(username)
    fun hasAssumedSpirit(username: String): Boolean = assumedSpirit[username] == true
}
