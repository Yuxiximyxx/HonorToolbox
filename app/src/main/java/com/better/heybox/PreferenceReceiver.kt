package com.better.heybox

/**
 * 原模块热重载广播接收器的常量替身（荣耀工具箱合入）：传统
 * Xposed API 没有热重载，设置面板里的「重载模块」仍按原样
 * 发出这条广播，但工具箱进程没有注册对应接收器，广播无人
 * 接收——实际效果以强停/重开小黑盒为准（已在回报中注明）。
 * 这里只保留原类被引用的动作常量与类名形态。
 */
object PreferenceReceiver {
    const val ACTION_RELOAD_MODULE = "com.better.heybox.RELOAD_MODULE"
}
