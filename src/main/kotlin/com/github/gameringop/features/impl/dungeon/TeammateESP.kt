package com.github.gameringop.features.impl.dungeon

import com.github.gameringop.event.impl.CheckEntityGlowEvent
import com.github.gameringop.event.impl.RenderWorldEvent
import com.github.gameringop.features.Feature
import com.github.gameringop.ui.clickgui.components.getValue
import com.github.gameringop.ui.clickgui.components.impl.ColorSetting
import com.github.gameringop.ui.clickgui.components.impl.ToggleSetting
import com.github.gameringop.ui.clickgui.components.provideDelegate
import com.github.gameringop.ui.clickgui.components.section
import com.github.gameringop.ui.clickgui.components.withDescription
import com.github.gameringop.utils.MathUtils
import com.github.gameringop.utils.dungeons.DungeonListener
import com.github.gameringop.utils.dungeons.enums.DungeonClass
import com.github.gameringop.utils.location.LocationUtils
import com.github.gameringop.utils.render.Render3D
import com.github.gameringop.utils.render.RenderHelper.renderVec
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.world.entity.Entity
import java.awt.Color

object TeammateESP: Feature("Highlights your dungeon party.") {
    val highlight by ToggleSetting("Highlight Teammates", true)
    val drawName by ToggleSetting("Show Teammate Name", true)
    
    private val archerColor by ColorSetting("Archer Color", Color(255, 170, 0), false).section("Class Colors")
    private val berserkColor by ColorSetting("Berserk Color", Color(170, 0, 0), false)
    private val mageColor by ColorSetting("Mage Color", Color(85, 255, 255), false)
    private val healerColor by ColorSetting("Healer Color", Color(255, 85, 255), false)
    private val tankColor by ColorSetting("Tank Color", Color(0, 170, 0), false)

    override fun init() {
        register<CheckEntityGlowEvent> {
            if (! highlight.value) return@register
            if (! LocationUtils.inDungeon) return@register
            if (event.entity !is AbstractClientPlayer) return@register

            for (teammate in DungeonListener.dungeonTeammates) {
                if (teammate.entity?.id != event.entity.id) continue
                event.color = getClassColor(teammate.clazz)
            }
        }

        register<RenderWorldEvent> {
            if (! drawName.value) return@register
            if (! LocationUtils.inDungeon) return@register
            for (teammate in DungeonListener.dungeonTeammatesNoSelf) {
                val entity = teammate.entity ?: continue
                val color = getClassColor(teammate.clazz).let { java.awt.Color(it.red, it.green, it.blue) }
                val hexColor = String.format("#%02x%02x%02x", color.red, color.green, color.blue)
                val renderVec = entity.renderVec
                val distance = MathUtils.distance3D(renderVec, mc.player !!.renderVec)
                val scale = (distance * 0.12f).coerceAtLeast(1.0)

                Render3D.renderString(
                    "&e[${teammate.clazz.name[0]}&e] &$hexColor${teammate.name}",
                    renderVec.x,
                    renderVec.y + entity.bbHeight + 0.7 + distance * 0.015f,
                    renderVec.z,
                    scale = scale,
                    phase = true
                )
            }
        }
    }

    private fun getClassColor(dungeonClass: DungeonClass): Color {
        return when (dungeonClass) {
            DungeonClass.Archer -> archerColor.value
            DungeonClass.Berserk -> berserkColor.value
            DungeonClass.Mage -> mageColor.value
            DungeonClass.Healer -> healerColor.value
            DungeonClass.Tank -> tankColor.value
            else -> Color.WHITE
        }
    }

    @JvmStatic
    fun shouldHideNametag(entity: Entity): Boolean {
        if (! drawName.value) return false
        if (! LocationUtils.inDungeon) return false
        return DungeonListener.dungeonTeammatesNoSelf.any { it.entity?.id == entity.id }
    }
}
