package com.github.gameringop.features.impl.dungeon

import com.github.gameringop.SoTerm
import com.github.gameringop.event.impl.DungeonEvent
import com.github.gameringop.event.impl.TickEvent
import com.github.gameringop.features.Feature
import com.github.gameringop.features.impl.dev.HypixelAPI
import com.github.gameringop.ui.clickgui.components.*
import com.github.gameringop.ui.clickgui.components.impl.ColorSetting
import com.github.gameringop.ui.clickgui.components.impl.DropdownSetting
import com.github.gameringop.ui.clickgui.components.impl.ToggleSetting
import com.github.gameringop.ui.hud.HudElement
import com.github.gameringop.ui.hud.getValue
import com.github.gameringop.ui.hud.provideDelegate
import com.github.gameringop.utils.ChatUtils
import com.github.gameringop.utils.dungeons.DungeonListener
import com.github.gameringop.utils.dungeons.DungeonUtils
import com.github.gameringop.utils.dungeons.map.DungeonInfo
import com.github.gameringop.utils.dungeons.map.core.RoomState
import com.github.gameringop.utils.dungeons.map.handlers.ScoreCalculation
import com.github.gameringop.utils.location.LocationUtils
import com.github.gameringop.utils.render.Render2D
import com.github.gameringop.utils.render.Render2D.width
import net.minecraft.client.gui.GuiGraphics
import java.awt.Color
import kotlin.math.ceil

object DungeonScoreHud : Feature("Dungeon Score HUD") {
    
    private val showInBoss by ToggleSetting("Show in Boss", true).section("Main")
    
    private val spiritTracking by DropdownSetting("Spirit Tracking", 1, listOf(
        "Off",
        "Assume Yes",
        "Auto (Requires API Key)"
    )).section("Spirit Tracking")
    
    private val showDungeonStatus by ToggleSetting("Show Dungeon Status", true).section("Sections")
    private val showScoreSection by ToggleSetting("Show Score Section", true)
    
    private val showDeaths by ToggleSetting("Show Deaths", true).section("Toggles").showIf { showDungeonStatus.value }
    private val showMissingPuzzles by ToggleSetting("Show Missing Puzzles", true).showIf { showDungeonStatus.value }
    private val showFailedPuzzles by ToggleSetting("Show Failed Puzzles", true).showIf { showDungeonStatus.value }
    private val showSecrets by ToggleSetting("Show Secrets", true).showIf { showDungeonStatus.value }
    private val showCrypts by ToggleSetting("Show Crypts", true).showIf { showDungeonStatus.value }
    private val showMimic by ToggleSetting("Show Mimic", true).showIf { showDungeonStatus.value }
    private val showPrince by ToggleSetting("Show Prince", true).showIf { showDungeonStatus.value }
    
    private val showSkillScore by ToggleSetting("Show Skill Score", true).section("Toggles").showIf { showScoreSection.value }
    private val showExploreScore by ToggleSetting("Show Explore Score", true).showIf { showScoreSection.value }
    private val showSpeedScore by ToggleSetting("Show Speed Score", true).showIf { showScoreSection.value }
    private val showBonusScore by ToggleSetting("Show Bonus Score", true).showIf { showScoreSection.value }
    private val showTotalScore by ToggleSetting("Show Total Score", true).showIf { showScoreSection.value }
    private val showRank by ToggleSetting("Show Rank", true).showIf { showScoreSection.value }
    
    private val headerColor by ColorSetting("Header Color", Color(0, 150, 255)).section("Colors")
    private val textColor by ColorSetting("Text Color", Color.WHITE)
    private val valueColor by ColorSetting("Value Color", Color(85, 255, 85))
    private val warningColor by ColorSetting("Warning Color", Color(255, 85, 85))
    private val paulColor by ColorSetting("Paul Bonus Color", Color(255, 170, 0))
    private val spiritColor by ColorSetting("Spirit Color", Color(255, 170, 0))
    
    private var missingPuzzles = 0
    private var failedPuzzles = 0
    private var firstDeathHadSpirit = false
    private var checkedSpiritForFirstDeath = false
    
    private val textLines = mutableListOf<String>()
    
    private val hud by hudElement(
        name = "Dungeon Score HUD",
        enabled = { LocationUtils.inDungeon && (showInBoss.value || !LocationUtils.inBoss) },
        shouldDraw = { true }
    ) { ctx, demo ->
        if (demo) {
            drawDemo(ctx)
        } else {
            updateData()
            drawActual(ctx)
        }
    }
    
