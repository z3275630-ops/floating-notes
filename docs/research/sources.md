# 研究依据与证据边界

核查日期：2026-11-08。公开文档阅读；尚未完成付费账号逐项实测。功能设计属于本项目方案，不能反向当作 flomo 已实现事实。

## 17. 调研依据与查阅入口

核查日期：2026-11-08。下列资料支撑平台和工具相关事实；颜色、布局、动效与性能门槛属于本方案的设计建议。

- [S1] Android Developers — Blurring in Compose：区分组件自身模糊与背景模糊，以及平台支持限制。
  https://developer.android.com/develop/ui/compose/graphics/blur/overview
- [S2] Android Developers — Compose Performance；Baseline Profiles：release 构建、状态与性能验证。
  https://developer.android.com/develop/ui/compose/performance
  https://developer.android.com/develop/ui/compose/performance/baseline-profiles
- [S3] Android Developers — Set up window insets：输入法边距、动画与布局时机。
  https://developer.android.com/develop/ui/compose/system/insets-ui
- [S4] Android Developers — Define work requests：后台工作的延迟、周期和系统约束。
  https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- [S5] Android Developers — Schedule alarms；Android 14 exact alarm changes：提醒与精确闹钟权限。
  https://developer.android.com/develop/background-work/services/alarms
  https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- [S6] Android Developers — Migrate your Room database：破坏性迁移的数据删除风险。
  https://developer.android.com/training/data-storage/room/migrating-db-versions
- [S7] SQLite — FTS5 Extension：unicode61 与 trigram tokenizer 行为。
  https://www.sqlite.org/fts5.html
- [S8] Android Developers — Notification runtime permission：Android 13+ 通知授权。
  https://developer.android.com/develop/ui/compose/notifications/notification-permission
- [S9] Android Developers — Accessibility API defaults：48dp 触摸区域。
  https://developer.android.com/develop/ui/compose/accessibility/api-defaults
- [S10] Haze 作者发布说明 — Haze 2.1: Shatterproof Glass：Glass 稳定性、实验能力和性能取舍。
  https://chrisbanes.me/posts/haze-2.1.0/
- [S11] SQLite — Backup API：运行中数据库的一致性备份。
  https://www.sqlite.org/backup.html

### 17.1 本轮新增平台依据

- [S12] Android Developers — Glance / AppWidgets 限制。
  https://developer.android.com/develop/ui/compose/glance
  https://developer.android.com/develop/ui/compose/glance/create-app-widget
- [S13] Android Developers — 前台服务后台启动与 microphone 限制。
  https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
  https://developer.android.com/develop/background-work/services/fgs/service-types

### 17.2 flomo 官方功能资料与 Android 发布记录

这些资料用于核实能力与设计动机，不代表本 App 获得 flomo 授权，也不表示采用其私有服务或复制其内部算法。归纳保持简短，具体实施规格为本方案的设计建议。

- [F-A] 总体功能与客户端：
  https://help.flomoapp.com/basic/how-to-use.html
  https://help.flomoapp.com/basic/app.html
- [F-B] 语音输入：
  https://help.flomoapp.com/ai/aivoice.html
- [F-C] 多级标签：
  https://help.flomoapp.com/basic/tag.html
- [F-D] 每日回顾：
  https://help.flomoapp.com/advance/lucky.html
- [F-E] 随机漫步：
  https://help.flomoapp.com/advance/random-walk.html
- [F-F] 相关笔记：
  https://help.flomoapp.com/ai/xgbj.html
- [F-G] 找一找：
  https://help.flomoapp.com/ai/aifind.html
- [F-H] AI 洞察：
  https://help.flomoapp.com/ai/insight.html
- [F-I] Agent：
  https://help.flomoapp.com/ai/agent.html
- [F-J] AI 记忆档案：
  https://help.flomoapp.com/ai/memory.html
- [F-K] 认知地图：
  https://help.flomoapp.com/advance/cognitive-map.html
- [F-L] 引用与反向链接：
  https://help.flomoapp.com/advance/thread.html
- [F-M] 热力图与统计：
  https://help.flomoapp.com/advance/notes-graph.html
- [F-N] 快捷与高级搜索：
  https://help.flomoapp.com/advance/search.html
- [F-O] 历史版本：
  https://help.flomoapp.com/basic/history.html
- [F-P] MCP：官方搜索索引可读，部分直接打开路径回落到首页；基础能力由官方总览交叉确认，会员范围存在文档差异。
  https://help.flomoapp.com/advance/mcp/
- [F-Q] 官网指向的小米应用商店开发者页面，核查 Android 功能介绍及“新版特性”：
  https://app.mi.com/details?id=com.flomo.app

### 17.3 尚需实机验证的细节

未验证各安卓分发渠道的功能入口是否完全同步；未验证某账号实际会员权益；未验证漫步路径历史在 flomo 各端的保存方式；未验证官方记忆与 Agent 各层记忆的实际更新时延。本文没有把这些未知项当作确定事实。

本产品实施前仍需检查现有仓库、模型供应商、服务预算和真实导出样本。它们影响实现方案与估算，不改变本版已列明的目标功能。
