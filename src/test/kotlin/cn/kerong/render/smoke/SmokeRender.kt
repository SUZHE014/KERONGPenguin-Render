package cn.kerong.render.smoke

import cn.kerong.render.render.InfoCardAssets
import cn.kerong.render.render.InfoCardRenderer
import cn.kerong.render.render.LeaderboardRenderer
import cn.kerong.render.render.OnlineListRenderer
import cn.kerong.render.render.PerformanceRenderer
import cn.kerong.render.stats.PerformanceSnapshot
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO

/**
 * 独立冒烟测试：脱离 Bukkit / HuHoBot 运行时，直接渲染五类卡片并做
 * 结构校验（PNG 有效、尺寸符合预期、非空白），输出到 build/smoke/。
 *
 * 运行：./gradlew smokeRender
 */
object SmokeRender {

    private var passed = 0
    private var failed = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File("build/smoke").apply { mkdirs() }
        val tmpData = File("build/smoke/data").apply { mkdirs() }

        // 1. 查信息卡片（9 项统计，内置背景，无头像 → 占位块）
        runCase("info-card") {
            val items = listOf(
                InfoCardRenderer.CardItem("总在线时长", "3天 4小时 25分钟"),
                InfoCardRenderer.CardItem("今日在线", "1小时 12分钟"),
                InfoCardRenderer.CardItem("怪物击杀", "1,532"),
                InfoCardRenderer.CardItem("屠龙次数", "6"),
                InfoCardRenderer.CardItem("玩家击杀", "12"),
                InfoCardRenderer.CardItem("死亡次数", "88"),
                InfoCardRenderer.CardItem("钓鱼", "214"),
                InfoCardRenderer.CardItem("远古残骸", "37"),
                InfoCardRenderer.CardItem("累计签到", "128 次"),
            )
            val height = InfoCardRenderer.cardHeight(items.size)
            val background = InfoCardAssets.resolveBackground(
                tmpData, InfoCardRenderer.WIDTH, height, true,
                InfoCardAssets.BUILTIN_BACKGROUND_CARD,
            )
            val png = InfoCardRenderer.render(
                InfoCardRenderer.CardData(
                    playerName = "Steve_Test",
                    items = items,
                    avatar = null,
                    background = background,
                    serverName = "冒烟测试服务器",
                    updateTime = "12:34",
                )
            )
            check("查信息尺寸 900x$height", png, InfoCardRenderer.WIDTH, height)
        }

        // 2. 查在线（130 人 → 3 页，每页独立抽背景 provider）
        runCase("online-list") {
            val entries = (1..130).map { i ->
                OnlineListRenderer.OnlineEntry(name = "Player_%03d".format(i), avatar = null)
            }
            var providerCalls = 0
            val data = OnlineListRenderer.OnlineListData(
                serverName = "冒烟测试服务器",
                updateTime = "12:34",
                entries = entries,
                backgroundProvider = { height ->
                    providerCalls++
                    InfoCardAssets.resolveBackground(
                        tmpData, OnlineListRenderer.WIDTH, height, true,
                        InfoCardAssets.BUILTIN_BACKGROUND_CARD,
                    )
                },
            )
            val pages = OnlineListRenderer.renderPages(data)
            assert("查在线分页 = 3（130 人 / 每页 60）", pages.size == 3)
            assert("背景 provider 恰好调用 3 次（每页独立抽图）", providerCalls == 3)
            check("查在线第1页宽 1350", pages[0], OnlineListRenderer.WIDTH, OnlineListRenderer.measurePageHeight(60, true))
        }

        // 3. 在线排行榜（25 行 → 2 页）
        runCase("leaderboard") {
            val entries = (1..25).map { i ->
                LeaderboardRenderer.LeaderEntry(
                    rank = i,
                    name = "Ranker_%02d".format(i),
                    playSeconds = (25 - i) * 36000L + i * 60L,
                    avatar = null,
                )
            }
            val h1 = LeaderboardRenderer.measurePageHeight(20, true)
            val h2 = LeaderboardRenderer.measurePageHeight(5, false)
            val bg1 = InfoCardAssets.resolveBackground(
                tmpData, LeaderboardRenderer.WIDTH, h1, true, InfoCardAssets.BUILTIN_BACKGROUND_LEADERBOARD,
            )
            val bg2 = InfoCardAssets.resolveBackground(
                tmpData, LeaderboardRenderer.WIDTH, h2, true, InfoCardAssets.BUILTIN_BACKGROUND_LEADERBOARD,
            )
            val page1 = LeaderboardRenderer.renderPage(
                LeaderboardRenderer.LeaderboardData(
                    serverName = "冒烟测试服务器", updateTime = "12:34",
                    entries = entries.take(20), totalPlayers = 25, topLimit = 25,
                    background = bg1,
                ),
                firstPage = true,
            )
            val page2 = LeaderboardRenderer.renderPage(
                LeaderboardRenderer.LeaderboardData(
                    serverName = "冒烟测试服务器", updateTime = "12:34",
                    entries = entries.drop(20), totalPlayers = 25, topLimit = 25,
                    background = bg2,
                ),
                firstPage = false,
            )
            check("排行榜首页 1200x$h1", page1, LeaderboardRenderer.WIDTH, h1)
            check("排行榜续页 1200x$h2", page2, LeaderboardRenderer.WIDTH, h2)
        }

