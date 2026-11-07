package cn.kerong.render

import cn.kerong.render.bridge.HuHoBridge
import cn.kerong.render.command.HbkrgCommand
import cn.kerong.render.events.StatsListener
import cn.kerong.render.render.BackgroundPackService
import cn.kerong.render.render.InfoCardAssets
import cn.kerong.render.scheduler.FoliaCompat
import cn.kerong.render.stats.MotdDisplayTracker
import cn.kerong.render.stats.PerformanceSnapshot
import cn.kerong.render.stats.PlaceholderVariableService
import cn.kerong.render.stats.PlayerStatsManager
import cn.kerong.render.stats.SparkBridge
import cn.kerong.render.stats.TickMonitor
import cn.kerong.render.tools.Cancelable
import cn.kerong.render.tools.ConsoleColors
import cn.kerong.render.tools.PluginFileLog
import cn.kerong.render.qqbind.MessageTemplates
import org.bukkit.Bukkit
import org.bukkit.command.PluginCommand
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * KERONGPenguin-Render —— KERONGPenguin 图片渲染功能的 HuHoBot 附属。
 *
 * 把 KERONGPenguin 主线版的五张图片卡片（查信息 / 查在线 / 查性能 /
 * 在线排行榜 / 财富榜）及其图片包下载、渲染配置、面板同步带到
 * HuHoBotPenguin 体系：
 * - QQ 侧指令经 [cn.kerong.render.KrgCommands]（HuHoBot BaseCommand 子类）
 *   注入 HuHoBot 的群消息分发链，最优先匹配，可按配置接管与 HuHoBot
 *   内置重名的指令（查在线 / 查信息，默认本附属接管）；
 * - 图片经 kloping SDK MessageChain base64 直传被动回复，不占群主动消息配额；
 * - 玩家数据（统计 / 排行）由附属自管理（stats/ 目录），支持从
 *   KERONGPenguin 的 QUUID 历史数据一键导入；
 * - QQ 绑定关系复用 HuHoBot 自己的绑定系统。
 */
class KrgRenderPlugin : JavaPlugin() {

    /** 指令接管注入是否成功（false 时重名指令回落 HuHoBot 内置行为）。 */
    @Volatile
    var injected: Boolean = false
        private set

    /** 注入看门狗（周期确保注入 + HuHoBot /huhobot reload 后重注入）。 */
    private var watchdog: Cancelable? = null

    override fun onEnable() {
        KrgShared.instance = this

        // 1. 配置：释放默认 config.yml + 补齐缺失键（升级场景）
        saveDefaultConfig()
        fillMissingConfigKeys()

        // 2. 数据目录：img/（自定义背景）、stats/（统计数据）
        File(dataFolder, "img").mkdirs()

        // 3. 消息模板释放（Markdown/ 目录，不覆盖已有）
        MessageTemplates.initialize()

        // 4. 玩家统计记录器（/查信息、/在线排行榜 数据源）
        PlayerStatsManager.initialize()

        // 5. KERONGPenguin 历史数据导入（存在 QUUID 目录时一次性执行）
        cn.kerong.render.migration.KerongImportService.runIfLegacyPresent(this)

        // 6. 渲染相关后台组件
        InfoCardAssets.ensureAvatarSweeper()
        TickMonitor.start(this)
        MotdDisplayTracker.portProvider = { server.port }

        // 7. 事件监听（统计累计 + 进退服播报由 HuHoBot 负责，这里只管统计）
        server.pluginManager.registerEvents(StatsListener(), this)

        // 8. /hbkrg 游戏内命令
        val command: PluginCommand? = getCommand("hbkrg")
        val hbkrg = HbkrgCommand(this)
        command?.setExecutor(hbkrg)
        command?.tabCompleter = hbkrg

        // 9. HuHoBot 桥接：注册扩展元数据 + 指令注入看门狗
        HuHoBridge.registerAddonMeta(this)
        watchdog = submitTimer(20L * 10, 20L * 10) {
            HuHoBridge.ensureCommandInjection(this)
        }
        HuHoBridge.ensureCommandInjection(this)

        logger.info("KERONGPenguin-Render v${description.version} 已加载（HuHoBotPenguin 图片渲染附属）")
    }

    override fun onDisable() {
        watchdog?.cancel()
        HuHoBridge.unregisterCommands()
        TickMonitor.stop()
        PlayerStatsManager.shutdown()
        InfoCardAssets.clearCaches()
        MotdDisplayTracker.reset()
        KrgShared.instance = null
    }

    // ---------- 配置 ----------

    /** 配置文件路径（移植代码以 configFile.parentFile 定位数据目录）。 */
    val configFile: File
        get() = File(dataFolder, "config.yml")

    /** 服务器名称（卡片副标题用；顶层 serverName 键，实时读取）。 */
    val serverName: String
        get() = config.getString("serverName", "")?.trim() ?: ""

