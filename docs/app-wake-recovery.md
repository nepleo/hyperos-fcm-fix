# 应用集中唤醒（custom.6）

## 行为与使用

主页“检查频率”下新增同样样式的“集中唤醒间隔”：关闭 / 15 分钟 / 30 分钟 / 1 小时 / 3 小时 / 6 小时。
默认关闭。不增加第二份应用名单，也不增加每个应用的唤醒开关。

复用已有 AppPolicy，只有 `appEnabled && autostartManaged && autostartEnabled` 的应用参与。
关闭应用总开关、关闭自启动管理、或设为禁止自启动，都会从后续批次中排除。
`autoUnstopEnabled` 保留原语义，只解除停止状态；它独立于集中唤醒，不会自动扩大唤醒名单。
执行前重新读取每个应用开关及用户选择；跳过工具自己。

启用/变更间隔后检查一次；开机恢复、Shizuku Binder/授权就绪事件触发检查；独立 WorkManager 周期任务兜底。
需要允许 FCM 工具开机启动、Shizuku 正常运行且已授权。开机事件仍遵循原有“开机检查”开关。

对所选 Android 用户和应用逐个查询准确 UID，确认 `get-uid-state` 为 `20 (NONEXISTENT)`
才解析该包的 launcher 并启动真实入口。已运行时跳过；未知状态不猜测；无 launcher 的包跳过。
批次逐个执行，复用现有 PrivilegedServiceClient mutex，不并发拉起所有应用。

启动可能将对应应用页面切到前台；不保证静默，不自动模拟返回键或切回桌面。
不会停止目标应用、伪造 FCM 消息或清除其数据。开启本功能后也可能重新启动用户主动停止的入选应用。
后台检查由 WorkManager 调度，休眠时可能延迟；没有增加精确闹钟、前台服务或常驻通知。

最近批次摘要显示在间隔选项下，命令及系统返回在现有日志页的 AppWake 标签中。
启动确认只表示 Android 返回 `Status: ok`，不是 Token 注册、服务器绑定、长连接或推送送达确认。
现有进程可能冻结、未完成初始化或连接失效；本版本不会凭“有进程”断言通知正常，也不会自动反复打开它。

## 实现前的可靠性审查与执行约束

正常路径：持久化间隔 → 唯一周期/事件任务 → 检查设备和 Shizuku → 查询 UID 与进程状态 →
仅在进程不存在时解析本包入口 → 启动 → 保存执行报告。应用名单直接从现有策略读取。

| 失败来源/语义 | 位置 | Worker 策略 | 重启/人工恢复 | 风险 |
|---|---|---|---|---|
| 不支持的设备 / Invalid | AppWakeWorker | 跳过 | 需要受支持的设备 | 不修改系统 |
| Binder 或授权缺失 / Unavailable | AppWakeWorker | 记录；等待 ready 事件或下一周期 | 启动/授权 Shizuku | 无依赖轮询风暴 |
| 未安装 / NotFound | wakeApp | 返回明确状态 | 安装正确包后再检查 | 不启动其他包 |
| UID、进程查询异常 / Unknown | wakeApp | 不自动启动，记录原始返回 | 日志诊断 | 不能把查询失败当进程不存在 |
| 入口解析失败 / Invalid | wakeApp | 结束本轮并记录 | 检查安装包及系统适配；无入口正常跳过 | 不使用隐式解析到其他包 |
| 系统拒绝启动 / Unavailable | wakeApp | 记录失败；不立即重试 | 由下一周期再检查 | 命令成功不等于业务成功 |
| 启动超时、Binder 响应丢失 / UnknownResult | 服务/Client | 不立即重复副作用 | 下一周期先反查进程 | 启动可能已经发生 |
| Worker 取消或进程被杀 | Worker | 传播协程取消 | WorkManager 恢复调度并重新读取设置 | 已发送启动不能撤销 |
| 持久化间隔失败 / Internal | GuardSettingsStore | commit 失败不更新 UI 状态 | 修复设备存储后重试 | 不谎报保存成功 |

副作用：保存唤醒间隔、安排唯一任务、启动目标应用、保存报告。设置与唯一任务可重复执行。
启动采用现有 PrivilegedServiceClient mutex 串行化，并在自动模式先反查进程；没有 exactly-once 保证，
外部启动成功而本地报告未保存时，下一轮从系统实际状态恢复。
没有额外多步业务状态机；持久化配置和 WorkManager 是恢复依据，日志报告不是恢复 checkpoint。
将间隔设为关闭会取消周期和排队的一次性任务，并在每个应用执行前重读开关；已经发给系统的请求仍可能完成。

主要缺口：HyperOS 可能阻止工具自身调度；cached/frozen 进程不能代表推送健康；执行期间存在进程状态竞态；
设备锁屏、不同 ROM 的 shell 命令行为、真实通知结果仍需设备验证。

分步改动：复用已有应用名单与新增间隔 → 添加有边界的 Shizuku 启动接口 → 复用主页间隔下拉框 →
测试命令输出异常及解析边界 → 编译与 lint → 实机验证留待有条件时完成。

## 源码依据

- Android `ActivityManagerShellCommand.getUidState` 输出数字及 `PROCESS_STATE_` 名称：
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActivityManagerShellCommand.java
- 复用本仓库 `SystemServiceCommandRunner`，命令有超时且不创建 shell 子进程。
- AIDL 新增 transaction 13，UserService version 19 → 20，避免 APK 升级后仍保留旧 daemon 代码。

## 待实机验证

间隔关闭时不启动；选择间隔后首次检查；重启解锁后且 Shizuku 就绪的恢复；已有进程时不抢前台；
进程不存在时启动；应用总开关/自启动管理/允许禁止的筛选；锁屏/Doze 延迟；Shizuku 死亡/重连；权限撤销；目标包未安装或无入口；
关闭间隔及应用开关时的在途请求；Lark/Telegram 真实收信结果。未完成这些检查前不能宣称修复漏消息根因。
