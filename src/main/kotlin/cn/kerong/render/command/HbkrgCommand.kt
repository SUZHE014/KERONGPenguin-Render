package cn.kerong.render.command

import cn.kerong.render.KrgRenderPlugin
import cn.kerong.render.bridge.HuHoBridge
import cn.kerong.render.render.BackgroundPackService
import cn.kerong.render.render.InfoCardAssets
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import java.util.Locale

/**
 * /hbkrg 主命令（附属版）：reload / info / mem / bpng / bpngsz / help。
 *
 * 自 KERONGPenguin 1.7.4 移植；与本体版的差异：
 * - 无 chs / qr —— QQ 连接与扫码登录归 HuHoBotPenguin 管（对应命令
 *   为 HuHoBot 的 /huhobot）；
 * - reload 重载附属配置后重新应用指令接管并触发 QQ 指令面板重同步
 *   （面板按钮由 HuHoBot 统一管理，附属只触发同步）；
 * - info 额外显示 HuHoBot 版本与指令注入状态。
 */
class HbkrgCommand(private val plugin: KrgRenderPlugin) : TabExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = if (args.isNotEmpty()) args[0].lowercase(Locale.ROOT) else "help"
        when (sub) {
            "reload" -> {
                sender.sendMessage("${ChatColor.YELLOW}正在重载附属配置并重新同步 QQ 指令面板（异步执行）…")
                plugin.submitAsync {
                    try {
                        val lines = plugin.reloadAddonConfig()
                        plugin.submit {
                            sender.sendMessage("${ChatColor.GREEN}已重载附属配置。")
                            for (line in lines) {
                                sender.sendMessage("${ChatColor.WHITE}$line")
                            }
                        }
                    } catch (t: Throwable) {
                        plugin.submit { sender.sendMessage("${ChatColor.RED}重载配置失败: ${t.message}") }
                    }
                }
                return true
            }
            "info" -> {
                val huhoVersion = try {
                    cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot.getInstance()
                        ?.description?.version ?: "未知"
                } catch (_: Throwable) {
                    "未知"
                }
                sender.sendMessage(
                    "附属: KERONGPenguin-Render v${plugin.description.version}\n" +
                        "HuHoBotPenguin: v$huhoVersion\n" +
                        "指令注入: ${HuHoBridge.statusLine()}"
                )
                return true
            }
            "mem" -> {
                sender.sendMessage("${ChatColor.GOLD}===== KERONGPenguin-Render 内存诊断 =====")
                for (line in InfoCardAssets.cacheStats().split('\n')) {
                    sender.sendMessage("${ChatColor.GRAY}$line")
                }
                sender.sendMessage(
                    "${ChatColor.YELLOW}提示: ${ChatColor.WHITE}已提交堆是 JVM 预留量，渲染高峰后扩张、" +
                        "不代表附属实际占用；若此处存活堆很小而面板内存很高，多查其他插件 / JVM 参数"
                )
                return true
            }
            "bpng" -> {
                handleBpng(sender, args)
                return true
            }
            "bpngsz" -> {
                handleBpngsz(sender, args)
                return true
            }
        }
        sendHelp(sender, label)
        return true
    }

    // ---------- 背景图片包（与 KERONGPenguin 同一清单源 / 同一 img/ 语义） ----------

    /**
     * /hbkrg bpng [包ID|stop]：无参列出图片包；带包 ID 下载（再次执行同一
     * 命令或 stop 可中断）；下载中的进度行经主线程发回命令发送者。
     */
    private fun handleBpng(sender: CommandSender, args: Array<out String>) {
        val service = BackgroundPackService
        val arg = args.drop(1).firstOrNull()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        when {
            arg.isEmpty() -> {
                if (!service.isImageDownloadEnabled()) {
                    service.imageDownloadDisabledLines().forEach { sender.sendMessage(it) }
                    return
                }
                sender.sendMessage("${ChatColor.YELLOW}正在获取图片包清单（异步）…")
                plugin.submitAsync {
                    val lines = service.listPacksLines()
                    plugin.submit { lines.forEach { sender.sendMessage(it) } }
                }
            }
            arg == "stop" -> {
                if (service.requestCancel()) {
                    sender.sendMessage("${ChatColor.YELLOW}已发送中断信号，当前下载（${service.activeDownloadBrief()}）将在本张图片结束后停止。")
                } else {
                    sender.sendMessage("${ChatColor.YELLOW}当前没有进行中的图片包下载。")
                }
            }
            else -> {
                if (!service.isImageDownloadEnabled()) {
                    service.imageDownloadDisabledLines().forEach { sender.sendMessage(it) }
                    return
                }
                if (service.hasActiveDownload()) {
                    service.requestCancel()
                    sender.sendMessage("${ChatColor.YELLOW}已发送中断信号，当前下载（${service.activeDownloadBrief()}）将在本张图片结束后停止。")
                    return
                }
                sender.sendMessage("${ChatColor.YELLOW}开始下载图片包 $arg（异步执行）…")
                plugin.submitAsync {
                    service.downloadPackById(plugin.dataFolder, arg) { line ->
                        plugin.submit { sender.sendMessage(line) }
                    }
                }
            }
        }
    }

    /** /hbkrg bpngsz yes|no：一键切换全部卡片背景（写入配置后自动重载）。 */
    private fun handleBpngsz(sender: CommandSender, args: Array<out String>) {
        val arg = args.drop(1).firstOrNull()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        when (arg) {
            "yes", "no" -> {
                val useCustom = arg == "yes"
                sender.sendMessage(
                    "${ChatColor.YELLOW}正在" +
                        (if (useCustom) "把全部卡片切换为 img/ 自定义背景" else "把全部卡片恢复为内置背景") +
                        "（写入配置并自动重载，异步执行）…",
                )
                plugin.submitAsync {
                    val lines = BackgroundPackService.switchBackgrounds(useCustom)
                    plugin.submit { lines.forEach { sender.sendMessage(it) } }
                }
            }
            else -> {
                sender.sendMessage("${ChatColor.RED}用法: /hbkrg bpngsz yes 开启 img/ 自定义背景；/hbkrg bpngsz no 恢复内置背景")
            }
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val prefix = args[0].lowercase(Locale.ROOT)
            return SUBCOMMANDS.filter { it.startsWith(prefix) }
        }
        if (args.size == 2) {
            val prefix = args[1].lowercase(Locale.ROOT)
            val suggestions = when (args[0].lowercase(Locale.ROOT)) {
                "bpng" -> listOf("stop")
                "bpngsz" -> listOf("yes", "no")
                else -> emptyList()
            }
            return suggestions.filter { it.startsWith(prefix) }
        }
        return emptyList()
    }

    /** 发送命令帮助。 */
    private fun sendHelp(sender: CommandSender, label: String) {
        sender.sendMessage("${ChatColor.GOLD}===== /$label 命令帮助（KERONGPenguin-Render 附属） =====")
        sender.sendMessage("${ChatColor.YELLOW}/$label help${ChatColor.WHITE} - 查看此帮助")
        sender.sendMessage("${ChatColor.YELLOW}/$label reload${ChatColor.WHITE} - 重载附属配置并重新应用指令接管与 QQ 指令面板")
        sender.sendMessage("${ChatColor.YELLOW}/$label info${ChatColor.WHITE} - 查看附属与 HuHoBot 版本、指令注入状态")
        sender.sendMessage("${ChatColor.YELLOW}/$label mem${ChatColor.WHITE} - 查看附属内存与缓存诊断（JVM 堆 + 各级缓存）")
        sender.sendMessage("${ChatColor.YELLOW}/$label bpng${ChatColor.WHITE} - 查看可下载的背景图片包；/$label bpng <包ID> 下载到 img/（下载中可再执行一次中断）")
        sender.sendMessage("${ChatColor.YELLOW}/$label bpngsz yes|no${ChatColor.WHITE} - 一键切换全部卡片是否使用 img/ 自定义背景图")
        sender.sendMessage("${ChatColor.GRAY}QQ 连接 / 扫码 / 面板总开关归 HuHoBot 管：/huhobot")
    }

    companion object {
        private val SUBCOMMANDS = listOf("reload", "info", "mem", "bpng", "bpngsz", "help")
    }
}
