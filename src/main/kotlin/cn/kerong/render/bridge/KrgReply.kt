package cn.kerong.render.bridge

import cn.kerong.render.KrgShared
import cn.huohuas001.bot.QClient
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.msg.MessageChain

/**
 * QQ 消息回复桥（对应 KERONGPenguin 的 QClient.replyWithImgBytes 链路）。
 *
 * 图片走 kloping SDK 的 MessageChain(base64 直传)被动回复——与
 * HuHoBot 内置背包查看指令同一发送通道，msg_id/msg_seq 由 SDK 从
 * 事件上下文带出，不占用群主动消息配额。
 *
 * 文本 / Markdown 走 HuHoBot 自己的 QClient（同样被动回复），
 * 保证与主插件行为一致。
 */
object KrgReply {

    /**
     * 回复 PNG 字节数组图片。
     *
     * 成功判定与 KERONGPenguin 1.7.4 一致：
     * - ret == null（平台无回执）/ 200（同步成功）/ 2022021x（QQ 已受理，异步下发或风控审核中）→ 成功；
     * - 读超时（请求已送达、应答未等到）视为成功，仅记 WARNING；
     * - 其余异常 / 拒收码按失败处理，调用方回退文本模板。
     */
    fun image(event: GroupMessageEvent, imageBytes: ByteArray): Boolean {
        val plugin = KrgShared.instance
        if (imageBytes.isEmpty()) {
            plugin?.log_warning("图片字节为空，无法回复图片消息")
            return false
        }
        val message = MessageChain().image(imageBytes)
        return try {
            val result = event.sendMessage(message)
            val ret = result?.ret
            when {
                ret == null || ret == 200 || (ret >= 20220210 && ret <= 20220219) -> true
                else -> {
                    plugin?.log_error("回复图片消息被 QQ 拒收（ret=$ret, msg=${result.msg ?: "无"}）")
                    false
                }
            }
        } catch (error: Exception) {
            if (isReadTimeout(error)) {
                plugin?.log_warning(
                    "回复图片消息等待 QQ 应答超时（${error.javaClass.simpleName}: ${error.message ?: "无"}）" +
                        "——请求已发出，图片大概率已投递，不再按失败处理"
                )
                true
            } else {
                plugin?.log_error("回复图片消息失败: ${error.message}")
                false
            }
        }
    }

    /** 回复纯文本（Markdown 通道，失败回退纯文本由 HuHoBot QClient 内部处理）。 */
    fun text(event: GroupMessageEvent, text: String): Boolean = try {
        QClient.replyText(event, text)
    } catch (_: Throwable) {
        false
    }

    /** 回复 Markdown（被动回复；带 @ 前缀的模板走本通道由 MessageTemplates 拼好）。 */
    fun markdown(event: GroupMessageEvent, content: String): Boolean = try {
        QClient.replyMarkdown(event, content, null)
    } catch (_: Throwable) {
        false
    }

    /** 判断异常链里是否是「读超时」（请求已发出、应答未等到）。最多回溯 3 层 cause。 */
    private fun isReadTimeout(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 3) {
            when {
                current is java.net.SocketTimeoutException -> return true
                current is java.io.InterruptedIOException &&
                    (current.message ?: "").contains("timeout", ignoreCase = true) -> return true
            }
            current = current.cause
            depth++
        }
        return false
    }
}
