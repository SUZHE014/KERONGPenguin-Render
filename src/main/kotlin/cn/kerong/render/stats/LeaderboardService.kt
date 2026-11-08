package cn.kerong.render.stats

import cn.kerong.render.KrgRenderPlugin
import cn.kerong.render.KrgShared
import cn.kerong.render.bridge.BindBridge
import cn.kerong.render.bridge.KrgReply
import cn.kerong.render.qqbind.EconomyName
import cn.kerong.render.qqbind.MessageTemplates
import cn.kerong.render.render.CardRenderPool
import cn.kerong.render.render.InfoCardAssets
import cn.kerong.render.render.LeaderboardRenderer
import cn.kerong.render.render.RenderSettings
import cn.kerong.render.scheduler.FoliaCompat
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import java.awt.image.BufferedImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 排行榜服务（/在线排行榜 + /财富榜）——自 KERONGPenguin 1.7.4 的
 * LeaderboardCommands / CoinsLeaderboardCommands 移植：
 *
 * - 数据源改为附属自管理的 PlayerStatsManager（stats/ 目录）并并入
 *   HuHoBot 绑定的玩家（财富榜玩家集）；
 * - 发送路径换 [KrgReply]（kloping MessageChain 被动回复）；
 * - 渲染层 LeaderboardRenderer 原样复用（分页 / 头像 / 背景每页独立抽取）。
 */
object LeaderboardService {

    /** 结果缓存有效期（同 KERONGPenguin：60 秒内重复查询整组复用）。 */
    private const val RESULT_TTL_MILLIS = 60_000L

    /** 相邻分页发送间隔（防 QQ 频控）。 */
    private const val SEND_INTERVAL_MILLIS = 400L

    /** 原始条目（扫描出的未排序数据）。 */
    private data class RawEntry(val name: String, val uuid: UUID?, val seconds: Long)

    /** 财富榜原始条目。 */
    private data class CoinsEntry(val name: String, val uuid: UUID?, val balance: Double)

    /** 用户冷却记录：OpenId → 上次触发时间。 */
    private val lastQueryAt = ConcurrentHashMap<String, Long>()

    /** 在线排行榜结果缓存（整组分页图片）。 */
    private var cachedPages: List<ByteArray> = emptyList()
    private var cachedAt: Long = 0L

    /** 财富榜结果缓存。 */
    private var coinsCachedPages: List<ByteArray> = emptyList()
    private var coinsCachedAt: Long = 0L

    // ---------- 在线排行榜 ----------

    fun handleOnline(plugin: KrgRenderPlugin, event: GroupMessageEvent, cooldownKey: String) {
        checkCooldown(event, cooldownKey)
        try {
            CardRenderPool.submit {
                try {
                    // 1. 全量扫描统计（含离线玩家与导入的历史数据）
                    val entries = collectOnlineEntries()
                    if (entries.isEmpty()) {
                        MessageTemplates.sendDirect(event, "leaderboard-no-data")
                        return@submit
                    }
                    val totalPlayers = entries.size.toLong()
                    val top = leaderboardTop()
                    val topEntries = entries.take(top)

                    // 2. 缓存未过期且人数未变：直接复用整组图片
                    if (cachedPages.isNotEmpty() &&
                        System.currentTimeMillis() - cachedAt < RESULT_TTL_MILLIS
                    ) {
                        sendPages(event, cachedPages)
                        return@submit
                    }

                    // 3. 主线程快照在线玩家 + 变量覆盖 + 并发预热头像
                    val onlineUuids = snapshotOnlineUuids()
                    val variableOverrides = snapshotVariableOverrides(
                        topEntries.map { it.name to it.uuid },
                        nameKey = "player-name",
                        valueKey = "total-playtime",
                    )
                    val avatars = fetchAvatars(
                        topEntries.map { it.name },
                        topEntries.map { it.uuid ?: onlineUuids[it.name] },
                    )

                    // 4. 分页渲染（第一页带标题，续页只画行内容）
                    val updateTime = SimpleDateFormat("HH:mm").format(Date())
                    val dataDirectory = plugin.dataFolder
                    val useBuiltinBackground = plugin.config.getBoolean("leaderboard.builtin-background", true)
                    val pages = ArrayList<ByteArray>(4)
                    var pageStart = 0
                    while (pageStart < topEntries.size) {
                        val pageEnd = minOf(pageStart + RenderSettings.leaderboardPageSize(), topEntries.size)
                        val pageEntries = topEntries.subList(pageStart, pageEnd).mapIndexed { offset, raw ->
                            LeaderboardRenderer.LeaderEntry(
                                rank = pageStart + offset + 1,
                                name = variableOverrides[raw.name]?.first ?: raw.name,
                                playSeconds = raw.seconds,
                                avatar = avatars[raw.name],
                                valueText = variableOverrides[raw.name]?.second,
                            )
                        }
                        val height = LeaderboardRenderer.measurePageHeight(pageEntries.size, pages.isEmpty())
                        val background = InfoCardAssets.resolveBackground(
                            dataDirectory,
                            LeaderboardRenderer.WIDTH,
                            height,
                            useBuiltinBackground,
                            InfoCardAssets.BUILTIN_BACKGROUND_LEADERBOARD,
                        )
                        val bytes = LeaderboardRenderer.renderPage(
                            LeaderboardRenderer.LeaderboardData(
                                serverName = plugin.serverName,
                                updateTime = updateTime,
                                entries = pageEntries,
                                totalPlayers = totalPlayers,
                                topLimit = top,
                                background = background,
                            ),
                            firstPage = pages.isEmpty(),
                        )
                        if (bytes.isNotEmpty()) pages.add(bytes)
                        pageStart = pageEnd
                    }
                    if (pages.isEmpty()) {
                        MessageTemplates.sendDirect(event, "leaderboard-render-failed")
                        return@submit
                    }

                    cachedPages = pages
                    cachedAt = System.currentTimeMillis()
                    sendPages(event, pages)
                } catch (error: Throwable) {
                    plugin.log_error("[在线排行榜] 渲染失败: ${error.message}")
                    plugin.log_error(
                        "[在线排行榜] 失败堆栈: " +
                            error.stackTraceToString().lineSequence().take(8).joinToString(" | ")
                    )
                    MessageTemplates.sendDirect(event, "leaderboard-render-failed")
                }
            }
        } catch (_: Throwable) {
            MessageTemplates.sendPlain(event, "server-busy")
        }
    }