    private fun updateData() {
        missingPuzzles = DungeonListener.puzzles.count { it.state == RoomState.UNOPENED }
        failedPuzzles = DungeonListener.puzzles.count { it.state == RoomState.FAILED }
    }
    
    private fun drawDemo(ctx: GuiGraphics): Pair<Float, Float> {
        textLines.clear()
        
        if (showDungeonStatus.value) {
            textLines.add("§9Dungeon Status")
            if (showDeaths.value) textLines.add("§f• §eDeaths:§c 0")
            if (showMissingPuzzles.value) textLines.add("§f• §eMissing Puzzles:§c 0")
            if (showFailedPuzzles.value) textLines.add("§f• §eFailed Puzzles:§c 0")
            if (showSecrets.value) textLines.add("§f• §eSecrets: §a50§7/§a50")
            if (showCrypts.value) textLines.add("§f• §eCrypts:§a 5")
            if (showMimic.value && (LocationUtils.dungeonFloorNumber ?: 0) >= 6) {
                textLines.add("§f• §eMimic:§a ✔")
            }
            if (showPrince.value) {
                textLines.add("§f• §ePrince:§a ✔")
            }
            textLines.add("")
        }
        
        if (showScoreSection.value) {
            textLines.add("§6Score")
            if (showSkillScore.value) textLines.add("§f• §eSkill Score:§a 100")
            if (showExploreScore.value) textLines.add("§f• §eExplore Score:§a 100 §7(§e60 §7+ §640§7)")
            if (showSpeedScore.value) textLines.add("§f• §eSpeed Score:§a 100")
            if (showBonusScore.value) textLines.add("§f• §eBonus Score:§a 17")
            if (showTotalScore.value) textLines.add("§f• §eTotal Score:§a 317 §7(§6+10§7)")
            if (showRank.value) textLines.add("§f• §eRank: §6§lS+")
        }
        
        var y = 0f
        var maxWidth = 0f
        
        textLines.forEach { line ->
            Render2D.drawString(ctx, line, 0, y.toInt(), textColor.value)
            maxWidth = maxOf(maxWidth, line.width().toFloat())
            y += 9f
        }
        
        return maxWidth to y
    }
    
    private fun drawActual(ctx: GuiGraphics): Pair<Float, Float> {
        textLines.clear()
        val floorNum = LocationUtils.dungeonFloorNumber ?: 0
        val isPaul = DungeonUtils.isPaul()
        val totalScore = ScoreCalculation.score
        val rank = getRank(totalScore)
        
        if (showDungeonStatus.value) {
            textLines.add("§9Dungeon Status")
            
            if (showDeaths.value) {
                val spiritText = getSpiritText()
                val deathsText = "§f• §eDeaths:§c ${ScoreCalculation.deathCount}$spiritText"
                textLines.add(deathsText)
            }
            
            if (showMissingPuzzles.value) {
                textLines.add("§f• §eMissing Puzzles:§c $missingPuzzles")
            }
            
            if (showFailedPuzzles.value) {
                textLines.add("§f• §eFailed Puzzles:§c $failedPuzzles")
            }
            
            if (showSecrets.value) {
                val foundSecrets = ScoreCalculation.foundSecrets
                val totalSecrets = DungeonInfo.secretCount
                val neededSecrets = calculateNeededSecrets()
                val secretsColor = if (foundSecrets >= neededSecrets) "§a" else "§c"
                
                val secretsText = "§f• §eSecrets: $secretsColor$foundSecrets§7/§a$neededSecrets"
                textLines.add(secretsText)
            }
            
            if (showCrypts.value) {
                val cryptsColor = if (ScoreCalculation.cryptsCount >= 5) "§a" else "§c"
                textLines.add("§f• §eCrypts: $cryptsColor${ScoreCalculation.cryptsCount}")
            }
            
            if (showMimic.value && floorNum >= 6) {
                val mimicText = "§f• §eMimic:§l${if (ScoreCalculation.mimicKilled) "§a ✔" else "§c ✘"}"
                textLines.add(mimicText)
            }
            
            if (showPrince.value) {
                val princeText = "§f• §ePrince:§l${if (ScoreCalculation.princeKilled) "§a ✔" else "§c ✘"}"
                textLines.add(princeText)
            }
            
            textLines.add("")
        }
        
        if (showScoreSection.value) {
            textLines.add("§6Score")
            
            if (showSkillScore.value) {
                val skillScore = calculateSkillScore()
                textLines.add("§f• §eSkill Score:§a $skillScore")
            }
            
            if (showExploreScore.value) {
                val clearScore = calculateClearScore()
                val secretsScore = calculateSecretsScore()
                val exploreScore = clearScore + secretsScore
                textLines.add("§f• §eExplore Score:§a $exploreScore §7(§e$clearScore §7+ §6$secretsScore§7)")
            }
            
            if (showSpeedScore.value) {
                val speedScore = calculateSpeedScore()
                textLines.add("§f• §eSpeed Score:§a $speedScore")
            }
            
            if (showBonusScore.value) {
                val bonusScore = calculateBonusScore()
                val displayedBonus = if (floorNum == 0) ceil(bonusScore * 0.7).toInt() else bonusScore
                textLines.add("§f• §eBonus Score:§a $displayedBonus")
            }
            
            if (showTotalScore.value) {
                val totalLine = "§f• §eTotal Score:§a $totalScore" + 
                    (if (isPaul && floorNum != 0) " §7(§6+10§7)" else if (isPaul && floorNum == 0) " §7(§6+7§7)" else "")
                textLines.add(totalLine)
            }
            
            if (showRank.value) {
                textLines.add("§f• §eRank: $rank")
            }
        }
        
        var y = 0f
        var maxWidth = 0f
        
        textLines.forEach { line ->
            Render2D.drawString(ctx, line, 0, y.toInt(), textColor.value)
            maxWidth = maxOf(maxWidth, line.width().toFloat())
            y += 9f
        }
        
        return maxWidth to y
    }
    
