package cn.kerong.render.stats

import cn.kerong.render.tools.Cancelable
import cn.kerong.render.tools.PluginFileLog
import cn.kerong.render.KrgShared
import cn.kerong.render.plugin
import cn.kerong.render.qqbind.BeijingTimeUtil
import cn.kerong.render.scheduler.FoliaCompat
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Statistic
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 玩家统计数据记录器（/查信息 卡片与 /在线排行榜 的数据源）——
 * 自 KERONGPenguin 1.7.4 移植，持久化层改为附属自管理。
 *
 * 设计目标：低开销、崩溃安全、按玩家 UUID 一人一条。
 * - 事件实时累计：挖掘残骸 / 击杀怪物 / 屠龙 / 击杀玩家 / 死亡 / 钓鱼 / 繁殖 / 触发袭击；
 * - 原版统计快照：行走与飞行距离、总伤害（进入时快照 + 每 60 秒增量同步 + 退出结算）；
 * - 游戏时间：会话实时累计（显示时加上当前会话未落账部分）；
 * - 持久化（附属版）：`stats/<玩家UUID>.yml` 一人一文件（player-name + stats 节），
 *   内存懒加载，脏标记 + 5 分钟周期 / 退出 / 关服保存，临时文件原子改名防半写；
 * - 名字索引：文件内记 player-name，内存双向索引（uuid→名字 / 名字→uuid）供
 *   绑定玩家名解析与排行榜枚举；支持从 KERONGPenguin 的 QUUID 主文件导入历史数据；
 * - “今日”边界用异步网络北京时间（[BeijingTimeUtil] 多时间源容错），主线程只读缓存。
 */
object PlayerStatsManager {

    /** 单个玩家的统计数据（可安全跨线程读取的快照字段）。 */
    data class PlayerStats(
        var playSeconds: Long = 0L,
        var mobKills: Long = 0L,
        var dragonKills: Long = 0L,
        var playerKills: Long = 0L,
        var deaths: Long = 0L,
        var fishCaught: Long = 0L,
        var ancientDebris: Long = 0L,
        var raidTriggers: Long = 0L,
        var animalsBred: Long = 0L,
        var walkCm: Long = 0L,
        var flyCm: Long = 0L,
        var damageDealt: Long = 0L,
    ) {
        /** 今日在线（秒，随统计持久化）。 */
        var todaySeconds: Long = 0L

        /** 今日日期（yyyy-MM-dd，跨天重置判定）。 */
        var today: String = ""
    }

    /** 已知玩家（排行榜枚举与名字解析用）。 */
    data class KnownPlayer(
        val uuid: UUID,
        val playerName: String,
    )

    /** 在线会话的运行时状态（仅主线程访问）。 */
    private class Session(
        val joinAt: Long,
        var lastWalkCm: Int = 0,
        var lastFlyCm: Int = 0,
        var lastDamage: Int = 0,
        var lastFlushAt: Long = joinAt,
    )

    private val statsByUuid = ConcurrentHashMap<UUID, PlayerStats>()
    private val sessions = ConcurrentHashMap<UUID, Session>()

    /** 名字索引（uuid → 最后一次记录的玩家名；进服 / 导入 / 首次落盘时更新）。 */
    private val nameByUuid = ConcurrentHashMap<UUID, String>()

    /** 待保存玩家（脏标记）：只重写有变动的玩家文件，未变动的零磁盘 IO。 */
    private val dirtyUuids: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var saveTask: Cancelable? = null

    @Volatile
    private var flushTask: Cancelable? = null

    /** 北京当前日期缓存（异步网络校时，主线程只读，绝不阻塞）。 */
    @Volatile
    private var beijingTodayCache: String = ""

    /** 北京日期周期刷新任务。 */
    @Volatile
    private var beijingRefreshTask: Cancelable? = null

    /** 数据目录下的统计文件目录（懒创建）。 */
    private val statsFolder: File?
        get() = KrgShared.instance?.dataFolder?.resolve("stats")?.takeIf {
            it.isDirectory || it.mkdirs()
        }

    // ---------- 生命周期 ----------