        // 4. 财富榜（15 行单页，valueText 金额）
        runCase("coins-leaderboard") {
            val entries = (1..15).map { i ->
                LeaderboardRenderer.LeaderEntry(
                    rank = i,
                    name = "Rich_%02d".format(i),
                    playSeconds = 0L,
                    avatar = null,
                    valueText = "%,d".format((16 - i) * 12500),
                )
            }
            val h = LeaderboardRenderer.measurePageHeight(15, true)
            val bg = InfoCardAssets.resolveBackground(
                tmpData, LeaderboardRenderer.WIDTH, h, true, InfoCardAssets.BUILTIN_BACKGROUND_LEADERBOARD,
            )
            val png = LeaderboardRenderer.renderPage(
                LeaderboardRenderer.LeaderboardData(
                    serverName = "冒烟测试服务器", updateTime = "12:34",
                    entries = entries, totalPlayers = 15, topLimit = 15,
                    background = bg,
                    title = "金币排行榜",
                    listHeader = "金币",
                ),
                firstPage = true,
            )
            check("财富榜 1200x$h", png, LeaderboardRenderer.WIDTH, h)
        }

        // 5. 查性能（固定单页，五指标卡）
        runCase("performance") {
            val h = PerformanceRenderer.measureHeight()
            val bg = InfoCardAssets.resolveBackground(
                tmpData, 1350, h, true, InfoCardAssets.BUILTIN_BACKGROUND_CARD,
            )
            val png = PerformanceRenderer.render(
                PerformanceRenderer.PerformanceData(
                    serverName = "冒烟测试服务器",
                    updateTime = "12:34",
                    snapshot = PerformanceSnapshot.capture(
                        tps = 19.86, mspt = 12.4, cpuOverride = 0.23,
                        onlinePlayers = 42, maxPlayers = 100,
                    ),
                    background = bg,
                )
            )
            check("查性能 1350x$h", png, PerformanceRenderer.WIDTH, h)
        }

        println()
        println("===== 冒烟结果: $passed 通过 / $failed 失败 =====")
        if (failed > 0) kotlin.system.exitProcess(1)
    }

    private fun runCase(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            failed++
            println("[FAIL] $name: ${t.message}")
            t.printStackTrace()
        }
    }

    private fun assert(desc: String, condition: Boolean) {
        if (condition) {
            passed++
            println("[PASS] $desc")
        } else {
            failed++
            println("[FAIL] $desc")
        }
    }

    /** 校验 PNG 有效性 / 尺寸 / 非空白，并落盘供人工复核。 */
    private fun check(desc: String, png: ByteArray, expectW: Int, expectH: Int) {
        val file = File("build/smoke", desc.substringBefore(' ') + ".png")
        file.writeBytes(png)
        val image = ImageIO.read(file)
        var ok = image != null && image.width == expectW && image.height == expectH
        if (ok) {
            // 非空白校验：统计非纯白像素占比（渲染出的卡片必然大量非白）
            var nonWhite = 0
            var total = 0
            val step = 17
            for (y in 0 until image.height step step) {
                for (x in 0 until image.width step step) {
                    total++
                    val rgb = image.getRGB(x, y)
                    if (rgb and 0xFFFFFF != 0xFFFFFF) nonWhite++
                }
            }
            ok = nonWhite * 100 > total * 60
            if (!ok) println("      （非白像素占比 ${(nonWhite * 100.0 / total).toInt()}% < 60%，疑似空白图）")
        }
        assert("$desc (${if (ok) "有效" else "异常"}, ${file.name}, ${png.size} B)", ok)
    }
}
