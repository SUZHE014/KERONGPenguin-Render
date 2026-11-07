package cn.kerong.render.stats

/**
 * 服务器性能快照（/查性能 卡片数据）——自 KERONGPenguin 1.7.4 移植，
 * 字段与采集口径保持一致；附属内由 [KrgRenderPlugin.performanceSnapshot] 组装。
 */
data class PerformanceSnapshot(
    /** 最近 1 分钟平均 TPS（满值 20；核心不支持读取时为 null）。 */
    val tps: Double?,
    /** 平均每 tick 耗时（毫秒；核心不支持读取时为 null）。 */
    val mspt: Double?,
    /** JVM 堆已用（MB）。 */
    val usedMemoryMb: Long,
    /** JVM 堆上限（MB）。 */
    val maxMemoryMb: Long,
    /** 本进程 CPU 占用（0.0 ~ 1.0；读取失败时为 null）。 */
    val cpuLoad: Double?,
    /** 当前在线玩家数（仅人数展示；读不到为 null，1.6.7.6 新增；
     *  1.6.7.8 起快照不再携带延迟数据——延迟指标整体下线）。 */
    val onlinePlayers: Int?,
    /** 服务器真实最大人数（1.6.9.3 新增；读不到为 null）。 */
    val maxPlayers: Int? = null,
) {
    companion object {
        /**
         * 进程 CPU 指数平滑值（1.6.7.6 新增，解决「数值忽高忽低」）。
         *
         * getProcessCpuLoad 是**瞬时采样**（两次查询相隔 60 秒，每次抓的是
         * 那一刻的占用，高峰 / 空闲时刻抓到天差地别的值——多线程核心下
         * 线程多、波动更大）。这里跨查询保留一份 EWMA 平滑值
         * （0.7 旧值 + 0.3 新采样，有效时间常数约 3 次查询），
         * 与 spark 的 1 分钟均值口径趋同；spark 提供的均值不经此平滑。
         */
        @Volatile
        private var smoothedCpu: Double? = null

        /**
         * 统一采集 JVM 内存与进程 CPU（双端一致），平台实现只需补
         * TPS / MSPT。
         *
         * - CPU 用进程级占用（getProcessCpuLoad，即任务管理器里本进程
         *   的 CPU 列；首次调用 JVM 可能返回 -1 表示暂不可用 → null；
         *   1.6.7.6 起无 spark 覆盖时经 EWMA 平滑，见 [smoothedCpu]）；
         * - 堆内存为 totalMemory - freeMemory（存活 + 待回收的堆占用，
         *   与面板上 JVM 已提交内存不是一回事，见 /hbkrg mem）。
         */
        @JvmStatic
        fun capture(
            tps: Double?,
            mspt: Double?,
        ): PerformanceSnapshot = capture(tps, mspt, null)

        /**
         * 1.6.7.2：CPU 可由调用方覆盖（检测到 spark 时直接用 spark 的
         * 进程 CPU 1 分钟均值，口径同为 0.0~1.0；cpuOverride 为 null 时
         * 仍走 getProcessCpuLoad 即时值）。
         */
        @JvmStatic
        fun capture(
            tps: Double?,
            mspt: Double?,
            cpuOverride: Double?,
        ): PerformanceSnapshot = capture(tps, mspt, cpuOverride, null)

        /**
         * 1.6.7.6：全参工厂——平台补齐在线人数（读不到传 null）。
         * 1.6.7.8：延迟参数移除——玩家 Ping 只反映「客户端到服务器」连接
         * 的往返，本地 / 同机连接下恒为 0ms，永远只代表服务器本地网络，
         * 对性能监控无实际意义（用户反馈），延迟指标与采集链路整体下线。
         * 1.6.9.3：新增 maxPlayers（服务器真实最大人数，读不到传 null），
         * 带默认值保持旧四参调用处字节码兼容。
         */
        @JvmStatic
        fun capture(
            tps: Double?,
            mspt: Double?,
            cpuOverride: Double?,
            onlinePlayers: Int?,
            maxPlayers: Int? = null,
        ): PerformanceSnapshot {
            // 1.6.7.7：TPS 物理上限 20 统一收敛——Minecraft 刻速率固定 20/s，
            // 任何 >20 的读数都是采集层异常（自研反射链读到的 Spigot 系
            // recentTps 无上限、启动追赶 / 空闲加速场景会算出 300+ 的瞬时值；
            // spark 个别版本同理），一律按满速 20 处理（服务器实际确实在
            // 以固定速率满速运行）
            val safeTps = tps?.coerceAtMost(20.0)
            val runtime = Runtime.getRuntime()
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
            val maxMb = runtime.maxMemory() / (1024L * 1024L)
            val cpu: Double?
            if (cpuOverride != null) {
                // spark 的 1 分钟均值本就是窗口均值，直接采用（并刷新平滑基准）
                cpu = cpuOverride
                smoothedCpu = cpuOverride
            } else {
                val instant = try {
                    val bean = java.lang.management.ManagementFactory.getPlatformMXBean(
                        com.sun.management.OperatingSystemMXBean::class.java
                    )
                    bean.processCpuLoad.takeIf { it >= 0.0 }
                } catch (_: Throwable) {
                    null
                }
                // EWMA 平滑（首次直接采用采样值）
                cpu = when {
                    instant == null -> smoothedCpu // 采样失败时沿用上次平滑值
                    else -> {
                        val next = smoothedCpu?.let { 0.7 * it + 0.3 * instant } ?: instant
                        smoothedCpu = next
                        next
                    }
                }
            }
            return PerformanceSnapshot(safeTps, mspt, usedMb, maxMb, cpu, onlinePlayers, maxPlayers)
        }

        // ---------- 核心指标分层读取（1.6.7.1：修复 TPS/MSPT 读不到） ----------

        /**
         * 从服务器对象分层读取 TPS（1 分钟平均）。
         *
         * 背景：1.6.7 只反射了 Paper 系 API 的 getTPS()，纯 Spigot
         * 核心（接口与 CraftServer 均无此方法）与 NeoForge（无 TPS
         * 字段）永远读不到。本方法按顺序尝试：
         *
         * 1. `getTPS()` 方法（Paper / Purpur / Folia 的 Server 接口，
         *    返回 double[3]：1/5/15 分钟平均）；
         * 2. `recentTps` 公有字段（Spigot / Paper 内部 MinecraftServer
         *    的 TPS 追踪数组）；
         * 3. NMS 跳板：`getServer()` 方法（CraftServer → 内部
         *    MinecraftServer，各 CraftBukkit 系核心均在）后重试 1、2；
         *
         * 全部失败返回 null（调用方可用 [tpsFromMspt] 由 MSPT 换算兜底）。
         * 全反射逐层 try，任何一层失败不影响后续层。
         */
        @JvmStatic
        fun readTps(serverObj: Any?): Double? {
            firstTps(invoke(serverObj, "getTPS"))?.let { return it }
            firstTps(readField(serverObj, "recentTps"))?.let { return it }
            val core = invoke(serverObj, "getServer")
            if (core != null && core !== serverObj) {
                firstTps(invoke(core, "getTPS"))?.let { return it }
                firstTps(readField(core, "recentTps"))?.let { return it }
            }
            return null
        }

        /**
         * 从服务器对象分层读取 MSPT（平均每 tick 耗时，毫秒）。
         *
         * 背景：1.6.7 在 NeoForge 端反射的 getAverageTickTime()/tickTimes
         * 在 1.21.1 运行时实为 getAverageTickTimeNanos()/tickTimesNanos
         *（Mojang 官方映射名），名字全错导致永远读不到。本方法按顺序尝试：
         *
         * 1. `getAverageTickTime()` 方法（Paper / Purpur 的 Server 接口，
         *    返回毫秒 double）；
         * 2. `getAverageTickTimeNanos()` 方法（1.20.5+ Mojang 映射的
         *    MinecraftServer 公有方法，NeoForge 1.21.1 走这里，纳秒 → 毫秒）；
         * 3. `tickTimesNanos` / `tickTimes` 字段（最近 100 tick 耗时数组，
         *    取平均纳秒 → 毫秒；Spigot 1.20.4 映射名为 tickTimes）；
         * 4. NMS 跳板：`getServer()` 后重试 1~3（纯 Spigot 走这里）。
         *
         * 全部失败返回 null。
         */
        @JvmStatic
        fun readMspt(serverObj: Any?): Double? {
            readMsptFrom(serverObj)?.let { return it }
            val core = invoke(serverObj, "getServer")
            if (core != null && core !== serverObj) {
                readMsptFrom(core)?.let { return it }
            }
            return null
        }

        /**
         * MSPT → TPS 换算兜底（与 Spark 等性能插件同款算法）：
         * TPS = min(20, 1000 / mspt)；mspt 无效（null 或 ≤0）返回 null。
         * NeoForge 无 TPS 追踪字段、Spigot 偶发字段名对不上时由此兜底，
         * 只要有 MSPT 就能给出 TPS。
         */
        @JvmStatic
        fun tpsFromMspt(mspt: Double?): Double? {
            return mspt?.takeIf { it > 0.0 }?.let { minOf(20.0, 1000.0 / it) }
        }

        /** MSPT 读取链（不含 NMS 跳板，见 [readMspt]）。 */
        private fun readMsptFrom(obj: Any?): Double? {
            invoke(obj, "getAverageTickTime")?.let { value ->
                (value as? Number)?.toDouble()?.takeIf { it > 0.0 }?.let { return it }
            }
            invoke(obj, "getAverageTickTimeNanos")?.let { value ->
                (value as? Number)?.toDouble()?.takeIf { it > 0.0 }?.let { return it / 1_000_000.0 }
            }
            return tickTimesAverageMillis(obj)
        }

        /** tickTimes 数组平均毫秒（映射名差异双兼容：1.20.5+ tickTimesNanos / 旧 tickTimes）。 */
        private fun tickTimesAverageMillis(obj: Any?): Double? {
            for (name in arrayOf("tickTimesNanos", "tickTimes")) {
                val array = readField(obj, name) as? LongArray ?: continue
                if (array.isEmpty()) continue
                // 数组首条可能为 0（刚启动时），取平均即可；纳秒 → 毫秒
                return array.average() / 1_000_000.0
            }
            return null
        }

        /**
         * 取 TPS 数组首元素（1 分钟平均）：兼容 double[] 与装箱 Number 数组。
         *
         * 1.6.7.7：读数统一封顶 20——Minecraft 刻速率固定 20/s，TPS 物理
         * 上限就是 20；Spigot 系（含部分新核心）的 recentTps 是对「瞬时
         * TPS = 1e9/相邻两刻间隔」的无上限指数平均，启动追赶 / 空闲加速
         * 等场景下间隔 < 50ms 会算出 300+ 的荒谬值（用户实测「查性能时
         * TPS 飙到 300+」），封顶到满速 20（此刻服务器确实在满速运行，
         * 显示值反而最接近物理事实）。
         */
        private fun firstTps(value: Any?): Double? {
            return when (value) {
                is DoubleArray -> value.firstOrNull()?.coerceAtMost(20.0)
                is Array<*> -> (value.firstOrNull() as? Number)?.toDouble()?.coerceAtMost(20.0)
                else -> null
            }
        }

        /** 调用公有方法（找不到 / 调用失败返回 null，不抛出）。 */
        private fun invoke(obj: Any?, name: String): Any? {
            if (obj == null) return null
            return try {
                obj.javaClass.methods.firstOrNull { it.name == name }?.invoke(obj)
            } catch (_: Throwable) {
                null
            }
        }

        /** 读字段（先公有 getField，失败再 getDeclaredField + 开放访问；全失败返回 null）。 */
        private fun readField(obj: Any?, name: String): Any? {
            if (obj == null) return null
            return try {
                obj.javaClass.getField(name).get(obj)
            } catch (_: Throwable) {
                try {
                    val field = obj.javaClass.getDeclaredField(name)
                    field.isAccessible = true
                    field.get(obj)
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }
}