    private fun getSpiritText(): String {
        return when (spiritTracking.value) {
            0 -> ""
            1 -> " §7(§6Spirit§7)"
            2 -> {
                val anySpirit = DungeonListener.dungeonTeammatesNoSelf.any { teammate ->
                    val status = HypixelAPI.getSpiritStatus(teammate.name)
                    when {
                        status == true -> true
                        status == null -> {
                            HypixelAPI.checkSpiritPet(teammate.name)
                            true
                        }
                        HypixelAPI.hasAssumedSpirit(teammate.name) -> true
                        else -> false
                    }
                }
                
                if (anySpirit) {
                    " §7(§6Spirit§7)"
                } else {
                    ""
                }
            }
            else -> ""
        }
    }
    
    private fun calculateNeededSecrets(): Int {
        val floor = LocationUtils.dungeonFloor ?: "F7"
        val req = when (floor) {
            "E", "F1", "F2" -> 0.3
            "F3" -> 0.5
            "F4" -> 0.6
            "F5" -> 0.7
            "F6" -> 0.85
            else -> 1.0
        }
        val totalSecrets = DungeonInfo.secretCount
        return if (totalSecrets > 0) ceil(totalSecrets * req).toInt() else 0
    }
    
    private fun calculateSkillScore(): Int {
        val totalRooms = if (ScoreCalculation.completedRooms > 0 && ScoreCalculation.clearedPercentage > 0) {
            (ScoreCalculation.completedRooms / (ScoreCalculation.clearedPercentage / 100.0)).toInt()
        } else 36
        
        val completedRooms = ScoreCalculation.completedRooms + 
            (if (DungeonListener.watcherClearTime == null) 1 else 0) + 
            (if (!LocationUtils.inBoss) 1 else 0)
        
        val clearPercent = if (totalRooms > 0) completedRooms.toDouble() / totalRooms else 0.0
        val baseSkill = 20 + (clearPercent * 80)
        
        val puzzlePenalty = (missingPuzzles + failedPuzzles) * 10
        
        val deathPenalty = when (spiritTracking.value) {
            0 -> ScoreCalculation.deathCount * 2
            1 -> (ScoreCalculation.deathCount * 2) - 1
            2 -> {
                val anySpirit = DungeonListener.dungeonTeammatesNoSelf.any { teammate ->
                    val status = HypixelAPI.getSpiritStatus(teammate.name)
                    status == true || status == null || HypixelAPI.hasAssumedSpirit(teammate.name)
                }
                
                if (anySpirit) {
                    (ScoreCalculation.deathCount * 2) - 1
                } else {
                    ScoreCalculation.deathCount * 2
                }
            }
            else -> ScoreCalculation.deathCount * 2
        }
        
        val floorNum = LocationUtils.dungeonFloorNumber ?: 0
        val rawScore = (baseSkill - puzzlePenalty - deathPenalty).toInt()
        
        return if (floorNum == 0) rawScore.coerceIn(14, 70) else rawScore.coerceIn(20, 100)
    }
    
