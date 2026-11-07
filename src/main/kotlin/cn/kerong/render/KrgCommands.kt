package cn.kerong.render

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.events.commands.BaseCommand
import cn.huohuas001.bot.events.commands.Commands
import cn.kerong.render.bridge.BindBridge
import cn.kerong.render.qqbind.MessageTemplates
import cn.kerong.render.stats.LeaderboardService
import cn.kerong.render.stats.OnlineListService
import cn.kerong.render.stats.PerformanceService
import cn.kerong.render.stats.QueryInfoService
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.qqpd.User

/**
 * 附属 QQ 指令处理器（HuHoBot BaseCommand 子类）——
 * KERONGPenguin 主线版五张图片卡片的入口：
 *
 * | 指令 | 卡片 | 与 HuHoBot 内置重名 |
 * |------|------|----------------------|
 * | 查信息 | 玩家生涯统计卡片 | 是（内置为 OpenId 文本查询） |
 * | 查在线 / 在线 | 在线玩家列表卡片 | 是（内置为自带渲染 / MOTD 图） |
 * | 查性能 | 服务器性能监控卡片 | 否 |
 * | 在线排行榜 | 总在线时长排行 | 否 |
 * | 财富榜 | 金币财富排行 | 否 |
 *
 * 重名指令经 [cn.kerong.render.bridge.HuHoBridge] 注入到 HuHoBot 分发链
 * **最前端**实现接管（override-commands 段可关闭，回落内置行为，默认接管）。
 * 本附属自身另有 commands.<指令名> 开关（关闭后回复 command-disabled 模板）。
 */
class KrgCommands : BaseCommand() {

