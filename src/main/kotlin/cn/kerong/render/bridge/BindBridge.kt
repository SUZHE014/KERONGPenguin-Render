package cn.kerong.render.bridge

import cn.kerong.render.stats.PlayerStatsManager
import cn.huohuas001.bot.state.CommandRepositories
import java.util.UUID

/**
 * HuHoBot 原生 QQ 绑定数据桥（1.0.0 覆盖版起为唯一绑定来源）。
 *
 * 附属**不自建绑定 / 迁移体系**——QQ OpenId ↔ MC 玩家的绑定关系完全由
 * HuHoBot 原生绑定系统（[CommandRepositories.bindings]，即 BindingRepository，
 * 数据存 HuHoBot 的 command-settings.dat）持有与维护：玩家用 HuHoBot 的
 * /绑定 命令完成绑定后，本附属的全部指令立即可查，无需任何导入或迁移。
 *
 * HuHoBot 的绑定为「一 OpenId 一玩家」（MC 玩家名全局唯一），因此不存在
 * KERONGPenguin 1.7.4 的多玩家选择流程。
 */
object BindBridge {

    /** QQ OpenId → 绑定的 MC 玩家名（HuHoBot 原生绑定仓库直读）。 */
    fun findPlayerByQq(openId: String): String? = try {
        CommandRepositories.bindings.getBinding(openId)?.playerName?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    /**
     * QQ OpenId → 玩家 UUID（统计记录键）：
     * 绑定玩家名 → 在线精确 / 名字索引 / Paper 离线缓存解析；
     * 解析不到 UUID 时返回 null（玩家名片仍可渲染，统计按空数据）。
     */
    fun findUuidByQq(openId: String): UUID? {
        val name = findPlayerByQq(openId) ?: return null
        return PlayerStatsManager.resolveUuid(name)
    }

    /** 是否已绑定（HuHoBot 原生绑定数据）。 */
    fun isBound(openId: String): Boolean = findPlayerByQq(openId) != null

    /** 全部绑定的玩家名（财富榜玩家集并集用）。 */
    fun allBoundPlayerNames(): List<String> = try {
        CommandRepositories.bindings.allBindings().values.map { it.playerName }.filter { it.isNotBlank() }
    } catch (_: Throwable) {
        emptyList()
    }
}