    private fun calculateClearScore(): Int {
        val totalRooms = if (ScoreCalculation.completedRooms > 0 && ScoreCalculation.clearedPercentage > 0) {
            (ScoreCalculation.completedRooms / (ScoreCalculation.clearedPercentage / 100.0)).toInt()
        } else 36
        
        val completedRooms = ScoreCalculation.completedRooms + 
            (if (DungeonListener.watcherClearTime == null) 1 else 0) + 
            (if (!LocationUtils.inBoss) 1 else 0)
        
        val clearPercent = if (totalRooms > 0) completedRooms.toDouble() / totalRooms else 0.0
        val rawScore = (60 * clearPercent).coerceIn(0.0, 60.0)
        
        return if (LocationUtils.dungeonFloor == "E") (rawScore * 0.7).toInt() else rawScore.toInt()
    }
    
    private fun calculateSecretsScore(): Int {
        val found = ScoreCalculation.foundSecrets
        val needed = calculateNeededSecrets()
        val percent = if (needed > 0) found.toDouble() / needed else 0.0
        val rawScore = (40 * percent).coerceIn(0.0, 40.0)
        
        return if (LocationUtils.dungeonFloor == "E") (rawScore * 0.7).toInt() else rawScore.toInt()
    }
    
    private fun calculateSpeedScore(): Int {
        val secondsElapsed = ScoreCalculation.secondsElapsed
        val floor = LocationUtils.dungeonFloor ?: "F7"
        
        val timeLimit = when (floor) {
            "E", "F1", "F2" -> 600
            "F3" -> 600
            "F4" -> 720
            "F5" -> 600
            "F6" -> 720
            "F7" -> 840
            "M1", "M2", "M3", "M4", "M5" -> 480
            "M6" -> 600
            "M7" -> 840
            else -> 600
        }
        
        val totalElapsed = secondsElapsed + 480 - timeLimit
        
        return if (LocationUtils.dungeonFloor == "E") {
            when {
                totalElapsed < 492 -> 70
                totalElapsed < 600 -> (140 - totalElapsed / 12.0).toInt()
                totalElapsed < 840 -> (115 - totalElapsed / 24.0).toInt()
                totalElapsed < 1140 -> (108 - totalElapsed / 30.0).toInt()
                totalElapsed < 3570 -> (98.5 - totalElapsed / 40.0).toInt()
                else -> 0
            }
        } else {
            when {
                totalElapsed < 492 -> 100
                totalElapsed < 600 -> (140 - totalElapsed / 12.0).toInt()
                totalElapsed < 840 -> (115 - totalElapsed / 24.0).toInt()
                totalElapsed < 1140 -> (108 - totalElapsed / 30.0).toInt()
                totalElapsed < 3570 -> (98.5 - totalElapsed / 40.0).toInt()
                else -> 0
            }
        }
    }
    
    private fun calculateBonusScore(): Int {
        var bonus = ScoreCalculation.cryptsCount.coerceAtMost(5)
        if (ScoreCalculation.mimicKilled && (LocationUtils.dungeonFloorNumber ?: 0) > 5) bonus += 2
        if (DungeonUtils.isPaul()) bonus += 10
        return bonus
    }
    
    private fun getRank(score: Int): String {
        return when {
            score >= 300 -> "§6§lS+"
            score >= 270 -> "§eS"
            score >= 230 -> "§5A"
            score >= 160 -> "§aB"
            score >= 100 -> "§9C"
            score >= 0 -> "§cD"
            else -> "§8F"
        }
    }
    
    override fun init() {
        register<DungeonEvent.Score> {
        }
        
        register<TickEvent.Server> {
            if (LocationUtils.inDungeon && !LocationUtils.inBoss) {
                updateData()
            }
        }
    }
    
    override fun onEnable() {
        super.onEnable()
        reset()
    }
    
    override fun onDisable() {
        super.onDisable()
        reset()
    }
    
    private fun reset() {
        firstDeathHadSpirit = false
        checkedSpiritForFirstDeath = false
    }
}
