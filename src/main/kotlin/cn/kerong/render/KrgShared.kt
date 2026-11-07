package cn.kerong.render

import cn.kerong.render.stats.PlayerStatsManager

/**
 * 全局共享单例：持有附属插件实例（对应 KERONGPenguin 的 BotShared）。
 */
object KrgShared {
    @Volatile
    var instance: KrgRenderPlugin? = null

    /** 获取当前插件实例。 */
    val plugin: KrgRenderPlugin
        get() = instance ?: error("KERONGPenguin-Render 插件实例尚未初始化")
}

/** 快捷访问当前插件实例（移植代码沿用 KERONGPenguin 的裸 plugin 习惯）。 */
val plugin: KrgRenderPlugin
    get() = KrgShared.instance
        ?: error("KERONGPenguin-Render 插件实例尚未初始化")
