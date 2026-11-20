# 荣耀工具箱（HonorToolbox）

为荣耀 MagicOS 做的 LSPosed 功能合集模块：把原先分散的几个小模块合成一个，
设置按**使用场景**分组（手势与唤醒 / 通知中心 / 桌面 / 耳机），全套 MIUIX 风格，
每个功能可以单独开关，关掉后对应系统行为与没装模块完全一致。

- 包名：`io.github.yuxiximyxx.honortoolbox`
- Xposed 底座：传统 Xposed API，单一入口按宿主包名分发，功能未启用不装任何 hook
- 配置通道：统一落 `/sdcard/Download/HonorToolbox/`，每功能一份，设置侧写后回读校验、hook 侧现读

## 第一阶段（v1.0.0）功能

| 分组 | 功能 | 宿主 |
|---|---|---|
| 手势与唤醒 | 快服务手势改向（底角斜滑改开自选应用） | com.hihonor.servicecenter |
| 手势与唤醒 | 长按导航条唤醒（改开自选应用；含单击导航条返回） | com.android.systemui 等 |
| 通知中心 | 通知中心主题（锁屏主题时钟渲染、运营商隐藏等） | com.android.systemui |
| 桌面 | 图标日夜切换（主题图标随深浅色切换版本） | com.hihonor.android.launcher |

第二阶段将合入：资源库增强（桌面组）、OPPO Enco Free4 与索尼 WH-1000XM4 的
设备中心增强（耳机组）。

## 安装注意

装本模块后，请在 LSPosed 里**停用或卸载旧的同名模块**，避免双重 hook；
作用域只勾本模块。旧模块的配置不会自动迁移，需在工具箱里重新设置。

## 来源

本模块由作者本人的几个独立模块合并而来：HonorShadeClock（通知中心主题）、
QuickGesture（快服务手势改向）、NavWake（长按导航条唤醒）、NightIcon（图标日夜切换）；
第二阶段将并入 MagicPods、MagicXM4、LibraryPlus。原仓库保持不动。
