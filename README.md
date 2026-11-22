# Smath

Smath 是一个原生 Android（Kotlin + Jetpack Compose）的对话式 AI Agent。界面参考 DeepSeek 消费端 App，只保留文字对话与图片输入。

## 下载

最新安装包见 [Releases](https://github.com/335589054/Smath/releases/latest)（最新已发布版本 1.4.0，Android 8.0+）；每个版本的 APK 也都保存在仓库 `dist/` 目录，当前 1.4.1 直接取 `dist/Smath-1.4.1-release.apk`。

```
https://github.com/335589054/Smath/releases/download/v1.4.0/Smath-1.4.0-release.apk
SHA-256: EE93691460AD30D0EAA9ED88FB8BD53E55991464F7A7FFF46F764179757ADE2A

dist/Smath-1.4.1-release.apk
SHA-256: 240C29E1D82A70916C037AF1C8C400114B6668B5F55BEE7CF1B123C961B833DC
```

## 功能

- **模型和 API Key**：内置 DeepSeek 官方与 ChatGPT 两个服务商预设，也支持添加任意 OpenAI Chat Completions 兼容服务商。API Key 只保存在本机应用私有目录。
- **Agent 预设**：内置数学问题、英语问题、知识点整理、苏格拉底式讲题。支持新建并保存多个自定义预设，首页展示全部预设；自定义预设可重命名和删除，并可用上下箭头调整顺序。设置页支持多选导出为 ZIP、从 ZIP 导入（导入项作为新的自定义预设追加，自动处理重名）。每个预设可配置学段（大学 / 高中 / 初中 / 小学 / 不限）和可多选的回答风格。苏格拉底式讲题不会直接给答案，而是通过一次一个关键问题引导用户自己写出步骤。英语预设只提供适合英语解答的风格（通俗、严谨、优美、从本质回答、先总体思路再详细过程），不会出现数学符号化选项。
- **回答风格**：通俗、严谨、优美、数学符号化、解释复杂符号的含义、从更本质的方法回答、先总体思路再详细过程、图文并茂。「图文并茂」需要先在设置中开启绘图 Agent，未开启时该选项置灰且不会被注入。
- **只在对话开始时注入**：基础人设提示词、学段要求和回答风格会固化为该会话的 system prompt；对话开始后预设锁定，不会在后续轮次重复叠加。预设选择器只出现在新对话欢迎页。
- **@ 上下文引用**：输入框左侧的 `@` 可引用当前会话的历史消息，或另一个会话的最近几轮内容。引用会显示为可移除的 chip，发送时作为“资料”附在本轮问题前，不会改写历史记录。
- **更聪明的系统提示**：借鉴 DeepSeek Harness 的分层提示思路，在保留用户指定基础人设的同时，加入任务拆解、条件核查、推导自检、区分事实与猜测、图片信息提取等质量约束，并按学段和风格继续叠加。
- **AI 标题与长回答续写**：首轮回答完成后，用一次独立短请求生成会话标题（可手动改名覆盖）。回答因输出上限被截断时，会自动请求续写，最多两次，并在仍不完整时给出提示。
- **思考过程**：默认折叠；折叠时显示最后一行思考内容，展开后显示完整 reasoning。点击单条思考的展开/收起会同步底部按钮状态；底部按钮也可统一展开或收起全部思考。模型选择面板可调思考强度（关闭 / 低 / 中 / 高 / 最高），并按服务商协议映射为 `thinking` 与 `reasoning_effort`。
- **回答定位**：输入框上方的“看回答”可直接跳到最后一次 AI 回答的开头；“跳转”会列出当前会话的所有提问和回答，选择后定位到对应消息；向下浏览历史时“到底部”可回到最新内容。
- **导出**：右上角菜单可将当前对话导出为 Markdown，也可把全部对话打包为 ZIP。ZIP 内含每段对话的 Markdown 与图片，不包含设置或 API Key。图片导出已移除，需要静态长图时请使用系统截屏。
- **特殊 Agent**：设置中可独立开启「绘图」和「计算器」。开关只决定是否在新会话的 system prompt 中注入协议；绘图 / 计算器命令的解析始终开启，因此关闭开关不会让历史会话里已有的图和计算结果消失。绘图使用 `[$$paint_start$$]` / `[$$paint_end$$]` 标记和 `color`、`axes`、`function`、`line`、`point`、`string`、`stroke_width` 命令；画布没有边界，可自由平移和缩放，右上角准星按钮可恢复默认位置和 100% 缩放；`string` 绘制文本，`stroke_width` 设置后续线条、函数曲线和点的笔宽（不影响文本），应用会把命令块渲染成画布，并支持 `</>` 打开命令编辑器重新执行；计算器使用 `[$$calc_start$$]` / `[$$calc_end$$]` 标记，在本地计算复数表达式并显示结果。标记块不会作为普通 Markdown 显示。
- **统一的弹窗与菜单**：新建/重命名文件夹、绘图命令编辑、文件夹与会话的“三个点”菜单、删除确认、全局提示词编辑都使用应用自绘的圆角弹窗和菜单，与应用整体风格一致，不再出现系统默认样式的对话框。
- **联网搜索**：使用 DeepSeek 官方 `https://api.deepseek.com/v1/tools/search`。可复用 DeepSeek 服务商的 Key，也可以在设置中单独填写搜索 Key；搜索结果会作为不可信上下文注入，并展示引用链接。
- **图片输入与裁剪**：拍照或从相册选择后直接进入裁剪页；裁剪默认保留整张图片，支持拖动边角或内部选区，画面两侧留出安全边距避免触发系统返回手势。
- **截屏**：可在设置中开启平板用悬浮截屏按钮。按钮可拖动，单击后请求系统录屏授权，截取一帧并回到应用进入裁剪页；平板在系统支持时会尝试以小窗口启动。截屏不会保存到相册，只落在应用缓存。
- **会话文件夹**：支持手动整理、按 Windows 风格日期分组（今天 / 昨天 / 本周 / 本月 / 更早），以及 AI 整理。AI 整理只把会话标题和更新时间发送给当前模型，并要求返回 JSON 后重建文件夹。
- **错误解释**：模型调用失败时，在对话流中展示常见 HTTP / 网络错误的含义、可重试性与原始错误信息。
- **Markdown 与公式**：本地打包 marked、DOMPurify 和 KaTeX（含完整字体），支持表格、代码块、引用、链接、行内公式 `$...$` / `\(...\)` 与独立公式 `$$...$$` / `\[...\]`。过宽的独立公式保持原字号，在公式区域左右滑动查看；渲染在本地 WebView 中完成，不依赖 CDN。

## 构建

环境要求：JDK 17+、Android SDK Platform 35、Build-Tools 34+、Gradle 8.7。

```powershell
# 项目已包含 Gradle Wrapper
.\gradlew.bat assembleDebug
```

本机离线构建时，如果 Gradle 的原生缓存目录不可写，可使用：

```powershell
$env:GRADLE_USER_HOME="$PWD\.gradle-home"
$env:GRADLE_RO_DEP_CACHE="$HOME\.gradle\caches"
.\gradlew.bat --offline assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

正式版使用本地 release 签名：

```powershell
$env:GRADLE_USER_HOME="$PWD\.gradle-home"
$env:GRADLE_RO_DEP_CACHE="$HOME\.gradle\caches"
.\gradlew.bat --offline assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。签名配置从根目录 `keystore.properties` 读取，密钥库 `keystore/smath-release.jks` 不随仓库提交，请自行妥善备份；仓库中的 `.gitignore` 已排除这两个文件。

也可以用 Android Studio 直接打开项目构建。`local.properties` 中的 `sdk.dir` 需要按本机 Android SDK 路径调整。

## 配置与数据

1. 打开左上角菜单 → 设置（模型和 API Key）。
2. 为要使用的服务商填写 Key，选择服务商和模型。
3. 如需联网搜索，填写 DeepSeek 官方 Key（或单独的搜索 Key），并在输入框上方打开「联网」。
4. 如需悬浮截屏，在设置底部打开「截屏悬浮按钮」，并按系统提示授予“显示在其他应用上层”权限。首次截屏还会请求一次系统录屏授权。

截屏功能只使用 `SYSTEM_ALERT_WINDOW`、前台服务和 MediaProjection 权限；不申请存储权限，也不写入相册。

会话、文件夹、设置和 API Key 都存储在应用私有目录的 `smath_data.json`。应用已关闭 Android 自动备份，避免 Key 被写入系统备份。卸载应用会清除这些数据。

## 说明

- `release` 构建使用项目本地生成的 release 密钥签名；仓库不包含该密钥，克隆后如需发布请自行配置 `keystore.properties` 与密钥库。
- 项目按需求未编写自动化测试；Markdown/KaTeX 资源和渲染链路已做静态校验。
- 联网搜索的计费、可用性和结果质量由 DeepSeek 官方平台决定。
- AI 整理是尽力而为的 JSON 解析；若模型未按要求返回 JSON，会提示重试，不会破坏现有会话。