    /**
     * 补齐缺失的配置键：以 JAR 内默认 config.yml 为底，把用户配置里
     * 缺失的键按默认值补上（isSet 探测，防止 defaults 回退导致新键不可见）。
     */
    private fun fillMissingConfigKeys() {
        try {
            val defaults = try {
                javaClass.classLoader.getResourceAsStream("config.yml")
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (_: Throwable) {
                null
            } ?: return
            val defaultsYaml = org.bukkit.configuration.file.YamlConfiguration()
            defaultsYaml.loadFromString(defaults)
            val current = config
            var changed = false
            for (key in defaultsYaml.getKeys(true)) {
                if (defaultsYaml.isConfigurationSection(key)) continue
                if (!current.isSet(key)) {
                    current.set(key, defaultsYaml.get(key))
                    changed = true
                }
            }
            // config-version 始终对齐 JAR 默认
            if (current.getInt("config-version", -1) != defaultsYaml.getInt("config-version", -1)) {
                current.set("config-version", defaultsYaml.getInt("config-version", -1))
                changed = true
            }
            if (changed) saveConfig()
        } catch (error: Throwable) {
            logger.warning("补齐配置默认键失败: ${error.message}")
        }
    }

    /** 重载配置（/hbkrg reload）：补键 → 模板缓存清理 → 指令接管重应用 → 面板重同步。 */
    fun reloadAddonConfig(): List<String> {
        val lines = mutableListOf<String>()
        reloadConfig()
        fillMissingConfigKeys()
        MessageTemplates.clearCache()
        PlayerStatsManager.saveToDiskAsync()
        injected = HuHoBridge.ensureCommandInjection(this)
        lines.add(
            if (injected) "指令接管已重新应用（查信息 / 查在线 / 查性能 / 在线排行榜 / 财富榜）"
            else "指令接管注入失败：重名指令将由 HuHoBot 内置行为回复（详见控制台日志）"
        )
        val panelResult = HuHoBridge.resyncPanels()
        lines.add(panelResult)
        return lines
    }

    // ---------- 调度（对齐 KERONGPenguin SchedulerProvider 语义，Folia 兼容） ----------

    fun submit(task: Runnable): Cancelable = FoliaCompat.runSync(this, task)

    fun submitAsync(task: Runnable): Cancelable = FoliaCompat.runAsync(this, task)

    fun submitLater(delayTicks: Long, task: Runnable): Cancelable =
        FoliaCompat.runSyncLater(this, delayTicks, task)

    fun submitTimer(delayTicks: Long, periodTicks: Long, task: Runnable): Cancelable =
        FoliaCompat.runTimer(this, delayTicks, periodTicks, task)

    // ---------- 日志（控制台 + 文件双写） ----------

    fun log_info(msg: String) {
        logger.info(ConsoleColors.decorateForConsole(msg))
        PluginFileLog.write(msg)
    }

    fun log_warning(msg: String) {
        logger.warning(ConsoleColors.decorateForConsole(msg))
        PluginFileLog.write("[警告] $msg")
    }

    fun log_error(msg: String) {
        logger.severe(ConsoleColors.decorateForConsole(msg))
        PluginFileLog.write("[错误] $msg")
    }

    // ---------- 性能快照（/查性能 数据源；与 KERONGPenguin Spigot 端同链路） ----------

    fun performanceSnapshot(): PerformanceSnapshot? {
        val api: org.bukkit.Server = Bukkit.getServer()
        val folia = try {
            FoliaCompat.isFolia
        } catch (_: Throwable) {
            false
        }

        // 1. spark 优先（插件版 → Paper 1.21+ 内置通道）
        var sparkTps: Double? = null
        var sparkMspt: Double? = null
        var sparkCpu: Double? = null
        try {
            val sparkPlugin = findSparkPlugin()
            val loader = if (sparkPlugin != null && sparkPlugin.isEnabled) {
                sparkPlugin.javaClass.classLoader
            } else {
                SparkBridge.bundledSparkClassLoader(bukkitApiLoader())
            }
            if (loader != null) {
                val metrics = SparkBridge.read(loader)
                if (metrics != null) {
                    sparkTps = metrics.tps
                    sparkMspt = metrics.mspt
                    sparkCpu = metrics.cpu
                }
            }
        } catch (_: Throwable) {
        }

        // 2. MSPT：Folia 优先自采样，其余核心反射优先、自采样兜底
        val mspt = sparkMspt
            ?: if (folia) {
                TickMonitor.sampledMspt() ?: PerformanceSnapshot.readMspt(api)
            } else {
                PerformanceSnapshot.readMspt(api) ?: TickMonitor.sampledMspt()
            }

        // 3. TPS：spark → (Folia: 自采样) → 反射 → MSPT 换算兜底
        val tps = sparkTps
            ?: (if (folia) TickMonitor.sampledTps() else null)
            ?: PerformanceSnapshot.readTps(api)
            ?: PerformanceSnapshot.tpsFromMspt(mspt)

        // 4. 在线人数（Folia 上经全局区域线程采样）
        val onlineCount = readOnlinePlayerCount(folia)

        // 5. 真实最大人数（真实值兜底；motd 展示值由 PerformanceService 切换）
        val realMaxPlayers = try {
            api.maxPlayers
        } catch (_: Throwable) {
            null
        }

        return try {
            PerformanceSnapshot.capture(tps, mspt, sparkCpu, onlineCount, realMaxPlayers)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readOnlinePlayerCount(folia: Boolean): Int? {
        val direct: () -> Int? = {
            try {
                Bukkit.getOnlinePlayers().size
            } catch (_: Throwable) {
                null
            }
        }
        return if (folia) {
            try {
                FoliaCompat.callSync(this, 3L, direct)
            } catch (_: Throwable) {
                null
            }
        } else {
            direct()
        }
    }

    private fun findSparkPlugin(): org.bukkit.plugin.Plugin? {
        return try {
            Bukkit.getPluginManager().getPlugin("spark")
                ?: Bukkit.getPluginManager().plugins.firstOrNull {
                    it.name.equals("spark", ignoreCase = true)
                }
        } catch (_: Throwable) {
            null
        }
    }

    private fun bukkitApiLoader(): ClassLoader? {
        return try {
            Bukkit::class.java.classLoader
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        /** PlaceholderAPI 变量获取的启动提示（KERONGPenguin 的 PapiDependencyCheck 简化版）。 */
        internal fun papiStartupHint(plugin: KrgRenderPlugin) {
            PlaceholderVariableService.isConfigured("total-playtime")
        }
    }
}
