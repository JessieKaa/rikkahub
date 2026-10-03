# 家人聊天模式：实施路线与验收记录

## 1. 当前状态与使用方式

- 当前状态：主实现已落地并 rebase 到上游 `a6dbb8cd`（family commit `7f6fe6da`，2.5.6/code 191）；可选 Firebase no-op 回退经 owner 批准并实现；本次验证的 ai、mediagen、app 三个模块 JVM 单测通过（80 套件 592/592）；排除 Firebase/WebUi 的 debug APK 已产出 v2.5.6/code 191，**未安装/未真机验证**；旧设备证据基于 R3 v2.5.5/code 190；正式签名发布构建与剩余能力验收待完成。
- 设计来源：[完整改造方案](family-mode-design.md)。
- 持久化入口：[根目录 AGENTS.md](../../AGENTS.md)。
- 源码检查基线：原始实现基线 `280a039c`；当前已 rebase 到上游 `a6dbb8cd`，family commit `7f6fe6da`，后续实施先核对当前源码。
- Firebase：默认 Firebase 应用存在时保持原行为；缺少默认应用时使用可选 `AnalyticsTracker`/no-op 回退，因此无需 Firebase 配置即可安全运行私有 debug 聊天 APK。正式签名发布构建/真实 google 配置仍待 owner。
- 验证状态：最新三模块 JVM（`:ai`、`:mediagen`、`:app` 的 `:ai:test :mediagen:test :app:testDebugUnitTest`，排除 `processDebugGoogleServices`、`buildWebUi`）→ 80 套件 592/592 通过；排除任务的 arm64 debug APK 已产出 v2.5.6/code 191，但未安装/未真机验证；旧 device 证据基于 R3 v2.5.5 APK（见第 8 节）。常规 Google Services 构建、lint 全量、仪器与完整验收矩阵未执行。

本路线为后续实施提供可检查的工作清单。完成一项应同时记录变更文件与验证证据；仅勾选状态不足以证明功能可用。

### 1.1 第一版交付范围

同一 APK、一个家庭助手及固定模型、隐藏管理员 PIN 入口、完整管理界面保留、家人聊天 UI、统一导航与写入限制、历史/搜索/收藏按助手限定、分享/通知/恢复入口收口，以及图片/语音/工具授权回归。

第一版家人模式关闭 Web 服务。多助手切换、远程配置、账号体系、Web 管理权限和 UI 全面重设计后续单独评估。

### 1.2 工作量估算

熟悉 Kotlin/Compose、沿用现有 UI、构建环境正常的情况下，稳定版预计约 **5～8 人日**，日历时间预留 **1～2 周**。构建环境、签名与设备兼容问题额外预留 1～2 天。

| 工作项 | 估算 |
|---|---:|
| 状态存储、PIN 和管理会话 | 0.5～1 天 |
| 导航及生命周期限制 | 1～1.5 天 |
| 聊天 UI 精简 | 1～1.5 天 |
| 固定助手与数据范围 | 0.5～1 天 |
| 外部入口、恢复和维护功能 | 0.5～1 天 |
| 回归测试与修复 | 1～2 天 |

这些数字为源码评估值。设置流错误恢复、MCP 助手上下文和异步停服属于重点风险，P0 后根据实际改动细化工作量。原型可约 1～2 人日验证界面和流程，正式交付需完成下面的验收矩阵。

## 2. 阶段与依赖

```text
D0 文档与持久化索引
 → P0 构建基线和入口清单
 → P1 本机模式与管理员会话
 → P2 导航、Intent 和恢复栈
 → P3 聊天界面与配置回调
 → P4 固定家庭助手与数据范围
 → P5 外部入口、备份、Web 和分发
 → P6 回归验证与家庭设备交付
```

| 阶段 | 当前状态 | 完成依据 |
|---|---|---|
| D0 文档与索引 | 已完成 | 两份方案/路线文档及根目录 AGENTS.md 索引；见第 8 节 |
| P0 构建基线与入口清单 | 已解决（可选回退）；发布配置待定 | 环境失败已定位并恢复；no-op Firebase 回退经 owner 批准并实现；签名发布仍待配置 |
| P1 本机模式与管理员会话 | 已实现；核心设备验证通过 | PIN 锁定/解锁/完成管理/后台重锁/force-stop 真机通过；其余待验 |
| P2 导航、Intent 和恢复栈 | 已实现；核心设备验证通过 | 返回/分享/PROCESS_TEXT/冷启动锁定真机通过；多实例/OEM 未验证 |
| P3 聊天 UI 与配置回调 | 已实现；部分设备验证通过 | 锁定隐藏设置/模型/助手/工具；仅配置快照，完整前后指纹对比未做 |
| P4 固定助手与数据范围 | 已实现；部分设备验证通过 | mock 模型/助手与分享/通知范围；真实 provider 未验证 |
| P5 特殊入口与分发 | 已实现；核心设备验证通过 | 动态快捷方式锁定/解锁、相机保留、分享导入真机通过；签名/legacy 未验证 |
| P6 回归与交付 | 部分完成（旧 APK 核心 ADB UI 通过） | rebase 后 592 JVM；新 v2.5.6 APK 未安装/未真机验证；真实 provider/ASR/TTS/MCP/工作区/Web/仪器全量/OEM 未验证 |

