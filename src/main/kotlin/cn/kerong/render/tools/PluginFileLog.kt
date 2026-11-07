package cn.kerong.render.tools

import cn.kerong.render.KrgShared
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 插件文件日志（自 KERONGPenguin 1.7.4 移植，目录改为附属数据目录）：
 *   plugins/KERONGPenguinRender/logs/krg/krg-render-yyyy-MM-dd.log
 *
 * 写入为逐行追加、按需打开即关，无后台线程、无内存缓存、无锁竞争
 * （低频日志，单行写入原子性由追加模式保证）。单文件超 512KB 轮转
 * 到 <基名>-N.log。
 */
object PluginFileLog {

    /** 颜色代码（§ + 一个字符，Minecraft / Bukkit 控制台颜色）。 */
    private val COLOR_CODE = Regex("§.")

    /** 剥离 § 颜色码。 */
    fun stripColorCodes(message: String): String = COLOR_CODE.replace(message, "")

    /** 单个日志文件大小上限（超限后写入 <基名>-N.log）。 */
    const val ROTATE_THRESHOLD_BYTES: Long = 512L * 1024

    /** 解析应写入的日志文件：优先 <基名>.log；已超限时顺次找第一个不满的 -N 后缀文件。 */
    fun resolveRotated(folder: File, baseName: String): File {
        var file = File(folder, "$baseName.log")
        if (file.exists() && file.length() >= ROTATE_THRESHOLD_BYTES) {
            var index = 1
            while (true) {
                val candidate = File(folder, "$baseName-$index.log")
                if (!candidate.exists() || candidate.length() < ROTATE_THRESHOLD_BYTES) {
                    file = candidate
                    break
                }
                index++
            }
        }
        return file
    }

    /** 当前日志文件（按天分文件；超 512KB 轮转）。 */
    private fun currentFile(): File? {
        return try {
            val folder = KrgShared.instance?.dataFolder?.resolve("logs/krg") ?: return null
            if (!folder.exists() && !folder.mkdirs()) return null
            resolveRotated(folder, "krg-render-${SimpleDateFormat("yyyy-MM-dd").format(Date())}")
        } catch (_: Throwable) {
            null
        }
    }

    /** 仅写入日志文件（不进控制台）。用于数据保存等只需留档的日志。 */
    fun write(message: String) {
        try {
            val file = currentFile() ?: return
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
            val plain = COLOR_CODE.replace(message, "")
            PrintWriter(FileWriter(file, true)).use { it.println("[$timestamp] $plain") }
        } catch (_: Throwable) {
        }
    }

    /** 信息：控制台 + 日志文件双写。 */
    fun infoAndKeep(message: String) {
        val plugin = KrgShared.instance ?: return
        plugin.logger.info(message)
        write(message)
    }

    /** 警告：控制台 + 日志文件双写。 */
    fun warnAndKeep(message: String) {
        val plugin = KrgShared.instance ?: return
        plugin.logger.warning(message)
        write(message)
    }

    /** 错误：控制台 + 日志文件双写。 */
    fun errorAndKeep(message: String) {
        val plugin = KrgShared.instance ?: return
        plugin.logger.severe(message)
        write(message)
    }
}