    /** 由插件启用时调用：启动定时任务。 */
    fun initialize() {
        // 立即异步网络校时一次 + 每 60 秒后台刷新北京日期缓存
        refreshBeijingTodayAsync()
        beijingRefreshTask = plugin.submitTimer(20L, 20L * 60) { refreshBeijingTodayAsync() }
        // 每 5 分钟异步保存（仅脏玩家）
        saveTask = plugin.submitTimer(20L * 60 * 5, 20L * 60 * 5) { saveToDiskAsync() }
        // 每 60 秒主线程增量同步（读取在线玩家原版统计 + 结算游戏时间）
        flushTask = plugin.submitTimer(20L * 60, 20L * 60) { flushOnlineSessions() }
    }

    /** 由插件停用时调用：结算并保存。 */
    fun shutdown() {
        beijingRefreshTask?.cancel()
        saveTask?.cancel()
        flushTask?.cancel()
        if (Bukkit.isPrimaryThread()) {
            // Folia 的 onDisable 也在全局区域线程上执行，走此快路径
            flushOnlineSessions()
            saveToDiskBlocking()
        } else {
            val bukkitPlugin = Bukkit.getPluginManager().getPlugin("KERONGPenguinRender")
            if (bukkitPlugin != null) {
                FoliaCompat.runSync(bukkitPlugin) {
                    flushOnlineSessions()
                    saveToDiskBlocking()
                }
            }
        }
    }

    // ---------- 事件累计 ----------

    /** 玩家进入：登记名字索引并懒加载统计。 */
    fun onJoin(player: Player) {
        recordName(player.uniqueId, player.name)
        val stats = loadOrCreate(player.uniqueId)
        rollTodayIfNeeded(stats)
        sessions[player.uniqueId] = Session(
            joinAt = System.currentTimeMillis(),
            lastWalkCm = safeStatistic(player, Statistic.WALK_ONE_CM),
            lastFlyCm = safeStatistic(player, Statistic.FLY_ONE_CM),
            lastDamage = safeStatistic(player, Statistic.DAMAGE_DEALT),
        )
    }

    /** 玩家退出：结算会话并异步保存（仅该玩家等脏文件）。 */
    fun onQuit(player: Player) {
        val uuid = player.uniqueId
        val session = sessions.remove(uuid) ?: return
        settleSession(player, session)
        saveToDiskAsync()
    }