## 3. 分阶段执行清单

### P0：核对环境与现状

- [ ] 阅读完整方案，确认第一版范围和未实施状态。
- [ ] 检查 Git 工作区，保留已有用户修改。
- [ ] 验证现有 Debug 构建和相关 JVM 测试，区分基线问题与新功能问题。
- [ ] 记录 JDK、Android SDK、目标设备、构建命令及失败日志位置。
- [ ] 重新审查注册的全部 `Screen`、配置回调、弹层、Intent、快捷方式、通知和安全模式。
- [ ] 建立入口清单，每个入口标记“保留/收起/管理权限/范围校验”及检查位置。
- [ ] 优先核实设置流失败终止进程、MCP 全局助手枚举，以及 Web 异步起停的影响范围，细化估算。

主要文件：`RouteActivity.kt`、`NavContext.kt`、`PreferencesStore.kt`、聊天页及共享选择组件、`AndroidManifest.xml`、`shortcuts.xml`、现有测试。

完成门槛：基线结果可复现；每类入口已有处理位置。构建失败若涉及现有 SDK、签名或依赖，先记录原始失败，评估是否需要用户提供环境信息。

### P1：本机模式与管理员会话

- [ ] 新增独立本机控制存储，包含记录版本、模式、初始化标志、家庭助手 ID 和 PIN 校验信息。
- [ ] 区分新安装、Loading、Ready 和读取/解析错误；错误保持恢复锁定状态。
- [ ] 为普通设置增加专用加载/错误信号，处理当前收集失败终止进程路径；审查启动消费者。
- [ ] 恢复/PIN 界面独立于普通设置读取，区分 IO/JSON 失败与 corruption handler 重置配置。
- [ ] 新增控制器与纯策略，接入 Koin，统一提供当前能力。
- [ ] 实现 PIN 二次确认、随机盐、标准派生函数、验证及简单限速。
- [ ] 实现长按应用名称触发验证，以及完整管理界面中的完成管理动作。
- [ ] 验证后只创建内存会话；后台超时、明确退出、进程重启均锁定。
- [ ] 管理 UI 配置模式和 PIN；开启/关闭动作检查授权并确认。
- [ ] 启用前校验家庭配置，原子持久化成功后再进入家人模式。
- [ ] 为状态机、PIN、异步过期结果、启用失败与读取异常添加测试。

建议新增：`FamilyModeStore`、`FamilyModeController`、`FamilyModePolicy`、`AdminUnlockDialog`。文件组织在实施时确定。

主要既有文件：`data/datastore/PreferencesStore.kt`、`utils/CoroutineUtils.kt`、启动配置消费者、`di/DataSourceModule.kt`、`di/AppModule.kt`、`di/ViewModelModule.kt`、现有设置页与聊天顶部。调整通用 Flow 工具时需单独审查其他使用者。

完成门槛：用户能完整初始化、开启模式、PIN 解锁并退出；进程重建保持锁定；错误处理不会自动开放管理。此阶段仍为开发原型。

### P2：统一导航及生命周期

- [ ] 定义家人页面白名单；未知页面默认拒绝。
- [ ] `Navigator.navigate` 和 `clearAndNavigate` 接入最新策略。
- [ ] 冷/热启动的 `handleIntent` 复用策略，暂停未就绪的待处理 Intent。
- [ ] 对恢复栈做全栈检查，在目的页面渲染之前清理管理目标。
- [ ] 加入目的页面渲染兜底检查，覆盖模式加载和会话失效。
- [ ] 完成管理时回到合法家庭根页面，清理管理弹层和可保存状态。
- [ ] 校验对话 ID、消息定位及分享参数；无效数据采用可恢复反馈。
- [ ] 明确根页面退出、预测返回、多 Activity 实例及重复 Intent 行为。
- [ ] 增加白名单、未知页面、恢复管理栈、Intent 队列及返回行为测试。

主要文件：`RouteActivity.kt`、`ui/context/NavContext.kt`、`utils/ChatUtil.kt`，以及新增模式组件。

完成门槛：管理目标的普通导航、恢复和 Intent 路径全部受限；锁定后返回键保持家人范围；合法分享只导入一次。

