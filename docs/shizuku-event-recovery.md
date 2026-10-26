# FCM 的 Shizuku 事件驱动恢复

正常路径：Application 注册 Sticky Binder/权限/死亡监听 → Binder 到达且权限已授权 → 持久化恢复代次 → 提交一次性任务 → 工作线程执行原有保护操作 → 当前代次成功后清除等待状态。

|失败面|语义与策略|恢复点|
|---|---|---|
|开机时 Shizuku 未启动|等待依赖；结束本次 Worker，不退避轮询|Binder 就绪回调|
|未授权/用户拒绝|等待授权；只通知用户打开界面，不在后台弹权限框|权限结果回调/界面恢复检查|
|重复 Binder、权限和 onResume 回调|同一个 Binder 只提交一次；通知状态不参与去重|进程内门闩，进程重建后允许重新确认|
|旧任务运行时依赖重启|旧代次不能完成/清除新代次状态|新回调提交后续唯一任务，避免 KEEP 丢失唤醒或 REPLACE 取消在途操作|
|关闭通知|通知只负责展示；持久化 pending 不依赖通知发布成功|回调继续执行恢复|
|真正的 RPC/执行错误，依赖仍可用|有界重试最多 3 次，终止后留待周期健康检查或用户操作|先确认 Binder 和恢复代次；原保护命令可重复应用|
|任务取消|重新抛出协程取消，不作为执行失败继续重试|WorkManager 管理取消|
|应用进程被杀|监听是进程内状态；待恢复标记/任务已持久化|Shizuku 的 Provider 交付可尝试启动应用；不是无条件系统唤醒保证|
|用户/系统强停、ROM 阻止 Provider/后台启动|无法保证及时收到回调|用户打开应用；保留已有周期健康检查兜底|

副作用：提交唯一任务（按 Binder 去重，APPEND_OR_REPLACE 保留不同代次后续任务）、写保护设置（复用原实现）、FCM 主动重连（保留原设置，仅在实际恢复任务执行，不放在回调中）。旧代次在执行前及操作之间检查会话；执行中的 Binder 调用不能假定可撤销。

状态：等待 Binder → 等待权限 → 已排队/执行 → 完成，或者执行失败需人工处理。pending 与 generation 持久化；通知内容不是 checkpoint。进程在排队前被杀仍保留 pending，后续 Provider 回调/应用启动/原健康检查可重新提交；操作完成后、清除 pending 前崩溃允许重新应用幂等保护设置。

范围：只调整依赖恢复与任务调度，不修改 PowerKeeperUserService、AIDL、保护命令、界面布局、图标和用户选择的执行间隔。不增加为了等待 Shizuku 的前台服务或定时 ping。已有周期保护与健康检查保留，等待依赖时不会启动退避重试循环。

证据：当前 SDK 13.1.5 的 Shizuku.addBinderReceivedListenerSticky、addBinderDeadListener、addRequestPermissionResultListener；ShizukuService.sendBinderToUserApp 通过受权限保护的 ShizukuProvider 交付 Binder，并尝试临时电源豁免。需设备验证开机顺序、通知关闭、拒绝后授权及运行中重启 Shizuku。

验证：0.3.1-custom.4（versionCode 14），72 项单元测试通过（含 4 项 Binder 去重/并发测试），完整 lint 0 错误、19 警告，Release 构建成功。签名 SHA-256 与此前自定义包一致；APK ZIP/DEX 新恢复逻辑标记已核验。未进行真实 HyperOS 开机、锁屏、关闭通知和依赖服务重启验证。

custom.5 通知调整：正常等待 Shizuku、恢复中和成功保持静默；依赖未启动/主动停止归 Shizuku 自身处理，FCM 不重复提醒。FCM 只通知缺少授权或执行最终失败，并使用一张可操作通知卡。权限、FCM 恢复失败、周期策略失败按类型保存，某一类成功不会误清另一类失败。升级时撤销 custom.4 的旧进度卡，pending/generation 与通知显示继续解耦。周期执行的逻辑失败及三次异常尝试耗尽才提示；中途重试不提示。

custom.5 验证：77 项单元测试通过（新增 5 项通知场景策略测试）；完整 lint 无错误；Release 构建与原签名、ZIP/DEX 标记校验通过。未进行真实 HyperOS 开机、配对和通知显示测试。
