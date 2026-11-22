package com.highcapable.yukihookapi.hook.core.api.priority

/**
 * YukiHookAPI 优先级枚举的传统 Xposed API 等价物（荣耀工具箱
 * 合入 BetterHeybox 时的适配层）：仅保留原模块实际用到的
 * 三档，数值映射到 XCallback 的优先级常量。
 */
enum class YukiHookPriority(val value: Int) {
    LOWEST(-10000),
    DEFAULT(50),
    HIGHEST(10000),
}