### P3：精简聊天 UI 与管理写入保护

- [ ] 侧栏保留聊天检索，收起设置、助手管理、昵称/头像编辑、更新、备份和非聊天页面。
- [ ] 输入区收起模型、搜索配置和推理等级。
- [ ] 附件面板保留附件与语音，收起 MCP、工作区和扩展管理。
- [ ] 覆盖共享模型/助手组件的编辑、长按、收藏排序及直接配置写入。
- [ ] 收起对话系统提示词覆盖、注入绑定和移动到其他助手。
- [ ] 错误与空白状态收起设置跳转，提供联系管理员和重试反馈。
- [ ] 收起手动记忆管理与原始技术参数，保留工具批准/拒绝和 `ask_user`。
- [ ] 保留已绑定快捷消息插入，区分插入与绑定配置。
- [ ] 配置回调在实际执行/提交时检查最新权限，拒绝过期管理回调。
- [ ] 写入清单涵盖 MCP 自动令牌刷新和工具发现，允许这些运行时维护，保护手动授权/清除和配置修改。
- [ ] 为普通配置建立测试指纹，按字段归一化合法 MCP 运行时变化，正常聊天后验证稳定。
- [ ] 验证管理模式仍可访问全部原有配置。

主要文件：`ChatDrawer.kt`、`ChatInput.kt`、`FilesPicker.kt`、`ChatPage.kt`、`ChatVM.kt`、`ChatList.kt`、`ConversationList.kt`、`ModelList.kt`、`AssistantPicker.kt`、`UseAssistant.kt`、`ErrorCard.kt`、工具结果 UI。

完成门槛：普通点击、长按与弹层均已审查；家人配置回调被拒绝；消息保存、自动记忆和工具运行正常。

### P4：固定家庭助手与数据范围

- [ ] 家人模式依据独立 `familyAssistantId` 解析有效助手和模型。
- [ ] 新对话和预设消息使用家庭助手；旧对话与 `lastConversationId` 检查归属。
- [ ] 输入区、附件能力、语音、工具与生成服务使用一致的有效助手。
- [ ] MCP 枚举、可用性预检和审批恢复显式使用当前会话/生成的助手绑定，覆盖并发管理测试与重新锁定。
- [ ] 历史、文件夹、搜索与收藏展示限定到家庭助手。
- [ ] 收藏在显示标题/预览前过滤归属；打开动作继续校验。
- [ ] 搜索收起全部助手范围和索引维护；删除/恢复操作校验归属。
- [ ] 拥有者结束其他助手测试后，恢复正确家庭根页面。
- [ ] 家庭助手/模型缺失时进入恢复状态，PIN 验证后允许修复。
- [ ] 增加跨助手旧对话、通知、收藏/搜索及配置失效测试。

主要文件：`ChatService.kt`、`data/ai/tools/ChatToolFactory.kt`、`data/ai/mcp/McpManager.kt`、`ChatVM.kt`、`ChatDrawerVM.kt`、`HistoryVM.kt`、`SearchVM.kt`、`SearchPage.kt`、`FavoriteVM.kt`，按需调整相关 Repository/DAO 查询。

完成门槛：实际生成和 UI 的助手/模型一致；其他助手内容不会通过历史、收藏或搜索显示；标准模式保持原行为。优先保持数据库结构，若查询需调整先评估再变更。

### P5：特殊入口、恢复、备份与分发

- [ ] 分享页直接使用家庭助手，保留草稿和附件，移除助手选择。
- [ ] 家人模式禁用独立翻译/图像生成快捷方式，保留安全拍照导入。
- [ ] 通知目标检查归属及返回栈；锁定时撤销/替换其他助手内容预览，必要前台任务采用通用通知。
- [ ] 家庭对话通知收起原始工具参数等技术信息。
- [ ] 安全模式保留基础恢复，切助手/改配置需要 PIN。
- [ ] Web 仅允许在标准模式或有效管理会话按配置启动，Loading/家人锁定/恢复锁定状态均禁止。
- [ ] 重新锁定先撤销管理能力、拒绝新管理 HTTP 提交，再取消/重验待启动任务，等待引擎及服务停止完成。
- [ ] 修正停止状态与实际完成的关系，串行化重启；停止未确认时保留通用过渡提示。
- [ ] 验证旧备份开启配置、服务重启、待启动任务和停服期间请求无法恢复锁定状态下的 Web 管理。
- [ ] 普通配置备份排除本机控制记录；既有设备恢复保留 PIN 与锁定状态。
- [ ] 恢复重启后检查家庭助手引用；失效时进入恢复状态。
- [ ] 新设备导入后由拥有者设置本机 PIN、检查资源和工具权限。
- [ ] 独立包名/签名按分发需求落实，检查包名引用、Firebase、authority 和快捷方式。
- [ ] 家人模式收起上游更新入口，定制更新由拥有者维护。