    /** 全量扫描统计记录：玩家名 + UUID + 总在线秒数，按秒数降序（并列按名字）。 */
    private fun collectOnlineEntries(): List<RawEntry> {
        val entries = ArrayList<RawEntry>()
        for (known in PlayerStatsManager.listKnownPlayers()) {
            val seconds = PlayerStatsManager.storedPlaySeconds(known.uuid)
            if (seconds <= 0L) continue
            entries.add(RawEntry(known.playerName, known.uuid, seconds))
        }
        entries.sortWith(compareByDescending<RawEntry> { it.seconds }.thenBy { it.name })
        return entries
    }

    // ---------- 财富榜 ----------

    fun handleCoins(plugin: KrgRenderPlugin, event: GroupMessageEvent, cooldownKey: String) {
        checkCooldown(event, cooldownKey)
        try {
            CardRenderPool.submit {
                try {
                    // 1. Vault 经济是否可用
                    if (!economyAvailable()) {
                        MessageTemplates.sendDirect(event, "coins-leaderboard-no-economy")
                        return@submit
                    }

                    // 2. 玩家集 = 统计记录 ∪ HuHoBot 绑定玩家，逐个查询余额
                    val entries = collectCoinsEntries()
                    if (entries.isEmpty()) {
                        MessageTemplates.sendDirect(event, "coins-leaderboard-no-data")
                        return@submit
                    }
                    val totalPlayers = entries.size.toLong()
                    val top = plugin.config.getInt("coins-leaderboard.top", 15).coerceAtLeast(1)
                    val topEntries = entries.take(top)

                    // 3. 缓存未过期：直接复用整组图片
                    if (coinsCachedPages.isNotEmpty() &&
                        System.currentTimeMillis() - coinsCachedAt < RESULT_TTL_MILLIS
                    ) {
                        sendPages(event, coinsCachedPages)
                        return@submit
                    }

                    // 4. 在线快照 + 变量覆盖（金币）+ 头像预热
                    val onlineUuids = snapshotOnlineUuids()
                    val variableOverrides = snapshotVariableOverrides(
                        topEntries.map { it.name to it.uuid },
                        nameKey = "player-name",
                        valueKey = "coins",
                    )
                    val avatars = fetchAvatars(
                        topEntries.map { it.name },
                        topEntries.map { it.uuid ?: onlineUuids[it.name] },
                    )

                    // 5. 分页渲染（与在线排行榜共用渲染器与内置底图）
                    val updateTime = SimpleDateFormat("HH:mm").format(Date())
                    val dataDirectory = plugin.dataFolder
                    val useBuiltinBackground = plugin.config.getBoolean("coins-leaderboard.builtin-background", true)
                    val economyName = EconomyName.current()
                    val pages = ArrayList<ByteArray>(4)
                    var pageStart = 0
                    while (pageStart < topEntries.size) {
                        val pageEnd = minOf(pageStart + RenderSettings.leaderboardPageSize(), topEntries.size)
                        val pageEntries = topEntries.subList(pageStart, pageEnd).mapIndexed { offset, raw ->
                            LeaderboardRenderer.LeaderEntry(
                                rank = pageStart + offset + 1,
                                name = variableOverrides[raw.name]?.first ?: raw.name,
                                playSeconds = 0L,
                                avatar = avatars[raw.name],
                                valueText = variableOverrides[raw.name]?.second ?: formatMoney(raw.balance),
                            )
                        }
                        val height = LeaderboardRenderer.measurePageHeight(pageEntries.size, pages.isEmpty())
                        val background = InfoCardAssets.resolveBackground(
                            dataDirectory,
                            LeaderboardRenderer.WIDTH,
                            height,
                            useBuiltinBackground,
                            InfoCardAssets.BUILTIN_BACKGROUND_LEADERBOARD,
                        )
                        val bytes = LeaderboardRenderer.renderPage(
                            LeaderboardRenderer.LeaderboardData(
                                serverName = plugin.serverName,
                                updateTime = updateTime,
                                entries = pageEntries,
                                totalPlayers = totalPlayers,
                                topLimit = top,
                                background = background,
                                title = "${economyName}排行榜",
                                listHeader = economyName,
                            ),
                            firstPage = pages.isEmpty(),
                        )
                        if (bytes.isNotEmpty()) pages.add(bytes)
                        pageStart = pageEnd
                    }
                    if (pages.isEmpty()) {
                        MessageTemplates.sendDirect(event, "coins-leaderboard-render-failed")
                        return@submit
                    }

                    coinsCachedPages = pages
                    coinsCachedAt = System.currentTimeMillis()
                    sendPages(event, pages)
                } catch (error: Throwable) {
                    plugin.log_error("[财富榜] 渲染失败: ${error.message}")
                    plugin.log_error(
                        "[财富榜] 失败堆栈: " +
                            error.stackTraceToString().lineSequence().take(8).joinToString(" | ")
                    )
                    MessageTemplates.sendDirect(event, "coins-leaderboard-render-failed")
                }
            }
        } catch (_: Throwable) {
            MessageTemplates.sendPlain(event, "server-busy")
        }
    }

