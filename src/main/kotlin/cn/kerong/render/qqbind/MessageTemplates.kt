package cn.kerong.render.qqbind

import cn.kerong.render.KrgShared
import cn.kerong.render.bridge.KrgReply
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * 消息模板系统——自 KERONGPenguin 1.7.4 移植（仅保留附属用到的模板）：
 * 附属在 QQ 发出的每一条文本回复对应一个 Markdown 模板文件，可在配置
 * messages 段改写「这条回复用哪个 .md 文件」。
 *
 * - 模板文件位于数据目录 Markdown/（plugins/KERONGPenguinRender/Markdown/），
 *   首次启动自动释放默认模板（不覆盖已有文件）；
 * - 占位符形如 {name} / {error}，未提供的占位符原样保留；
 * - 模板渲染出口统一应用经济别名（economy-alias，见 [EconomyName]）；
 * - 发送走 [KrgReply]（HuHoBot QClient 被动回复，与主插件一致）。
 */
object MessageTemplates {

    /** 模板目录名（位于插件数据目录下）。 */
    private const val DIRECTORY = "Markdown"

    /** 全部模板 ID（同时是默认文件名 <ID>.md 与资源路径 Markdown/<ID>.md）。 */
    val TEMPLATE_IDS = listOf(
        // —— 通用 ——
        "command-disabled",              // 命令被管理员关闭（commands 段）
        "query-too-frequent",            // 查询太频繁（图片命令冷却内）
        "server-busy",                   // 渲染队列繁忙兜底
        "image-send-failed",             // 图片发送失败
        "qq-unknown",                    // 无法识别 QQ 账号
        "manager-not-ready",             // HuHoBot 桥未就绪（简短版）
        "manager-not-ready-retry",       // 桥未就绪，请稍后再试
        "manager-not-ready-bind-retry",  // 绑定桥未就绪，请稍后再试
        // —— 查信息 ——
        "info-not-bound",                // 自己未绑定
        "info-target-not-bound",         // 被查询成员未绑定
        "info-render-failed",            // 信息卡片生成失败
        // —— 查在线 / 查性能 ——
        "online-data-unavailable",       // 服务器数据暂不可用
        "online-render-failed",          // 在线列表图片生成失败
        "performance-render-failed",     // 性能卡片图片生成失败
        // —— 在线排行榜 ——
        "leaderboard-no-data",           // 暂无在线时长数据
        "leaderboard-render-failed",     // 排行榜图片生成失败
        // —— 财富榜 ——
        "coins-leaderboard-no-economy",  // 未安装 Vault 经济插件
        "coins-leaderboard-no-data",     // 暂无金币数据
        "coins-leaderboard-render-failed", // 金币排行图片生成失败
    )

    /** 模板内容缓存：文件路径 →（修改时间戳，内容）。 */
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    /** 附属插件实例。 */
    private fun plugin() = try {
        KrgShared.instance
    } catch (_: Throwable) {
        null
    }

    /** 模板目录（数据目录下 Markdown/）。 */
    private fun directory(): File? = plugin()?.dataFolder?.resolve(DIRECTORY)

    /**
     * 从 messages 配置段读取模板 ID 对应的文件名（缺省 = <ID>.md）。
     */
    fun fileNameOf(id: String): String {
        val configured = try {
            plugin()?.config?.getConfigurationSection("messages")?.getString(id)?.trim()
        } catch (_: Throwable) {
            null
        }
        return if (configured.isNullOrEmpty()) "$id.md" else configured
    }

    /**
     * 释放全部默认模板到数据目录 Markdown/（已存在的文件不覆盖）。
     * 由插件启动时调用。
     */
    fun initialize() {
        val dir = directory() ?: run {
            plugin()?.log_warning("无法确定插件数据目录，未初始化消息模板")
            return
        }
        if (!dir.isDirectory && !dir.mkdirs()) {
            plugin()?.log_warning("无法创建消息模板目录: ${dir.path}")
            return
        }
        var released = 0
        for (id in TEMPLATE_IDS) {
            val fileName = fileNameOf(id)
            if (fileName != "$id.md") continue
            val target = dir.resolve(fileName)
            if (target.exists()) continue
            val resource = MessageTemplates::class.java.classLoader.getResourceAsStream("$DIRECTORY/$id.md") ?: continue
            try {
                resource.use { Files.copy(it, target.toPath()) }
                released++
            } catch (_: java.nio.file.FileAlreadyExistsException) {
            } catch (error: Exception) {
                plugin()?.log_warning("初始化消息模板 $fileName 失败: ${error.message}")
            }
        }
        if (released > 0) {
            plugin()?.log_info("已释放 $released 个默认消息模板到 ${dir.path}（可自行编辑，删除后重启恢复默认）")
        }
    }

    /**
     * 渲染模板：读取文件内容（mtime 缓存）→ 占位符替换 → 经济别名替换。
     * 文件缺失时回退 JAR 内置默认模板，仍缺失返回空串。
     */
    fun render(id: String, vars: Map<String, String> = emptyMap()): String {
        if (id !in TEMPLATE_IDS) return ""
        var text = loadContent(id) ?: return ""
        for ((key, value) in vars) {
            if (key.isNotEmpty()) text = text.replace("{$key}", value)
        }
        return EconomyName.apply(text)
    }

    /** 读取模板原始内容（带 mtime 缓存；文件缺失回退内置资源）。 */
    private fun loadContent(id: String): String? {
        val dir = directory() ?: return bundledResource(id)
        val file = dir.resolve(fileNameOf(id)).canonicalFile
        return try {
            if (!file.toPath().startsWith(dir.canonicalFile.toPath()) || !file.isFile) {
                return bundledResource(id)
            }
            val mtime = file.lastModified()
            val cached = cache[file.path]
            if (cached != null && cached.first == mtime) return cached.second
            val content = file.readText(Charsets.UTF_8)
            cache[file.path] = mtime to content
            content
        } catch (_: Throwable) {
            bundledResource(id)
        }
    }

    /** 读取 JAR 内置默认模板。 */
    private fun bundledResource(id: String): String? = try {
        MessageTemplates::class.java.classLoader.getResourceAsStream("$DIRECTORY/$id.md")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: Throwable) {
        null
    }

    /**
     * 带 @ 提及的模板回复：Markdown 优先、失败回退纯文本；渲染结果为空串时
     * 直接跳过（模板被清空的场景）。
     */
    fun sendDirect(event: GroupMessageEvent, id: String, vars: Map<String, String> = emptyMap()) {
        val message = render(id, vars)
        if (message.isEmpty()) return
        try {
            val userId = try {
                val contact = event.sender
                contact?.openid ?: contact?.id
            } catch (_: Throwable) {
                null
            }
            val content = if (!userId.isNullOrEmpty()) "<@$userId>\n$message" else message
            val sent = try {
                KrgReply.markdown(event, content)
            } catch (_: Throwable) {
                false
            }
            if (!sent) {
                try {
                    event.sendMessage(content)
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            try {
                event.sendMessage(message)
            } catch (_: Throwable) {
            }
        }
    }

    /** 不带 @ 提及的纯文本模板回复。 */
    fun sendPlain(event: GroupMessageEvent, id: String, vars: Map<String, String> = emptyMap()) {
        val message = render(id, vars)
        if (message.isEmpty()) return
        try {
            event.sendMessage(message)
        } catch (_: Throwable) {
        }
    }

    /** 清空缓存（/hbkrg reload 后重新读文件）。 */
    fun clearCache() = cache.clear()
}