主要文件：分享页及 VM、`ShortcutHandlerActivity.kt`、`SafeModeActivity.kt`、通知服务、`RikkaHubApp.kt`、`WebServerService.kt`、`WebServerManager.kt`、`web/WebApiModule.kt`、`web/routes/SettingsRoutes.kt`、备份相关文件、Manifest、快捷方式资源及 `app/build.gradle.kts`。

完成门槛：所有外部/恢复入口遵守策略；恢复不会开放管理；目标设备可安装并能保持本机锁定配置更新。

### P6：回归、交付与运行说明

- [ ] 完成第 4 节验收矩阵，逐项记录自动测试或设备证据。
- [ ] 执行目标模块构建、JVM 测试、Lint 和仪器测试，记录基线遗留问题。
- [ ] 在实际家人设备测试文本、图片、文件、语音和已启用工具。
- [ ] 拥有者验证初始化、PIN 维护、配置恢复和版本更新流程。
- [ ] 比较家人操作前后配置指纹，确认合法运行时写入继续工作。
- [ ] 检查 Git 变更未包含 PIN、签名私钥、API 密钥或家庭配置档案。
- [ ] 记录安装包位置、签名/版本信息、设备环境和已知限制，避免记录秘密值。
- [ ] 更新两份文档和根目录索引中的状态。

完成门槛：第一版必需验收项通过；阻塞问题已解决；拥有者能独立维护和重新初始化家人设备。

## 4. 验收矩阵

下面所有项目均为第一版验收范围，可由单元测试、Compose 仪器测试和真机手工测试组合证明。

| ID | 场景 | 预期结果 |
|---|---|---|
| A01 | 新安装及配置/模式加载中 | 加载完成后才决定完整初始化或家人界面 |
| A02 | 开启模式时助手/模型/PIN 无效，或保存失败 | 清楚反馈，访问状态保持一致 |
| A03 | 错误 PIN、正确 PIN、取消验证 | 错误限速；正确解锁；取消/过期结果不能解锁 |
| A04 | 完成管理后按返回、展开原管理弹层 | 回到家庭根页面，管理栈和弹层已清理 |
| A05 | 管理页面中杀进程再恢复 | 锁定；完整管理页面不会闪现 |
| A06 | 屏幕旋转/Activity 重建与真正进程重建 | 存活进程可保留会话；进程重建锁定 |
| A07 | 文件选择器/权限页短时离开与后台超时 | 短时按规则继续；超时先锁定再处理返回回调 |
| A08 | 锁定后旧异步配置回调提交 | 拒绝未提交的管理修改，界面保持一致 |
| A09 | 关闭家人模式、修改 PIN | 需要当前管理权限和明确确认 |
| N01 | 所有现有管理 Screen、未来未知 Screen | 家人模式默认拒绝，管理会话正常可用 |
| N02 | 冷/热启动分享，配置未就绪，重复 Intent | 就绪后进入家庭聊天，内容只导入一次 |
| N03 | 非法 UUID、失效附件、旧定位参数 | 可恢复反馈，保留合法根页面 |
| N04 | 家庭/其他助手通知进入；多 Activity 实例 | 合法对话正确打开；其他目标受限；锁定同步生效 |
| N05 | 长按菜单、静态快捷方式、翻译/图像生成动作 | 家人可用功能保留，管理及非聊天入口收口 |
| N06 | 崩溃安全模式 | 可重试恢复；切助手/改配置受限 |
| N07 | 管理测试任务已有通知，重新锁定时仍在生成 | 其他助手内容预览撤销/替换；通用前台通知保留任务生命周期 |
| C01 | 拥有者测试其他助手后结束管理，恢复旧聊天 | 新对话及实际生成始终使用家庭助手 |
| C02 | 历史、搜索、收藏、文件夹、批量/单项操作 | 展示和操作限定家庭助手，管理范围入口收起 |
| C03 | 正常聊天、重试、复制、附件、语音后对比配置 | 管理配置指纹保持稳定 |
| C04 | 消息保存、启动计数、自动记忆、工具结果、MCP 自动刷新/发现 | 合法运行时写入继续成功；手动绑定、连接、启用和审批配置受保护 |
| C05 | 模型/搜索/推理/MCP/扩展等配置回调直接触发 | 家人模式拒绝，管理会话允许 |
| C06 | 家庭助手删除、模型失效、IO 重试耗尽、JSON 错误、corruption 重置 | 恢复界面保持可用；独立 PIN 可用时允许受限修复；错误不会触发反复终止进程 |
| C07 | 拥有者测试、全局助手变化、锁定与 MCP 调用交错 | 工具枚举、预检和审批恢复采用对应生成的助手绑定 |
| T01 | 图片、拍照、文件及模型支持的音视频 | 附件导入、发送、预览正常 |
| T02 | ASR、TTS、语音对话及权限恢复 | 已配置能力正常；缺失配置采用通俗反馈 |
| T03 | 工具批准/拒绝、`ask_user` 回答 | 能继续生成；审批等待和恢复正常 |
| T04 | 已启用记忆、搜索、MCP、Skill、工作区产物 | 能力保持；手动管理入口收起；高影响调用遵守审批 |
| B01 | 既有家人设备导入备份并重启 | 本机 PIN/锁定状态保留；家庭引用失效进入恢复 |
| B02 | 新设备导入普通配置，选择携带/排除数据库 | 拥有者重新配置本机模式，数据范围明确 |
| W01 | 旧设置 Web 开启、后台服务启动/重启 | Loading、家人锁定和恢复锁定禁止启动；标准模式及有效管理会话按配置允许 |
| W02 | 管理会话临时运行 Web 服务后锁定 | 管理提交门禁立即生效，确认引擎/端口/服务停止后完成过渡；PIN 与 Web 身份独立 |
| W03 | 起停交错、待启动任务、停服期间的请求和服务重启 | 过期启动被取消或拒绝；新管理写入被拒绝；重启串行化，锁定后端口保持关闭 |
| R01 | 家人更新提示、安装定制更新 | 上游下载入口收起，同签名更新保持数据和锁定状态 |
| R02 | PIN 忘记/本机控制记录损坏 | 清楚恢复说明，错误不会自动开放管理 |