    /**
     * 主线程同步一次在线玩家的增量（原版统计差量 + 游戏时间落账）。
     *
     * Folia：原版统计（getStatistic）属实体状态，必须在玩家所属区域线程
     * 读取——每个在线玩家的差量读取分别经实体调度器提交。
     */
    fun flushOnlineSessions() {
        val now = System.currentTimeMillis()
        val folia = FoliaCompat.isFolia
        val bukkitPlugin = if (folia) {
            try {
                Bukkit.getPluginManager().getPlugin("KERONGPenguinRender")
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        for ((uuid, session) in sessions) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            val stats = loadOrCreate(uuid)
            rollTodayIfNeeded(stats)
            val elapsed = (now - session.lastFlushAt).coerceAtLeast(0L) / 1000L
            if (folia && bukkitPlugin != null) {
                FoliaCompat.runOnPlayer(bukkitPlugin, player) {
                    applyStatDelta(player, session, stats)
                }
            } else {
                applyStatDelta(player, session, stats)
            }
            stats.playSeconds += elapsed
            stats.todaySeconds += elapsed
            session.lastFlushAt = now
            markDirty(uuid)
        }
    }

    /** 破坏方块（远古残骸）。 */
    fun onBlockBreak(player: Player, material: Material) {
        if (material == Material.ANCIENT_DEBRIS) {
            loadOrCreate(player.uniqueId).ancientDebris++
            markDirty(player.uniqueId)
        }
    }

    /** 实体死亡（怪物击杀 / 屠龙 / 击杀玩家）。 */
    fun onEntityDeath(killer: Player, entityType: EntityType) {
        val stats = loadOrCreate(killer.uniqueId)
        if (entityType == EntityType.PLAYER) {
            stats.playerKills++
        } else if (entityType == EntityType.ENDER_DRAGON) {
            stats.dragonKills++
        } else if (entityType.isAlive) {
            stats.mobKills++
        }
        markDirty(killer.uniqueId)
    }

    /** 玩家死亡。 */
    fun onPlayerDeath(player: Player) {
        loadOrCreate(player.uniqueId).deaths++
        markDirty(player.uniqueId)
    }

    /** 钓到鱼。 */
    fun onFishCaught(player: Player) {
        loadOrCreate(player.uniqueId).fishCaught++
        markDirty(player.uniqueId)
    }

    /** 繁殖动物。 */
    fun onAnimalBred(player: Player) {
        loadOrCreate(player.uniqueId).animalsBred++
        markDirty(player.uniqueId)
    }

    /** 触发袭击。 */
    fun onRaidTrigger(player: Player) {
        loadOrCreate(player.uniqueId).raidTriggers++
        markDirty(player.uniqueId)
    }

    // ---------- 查询 ----------

    /**
     * 查询玩家统计快照（含在线会话的实时增量）。
     * 线程安全：异步线程调用时自动切回主线程读取（Folia 上切到该玩家的
     * 实体线程——原版统计属实体状态）；该玩家尚未在内存时先懒加载他的
     * 数据文件。展示层按当前北京日期对副本做今日跨天归零。
     */
    fun query(uuid: UUID): PlayerStats {
        val stored = ensureLoaded(uuid) ?: PlayerStats()
        val session = sessions[uuid] ?: return rolledForToday(copyOf(stored))
        val bukkitPlugin = Bukkit.getPluginManager().getPlugin("KERONGPenguinRender")
            ?: return rolledForToday(copyOf(stored))
        return if (Bukkit.isPrimaryThread()) {
            queryOnMainThread(uuid, session, stored)
        } else {
            try {
                FoliaCompat.callOnPlayerByUuid(bukkitPlugin, uuid, 3L) {
                    queryOnMainThread(uuid, session, stored)
                } ?: rolledForToday(copyOf(stored))
            } catch (_: Exception) {
                rolledForToday(copyOf(stored))
            }
        }
    }

    /** 在主线程计算实时快照（不落账，仅用于展示）。 */
    private fun queryOnMainThread(uuid: UUID, session: Session, stored: PlayerStats): PlayerStats {
        val stats = statsByUuid[uuid] ?: stored
        val player = Bukkit.getPlayer(uuid) ?: return rolledForToday(copyOf(stats))
        val copy = copyOf(stats)
        val now = System.currentTimeMillis()
        val elapsed = (now - session.lastFlushAt).coerceAtLeast(0L) / 1000L
        copy.playSeconds = stats.playSeconds + elapsed
        copy.todaySeconds = if (rollDay(copy)) elapsed else stats.todaySeconds + elapsed
        copy.walkCm = stats.walkCm + (safeStatistic(player, Statistic.WALK_ONE_CM) - session.lastWalkCm).coerceAtLeast(0)
        copy.flyCm = stats.flyCm + (safeStatistic(player, Statistic.FLY_ONE_CM) - session.lastFlyCm).coerceAtLeast(0)
        copy.damageDealt = stats.damageDealt + (safeStatistic(player, Statistic.DAMAGE_DEALT) - session.lastDamage).coerceAtLeast(0)
        return copy
    }

    /**
     * 展示层跨天归零：存档日期不是今天时把副本的今日在线归零——
     * 玩家全天未上线的场景照常展示旧日期的时长会误导；只动副本不改存档。
     */
    private fun rolledForToday(copy: PlayerStats): PlayerStats {
        val today = beijingToday()
        if (copy.today != today) {
            copy.today = today
            copy.todaySeconds = 0L
        }
        return copy
    }

    // ---------- 名字解析与枚举 ----------

    /** 记录玩家名索引（进服 / 导入时调用）。 */
    fun recordName(uuid: UUID, playerName: String) {
        if (playerName.isBlank()) return
        nameByUuid[uuid] = playerName
    }

    /**
     * 按玩家名解析 UUID（绑定玩家名 → 统计记录）：
     * 在线精确匹配 → 内存名字索引 → Paper 的 getOfflinePlayerIfCached
     * （非阻塞缓存查询，反射调用；非 Paper / 未缓存返回 null）。
     */
    fun resolveUuid(playerName: String): UUID? {
        if (playerName.isBlank()) return null
        try {
            Bukkit.getPlayerExact(playerName)?.let { return it.uniqueId }
        } catch (_: Throwable) {
        }
        for ((uuid, name) in nameByUuid) {
            if (name.equals(playerName, ignoreCase = true)) return uuid
        }
        return offlineCachedUuid(playerName)
    }

    /** Paper 系的 getOfflinePlayerIfCached 反射（非阻塞；不存在或未缓存返回 null）。 */
    private fun offlineCachedUuid(playerName: String): UUID? {
        return try {
            val method = Bukkit::class.java.getMethod("getOfflinePlayerIfCached", String::class.java)
            val offline = method.invoke(null, playerName) ?: return null
            val hasPlayed = offline.javaClass.getMethod("hasPlayedBefore").invoke(offline) as? Boolean ?: false
            if (!hasPlayed) return null
            offline.javaClass.getMethod("getUniqueId").invoke(offline) as? UUID
        } catch (_: Throwable) {
            null
        }
    }

    /** 已登记的玩家名（索引中有该 UUID 时）。 */
    fun nameOf(uuid: UUID): String? = nameByUuid[uuid]

    /**
     * 存档口径的总在线秒数（排行榜全量扫描用）：内存优先 → 懒加载文件，
     * 不做在线会话实时增量（与 KERONGPenguin 直接扫文件的口径一致，
     * 在线玩家的当前会话由 60 秒周期落账补齐）。
     */
    fun storedPlaySeconds(uuid: UUID): Long = ensureLoaded(uuid)?.playSeconds ?: 0L

    /**
     * 枚举全部已知玩家（排行榜数据源）：stats/ 目录全量扫描 + 内存索引补充，
     * 返回 (uuid, playerName) 列表；统计经 [query] 另行读取。
     */
    fun listKnownPlayers(): List<KnownPlayer> {
        val result = LinkedHashMap<UUID, String>()
        // 1. 目录扫描（含从未进服但被导入 / 绑定过的历史玩家）
        val files = statsFolder?.listFiles { f -> f.isFile && f.name.endsWith(".yml") } ?: emptyArray()
        for (file in files) {
            val uuid = try {
                UUID.fromString(file.name.removeSuffix(".yml"))
            } catch (_: IllegalArgumentException) {
                continue
            }
            if (uuid !in result) {
                val name = try {
                    YamlConfiguration.loadConfiguration(file).getString("player-name") ?: ""
                } catch (_: Throwable) {
                    ""
                }
                result[uuid] = name
            }
        }
        // 2. 内存索引补充名字（文件里名字旧了以内存为准）
        for ((uuid, name) in nameByUuid) {
            result[uuid] = name
        }
        return result.mapNotNull { (uuid, name) ->
            if (name.isBlank()) null else KnownPlayer(uuid, name)
        }
    }

    // ---------- 会话结算 ----------

    /** 将会话的原版统计差量与游戏时间落账。 */
    private fun settleSession(player: Player, session: Session) {
        val stats = loadOrCreate(player.uniqueId)
        rollTodayIfNeeded(stats)
        applyStatDelta(player, session, stats)
        val elapsed = (System.currentTimeMillis() - session.lastFlushAt).coerceAtLeast(0L) / 1000L
        stats.playSeconds += elapsed
        stats.todaySeconds += elapsed
        markDirty(player.uniqueId)
    }

    /** 应用原版统计差量并更新快照。 */
    private fun applyStatDelta(player: Player, session: Session, stats: PlayerStats) {
        val walk = safeStatistic(player, Statistic.WALK_ONE_CM)
        val fly = safeStatistic(player, Statistic.FLY_ONE_CM)
        val damage = safeStatistic(player, Statistic.DAMAGE_DEALT)
        stats.walkCm += (walk - session.lastWalkCm).coerceAtLeast(0)
        stats.flyCm += (fly - session.lastFlyCm).coerceAtLeast(0)
        stats.damageDealt += (damage - session.lastDamage).coerceAtLeast(0)
        session.lastWalkCm = walk
        session.lastFlyCm = fly
        session.lastDamage = damage
    }

    // ---------- 装载 ----------

    /** 懒加载：内存没有该玩家时读他的 stats/<uuid>.yml。无文件或读取失败返回 null。 */
    private fun ensureLoaded(uuid: UUID): PlayerStats? {
        statsByUuid[uuid]?.let { return it }
        val file = statsFolder?.resolve("${uuid}.yml") ?: return null
        if (!file.isFile) return null
        return try {
            val yaml = YamlConfiguration.loadConfiguration(file)
            val name = yaml.getString("player-name")
            if (!name.isNullOrBlank()) nameByUuid.putIfAbsent(uuid, name)
            val stats = statsFromSection(yaml)
            statsByUuid.putIfAbsent(uuid, stats) ?: stats
        } catch (_: Throwable) {
            null
        }
    }

    /** 取统计数据：内存优先 → 懒加载 → 最终创建空记录。 */
    private fun loadOrCreate(uuid: UUID): PlayerStats {
        ensureLoaded(uuid)?.let { return it }
        val fresh = PlayerStats()
        return statsByUuid.putIfAbsent(uuid, fresh) ?: fresh
    }

    // ---------- 持久化 ----------

    /** 异步保存。 */
    fun saveToDiskAsync() {
        try {
            plugin.submitAsync { saveToDiskBlocking() }
        } catch (_: Throwable) {
            // 插件停用阶段忽略
        }
    }

    /**
     * 阻塞保存（异步线程或关服时调用；只写脏玩家的 stats/<uuid>.yml，
     * 临时文件 + 原子改名，半写文件不会被读到）。
     */
    @Synchronized
    fun saveToDiskBlocking() {
        try {
            if (dirtyUuids.isEmpty()) return
            val startedAt = System.currentTimeMillis()
            var saved = 0
            val iterator = dirtyUuids.iterator()
            while (iterator.hasNext()) {
                val uuid = iterator.next()
                // 先摘除再写：写入期间产生的新变动会重新标脏，下轮补写，不丢数据
                iterator.remove()
                val stats = statsByUuid[uuid] ?: continue
                if (writePlayerFile(uuid, stats)) {
                    saved++
                } else {
                    markDirty(uuid) // 写失败：保住脏标记，下轮重试
                }
            }
            PluginFileLog.write(
                "[数据保存] 玩家统计数据已保存：$saved 个玩家，耗时 ${System.currentTimeMillis() - startedAt}ms"
            )
        } catch (error: Exception) {
            PluginFileLog.errorAndKeep("[数据保存] 保存统计数据失败: ${error.message}")
        }
    }

    /** 单玩家文件原子写入（player-name + stats 节）。 */
    private fun writePlayerFile(uuid: UUID, stats: PlayerStats): Boolean {
        val folder = statsFolder ?: return false
        return try {
            val yaml = YamlConfiguration()
            nameByUuid[uuid]?.let { yaml.set("player-name", it) }
            writeStatsSection(yaml, stats)
            val target = folder.resolve("${uuid}.yml")
            val temp = folder.resolve("${uuid}.yml.part")
            yaml.save(temp)
            if (target.exists() && !target.delete()) return false
            temp.renameTo(target)
        } catch (_: Throwable) {
            false
        }
    }

    // ---------- KERONGPenguin 数据导入 ----------

    /**
     * 导入一条 KERONGPenguin 历史统计（迁移服务调用）：仅当本附属尚无
     * 该玩家记录，或已有记录的 play-seconds 小于导入值时覆盖合并
     * （取两侧各字段最大值，保证导入不丢新数据、新数据不被旧值覆盖）。
     */
    fun importRecord(playerName: String, playerUuid: UUID, imported: PlayerStats) {
        if (playerName.isBlank()) return
        recordName(playerUuid, playerName)
        val current = loadOrCreate(playerUuid)
        current.playSeconds = maxOf(current.playSeconds, imported.playSeconds)
        current.mobKills = maxOf(current.mobKills, imported.mobKills)
        current.dragonKills = maxOf(current.dragonKills, imported.dragonKills)
        current.playerKills = maxOf(current.playerKills, imported.playerKills)
        current.deaths = maxOf(current.deaths, imported.deaths)
        current.fishCaught = maxOf(current.fishCaught, imported.fishCaught)
        current.ancientDebris = maxOf(current.ancientDebris, imported.ancientDebris)
        current.raidTriggers = maxOf(current.raidTriggers, imported.raidTriggers)
        current.animalsBred = maxOf(current.animalsBred, imported.animalsBred)
        current.walkCm = maxOf(current.walkCm, imported.walkCm)
        current.flyCm = maxOf(current.flyCm, imported.flyCm)
        current.damageDealt = maxOf(current.damageDealt, imported.damageDealt)
        markDirty(playerUuid)
    }

    // ---------- stats 节读写 ----------

    /** 从统计文件的 stats 节读出统计。 */
    private fun statsFromSection(yaml: YamlConfiguration): PlayerStats = PlayerStats(
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
    ).also {
        it.todaySeconds = yaml.getLong("stats.today-seconds")
        it.today = yaml.getString("stats.today") ?: ""
    }

    /** 把统计写入 stats 节（今日在线随统计落盘，重启 / 重进不清零，跨天才重置）。 */
    private fun writeStatsSection(yaml: YamlConfiguration, stats: PlayerStats) {
        yaml.set("stats.play-seconds", stats.playSeconds)
        yaml.set("stats.mob-kills", stats.mobKills)
        yaml.set("stats.dragon-kills", stats.dragonKills)
        yaml.set("stats.player-kills", stats.playerKills)
        yaml.set("stats.deaths", stats.deaths)
        yaml.set("stats.fish-caught", stats.fishCaught)
        yaml.set("stats.ancient-debris", stats.ancientDebris)
        yaml.set("stats.raid-triggers", stats.raidTriggers)
        yaml.set("stats.animals-bred", stats.animalsBred)
        yaml.set("stats.walk-cm", stats.walkCm)
        yaml.set("stats.fly-cm", stats.flyCm)
        yaml.set("stats.damage-dealt", stats.damageDealt)
        yaml.set("stats.today-seconds", stats.todaySeconds)
        yaml.set("stats.today", stats.today)
    }

    // ---------- 工具 ----------

    private fun markDirty(uuid: UUID) {
        dirtyUuids.add(uuid)
    }

    /**
     * 北京当前日期：优先异步网络校时缓存；缓存未就绪（启动初期 / 断网）
     * 时本地 Asia/Shanghai 时钟兜底，主线程调用零网络开销、零阻塞。
     */
    private fun beijingToday(): String {
        val cached = beijingTodayCache
        if (cached.isNotEmpty()) return cached
        return SimpleDateFormat("yyyy-MM-dd")
            .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai") }
            .format(Date())
    }

    /** 后台异步刷新北京日期缓存。网络失败保留旧值（读取方本地兜底）。 */
    private fun refreshBeijingTodayAsync() {
        try {
            plugin.submitAsync {
                val date = try {
                    BeijingTimeUtil.getBeijingDate()
                } catch (_: Throwable) {
                    null
                }
                if (!date.isNullOrEmpty()) {
                    if (beijingTodayCache.isNotEmpty() && beijingTodayCache != date) {
                        PluginFileLog.write("[统计] 北京日期已跨天：$beijingTodayCache → $date")
                    }
                    beijingTodayCache = date
                }
            }
        } catch (_: Throwable) {
            // 插件停用阶段忽略
        }
    }

    /** 跨天时重置今日在线。 */
    private fun rollTodayIfNeeded(stats: PlayerStats) {
        val today = beijingToday()
        if (stats.today != today) {
            stats.today = today
            stats.todaySeconds = 0L
        }
    }

    private fun rollDay(stats: PlayerStats): Boolean {
        val today = beijingToday()
        if (stats.today != today) {
            stats.today = today
            stats.todaySeconds = 0L
            return true
        }
        return false
    }

    /** 复制一份数据（避免外部修改内存数据）。 */
    private fun copyOf(stats: PlayerStats): PlayerStats = PlayerStats(
        playSeconds = stats.playSeconds,
        mobKills = stats.mobKills,
        dragonKills = stats.dragonKills,
        playerKills = stats.playerKills,
        deaths = stats.deaths,
        fishCaught = stats.fishCaught,
        ancientDebris = stats.ancientDebris,
        raidTriggers = stats.raidTriggers,
        animalsBred = stats.animalsBred,
        walkCm = stats.walkCm,
        flyCm = stats.flyCm,
        damageDealt = stats.damageDealt,
    ).also {
        it.todaySeconds = stats.todaySeconds
        it.today = stats.today
    }

    /** 安全读取原版统计（不支持时返回 0）。 */
    private fun safeStatistic(player: Player, statistic: Statistic): Int = try {
        player.getStatistic(statistic)
    } catch (_: Throwable) {
        0
    }
}
