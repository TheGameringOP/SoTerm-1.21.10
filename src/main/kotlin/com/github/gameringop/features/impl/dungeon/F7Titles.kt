package com.github.gameringop.features.impl.dungeon

import com.github.gameringop.event.EventBus
import com.github.gameringop.event.impl.*
import com.github.gameringop.features.Feature
import com.github.gameringop.ui.clickgui.components.getValue
import com.github.gameringop.ui.clickgui.components.impl.DropdownSetting
import com.github.gameringop.ui.clickgui.components.impl.ToggleSetting
import com.github.gameringop.ui.clickgui.components.provideDelegate
import com.github.gameringop.ui.clickgui.components.withDescription
import com.github.gameringop.utils.ChatUtils
import com.github.gameringop.utils.ChatUtils.unformattedText
import com.github.gameringop.utils.NumbersUtils.toFixed
import com.github.gameringop.utils.dungeons.DungeonListener
import com.github.gameringop.utils.location.LocationUtils.dungeonFloorNumber
import com.github.gameringop.utils.location.LocationUtils.inBoss
import com.github.gameringop.utils.render.Render2D
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.sounds.SoundEvents

object F7Titles: Feature(name = "F7 Titles", description = "Custom Titles for F7 boss fight.") {
    private val crystalTitles by ToggleSetting("Crystal Titles")
    private val witherTitles by ToggleSetting("Wither Titles")
    private val lightningTimer by ToggleSetting("Lightning Timer")
    
    private val titleMode by DropdownSetting("Title Mode", 0, listOf("Titles", "Draw"))
        .withDescription("Titles: Minecraft titles, Draw: Rendered text on screen (like Room Alerts)")

    private val crystalRegex = Regex("^(\\d)/(\\d) Energy Crystals are now active!$")
    private val enragedRegex = Regex("^⚠ (\\w+) is enraged! ⚠$")

    private var timerTime = 0L
    private var maxorDead = false
    private var goldorDead = false
    private var necronDead = false
    private var goldorStart = false
    private var necronStart = false

    override fun init() {
        register<WorldChangeEvent> {
            maxorDead = false
            goldorDead = false
            necronDead = false
            goldorStart = false
            necronStart = false
            timerTime = 0L
            timerRenderer.unregister()
        }

        register<ChatMessageEvent> {
            if (dungeonFloorNumber != 7 || !inBoss || !witherTitles.value) return@register
            when (event.unformattedText) {
                "[BOSS] Maxor: YOU TRICKED ME!", "[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!" -> showTitle("&dMaxor Stunned!")
                "[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!" -> showTitle("&bStorm Crushed!")
                "[BOSS] Storm: I should have known that I stood no chance." -> showTitle("&bStorm Dead!")
                "[BOSS] Necron: ARGH!" -> necronStart = true
                "The Core entrance is opening!" -> goldorStart = true
            }
        }

        register<MainThreadPacketReceivedEvent.Pre> {
            if (dungeonFloorNumber != 7 || !inBoss) return@register

            when (val packet = event.packet) {
                is ClientboundSetSubtitleTextPacket -> {
                    val text = packet.text.unformattedText
                    if (text.isBlank()) return@register

                    if (crystalTitles.value) {
                        if (text == "The Energy Laser is charging up!") {
                            event.isCanceled = true
                            return@register
                        }

                        crystalRegex.find(text)?.destructured?.let { (min, max) ->
                            val progress = formatProgress(min.toInt(), max.toInt())
                            showTitle("&3Crystal&r($progress)")
                            event.isCanceled = true
                            return@register
                        }
                    }

                    enragedRegex.find(text)?.destructured?.component1()?.let { boss ->
                        val color = when (boss) {
                            "Storm" -> {
                                mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1f))
                                "&b"
                            }
                            "Maxor" -> "&5"
                            else -> ""
                        }
                        showTitle("$color$text")
                        event.isCanceled = true
                    }
                }

                is ClientboundSetTitleTextPacket -> {
                    if (!lightningTimer.value) return@register

                    val text = packet.text.unformattedText
                    if (text.isBlank()) return@register
                    val number = text.toIntOrNull() ?: return@register
                    event.isCanceled = true

                    if (!timerRenderer.isRegistered() && (number == 4 || number == 6)) {
                        timerTime = DungeonListener.currentTime + (number * 1.35 * 20.0).toLong()
                        timerRenderer.register()
                    }
                }
            }
        }

        register<BossBarUpdateEvent> {
            if (!witherTitles.value) return@register
            if (dungeonFloorNumber != 7 || !inBoss) return@register
            if (event.progress > 0f) return@register
            val name = event.name.unformattedText
            val entry = DungeonListener.bossEntryTime ?: return@register

            if (name.contains("Maxor") && !maxorDead && DungeonListener.currentTime - entry > 120) {
                maxorDead = true
                showTitle("&dMaxor Dead!")
            }
            else if (name.contains("Goldor") && !goldorDead && goldorStart) {
                goldorDead = true
                showTitle("&7Goldor Dead!")
            }
            else if (name.contains("Necron") && !necronDead && necronStart) {
                necronDead = true
                showTitle("&cNecron Dead!!")
            }
        }
    }

    private val timerRenderer = EventBus.register<RenderOverlayEvent> {
        if (!enabled) return@register
        val timeLeft = (timerTime - DungeonListener.currentTime) / 20.0

        if (timeLeft <= 0) {
            this.listener.unregister()
            showTitle("&aStorm's Lightning Ended!")
            return@register
        }

        val width = mc.window.guiScaledWidth
        val height = mc.window.guiScaledHeight

        Render2D.drawCenteredString(
            event.context,
            "&l&c${timeLeft.toFixed(1)}",
            width / 2f,
            height / 2f - height / 13f,
            scale = 3f
        )
    }.unregister()

    private fun showTitle(text: String) {
        when (titleMode.value) {
            0 -> {
                mc.player?.connection?.sendChat("")
                mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
            }
            1 -> {
                ChatUtils.showTitle(text)
                mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
            }
        }
    }

    private fun formatProgress(current: Int, max: Int): String {
        val minColor = if (current == max) "&b" else "&c"
        return "$minColor$current&r/&r&b$max&r"
    }
}
