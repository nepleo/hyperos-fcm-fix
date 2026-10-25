# FCM 自定义版交付说明

## 最新版本：0.3.1-custom.6（versionCode 16）

交付日期：2026-10-25。新增通过 Shizuku 集中唤醒应用：

- 直接复用现有应用总开关及“自启动管理 → 允许”配置，不新增应用名单。
- 在原检查频率下提供同样样式的“集中唤醒间隔”：关闭、15 分钟、30 分钟、1 小时、3 小时、6 小时，默认关闭。
- 配置间隔后、开机恢复及 Shizuku 就绪后检查，并由独立周期任务兜底。
- 每批逐个查询所选用户的应用进程；确认没有进程时才启动实际 launcher，已有进程跳过。
- 无入口的系统包跳过；关闭应用开关后，后续批次不再启动该应用。
- 最近执行摘要显示在主页，详细结果记录在现有日志页。

启动可能将应用页面带到前台；休眠可能延迟调度；启动成功不代表推送已恢复。
没有设备环境，尚未进行真实 HyperOS 重启、锁屏、Lark/Telegram 推送验证。

构建、测试和签名信息见 `artifacts/release-info.json`。已签名安装包随本次对话交付。
实现及失败处理说明见 [app-wake-recovery.md](docs/app-wake-recovery.md)。

## 历史交付：custom.5


交付日期：2026-10-25。版本：`0.3.1-custom.5`，versionCode：`15`。

[下载已签名 APK](artifacts/FCM-0.3.1-custom.5-quiet-release.apk) · [校验值](artifacts/SHA256SUMS) · [版本信息](artifacts/release-info.json)

## 此次提交包含的全部定制

- 应用名称 FCM；保留小尺寸单蓝色 G、白底图标，替换桌面、圆形、自适应、单色及应用信息使用的图标入口。
- 隐藏最近任务卡片开关，默认关闭。
- 周期执行选项保留关闭、15 分钟、30 分钟、1 小时、3 小时、6 小时。
- 开机检查与 Shizuku 依赖恢复。Sticky Binder 就绪、权限结果和 Binder 死亡监听放在应用级，事件回调不执行实际保护重活。
- 同一 Binder 会话去重；恢复状态与通知权限分离；旧连接任务不能清除新连接的恢复状态。
- Shizuku 未运行时结束本次等待任务，等待就绪事件，不因依赖缺失启动退避轮询。实际操作失败最多尝试 3 次。
- 正常等待、恢复中和成功不通知；只在需要 Shizuku 授权或保护操作最终失败时提醒。Shizuku 自身的启动故障由 Shizuku 提醒，FCM 不重复报。
- 升级后撤销旧版遗留的进度通知，保留待恢复状态。成功按告警类型清理通知，避免误清其他操作的失败提醒。

原有保护命令、`PowerKeeperUserService` 和 AIDL 保持不变；原有周期保护与健康检查继续作为兜底。没有增加为了等待 Shizuku 而运行的 FCM 前台服务。

## 使用与边界

1. 本应用面向符合现有兼容检查的小米 HyperOS、机主用户设备，需要系统提供 `com.miui.system` 共享库。
2. 先让 Shizuku 配对并正常启动，再在 FCM 中授予 Shizuku 权限；已授权时不需每次重新申请。
3. 根据需要打开开机检查，并在系统中允许自启动；允许 FCM 通知才能看见授权和最终失败提醒。关闭通知不阻断事件恢复逻辑。

Binder 监听是进程内事件机制，不能保证在应用被系统强停或 ROM 拦截启动后仍然收到事件。Shizuku 通过 Provider 交付 Binder 可尝试拉起应用，仍需实际设备验证。周期任务和恢复不保证休眠时实时执行；FCM 服务连接正常也不能单独证明 Lark 已收到具体消息。

## 验证与构建

- 77 项单元测试通过，包括 Binder 去重/并发与通知场景测试。
- 完整 lint 无错误，Release 构建成功。
- 已校验 APK ZIP、签名和通知策略 DEX 标记；未进行真实 HyperOS 开机、锁屏及通知显示验证。

环境：JDK 21、Android SDK/Build Tools 37。配置本机 SDK 后执行：

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease :app:lintDebug
```

构建输出的 Release APK 需要使用自己的原签名密钥签名后才能继续覆盖安装。仓库只包含本次已签名成品，不包含私钥、密码、SDK 路径和构建缓存；证书指纹记录在 `artifacts/release-info.json`。

事件恢复及失败处理说明见 [shizuku-event-recovery.md](docs/shizuku-event-recovery.md)。