    /** /查信息 —— 玩家生涯统计卡片（绑定玩家数据；@成员 可查他人）。 */
    @Commands(command = "查信息", describe = "查询玩家生涯统计卡片")
    fun queryPersonalInfo(api: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!ownCommandEnabled("查信息")) {
            MessageTemplates.sendDirect(event, "command-disabled")
            return
        }
        val bridge = try {
            BindBridge.getInstance()
        } catch (_: Throwable) {
            null
        }
        if (bridge == null) {
            MessageTemplates.sendDirect(event, "manager-not-ready-bind-retry")
            return
        }
        val qq = try {
            userId(event)
        } catch (_: Throwable) {
            "<unknown>"
        }
        if (qq.isEmpty() || qq == "<unknown>") {
            MessageTemplates.sendDirect(event, "qq-unknown")
            return
        }
        val plugin = KrgShared.instance ?: return
        // @ 了其他成员则查询该成员的绑定玩家（排除机器人自身）
        val mentioned = extractMentionedQqInfo(event)
        if (mentioned != null) {
            val targetId = mentioned.firstOrNull() ?: return
            val targetName = mentioned.getOrNull(1) ?: targetId
            if (BindBridge.findPlayerByQq(targetId) == null) {
                MessageTemplates.sendDirect(event, "info-target-not-bound", mapOf("name" to targetName))
                return
            }
            QueryInfoService.handle(plugin, event, qq, targetId, targetName)
            return
        }
        if (!BindBridge.isBound(qq)) {
            MessageTemplates.sendDirect(event, "info-not-bound")
            return
        }
        QueryInfoService.handle(plugin, event, qq)
    }

    /** /查在线 —— 在线玩家列表卡片（分页；多页每页独立抽背景）。 */
    @Commands(command = "查在线", describe = "查询在线玩家列表卡片")
    fun queryOnline(api: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!ownCommandEnabled("查在线")) {
            MessageTemplates.sendDirect(event, "command-disabled")
            return
        }
        val plugin = KrgShared.instance ?: return
        val qq = try {
            userId(event)
        } catch (_: Throwable) {
            "<unknown>"
        }
        val cooldownKey = if (qq.isEmpty() || qq == "<unknown>") "group:${groupId(event)}" else qq
        OnlineListService.handle(plugin, event, cooldownKey)
    }

    /** /在线 —— /查在线 的短别名（同一行为；随 override-commands.查在线 一并接管）。 */
    @Commands(command = "在线", describe = "在线列表（/查在线 别名）")
    fun queryOnlineAlias(api: HuHoBot, event: GroupMessageEvent, params: String) {
        queryOnline(api, event, params)
    }

    /** /查性能 —— 服务器性能监控卡片（TPS / MSPT / 内存 / CPU / 在线人数）。 */
    @Commands(command = "查性能", describe = "查询服务器性能卡片")
    fun queryPerformance(api: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!ownCommandEnabled("查性能")) {
            MessageTemplates.sendDirect(event, "command-disabled")
            return
        }
        val plugin = KrgShared.instance ?: return
        val qq = try {
            userId(event)
        } catch (_: Throwable) {
            "<unknown>"
        }
        val cooldownKey = if (qq.isEmpty() || qq == "<unknown>") "group:${groupId(event)}" else qq
        PerformanceService.handle(plugin, event, cooldownKey)
    }

    /** /在线排行榜 —— 总在线时长排行（全量玩家，分页图片）。 */
    @Commands(command = "在线排行榜", describe = "总在线时长排行榜")
    fun onlineLeaderboard(api: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!ownCommandEnabled("在线排行榜")) {
            MessageTemplates.sendDirect(event, "command-disabled")
            return
        }
        val plugin = KrgShared.instance ?: return
        val qq = try {
            userId(event)
        } catch (_: Throwable) {
            "<unknown>"
        }
        val cooldownKey = if (qq.isEmpty() || qq == "<unknown>") "group:${groupId(event)}" else qq
        LeaderboardService.handleOnline(plugin, event, cooldownKey)
    }

    /** /财富榜 —— 金币财富排行（需 Vault 经济；经济名称走 economy-alias）。 */
    @Commands(command = "财富榜", describe = "金币财富排行榜")
    fun coinsLeaderboard(api: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!ownCommandEnabled("财富榜")) {
            MessageTemplates.sendDirect(event, "command-disabled")
            return
        }
        val plugin = KrgShared.instance ?: return
        val qq = try {
            userId(event)
        } catch (_: Throwable) {
            "<unknown>"
        }
        val cooldownKey = if (qq.isEmpty() || qq == "<unknown>") "group:${groupId(event)}" else qq
        LeaderboardService.handleCoins(plugin, event, cooldownKey)
    }

    // ---------- 内部 ----------

    /** 本附属自身命令开关（commands.<名>，默认开）。 */
    private fun ownCommandEnabled(name: String): Boolean = try {
        KrgShared.instance?.config?.getBoolean("commands.$name", true) ?: true
    } catch (_: Throwable) {
        true
    }

    /** 发送者 OpenId。 */
    private fun userId(event: GroupMessageEvent): String = try {
        val contact = event.sender
        contact?.openid ?: contact?.id ?: ""
    } catch (_: Throwable) {
        ""
    }

    /** 群 OpenId。 */
    private fun groupId(event: GroupMessageEvent): String = try {
        event.groupOpenId ?: event.groupId
    } catch (_: Throwable) {
        ""
    }

    /** @成员 解析（取第一个非机器人成员的 id + 昵称）。 */
    private fun extractMentionedQqInfo(event: GroupMessageEvent): List<String>? {
        return try {
            val mentions: Array<User>? = event.rawMessage?.mentions
            if (mentions.isNullOrEmpty()) return null
            for (user in mentions) {
                try {
                    if (user.bot == true) continue
                } catch (_: Throwable) {
                }
                val id = user.id ?: continue
                val name = user.username
                return listOf(id, name ?: id)
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        /** 重名接管族：开关键 → 受控指令列表（查在线 含别名 在线）。 */
        val OVERRIDE_FAMILIES: List<Pair<String, List<String>>> = listOf(
            "查在线" to listOf("查在线", "在线"),
            "查信息" to listOf("查信息"),
        )

        /** 本处理器全部指令名。 */
        val ALL_KEYS: List<String> = listOf("查信息", "查在线", "在线", "查性能", "在线排行榜", "财富榜")
    }
}