### 4.1 配置指纹口径

指纹覆盖提供商及模型配置、家庭助手参数、搜索服务、MCP/Skill 绑定、提示词/注入配置、语音默认配置、显示偏好等管理配置。

允许变化的运行时数据应单独记录，例如消息、标题、自动记忆、启动计数，以及现有逻辑更新的当前助手选择。当前助手选择即使变化，家人有效助手仍由本机 `familyAssistantId` 决定。

MCP OAuth 自动刷新的令牌/有效期，以及服务发现产生的工具 schema、描述和新发现条目的基线变化，按运行时字段归一化；既有服务绑定、连接配置、启用及工具审批选项继续检查。手动重新授权或清除授权仍需要管理能力。指纹规则逐字段记录，避免把整个 MCP 配置排除。

涉及真正配置迁移或明确拥有者修改的测试，应记录其预期变化，避免误判。

## 5. 验证命令与证据

建议先运行目标模块，再按影响范围扩大到仓库级验证：

```bash
git diff --check
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

`connectedDebugAndroidTest` 需要可用模拟器或真机。实际设备还需完成分享、通知、拍照/麦克风权限和后台恢复测试。

证据记录应包括：

- 命令、运行结果、报告路径和源码版本。
- 设备型号、Android 版本、安装版本及对应验收 ID。
- 截图或录屏可用于证明入口收口；先检查其中的密钥、私人聊天和个人信息。
- 失败属于基线或本次改动的判断及依据。
- 尚未验证的场景和具体阻塞信息。

## 6. 迭代与阻塞处理

每个阶段采用小范围修改：先补可测试的策略，再接入入口，最后验证保留能力。阶段结束重查涉及的导航、配置回调和运行时写入。

遇到构建环境、签名、SDK、数据迁移或需求范围问题时，记录已尝试路径、错误日志、阻塞影响和需要的用户输入。缺乏可靠验证路径时停在当前阶段，保留未完成状态。

如果新增方案涉及远程管理、数据库结构迁移、服务重构或 UI 全面重设计，先更新设计与成本范围，再实施。

## 7. 上游合并与维护检查

每次跟进上游时：

- [ ] 检查新增/修改的 Screen、Intent、通知目标和快捷方式。
- [ ] 检查聊天侧栏、输入区、附件弹层、错误提示和工具结果的新入口。
- [ ] 检查新增配置写入以及全局助手/模型解析行为。
- [ ] 检查备份、恢复和 Web 启动行为。
- [ ] 重跑模式策略与回归测试，更新入口清单和验收证据。
- [ ] 更新文档中发生变化的源码符号和设计决策。

## 8. 当前工作记录与后续记录模板

### D0：方案与路线持久化

- 状态：完成文档整理与根目录索引。
- 新增：`docs/plans/family-mode-design.md`、`docs/plans/family-mode-roadmap.md`。
- 更新：根目录 `AGENTS.md`，增加相关任务的阅读入口、第一版约束和状态维护规则。
- 范围：仅文档，应用功能代码保持原样。
- 验证：文档相对链接/当前源码路径、末尾换行/空白/代码围栏检查及 `git diff --check` 通过；变更范围为文档与索引。
- 复核：独立只读审查后补充设置读取失败恢复、Web 异步过渡、MCP 合法运行时写入/助手上下文，以及锁定后的通知展示要求。
- 尚未执行：Gradle 构建、JVM 测试、仪器测试和设备验收。
- 下一阶段：收到实施指令后从 P0 核对构建基线和入口清单开始。

### P0～P6：实现落地与验证记录（2026-10-02）

- 阶段 / 日期 / 源码版本：主实现已落地 / 2026-10-02 / 工作区基于 `280a039c`。
- 状态：P0 已解决（可选回退）；P1～P5 已实现、核心设备验证通过；P6 部分完成（编译/JVM 与核心 ADB 已验证，真实能力/仪器/发布未完成）。**未宣称 P6 完成或验收全部通过。**
- 最终验证证据（取代此前的失败/挂起报告）：
  - 单元测试（rebased 前 app-only 基线）：`:app:testDebugUnitTest -x processDebugGoogleServices -x buildWebUi` → EXIT 0；46 套件 354/354 通过、0 失败、0 跳过。日志 `/tmp/rikkahub-family-device/testDebugUnitTest-20261002-174023.log`。该计数不是最新；rebase 后 ai、mediagen、app 三模块计数见 2026-10-03 记录。早前 `342/348/349` 运行（含 `LINTFIX-test-20261002-162801.log`）已被取代，仅作历史。
  - 编译：`:app:compileDebugKotlin` 与 `:app:compileDebugUnitTestKotlin` 在同一排除下成功。
  - Lint：`:app:lintDebug -x processDebugGoogleServices -x buildWebUi` → EXIT 1；46 个基线 error、324 warning、4 hint，未新增 error/warning；1 个非阻断 `ReportShortcutUsage` hint 来自新动态快捷方式助手。**不宣称 lint 全量通过或新问题为零。** 日志 `LINTFIX-lint-20261002-162832.log`。
  - 工作区卫生：`git diff --check` 通过；49 个跟踪修改 + 34 个未跟踪，未暂存；新增/未跟踪代码与文档无行尾空白/制表符/CRLF，均以换行结尾；无 lockfile/manifest/SDK/build-config 变更；无 `google-services.json`、`local.properties`、`app.key`。
- 已实现并落地（源码核对；具体签名以源码为准）：
  - 核心：`data/familymode/`（`FamilyModeModels`、`FamilyModeStore`、`FamilyModeController`、`FamilyModePolicy`、`FamilyPinCrypto`、`EffectiveAssistantResolver`）、`di/FamilyModeModule.kt`、`ui/pages/familymode/`（管理/恢复/解锁）。
  - 接入：`RikkaHubApp.kt`、`RouteActivity.kt`、`PreferencesStore.kt`、`McpManager.kt`、`ChatToolFactory.kt`、聊天/历史/搜索/收藏/设置各 VM 与页面、`WebServerManager.kt`、`WebApiModule.kt`、`SettingsRoutes.kt`、通知与快捷方式/安全模式入口、分享路由。
  - 已核对命名与语义：记录字段实现用 `pin`（提案 `pinRecord`）；`EffectiveAssistantResolver` 在家庭助手缺失时返回 `null`（fail-closed，不回退全局助手）；`SettingsStore` 新增 `SettingsLoadState`（Loading/Ready/Error）与 `SettingsWriteKind`，收集失败不再终止进程。
- 最终审查修复（已应用）：
  - 锁定/完成管理时立即撤销管理会话与写入能力；角色归属按当前生成/会话助手绑定校验。
  - 家庭助手缺失 fail-closed；搜索/收藏按家庭助手过滤展示与打开动作。
  - 重新锁定后清理/替换其他助手通知预览，保留任务生命周期。
  - Web 可等待停服与取消：`WebServerLifecycleController` 串行化并取消待启动任务，`stopAndAwait` 完成后才切换。
  - 恢复导入后保持本机 PIN/锁定并重新校验家庭引用。
- 测试挂起经验（历史留存）：早前聚焦运行出现挂起，根因是暂停调度器上被取消的协程需显式推进（pumped async）才能完成，已修复 Web 测试夹具。旧 `testDebugUnitTest-20261002-145450/150736`、`HANG-run-20261002-160500`、`VALIDATOR-FINAL.md` 的失败/挂起记录由本次 `VALIDATOR-LINTFIX-FINAL.md` 取代，仅作历史保留。
- 并发实现与文件归属：核心/PIN/策略、导航接入、MCP 助手枚举、Web 生命周期、UI 标志与运行时数据范围由不同实现子代理按文件边界负责，主代理仅协调；UI 标志经独立只读复核并应用陈旧模型/助手选择弹层修复。
- 已知未验证缺口（未宣称通过）：真实 provider/LLM、ASR/TTS/语音、MCP、工作区、完整 Web 生命周期、pinned 旧版快捷方式（本设备无 pinned）、OEM 矩阵、正式仪器全量测试与正式签名发布构建；真实 `SettingsStore`/备份集成、系统权限流、服务通知、UI 配置指纹前后对比、SDK/OEM 行为仍待验。
- 交付与分发：包名/签名/分发方式有意保持不变，等待 owner 后续输入。
- 设计调整及理由：共享实现已存在但签名/字段可能偏离提案，设计文档已改为“以源码为准”；仅记录已核对项，不推断未验证 API。第一版原始设计范围不变；可选 UI 打磨与精修提供商资源按需后续处理。
- 下一步 / 所需输入：
  1. owner 在设备上解锁（长按标题约 5 秒，输入临时测试 PIN `<TEST_PIN>`），切换为真实 provider/模型并修改 PIN，然后完成管理。
  2. 真实 `app/google-services.json` 仅在需要 Firebase 时由 owner 提供（不要提交到 Git，也不要伪造）；私有 debug 聊天不再强制依赖它。
  3. 在真机执行剩余 P6 能力验证（真实 provider、ASR/TTS/语音、MCP、工作区、Web、pinned 快捷方式、OEM 矩阵与正式仪器测试）。
  4. 明确发布包名/签名/分发方案后再做定制更新与安装验证。

### 设备预检与 ADB 真机验证（R3，2026-10-02 17:40–17:53）

下列事实来自 `/tmp/rikkahub-family-device/EXECUTOR_REPORT_R3.md` 与同目录日志/截图，取代早前 `assembleDebug-20261002-164327.log` / `assembleDebug-nogoogle-20261002-164346.log` 的设备预检与旧失败记录。**本节设备证据基于 v2.5.5/code 190（SHA `94a4fc84...`）；rebase 后的 v2.5.6/code 191 APK 尚未安装或真机验证。**

- 已批准并实现的修复：
  - 可选 `AnalyticsTracker`/no-op：仅当缺少默认 Firebase 应用时启用；已配置 Firebase 时默认行为不变。
  - Koin 家族来源/设置来源别名 + 显式 `AppScope` 启动 DI 修复。
  - `FamilyShare` 将 `Chat.text` 以 base64 恰好导入一次。
  - 静态翻译/图像生成快捷方式移除，改为锁定：动态 `dynamic_translator` / `dynamic_image_gen` 仅在标准/管理会话发布，相机静态快捷方式保留。
- 最新构建与单元测试：
  - `:app:testDebugUnitTest`（`/tmp/rikkahub-family-device/testDebugUnitTest-20261002-174023.log`）→ 46 套件 354/354 通过、0 失败、0 跳过。
  - `:app:assembleDebug -x processDebugGoogleServices -x buildWebUi`（`assembleDebug-nogoogle-20261002-174051.log`）成功；常规 `assembleDebug` 仍需要真实（非伪造）`app/google-services.json`，但 owner 批准的回退使无 Firebase 配置的私有 debug 聊天 APK 可安全运行。
  - APK：`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`，SHA-256 `94a4fc84d2b9439b34cf3e4ebebdd364fd42efc76515103cd83b3b30c694195f`，debug 包名 `me.rerere.rikkahub.debug`，v2.5.5/code 190，同一 debug 证书。
- Lint：46 个基线 error、324 warning、4 hint；**未新增 error/warning**，1 个非阻断 `ReportShortcutUsage` hint 来自新动态快捷方式助手。**不宣称 lint 全量通过或新问题为零。**
- 真机验证（serial `<ADB_SERIAL>`，`TYH201H`，Android 9/API 28，arm64）：
  - `adb install -r` 保留数据（`firstInstallTime` 16:57:55、`lastUpdateTime` 17:45:29）；未 wipe、未重置。
  - 经 UI 仅用设置初始化；`main` + `fast` mock（`LocalMock`/`FamilyMock`），mock 流式 `MOCK_OK` 与标题/建议。
  - 真实 UI 创建 PIN `<TEST_PIN>`（测试用）；锁定隐藏设置/模型/助手/工具；错误 PIN 拒绝、正确解锁、完成管理后返回安全；force-stop 维持锁定；后台 70 秒自动重锁。
  - `ACTION_SEND` `family-test` 与 `ACTION_PROCESS_TEXT` `proctest-1` 精确解码预填、无选择器、无 FATAL；3 秒后重复相同分享未被丢弃。
  - 锁定 launcher 弹窗仅相机、无 immutable 异常；管理员会话发布 `dynamic_translator`/`dynamic_image_gen` 且意图可打开；重锁后移除。最终安装后无新 crash/ANR。
  - 证据示例：`r3_final_locked.png`、`r3_popup_after_complete.png`、`ui-r3_send_reply.xml`；完整清单见 `EXECUTOR_REPORT_R3.md`。
- 配置指纹（设备只读 `run-as` 快照，非前后对比）：`no_backup/family_mode.preferences_pb` 含 `version:1`、`familyModeEnabled:true`、`setupCompleted:true`、`familyAssistantId`、PBKDF2 记录（iterations 40000）、`backgroundLockTimeoutMs:60000`；`settings.preferences_pb` 含 `FamilyMock`/`mock-model`/`LocalMock`（`127.0.0.1:8088/v1`，`enableWebSearch:false`）。**仅快照，不宣称完整前后指纹对比。**
- 设备遗留状态：应用已安装（`-r`，数据保留）、`FAMILY_LOCKED`、pid 21841（记录时，非当前实时声明）、家庭聊天页；临时测试 PIN `<TEST_PIN>`；原始 IME 与自动旋转已恢复；自有 mock 已停止、`adb reverse tcp:8088` 已移除，保留既有 `tcp:18099`。
- 历史（已由本节取代）：早前 `342/348/349` 失败/缺少 Firebase 的构建、startup-DI 崩溃、分享崩溃与静态快捷方式失败记录不再代表当前状态；保留于旧日志。
- 安全：无真实 API key、付费 LLM 或私有用户数据。
- 敏感执行值：临时测试 PIN 与物理 ADB serial 在文档中以 `<TEST_PIN>` / `<ADB_SERIAL>` 占位符表示，实际值仅保留在 `/tmp` 私有验证报告与本地会话中，不进入 Git；家人设备交付前 owner 必须更换 PIN 并切换到真实 provider/模型。
- 未验证：真实 provider/LLM、ASR/TTS/语音、MCP、工作区、完整 Web 生命周期、pinned 旧版快捷方式（本设备无 pinned）、OEM 矩阵、正式仪器全量测试。

### 上游 rebase、三个模块验证与发布同步（2026-10-03）

- 仓库同步：原 `origin` 已重命名为 `upstream`（`git@github.com/rikkahub/rikkahub.git`）；当前 `origin` 为 `git@github.com:JessieKaa/rikkahub.git`。批准的 rebase 将 family commit `7f6fe6dabdf142808221ab88d33398db7631c64a` 落到上游 `a6dbb8cd`（2.5.6/code 191）；备份 ref `backup/family-mode-pre-rebase` @ `70d00db89498a7f655cb621ec876373107665b5a`。Rebase 仅 1 处 `AssistantVM` 冲突，解决：保留 `updateManagement` 布尔早返回于 `copyMemories` 之前；3 处自动重叠经复核 PASS；家庭范围与 23 项上游变更均保留。
- 最新三模块 JVM（`/tmp/rikkahub-family-rebase/VALIDATOR_REPORT.txt`；`:ai:test :mediagen:test :app:testDebugUnitTest`，排除 `processDebugGoogleServices`、`buildWebUi`）→ 80 套件 592/592 通过、0 失败/错误/跳过（ai 27/198、mediagen 5/28、app 48/366）。**旧 app 46/354 是 rebase 前 R3 历史基线，不是最新计数。**
- 最新 debug 组装（同排除，`assemble-20261003-175251.log`）：`app-arm64-v8a-debug.apk`，SHA-256 `1ae94287c82800d18140c92d907696ac07722f550baf1bd6beeea1b02b6c6203`，包名 `me.rerere.rikkahub.debug`，v2.5.6/code 191，minSdk 26 / targetSdk 37，debug 签名。**新 APK 未安装、未真机验证**；设备上仍为 R3 v2.5.5/code 190（SHA `94a4fc84...`）。**不宣称 2.5.6 真机验证或无新崩溃。**
- 未运行 lint（旧基线 46 errors 仍未修复）；无真实 `google-services.json`，常规 `assembleDebug` 未宣称通过。web-ui 无变更，`-x buildWebUi` 跳过；生成静态资源较 web-ui 源码新，陈旧风险低。
- `videogen/` 为生成残留，仅通过 `.git/info/exclude` 本地忽略（非源码变更、未提交）。
- 发布同步（截至本记录 2026-10-03）：本次验证前尚未执行 push；最终发布状态以 Git 远程 refs 核验为准。不宣称未来 push 成功，不填写未知的提交自身 hash/merge。

### 后续阶段记录模板

```text
阶段 / 日期 / 源码版本：
状态：未开始 / 进行中 / 已完成 / 阻塞
变更文件：
完成项：
验证命令与报告：
设备与验收 ID：
已知问题 / 尚未验证：
设计调整及理由：
下一步 / 所需输入：
```
