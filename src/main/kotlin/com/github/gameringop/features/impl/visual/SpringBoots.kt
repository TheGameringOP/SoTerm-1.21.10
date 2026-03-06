package com.github.gameringop.features.impl.visual

import com.github.gameringop.SoTerm.mc
import com.github.gameringop.event.impl.MainThreadPacketReceivedEvent
import com.github.gameringop.event.impl.RenderWorldEvent
import com.github.gameringop.event.impl.TickEvent
import com.github.gameringop.features.Feature
import com.github.gameringop.ui.clickgui.components.*
import com.github.gameringop.ui.clickgui.components.impl.ColorSetting
import com.github.gameringop.ui.clickgui.components.impl.ToggleSetting
import com.github.gameringop.ui.hud.HudElement
import com.github.gameringop.ui.hud.getValue
import com.github.gameringop.ui.hud.provideDelegate
import com.github.gameringop.utils.Utils.equalsOneOf
import com.github.gameringop.utils.items.ItemUtils.skyblockId
import com.github.gameringop.utils.location.LocationUtils
import com.github.gameringop.utils.render.Render2D
import com.github.gameringop.utils.render.Render2D.width
import com.github.gameringop.utils.render.Render3D
import com.github.gameringop.utils.render.RenderHelper.renderVec
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EquipmentSlot
import java.awt.Color

object SpringBoots : Feature("Spring Boots Display") {
    
    private val modEnabled by ToggleSetting("Enabled", true).section("Main")
    private val show2DHud by ToggleSetting("Show 2D HUD", true)
        .withDescription("Shows jump height as text on screen")
    private val show3DBox by ToggleSetting("Show 3D Box", true)
        .withDescription("Shows a 3D box above your head at jump height")
    
    private val boxColor by ColorSetting("Box Color", Color.CYAN, false)
        .withDescription("Color of the 3D box")
        .showIf { show3DBox.value }
    private val boxOutline by ToggleSetting("Box Outline", true)
        .withDescription("Show box outline")
        .showIf { show3DBox.value }
    private val boxFill by ToggleSetting("Box Fill", false)
        .withDescription("Fill the box with color")
        .showIf { show3DBox.value }
    private val boxPhase by ToggleSetting("See Through Walls", true)
        .withDescription("Box visible through walls")
        .showIf { show3DBox.value }
    
    private val textColor by ColorSetting("Text Color", Color.WHITE, false)
        .withDescription("Color of the height text")
        .showIf { show2DHud.value }
    
    private val pitchSet = setOf(0.82539684f, 0.8888889f, 0.93650794f, 1.0476191f, 1.1746032f, 1.3174603f, 1.7777778f)
    private var blockAmount = 0f
    private var highCount = 0
    private var lowCount = 0
    private var lastBlockAmount = 0f
    
    private val blocksList = listOf(
        0.0f, 3.0f, 6.5f, 9.0f, 11.5f, 13.5f, 16.0f, 18.0f, 19.0f,
        20.5f, 22.5f, 25.0f, 26.5f, 28.0f, 29.0f, 30.0f, 31.0f, 33.0f,
        34.0f, 35.5f, 37.0f, 38.0f, 39.5f, 40.0f, 41.0f, 42.5f, 43.5f,
        44.0f, 45.0f, 46.0f, 47.0f, 48.0f, 49.0f, 50.0f, 51.0f, 52.0f,
        53.0f, 54.0f, 55.0f, 56.0f, 57.0f, 58.0f, 59.0f, 60.0f, 61.0f
    )
    
    private fun isWearingSpringBoots(): Boolean {
        return mc.player?.getItemBySlot(EquipmentSlot.FEET)?.skyblockId == "SPRING_BOOTS"
    }
    
    private val hud by hudElement(
        name = "Spring Boots Height",
        enabled = { modEnabled.value && show2DHud.value && LocationUtils.inSkyblock },
        shouldDraw = { true }
    ) { context, demo ->
        val displayAmount = if (demo) 33.0f else blockAmount
        if (displayAmount <= 0f && !demo) return@hudElement 0f to 0f
        
        val text = "§l${String.format("%.1f", displayAmount)} blocks"
        Render2D.drawString(context, text, 0, 0, textColor.value)
        
        return@hudElement text.width().toFloat() to 9f
    }
    
    override fun init() {
        register<MainThreadPacketReceivedEvent.Pre> {
            if (!modEnabled.value || !LocationUtils.inSkyblock) return@register
            if (event.packet !is ClientboundSoundPacket) return@register
            val player = mc.player ?: return@register
            
            val id = event.packet.sound.value().location
            val pitch = event.packet.pitch
            
            when {
                SoundEvents.NOTE_BLOCK_PLING.`is`(id) && player.isCrouching && isWearingSpringBoots() -> {
                    when (pitch) {
                        0.6984127f -> lowCount = (lowCount + 1).coerceAtMost(2)
                        in pitchSet -> highCount++
                    }
                    
                    val index = (lowCount + highCount).coerceIn(blocksList.indices)
                    blockAmount = blocksList[index]
                }
                
                SoundEvents.FIREWORK_ROCKET_LAUNCH.location == id && pitch.equalsOneOf(0.0952381f, 1.6984127f) -> {
                    highCount = 0
                    lowCount = 0
                    blockAmount = 0f
                }
            }
        }
        
        register<TickEvent.End> {
            if (!modEnabled.value || !LocationUtils.inSkyblock) return@register
            val player = mc.player ?: return@register
            
            if (!player.isCrouching || !isWearingSpringBoots()) {
                highCount = 0
                lowCount = 0
                blockAmount = 0f
            }
        }
        
        register<RenderWorldEvent> {
            if (!modEnabled.value || !LocationUtils.inSkyblock) return@register
            if (!show3DBox.value) return@register
            if (blockAmount == 0f) return@register
            
            val player = mc.player ?: return@register
            val pos = player.renderVec
            
            Render3D.renderBox(
                ctx = it.ctx,
                x = pos.x,
                y = pos.y,
                z = pos.z,
                width = 1.0,
                height = blockAmount.toDouble(),
                outlineColor = boxColor.value,
                fillColor = boxColor.value,
                outline = boxOutline.value,
                fill = boxFill.value,
                phase = boxPhase.value
            )
        }
    }
}
