# Smath

Smath 是一个原生 Android（Kotlin + Jetpack Compose）对话型 AI Agent，界面参考 DeepSeek 消费端 App，只做文字对话与图片输入，把「讲题」这件事做得尽量完整：学段与风格预设、Markdown 与公式渲染、网页搜索、会话文件夹、以及本地工具调用。

最新版本 **2.0.0**：新增**复杂分支对话**（每轮问答是一个节点，可看拓扑、拖动节点、选中后从该节点新建分支或合并多条分支），图片编辑页加入画笔，截屏改为「打开开关即授权」，并内置检查更新。

## 功能

- **复杂分支对话**：新建会话时可开启。一轮问答＝一个节点，`@` 位置变成分支图标，点开是「对话拓扑」：长按看详情、拖动摆位、单击多选；选 1 个从该节点新建分支，选 2 个以上合并上游上下文（拓扑关系一并交给模型）。删除节点会连带下游并先确认，「跳转」只跳当前分支。
- **对话与渲染**：流式输出、思考过程（默认折叠，折叠时显示最后一行）、Markdown、KaTeX 公式（超宽公式可横向拖动，拖动时不会误触发页面滚动）、代码块、表格、引用；长回答自动续写，避免被长度上限截断。
- **多步工具调用**：模型输出 `[$$calc_start$$]…[$$calc_end$$]`、`[$$paint_start$$]…[$$paint_end$$]`、`[$$linalg_start$$]…[$$linalg_end$$]` 工具块后会结束本轮，应用本地执行并把真实结果回传，模型据此继续作答（最多 4 轮）。
  - 计算器：复数、常见数值函数。
  - 绘图 Agent：函数图像、直线、点、文字标注，无限画布，支持双指缩放与拖动，可编辑重绘。
  - 线性代数计算器：`det / rank / inv / rref / transpose / trace / charpoly / solve / mul / factor / expand / simplify / subst`，结果用 LaTeX 显示。设置页提供「查看工具示例效果」。
- **Agent 预设**：数学问题、英语问题、知识点整理、苏格拉底式讲题、自定义；支持新建多个预设、排序、勾选后导出/导入 ZIP。预设只在会话开始时注入一次，用来设置学段与解答风格。
  - 学段：大学 / 高中 / 初中 / 小学。
  - 风格：通俗、严谨、优美、数学符号化、解释复杂符号的含义、从更本质的方法回答、先总体思路再详细过程、图文并茂（需开启绘图 Agent）、多调用数值检验（需开启任一计算类 Agent）。
- **模型与 API Key**：预设 DeepSeek 官方与 OpenAI 官方，可添加任意 OpenAI 兼容服务商（Base URL、模型列表、Key）；支持思考强度（关闭 / 低 / 中 / 高 / 最高）。Key 仅保存在应用私有目录，不参与备份。
- **联网搜索**：默认使用 DeepSeek 官方 `/v1/tools/search`，可单独配置 Key，结果作为上下文注入并附带引用。
- **图片**：拍照或相册选图后直接进入编辑页，默认全选（不裁剪）；工具栏可切到画笔，红/白/蓝/黑四色、粗细可调、可撤销（白色适合涂掉不需要的题目）。也可附加历史对话片段作为上下文。
- **会话管理**：文件夹（手动整理 / AI 整理 / 按日期分组）、置顶、重命名、Markdown 与 ZIP 批量导出。
- **其他**：平板截屏悬浮按钮（打开开关即请求一次录屏授权，之后点悬浮球直接截图并进入裁剪）、检查更新（对比 GitHub 最新 tag，可走 gh-proxy 镜像或官方源）、错误码解释（把模型接口返回的错误用人话写在对话流里）、自动跟随输出开关。

## 安装

从 [Releases](https://github.com/335589054/Smath/releases) 下载 APK 安装即可，minSdk 26（Android 8.0），targetSdk 35。

当前版本 2.0.0：`Smath-2.0.0-release.apk`（12,948,317 字节，SHA-256 `95A21B572A8418C4DF198F76B631EFB4330CB012835F4DA76B5B65572A1151D7`），保存在仓库 `dist/` 目录；
已发布的最新 Release 仍是 1.5.0：<https://github.com/335589054/Smath/releases/download/v1.5.0/Smath-1.5.0-release.apk>

`dist/` 目录里也保存了各版本产物与 `SHA256SUMS.txt` 校验和。

## 配置

1. 首次启动进入「设置 → 模型和 API Key」，选一个服务商填入 API Key（或添加自定义服务商）。
2. 需要联网搜索时填写 DeepSeek Key（可与服务商 Key 相同）。
3. 在首页选择 Agent 预设；需要工具能力时到「特殊 Agent」开启绘图 / 计算器 / 线性代数计算器。
4. 全局提示词可在设置中查看与修改，只影响之后新建的会话。

## 构建

```powershell
$env:GRADLE_USER_HOME=".\.gradle-home"
$env:GRADLE_RO_DEP_CACHE="$env:USERPROFILE\.gradle\caches"
gradle --offline assembleRelease
```

- `local.properties` 需要指向本机 Android SDK。
- release 签名读取 `keystore.properties`（不入库），缺失时请自行生成 keystore。
- 公式与 Markdown 依赖随包内置：`app/src/main/assets/vendor/` 下的 marked、DOMPurify、KaTeX（含全部字体）。

## 目录

```
app/src/main/java/com/smath/app/
  api/        聊天与搜索接口、错误码目录
  agent/      计算器、绘图解析、符号计算、工具执行器
  data/       数据模型、持久化、预设导入导出
  export/     会话导出（Markdown / ZIP）
  screenshot/ 截屏悬浮窗与录屏服务
  ui/         聊天、设置、抽屉、裁剪、Markdown 视图
app/src/main/assets/markdown.html   Markdown + KaTeX 渲染页
docs/         宣传片脚本与各版发布说明
```

## 数据与隐私

- 会话、预设与服务商配置保存在应用私有目录的 `smath_data.json`，系统备份已关闭。
- 只有发起对话时才会把消息发送到你选择的服务商；联网搜索会先把查询词发给 DeepSeek。
- 本仓库不含任何 API Key。

## 许可

代码以 MIT 许可发布，见 [LICENSE](LICENSE)。内置第三方组件（marked、DOMPurify、KaTeX 及其字体）遵循各自许可，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。