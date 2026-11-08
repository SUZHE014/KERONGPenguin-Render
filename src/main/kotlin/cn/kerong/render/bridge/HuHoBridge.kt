package cn.kerong.render.bridge

import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.events.GroupMessageHandler
import cn.huohuas001.bot.events.commands.BaseCommand
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import cn.kerong.render.KrgCommands
import cn.kerong.render.KrgRenderPlugin

/**
 * HuHoBot 桥接层：附属登记 + QQ 指令注入 + 面板同步。
 *
 * ## 指令注入原理（对齐 HuHoBot v1.21.0 分发链）
 *
 * HuHoBot 的群消息分发按 `GroupMessageHandler.commands` 列表顺序逐一匹配
 * （内置 PublicCommands 排第一），命中即消费；QQ 指令面板与 /帮助 也从
 * 同一列表构建（重名去重取先出现者）。因此附属的 [KrgCommands] 通过反射
 * **插到列表第 0 位**实现接管：
 * - 分发：附属先于内置匹配 → 重名指令（查在线）由附属回复；
 * - 面板：去重时附属条目胜出（名称 + 附属描述）；
 * - 关闭接管（override-commands 配置 false）时从指令表摘除对应条目，
 *   消息自然回落到 HuHoBot 内置行为。
 *
 * ## 生命周期看门狗
 *
 * HuHoBot 的 `/huhobot reload`（fullRestart）会重建 GroupMessageHandler，
 * 旧注入随之失效；看门狗周期性核验注入状态，失效自动重注入并重同步面板
 * （QQ 面板写接口限频 10 QPM，QClient 内部已做节流与合并）。
 *
 * ## 反射面（已对照 1.21.0 发行 JAR 逐一验证）
 *
 * 公开 API 全部走编译期直接调用（HuHoBotSpigot.getInstance / registerAddon、
 * QClient.syncGroupPanels、CommandRepositories.bindings）；反射仅剩三个
 * 私有字段，全部存在于 v1.21.0：
 * - `QClient.groupMessageHandler`（private static，Kotlin object 属性）
 * - `GroupMessageHandler.commands`（private final CopyOnWriteArrayList）
 * - `BaseCommand.commandMap`（private final Map<String, CommandHandler>）
 *
 * 反射失败一律降级（记录日志、注入标记 false），附属不崩、HuHoBot 不受影响；
 * HuHoBot 未来版本若调整内部字段名，附属表现为「接管失效 + 明确日志」，
 * 重名指令回落内置行为。
 */
object HuHoBridge {

    /** 附属在 HuHoBot 扩展中心登记的名称（与 plugin.yml name 一致）。 */
    const val ADDON_NAME = "KERONGPenguinRender"

    /** 已创建的指令处理器（单例复用，重注入不换实例）。 */
    @Volatile
    private var commandsHandler: KrgCommands? = null

    /** 看门狗上一轮注入是否成功（避免重复日志刷屏）。 */
    @Volatile
    private var lastInjectionState: Boolean? = null

    /**
     * 指令表完整快照（指令名 → BaseCommand 内部 CommandHandler）：
     * 首次注入时备份；接管开关从「关」切回「开」时从备份回填。
     */
    private val backupEntries = HashMap<String, Any>()

    // ---------- 附属登记 ----------

    /** 在 HuHoBot 的 /addons 扩展中心登记附属元数据（公开 API 直调）。 */
    fun registerAddonMeta(plugin: KrgRenderPlugin) {
        try {
            val bot = HuHoBotSpigot.getInstance() ?: run {
                plugin.logger.warning("未找到 HuHoBotPenguin 主插件实例，跳过扩展登记")
                return
            }
            bot.registerAddon(
                ADDON_NAME,
                plugin.description.version,
                "KERONGPenguin 图片渲染附属（玩家名片 / 查在线 / 查性能 / 排行榜）",
                "KERONG",
            )
            plugin.logger.info("已在 HuHoBot 扩展中心登记：$ADDON_NAME")
        } catch (error: Throwable) {
            plugin.logger.warning("HuHoBot 扩展登记失败（不影响功能）: ${error.message}")
        }
    }

    // ---------- 指令注入 ----------

