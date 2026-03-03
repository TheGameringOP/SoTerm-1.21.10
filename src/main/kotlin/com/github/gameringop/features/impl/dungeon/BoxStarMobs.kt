package com.github.gameringop.features.impl.dungeon

import com.github.gameringop.event.impl.CheckEntityGlowEvent
import com.github.gameringop.event.impl.EntityDeathEvent
import com.github.gameringop.event.impl.MainThreadPacketReceivedEvent
import com.github.gameringop.event.impl.RenderWorldEvent
import com.github.gameringop.event.impl.WorldChangeEvent
import com.github.gameringop.features.Feature
import com.github.gameringop.ui.clickgui.components.*
import com.github.gameringop.ui.clickgui.components.impl.ColorSetting
import com.github.gameringop.ui.clickgui.components.impl.DropdownSetting
import com.github.gameringop.ui.clickgui.components.impl.ToggleSetting
import com.github.gameringop.utils.ChatUtils.formattedText
import com.github.gameringop.utils.ChatUtils.removeFormatting
import com.github.gameringop.utils.ChatUtils.unformattedText
import com.github.gameringop.utils.ColorUtils.withAlpha
import com.github.gameringop.utils.Utils.equalsOneOf
import com.github.gameringop.utils.location.LocationUtils
import com.github.gameringop.utils.location.LocationUtils.dungeonFloorNumber
import com.github.gameringop.utils.location.LocationUtils.inBoss
import com.github.gameringop.utils.render.Render3D
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.entity.player.Player
import java.awt.Color

object StarMob: Feature("Highlights all starred mobs in a dungeon.") {
    private val dungeonMobRegex = Regex("^.+❤$")

    private val starMobs = HashSet<Int>()
    private val checked = HashSet<Int>()

	private val mode by DropdownSetting("Render Mode", 1, listOf("Fill", "Outline", "Filled Outline"))
	
	private val esp by ToggleSetting("See Through Walls")

    private val starMobColor by ColorSetting("Star Mob Color", Color.GREEN, false).section("General Colors").withDescription("Default color for all Starred mobs.")
    private val batColor by ColorSetting("Bat Color", Color.GREEN, false).withDescription("The color used for highlighted bats.")
    private val felColor by ColorSetting("Fel Color", Color.GREEN, false).withDescription("The color used for fels.")


    override fun init() {
        register<MainThreadPacketReceivedEvent.Post> {
            if (! LocationUtils.inDungeon || inBoss) return@register
            if (event.packet !is ClientboundSetEntityDataPacket) return@register
            val entity = mc.level?.getEntity(event.packet.id) ?: return@register
            if (entity is ArmorStand) {
                val name = entity.customName?.formattedText ?: return@register
                if (name.matches(dungeonMobRegex) && name.contains("✯")) {
                    checkStarMob(entity, name)
                }
            }
            else if (entity is Player) {
                val name = mc.connection?.getPlayerInfo(entity.uuid)?.profile?.name ?: return@register
                if (name.equalsOneOf("Shadow Assassin", "Lost Adventurer", "Diamond Guy", "King Midas")) {
                    starMobs.add(entity.id)
                }
            }
        }

        register<EntityDeathEvent> {
            if (! LocationUtils.inDungeon || inBoss) return@register
            starMobs.removeIf { it == event.entity.id }
            checked.removeIf { it == event.entity.id }
        }

        register<WorldChangeEvent> {
            starMobs.clear()
            checked.clear()
        }

		register<RenderWorldEvent> {
		    val e = this.event
		    if (!LocationUtils.inDungeon || inBoss) return@register
		    if (starMobs.isEmpty()) return@register
		
		    for (id in starMobs) {
		        val entity = mc.level?.getEntity(id) ?: continue
		        if (!entity.isAlive) continue
		        val color = getColor(entity) ?: starMobColor.value
		        val partial = e.partialTicks
		        val interpX = entity.xOld + (entity.x - entity.xOld) * partial
		        val interpY = entity.yOld + (entity.y - entity.yOld) * partial
		        val interpZ = entity.zOld + (entity.z - entity.zOld) * partial
		        val width = entity.boundingBox.xsize
		        val height = entity.boundingBox.ysize
		
		        Render3D.renderBox(
		            e.ctx,
		            interpX,
		            interpY,
		            interpZ,
		            width,
		            height,
		            outlineColor = color,
		            fillColor = color.withAlpha(50),
		            outline = mode.value.equalsOneOf(1, 2),
		            fill = mode.value.equalsOneOf(0, 2),
		            phase = esp.value
		        )
		    }
		}
    }

    private fun getColor(entity: Entity): Color? {
        if (entity is Bat) return if (! entity.isPassenger) batColor.value else null
        if (entity is EnderMan) return if (entity.name.unformattedText == "Dinnerbone") felColor.value else null
        if (entity is Player) {
            val name = entity.name.unformattedText.takeUnless { it.isBlank() } ?: return null
            if (name.contains("Shadow Assassin")) return starMobColor.value

            if (dungeonFloorNumber != 4 && ! inBoss) {
                val bootsName = entity.getItemBySlot(EquipmentSlot.FEET).takeUnless { it.isEmpty }?.hoverName?.unformattedText ?: return null

                return when (name) {
                    "Lost Adventurer" -> starMobColor.value
                    "Diamond Guy" -> if ("Perfect Boots" in bootsName) starMobColor.value else null
                    else -> null
                }
            }
        }

        return null
    }

    private fun checkStarMob(armorStand: Entity, name: String) {
        if (! checked.add(armorStand.id)) return
        val name = name.removeFormatting().uppercase()
        // withermancers are always -3 to real entity the -1 and -2 are the wither skulls that they shoot
        val id = if (name.contains("WITHERMANCER")) 3 else 1
        val realEntityId = armorStand.id - id

        val mob = armorStand.level().getEntity(realEntityId)
        if (mob !is ArmorStand && realEntityId !in starMobs && mob != null) {
            starMobs.add(realEntityId)
            return
        }

        val possibleEntities = armorStand.level().getEntities(
            armorStand, armorStand.boundingBox.move(0.0, - 1.0, 0.0)
        ) { it !is ArmorStand }

        possibleEntities.find {
            ! starMobs.contains(it.id) && when (it) {
                is Player -> ! it.isInvisible && it.uuid.version() == 2 && it != mc.player
                is WitherBoss -> false
                else -> true
            }
        }?.let {
            if (getColor(it) == null) starMobs.add(it.id)
        }
    }
}