    /** 财富榜玩家集：统计记录 ∪ HuHoBot 绑定玩家；按 UUID 查 Vault 余额，余额 ≤ 0 跳过。 */
    private fun collectCoinsEntries(): List<CoinsEntry> {
        val entries = ArrayList<CoinsEntry>()
        val seen = HashSet<UUID>()
        // 1. 统计记录（含导入的历史玩家）
        for (known in PlayerStatsManager.listKnownPlayers()) {
            seen.add(known.uuid)
            val balance = queryBalance(Bukkit.getOfflinePlayer(known.uuid)) ?: continue
            if (balance <= 0.0) continue
            entries.add(CoinsEntry(known.playerName, known.uuid, balance))
        }
        // 2. HuHoBot 绑定玩家（未进统计记录的补充）
        for (name in BindBridge.allBoundPlayerNames()) {
            val uuid = PlayerStatsManager.resolveUuid(name) ?: continue
            if (!seen.add(uuid)) continue
            val balance = queryBalance(Bukkit.getOfflinePlayer(uuid)) ?: continue
            if (balance <= 0.0) continue
            entries.add(CoinsEntry(name, uuid, balance))
        }
        entries.sortWith(compareByDescending<CoinsEntry> { it.balance }.thenBy { it.name })
        return entries
    }

    // ---------- 公共工具 ----------

    /** 查询冷却（render.command-cooldown-seconds；排行榜与财富榜共用计数）。 */
    private fun checkCooldown(event: GroupMessageEvent, cooldownKey: String) {
        val cooldownMillis = RenderSettings.commandCooldownMillis()
        if (cooldownMillis <= 0) return
        val now = System.currentTimeMillis()
        val lastAt = lastQueryAt[cooldownKey] ?: 0L
        if (now - lastAt < cooldownMillis) {
            MessageTemplates.sendDirect(event, "query-too-frequent")
            return
        }
        lastQueryAt[cooldownKey] = now
        if (lastQueryAt.size > 1024) lastQueryAt.clear()
    }

    /** 在线排行榜显示条数上限（leaderboard.top，默认 15）。 */
    private fun leaderboardTop(): Int =
        (KrgShared.instance?.config?.getInt("leaderboard.top", 15) ?: 15).coerceAtLeast(1)

