package cn.kerong.render.migration

import cn.kerong.render.KrgRenderPlugin
import cn.kerong.render.stats.PlayerStatsManager
import cn.kerong.render.tools.PluginFileLog
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

/**
 * KERONGPenguin 历史数据导入（一次性，幂等）。
 *
 * 服务器从 KERONGPenguin 本体切换到 HuHoBotPenguin + 本附属时，
 * 把本体 QUUID/ 目录里的统计数据（QUUID/&lt;QUUID&gt;.yml 的 playerName /
 * playerUuid / stats 节）导入附属自管理的 stats/ 目录——
 * /查信息 的生涯统计与 /在线排行榜 的历史时长无缝延续。
 *
 * 规则：
 * - 仅当检测到 plugins/KERONGPenguin/QUUID/ 目录且附属 stats/ 目录为空
 *   （首次运行）时执行，避免每次启动重复扫描；
 * - 每条记录取 playerName + playerUuid + stats 节（今日在线不导入——
 *   跨插件边界无意义）；playerUuid 缺失时尝试名字解析，仍失败则跳过；
 * - 导入合并规则见 [PlayerStatsManager.importRecord]（两侧各字段取最大值，
 *   新数据不被旧值覆盖）；
 * - 导入完成后写入 stats/.imported 标记，彻底避免重复执行；
 * - 只读取，绝不改动 KERONGPenguin 的任何文件（本体可能随时被回装）。
 */
object KerongImportService {

    /** 导入完成标记（附属 stats/ 目录下）。 */
    private val MARKER = File(".imported")

    fun runIfLegacyPresent(plugin: KrgRenderPlugin) {
        try {
            plugin.submitAsync { import(plugin) }
        } catch (_: Throwable) {
        }
    }

    private fun import(plugin: KrgRenderPlugin) {
        val statsFolder = File(plugin.dataFolder, "stats")
        if (!statsFolder.isDirectory && !statsFolder.mkdirs()) return
        val marker = File(statsFolder, MARKER.name)
        if (marker.isFile) return // 已导入过

        val legacyDir = File(plugin.dataFolder.parentFile, "KERONGPenguin/QUUID")
        if (!legacyDir.isDirectory) {
            // 本体不存在（全新安装）：直接写标记，跳过后续启动的目录探测
            marker.writeText("no-legacy\n")
            return
        }

        val files: Array<java.io.File> = legacyDir.listFiles { f -> f.isFile && f.name.endsWith(".yml") }
            ?.filter { it.name != "index.yml" && it.name != "skip.yml" && it.name != "blacklist.yml" }
            ?.toTypedArray()
            ?: emptyArray()
        if (files.isEmpty()) {
            marker.writeText("no-legacy\n")
            return
        }

        var imported = 0
        var skipped = 0
        for (file in files) {
            try {
                val yaml = YamlConfiguration.loadConfiguration(file)
                val name = yaml.getString("playerName", "")?.trim() ?: ""
                if (name.isEmpty()) {
                    skipped++
                    continue
                }
                val uuidStr = yaml.getString("playerUuid", "")?.trim()
                val uuid = when {
                    !uuidStr.isNullOrEmpty() -> try {
                        UUID.fromString(uuidStr)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                    else -> null
                } ?: PlayerStatsManager.resolveUuid(name)
                if (uuid == null) {
                    skipped++
                    continue
                }
                val stats = PlayerStatsManager.PlayerStats(
                    playSeconds = yaml.getLong("stats.play-seconds"),
                    mobKills = yaml.getLong("stats.mob-kills"),
                    dragonKills = yaml.getLong("stats.dragon-kills"),
                    playerKills = yaml.getLong("stats.player-kills"),
                    deaths = yaml.getLong("stats.deaths"),
                    fishCaught = yaml.getLong("stats.fish-caught"),
                    ancientDebris = yaml.getLong("stats.ancient-debris"),
                    raidTriggers = yaml.getLong("stats.raid-triggers"),
                    animalsBred = yaml.getLong("stats.animals-bred"),
                    walkCm = yaml.getLong("stats.walk-cm"),
                    flyCm = yaml.getLong("stats.fly-cm"),
                    damageDealt = yaml.getLong("stats.damage-dealt"),
                )
                PlayerStatsManager.importRecord(name, uuid, stats)
                imported++
            } catch (_: Throwable) {
                skipped++
            }
        }

        PlayerStatsManager.saveToDiskBlocking()
        marker.writeText("imported:$imported:skipped:$skipped\n")
        val summary = "[数据导入] 已从 KERONGPenguin 导入 $imported 名玩家的统计数据" +
            if (skipped > 0) "（$skipped 条无玩家名 / UUID 解析失败被跳过）" else ""
        PluginFileLog.write(summary)
        plugin.log_info(summary)
    }
}
