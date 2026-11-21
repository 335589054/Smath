# Smath 宣传片：素材、分镜与交付

> 全部画面来自真机录屏与截图，程序化合成，无 UI 示意稿。最后更新：2026-11-21。

## 一、素材与实测内容

| 文件 | 规格 | 时长 | 实测内容时间轴 |
| --- | --- | --- | --- |
| `out/footage/01-pain.png` | 1080×2400 静帧 | — | 「已到当前时段额度上限」报错态，用作痛点镜底图 |
| `out/footage/02-preset.mp4` | 1220×2656 H.264 | 15.46s | 0–2s 首页预设卡片；4–8s 预设配置表（学段 / 解答风格）；10–14s 回首页 |
| `out/footage/03-ask-thinking.mp4` | 同上 | 26.01s | 1.5s 输入「求 f(x)=x³-3x 的单调区间与极值」→ 2s 思考 → 4s 起流式作答 → 6s 后静止 |
| `out/footage/04-formula-scroll.mp4` | 同上 | 13.07s | 画面静止，变化仅在公式区横向拖动（长展开式一行写完） |
| `out/footage/05-drawing.mp4` | 同上 | 14.50s | `y=sin(x)/x` 画布拖动平移 |
| `out/footage/06-calculator.mp4` | 同上 | 12.28s | `(3+4i)/(1-2i)` 的模与辐角卡片 |
| `out/footage/07-settings.mp4` | 同上 | 14.05s | 0–2s 会话列表（文件夹 + 日期分组）；2–14s 设置页滚动（模型和 API Key → 联网搜索 → Agent 预设） |
| `out/footage/08-endcard.png` | 1080×2400 静帧 | — | 落版参考 |

录屏均为 1220×2656 竖屏、h264 yuv420p、无音轨；抽帧 + OCR 校验脚本为 `tools/ocr_batch.ps1`。

## 二、主片分镜（1920×1080 / 53s / 8 镜硬切）

| 时间 | 镜 | 舞台 | 大字 | 旁白 | 素材 in-point |
| --- | --- | --- | --- | --- | --- |
| 0–6.0s | 1 痛点 | 纯黑，公式右缘渐隐 | 已到当前时段额度上限 | 你有没有遇到过，AI 讲到一半就停了？ | 程序化（Cambria 116px 公式） |
| 6.0–12.5s | 2 预设 | 浅灰 `#F7F8FA` | 先选讲法，再讲题 | 数学、英语、知识点整理，学段与解答风格都能调。 | `02-preset.mp4` @0.0s |
| 12.5–20.0s | 3 提问与思考 | 浅灰 | 先思考，再解答 | 输入一道高中导数题，看它怎么一步步想清楚。 | `03-ask-thinking.mp4` @1.5s |
| 20.0–28.0s | 4 长公式 | 浅灰 | 公式不截断，可以左右滑 | 长展开式一行写完，按住就能横向拖动。 | `04-formula-scroll.mp4` @1.0s |
| 28.0–36.0s | 5 绘图 | 浅灰 | 无限画布，随手拖动 | 函数图像、几何关系，直接画给你看。 | `05-drawing.mp4` @1.0s |
| 36.0–41.0s | 6 计算器 | 浅灰 | 复数也能算 | 模型调用计算器 agent，把结果验算给自己看。 | `06-calculator.mp4` @0.5s |
| 41.0–48.0s | 7 设置 | 深色 `#0F1115` | 你的 Key，你的模型 | DeepSeek 官方、ChatGPT、自定义；Key 只留本机。 | `07-settings.mp4` @0.5s |
| 48.0–53.0s | 8 落版 | 品牌蓝 `#4D6BFE` | Smath · 深度求索 · 对话 Agent | 开源 · 免费 · v1.4.0 | 程序化 |

**竖屏短片（1080×1920 / 30s）**：痛点 4s → 预设 5s → 长公式 8s → 绘图 6s → 计算器 4s → 落版 3s，不含第 3、7 镜。

## 三、视觉规格

- 手机素材按原比例缩放到 395×860（横屏，置于 `(1325,110)`）/ 657×1430（竖屏，置于 `(211,120)`），30/34px 圆角遮罩 + 26px 高斯投影，浮在舞台右侧（竖屏居中）。
- 大字 `C:\Windows\Fonts\msyhbd.ttc` 66px（竖屏 58px）；旁白 `msyh.ttc` 34px、`#6B7280`（深色镜 `#98A1B0`，竖屏 `#4B5563`）。
- 每层 0.75s 淡入并**上推 32px**（`overlay=0:'32*(1-min(1,max(0,(t-st)/0.75)))'`），蒙版上推式出现，不用硬切。
- 标点不单独换行（`wrap()` 对 `，。；：、？！）】` 做粘附处理）。

## 四、交付物（`dist/`）

