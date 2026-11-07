package cn.kerong.render.bridge

import cn.kerong.render.KrgRenderPlugin
import cn.kerong.render.KrgShared
import cn.kerong.render.KrgCommands
import cn.kerong.render.stats.PlayerStatsManager
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.state.CommandRepositories
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/**
 * HuHoBot 绑定桥：QQ OpenId ↔ MC 玩家（复用 HuHoBot 自己的绑定系统，
 * 数据存 HuHoBot 的 command-settings.dat，附属不自建绑定）。
 *
 * HuHoBot 的绑定为「一 OpenId 一玩家」（MC 玩家名全局唯一），
 * 因此不存在 KERONGPenguin 1.7.4 的多玩家选择流程。
 */
object BindBridge {

    /** 绑定查询入口（HuHoBot 状态仓库；静态对象，始终可用）。 */
    private fun repository(): Any? = try {
        CommandRepositories.bindings
    } catch (_: Throwable) {
        null
    }

    /** 桥是否可用（HuHoBot 类在 classpath；硬依赖下恒 true，保留防御）。 */
    fun getInstance(): BindBridge? = if (repository() != null) this else null

    /** QQ OpenId → 绑定的 MC 玩家名。 */
    fun findPlayerByQq(openId: String): String? = try {
        val bindings = CommandRepositories.bindings
        bindings.getBinding(openId)?.playerName?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    /**
     * QQ OpenId → 统计记录键（本附属以玩家 UUID 为记录键）：
     * 绑定玩家名 → 在线精确 / 名字索引 / Paper 离线缓存解析。
     * 解析不到 UUID 时回退玩家名本身（查信息仍可渲染，统计按空数据）。
     */
    fun findQuuidByQq(openId: String): String? {
        val name = findPlayerByQq(openId) ?: return null
        return PlayerStatsManager.resolveUuid(name)?.toString() ?: name
    }

    /** 统计记录键 → 玩家 UUID 字符串（键即 UUID 字符串；解析失败返回 null）。 */
    fun getPlayerUuidByQuuid(quuid: String): String? = try {
        java.util.UUID.fromString(quuid)
        quuid
    } catch (_: IllegalArgumentException) {
        null
    }

    /** 是否已绑定。 */
    fun isBound(openId: String): Boolean = findPlayerByQq(openId) != null

    /** 全部绑定的玩家名（财富榜玩家集并集用）。 */
    fun allBoundPlayerNames(): List<String> = try {
        CommandRepositories.bindings.allBindings().values.map { it.playerName }.filter { it.isNotBlank() }
    } catch (_: Throwable) {
        emptyList()
    }
}