    /**
     * 确保指令已注入当前 GroupMessageHandler（幂等）：
     * - 处理器未创建 → 创建；
     * - 未注入或 HuHoBot 已重建处理器 → 插入到 commands 列表第 0 位；
     * - 按配置应用重名接管（override-commands）；
     * - 注入状态变化时触发面板重同步。
     *
     * @return 注入是否成功
     */
    fun ensureCommandInjection(plugin: KrgRenderPlugin): Boolean {
        val handler = commandsHandler ?: KrgCommands().also { commandsHandler = it }
        return try {
            val groupHandler = currentGroupMessageHandler() ?: run {
                // QQ 客户端尚未启动（凭据未配置 / 异步启动未完成）：静默等待下一轮
                return false
            }
            val commands = commandsListOf(groupHandler) ?: run {
                plugin.logger.warning("无法访问 HuHoBot 指令分发列表（内部结构变化），注入中止")
                lastInjectionState = false
                return false
            }
            val alreadyInjected = commands.any { it === handler }
            if (!alreadyInjected) {
                commands.add(0, handler)
            }
            // 应用 / 重应用重名接管开关（重载配置后即时生效）
            applyOverrideSwitches(plugin, handler)
            if (lastInjectionState != true) {
                plugin.logger.info("QQ 指令已注入 HuHoBot 分发链（玩家名片 / 查在线 / 在线 / 查性能 / 在线排行榜 / 财富榜）")
                resyncPanels()
            }
            lastInjectionState = true
            true
        } catch (error: Throwable) {
            if (lastInjectionState != false) {
                plugin.logger.warning(
                    "QQ 指令注入 HuHoBot 失败：${error.javaClass.simpleName}: ${error.message}——" +
                        "重名指令将由 HuHoBot 内置行为回复；专属指令（查性能 / 在线排行榜 / 财富榜）不可用。"
                )
            }
            lastInjectionState = false
            false
        }
    }

    /**
     * 按配置应用重名接管：override-commands 段中关闭的指令族从处理器的
     * 指令表摘除（消息回落 HuHoBot 内置），开启的确保在表内（从快照回填）。
     * 「查在线」开关同时控制其别名「在线」（与 KERONGPenguin 同族语义）。
     */
    private fun applyOverrideSwitches(plugin: KrgRenderPlugin, handler: KrgCommands) {
        val commandMap = commandMapOf(handler) ?: return
        synchronized(backupEntries) {
            for (key in commandMap.keys) {
                if (key !in backupEntries) backupEntries[key] = commandMap[key]!!
            }
        }
        val config = plugin.config
        for ((switchKey, family) in KrgCommands.OVERRIDE_FAMILIES) {
            val enabled = config.getBoolean("override-commands.$switchKey", true)
            for (command in family) {
                if (enabled) {
                    val backup = backupEntries[command]
                    if (backup != null && !commandMap.containsKey(command)) {
                        commandMap[command] = backup
                    }
                } else {
                    commandMap.remove(command)
                }
            }
        }
    }

    /** 卸载：从当前处理器摘除（插件 onDisable 调用；HuHoBot 重启不回调）。 */
    fun unregisterCommands() {
        try {
            val handler = commandsHandler ?: return
            val groupHandler = currentGroupMessageHandler() ?: return
            val commands = commandsListOf(groupHandler) ?: return
            commands.remove(handler)
        } catch (_: Throwable) {
        }
    }

    // ---------- 面板同步 ----------

    /** 触发 QQ 指令面板重同步（公开 API 直调；HuHoBot 侧自带节流 / 合并 / 限频退避）。 */
    fun resyncPanels(): String {
        return try {
            QClient.syncGroupPanels()
            "QQ 指令面板同步已触发（重名指令按钮由本附属提供）"
        } catch (error: Throwable) {
            "QQ 指令面板同步触发失败: ${error.message}"
        }
    }

    // ---------- 私有字段反射（仅此三处，对照 1.21.0 JAR 验证过） ----------

    /** 当前 GroupMessageHandler 实例（QClient 私有静态属性；未启动返回 null）。 */
    private fun currentGroupMessageHandler(): GroupMessageHandler? = try {
        val field = QClient::class.java.getDeclaredField("groupMessageHandler")
        field.isAccessible = true
        field.get(null) as? GroupMessageHandler
    } catch (_: Throwable) {
        null
    }

    /** GroupMessageHandler 的指令处理器列表（private final CopyOnWriteArrayList）。 */
    private fun commandsListOf(groupHandler: GroupMessageHandler): MutableList<BaseCommand>? = try {
        val field = GroupMessageHandler::class.java.getDeclaredField("commands")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        field.get(groupHandler) as MutableList<BaseCommand>
    } catch (_: Throwable) {
        null
    }

    /** BaseCommand 子类的指令映射（private final Map<String, CommandHandler>）。 */
    private fun commandMapOf(handler: BaseCommand): MutableMap<String, Any>? = try {
        val field = BaseCommand::class.java.getDeclaredField("commandMap")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        field.get(handler) as MutableMap<String, Any>
    } catch (_: Throwable) {
        null
    }

    /** /hbkrg info 用：注入状态简述。 */
    fun statusLine(): String {
        return if (lastInjectionState == true) "已注入（附属指令最优先匹配）"
        else "未注入（等待 QQ 客户端启动或注入失败，详见日志）"
    }
}