| 文件 | 规格 | 用途 | SHA256（前 8 / 后 9 位） |
| --- | --- | --- | --- |
| `Smath-promo-1080p.mp4` | 1920×1080 / 30fps / 53.00s / H.264 CRF18 + AAC 326k / 4,132,643 B | 官网页头、GitHub Release、B 站 / YouTube | `deb09d10…aa55ef1d` |
| `Smath-promo-vertical-30s.mp4` | 1080×1920 / 30fps / 30.00s / 同上（AAC 323k）/ 2,920,583 B | 抖音 / 小红书 / 视频号 | `55f371c3…5eba2c65f9` |
| `Smath-social-1080.png` | 1080×1080 PNG / 35,889 B | GitHub Social Preview、公众号头图 | `73de90aa…f57e209` |
| `Smath-cover-16x9.png` | 1920×1080 PNG / 418,531 B | B 站 / YouTube 视频封面（横屏） | `340110ab…935da24d4` |
| `Smath-cover-4x3.png` | 1440×1080 PNG / 342,691 B | 4:3 展示位（公众号、知乎、视频号横版） | `95f0e2dc…c58518d9` |

完整校验和：`dist/SHA256SUMS-promo.txt`。

**封面**（`tools/promo/make_covers.py`）：底图为 `05-drawing.mp4` 在 6.0 s 处的实机帧，裁掉状态栏与手势条后贴进圆角手机卡；左侧标题 112px（16:9）/ 94px（4:3），下方是深色玻璃公式卡（`(1+x+x²+…+x¹⁰)²` 一行写完，字号自缩保证永不截断）。缩略图缩到 320px 宽时标题仍清晰，副标题与胶囊退为纹理层——封面只负责「一眼看懂 + 想看下去」，细节留给视频本身。

**配乐**（`tools/promo/add_music.py`）：音源为 `神椿市協奏中。 - ゆりかご (Instrumental).flac`（192 kHz / 24 bit 母带）。原则是只做「按小节裁切 + 淡入淡出 + 静态增益」，不叠加均衡、压缩或降噪。

| 成片 | 取用区间 | 增益 | 淡入 / 淡出 | 实测响度 |
| --- | --- | --- | --- | --- |
| 横屏 53s | 24.117–77.117 s（副歌在 16s 进入，34s 段落停顿，52s 段落收束） | +4.8 dB | 0.5 s / 2.4 s | −14.0 LUFS / 真峰值 −1.2 dBTP |
| 竖屏 30s | 91.980–121.980 s（开场即段落强起，高潮覆盖落版） | +4.2 dB | 0.12 s / 2.0 s | −14.0 LUFS / 真峰值 −1.6 dBTP |

裁切点对齐 120 BPM 的小节线（beat ≈ 0.5013 s、bar ≈ 2.0052 s、首拍偏移 ≈ 0.055 s），结尾落在段落收束处而非半句中间；增益按实测 LUFS 反推，−1.2 dBFS 的峰值余量刚好让 53s 那条**完全不需要限制器介入**。视频流全程 `-c:v copy`，重新封装不重编码，画面零损失（脚本会打印 `video_stream_unchanged: true` 自证）。音轨为 AAC-LC 320 kbps / 48 kHz 立体声。

## 五、重建与校验

```powershell
$py='C:\Users\33558\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
& $py tools\promo\make_promo.py            # 横屏主片
& $py tools\promo\make_promo_vertical.py   # 竖屏短片
& $py tools\promo\make_social.py           # 社交方图
& $py tools\promo\make_covers.py           # 16:9 / 4:3 视频封面
& $py tools\promo\add_music.py             # 配乐（必须最后跑，否则会被重新生成的无声音轨覆盖）
```

- ffmpeg 取自 `tools/ffdl/extract/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v9.0.2.exe`（由 PyPI 镜像的 `imageio-ffmpeg` wheel 解包得到，本机原本没有 ffmpeg）。
- 中间产物：`out/promo/assets/`（舞台与文字图层 PNG）、`out/promo/scenes/`（每镜 H.264 分片）。改文案只需重跑脚本。
- 校验：`powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\ocr_batch.ps1 -Dir <目录>` 对抽帧做 Windows.Media.Ocr，确认字幕与画面对位；成片再用 `ffmpeg -v error -i <file> -f null -` 全解码，无输出即无坏帧。

## 六、已知限制与可选增强

- 绘图镜只呈现**平移**：录屏期间 adb 无法注入多点触控，故文案写作「随手拖动」，不承诺演示缩放。
- 痛点镜公式刻意右缘渐隐，暗示「答案被切断」；左缘保留 `f(x)=` 完整，避免像录坏的画面。
- 配乐版权：`ゆりかご (Instrumental)` 是商业曲目，仅确定用于本地成片；若要公开发布，需自行确认授权或替换为可商用音源（换曲只改 `add_music.py` 顶部的 `TRACK` 与 `JOBS` 区间后重跑）。
- 尚未做：真人 / TTS 旁白、烧字幕导出 SRT、第 3 镜「已深度思考」的强调动画。