    /** Vault + 经济服务是否就绪。 */
    private fun economyAvailable(): Boolean {
        return try {
            val vault = Bukkit.getPluginManager().getPlugin("Vault") ?: return false
            if (!vault.isEnabled) return false
            val economyClass = Class.forName("net.milkbowl.vault.economy.Economy")
            val servicesManager = Bukkit.getServer().javaClass.getMethod("getServicesManager").invoke(Bukkit.getServer())
            val rsp = servicesManager.javaClass.getMethod("getRegistration", Class::class.java).invoke(servicesManager, economyClass) ?: return false
            val provider = Class.forName("org.bukkit.plugin.RegisteredServiceProvider").getMethod("getProvider").invoke(rsp) ?: return false
            provider != null
        } catch (_: Throwable) {
            false
        }
    }

    /** 反射调用 Vault Economy 查询玩家余额（避免编译期依赖）。 */
    private fun queryBalance(offline: OfflinePlayer): Double? {
        return try {
            val economyClass = Class.forName("net.milkbowl.vault.economy.Economy")
            val servicesManager = Bukkit.getServer().javaClass.getMethod("getServicesManager").invoke(Bukkit.getServer())
            val rsp = servicesManager.javaClass.getMethod("getRegistration", Class::class.java).invoke(servicesManager, economyClass) ?: return null
            val economy = Class.forName("org.bukkit.plugin.RegisteredServiceProvider").getMethod("getProvider").invoke(rsp) ?: return null
            val balance = economyClass.getMethod("getBalance", OfflinePlayer::class.java)
                .invoke(economy, offline) as? Number
            balance?.toDouble()
        } catch (_: Throwable) {
            null
        }
    }

    /** 金额显示（整数去掉小数点，与玩家名片卡片同风格）。 */
    private fun formatMoney(amount: Double): String =
        if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()

    /** 主线程快照在线玩家（异步调用时自动切换；超时 / 失败返回空表）。 */
    private fun snapshotOnlineUuids(): Map<String, UUID?> {
        val snapshot: () -> Map<String, UUID?> = {
            try {
                Bukkit.getOnlinePlayers().associate { player ->
                    player.name to player.uniqueId
                }
            } catch (_: Throwable) {
                emptyMap()
            }
        }
        if (Bukkit.isPrimaryThread()) return snapshot()
        val bukkitPlugin = try {
            Bukkit.getPluginManager().getPlugin("KERONGPenguinRender")
        } catch (_: Throwable) {
            null
        } ?: return emptyMap()
        return try {
            FoliaCompat.callSync(bukkitPlugin, 3L) { snapshot() } ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** 主线程快照解析 top 玩家的变量覆盖（玩家名 / 数值键）。 */
    private fun snapshotVariableOverrides(
        entries: List<Pair<String, UUID?>>,
        nameKey: String,
        valueKey: String,
    ): Map<String, Pair<String?, String?>> {
        val enabled = try {
            PlaceholderVariableService.isEnabled()
        } catch (_: Throwable) {
            false
        }
        if (!enabled) return emptyMap()
        val snapshot: () -> Map<String, Pair<String?, String?>> = {
            PlaceholderVariableService.resolveBatch(entries, nameKey = nameKey, valueKey = valueKey)
        }
        if (Bukkit.isPrimaryThread()) return snapshot()
        val bukkitPlugin = try {
            Bukkit.getPluginManager().getPlugin("KERONGPenguinRender")
        } catch (_: Throwable) {
            null
        } ?: return emptyMap()
        return try {
            FoliaCompat.callSync(bukkitPlugin, 3L) { snapshot() } ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** 并发预热头像（共享 8 线程池 + 12 秒渲染预算；超预算本轮占位块）。 */
    private fun fetchAvatars(names: List<String>, uuids: List<UUID?>): Map<String, BufferedImage> {
        if (names.isEmpty()) return emptyMap()
        return InfoCardAssets.fetchAvatarBatch(names.mapIndexed { index, name ->
            InfoCardAssets.AvatarRequest(uuid = uuids.getOrNull(index), playerName = name)
        })
    }

    /** 逐张发送分页图片（相邻间隔 400ms）；任一张失败提示重试。 */
    private fun sendPages(event: GroupMessageEvent, pages: List<ByteArray>) {
        var allSent = true
        for ((index, page) in pages.withIndex()) {
            if (index > 0) {
                try {
                    Thread.sleep(SEND_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
            }
            if (!KrgReply.image(event, page)) {
                allSent = false
                break
            }
        }
        if (!allSent) {
            MessageTemplates.sendPlain(event, "image-send-failed")
        }
    }
}
