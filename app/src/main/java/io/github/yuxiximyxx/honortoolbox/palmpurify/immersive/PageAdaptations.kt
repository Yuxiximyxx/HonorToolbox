package io.github.yuxiximyxx.honortoolbox.palmpurify.immersive

/**
 * 按页面登记的内边距。没有写在这里的 Activity 不补 padding。
 *
 * 顶部找标题/工具栏一类的横条，补 statusBarHeight。
 * 底部用 tab_title_content 向上找到同时包住四个 tab 和中间加号的父容器，改成悬浮条。
 * 找不到就只打日志，不给整页硬垫。
 */
object PageAdaptations {
    data class Spec(
        val activityClass: String,
        val padTop: Boolean,
        val padBottom: Boolean,
    )

    val pages: List<Spec> = listOf(
        Spec(
            activityClass = "com.tencent.zone.main.zm.GameHomeActivity",
            padTop = true,
            padBottom = true,
        ),
    )

    fun specFor(className: String): Spec? = pages.find { it.activityClass == className }
}
