# 设计：学习为主，限时为辅（0.2 ~ 0.4）

状态：**草案，待确认**。确认前不动代码。

## 1. 目标与边界

应用从"限制看电视的工具"转向"学习工具，限时是辅助"。本阶段做六件事：

| # | 功能 | 一句话 |
|---|---|---|
| A | 错题优先出题 | 近期答错的题更常出现，掌握后淡出；同一轮里答错的题隔几题再考一次 |
| B | 难度自适应 | 数学出题范围按近期正确率自动升降，家长设的范围是上限 |
| C | 音效反馈 | 答对、答错、通关、抽到贴纸各有一个短音效 |
| D | 主动学习入口 + 学习换时间 | 孩子不用等锁屏，可在电视首页主动学习；完成一轮换加时 |
| E | 贴纸册 | 每完成一轮抽一张贴纸，电视首页有贴纸册 |
| F | 手机端周报 | 一周答题数、正确率、各科表现、最弱的题、哪天没学 |

**不做**（避免范围膨胀）：连续打卡火苗、吉祥物养成、新题型、数学错题回炉（数学是即时生成的，见 §3.1）、云同步、多孩子账号。

**不加"学习模式/守护模式"开关。** 现有"暂停守护"（`Store.paused`）加上新的主动学习入口，已经覆盖"只学习不锁"。

## 2. 总体结构

```
 作答 ─▶ Store.recordAnswer ─▶ answers.json（已有，最近 1000 条）
                           └▶ daily.json（新：按天汇总，留 90 天，周报用）
 一轮结束 ─▶ Store.recordRound ─▶ daily.json 轮数
                              └▶ Rewards.onRound ─▶ rewards.json（贴纸、当日已得加时）
                                                 └▶ store.bonusSeconds += 加时（D）
 出题 ─▶ QuizMaker ◀─ ReviewWeights(answers.json)  （A）
                   ◀─ AdaptiveMath(answers.json)    （B）
 展示 ─▶ QuizView ─▶ Sfx（C）
```

三条原则：

1. **新逻辑写成纯函数**（权重、难度、奖励规则、周报聚合），放在 `quiz/`、`data/`，不依赖 Android，可直接单测。
2. **加时复用 `bonusSeconds`**，不引入"时间存款"。解锁或下一轮开始时它本来就清零，语义不变。
3. **所有新增设置项有默认值**，旧 `settings.json` 读取不受影响（`Settings.merge` 已是这个风格）。

## 3. 各功能设计

### 3.1 A 错题优先出题

**权重** `quiz/ReviewWeights.kt`（新，纯函数）：输入 `List<AnswerLog>`（新到旧）和当前时间，输出 `Map<String, Double>`（key 为 `Question.key`）。每个 key 取最近 6 条、30 天内的记录：

| 情形 | 权重 |
|---|---|
| 从没答过 | 1.5（略微优先新题） |
| 最近一次答错 | 4 |
| 答错过，之后只对了 1 次 | 2.5 |
| 答错过，之后连对 2 次 | 1.2 |
| 最近连续 3 次答对 | 0.4 |
| 其他 | 1 |

具体数值是初始值，写成常量，单测锁定"错题 > 新题 > 普通 > 已掌握"的顺序，不锁死数值。

**接入** `QuizMaker`：构造函数加 `weight: (Question) -> Double = { 1.0 }`，默认行为不变，现有测试不动。`Source` 里"洗牌后依次取"改为"加权无放回抽取"（Efraimidis–Spirakis：`key = random^(1/w)` 排序）。

**出题器的题**：
- 拼音出题器（`PinyinGenerator`）：生成多个候选（如 4 个），按权重挑一个。接口 `QuestionGenerator` 不变。
- 数学出题器：**不做**错题回炉。题是即时生成的，记录里没有完整题目，要回炉得再存一份题目 JSON。数学靠 B（难度自适应）。

**同轮回炉**（`QuizSession`）：答错的题插到 2 题之后再出现一次，最多一次。现有测试 `wrongAnswerMovesToNextQuestion` 只断言下一题不是刚答错的那题，仍然成立。

**开关**：`Settings.reviewWrong`，默认 `true`；关闭后权重恒为 1、不回炉。

### 3.2 B 难度自适应

`quiz/AdaptiveMath.kt`（新，纯函数）：`next(level, recentMathLogs, cap) -> Int`。

- 数学题的 key 以 `MATH|` 开头，从 `answers.json` 里取最近 20 条。
- 不足 10 条：不调整。正确率 ≥ 90%：`level += 5`。正确率 ≤ 60%：`level -= 5`。
- `level` 限制在 `[10, MathConfig.max]`，也就是家长设置的 `max` 是上限。`max < 10` 时不调整。
- **一轮结束时调整一次**，不在轮内变，避免孩子感觉题忽难忽易。`level` 存 `SharedPreferences`（`mathLevel`），出题时 `MathGenerator.generate(config.copy(max = level))`。
- 开关：`MathConfig.adaptive`，默认 `false`。老用户升级后行为不变，家长手动打开。

### 3.3 C 音效

`ui/Sfx.kt`（新）。**不引入音频资源**：启动时用代码合成几段 PCM（正弦波加泛音、指数衰减，类似木琴/铃音），22.05kHz 单声道 16 位，装进 `AudioTrack(MODE_STATIC)`。理由：免版权、不增大 APK、不用找素材。代价是音色偏"电子"。如果效果不好，后续换成几个 < 50KB 的 ogg（`SoundPool`）只改 `Sfx` 一个文件。

| 事件 | 音效 | 时长 |
|---|---|---|
| 答对 | C5→E5→G5 上行三连 | ~350ms |
| 答错 | 低音下行两连，柔和 | ~250ms |
| 一轮通关 | 四音号角 | ~900ms |
| 抽到贴纸 | 上行闪光音；稀有以上多一段 | ~600ms |

- 开关：`Settings.soundEffects`，默认 `true`。
- 音量走系统媒体音量，和 TTS 同一个流。
- **只在非录音时播放**：`QuizView.judge()` 是松开语音键、识别完成之后才调用，此时麦克风已关，不会被收进录音。
- 与 TTS 鼓励语叠音的问题：先播音效，TTS 延后约 400ms 再开口。**这一点需要在电视实机上听过才能定**。

### 3.4 D 主动学习入口 + 学习换时间

**入口**：电视首页（`MainActivity`）加按钮"📚 开始学习"，放在第一个并默认获得焦点；原"试一试答题"保留给家长。标题副文案改为"每天学一点，看电视的时间也能攒出来"之类（具体文案实现时定）。

**模式**：`QuizView` 现有的 `preview: Boolean` 改成三态：

```kotlin
enum class QuizMode { GATE, PREVIEW, STUDY }
```

| | GATE（锁屏答题） | PREVIEW（家长试一试） | STUDY（主动学习） |
|---|---|---|---|
| 返回键 | 吞掉 | 退出 | 退出，本轮不算 |
| 通过后 | 解锁、重置计时 | 只关闭 | 发奖励，不动计时/锁定状态 |
| 记录作答 | 是 | 是（沿用现状） | 是 |
| 记录轮数、调整数学难度 | 是 | 否（家长试做不算） | 是 |
| 发贴纸 | 是 | 否 | 是 |
| 发加时 | 否 | 否 | 是 |

`LockController.showQuiz(preview: Boolean)`、`QuizActivity.EXTRA_PREVIEW` 同步改成 `mode`。STUDY 的题数用 `Settings.studyQuestionCount`（默认 5，范围 1~20）。台词（`Phrases`）目前是"休息一下吧……继续看"，STUDY 要有独立的开场白和通关语。

**加时规则**（`data/Rewards.kt`，纯函数）：

- 家长设置：`rewardMinutesPerRound`（默认 5，0 表示关闭加时）、`rewardDailyCapMinutes`（默认 15）。
- STUDY 一轮通过：`earned = min(rewardMinutesPerRound, cap - 当天已得)`，再 `store.bonusSeconds += earned * 60`。
- 当天已得分钟数存在 `rewards.json`，按本地日期换天清零。
- 只有 STUDY 发加时。GATE 本身就是"答对才能继续"，再加时会双重奖励。

**为什么不用时间存款**：`bonusSeconds` 在锁屏解锁时清零。如果孩子在锁屏前学习，加时延长本轮；如果等锁屏后才学习，那是 GATE，不会发加时。所以不存在"攒着以后用"的场景，也就不需要新的状态。

**边界情况**：
1. 守护暂停（`paused`）时 STUDY 照常可用，只发贴纸，不发加时（无计时可加），首页给出说明。
2. `CountMode.ALL` 下计时器会把答题浮层所在的时间也算进去，可能在 STUDY 中途触发 `lock()`，而 `showQuiz` 因 `isShowingQuiz` 直接返回。处理：`GuardService.isCounting()` 在答题浮层显示时返回 `false`。
3. 绕过：STUDY 的返回键不能影响 GATE。`QuizMode.GATE` 下返回键行为与现在完全一致。

### 3.5 E 贴纸册

**素材**：复用 `assets/emoji/` 里已有的 575 张 Noto 表情，不新增资源。

**图鉴** `data/StickerCatalog.kt`（新）：手工挑选三档，实现时按已有码位筛选。

| 档位 | 数量 | 抽中概率 | 内容 |
|---|---|---|---|
| 普通 | ~60 | 70% | 常见动物、水果、食物 |
| 稀有 | ~25 | 25% | 海洋生物、交通工具、乐器 |
| 传说 | ~8 | 5% | 独角兽、龙、彩虹之类 |

单测校验图鉴里每个码位都在 `assets/emoji/`，避免抽到电视显示不出来的表情。

**存储** `rewards.json`（和 `answers.json` 同样用原子写入）：

```json
{ "stickers": {"1f984": {"n": 2, "first": 1730800000000}},
  "day": "2026-11-05", "earnedMinutes": 10 }
```

**抽取**：GATE 和 STUDY 每通过一轮抽一张。重复的计数加一。抽取函数接收 `Random` 以便测试。

**展示**：
- 通关画面（`QuizView.allDone`）：在现有彩带之后翻出一张贴纸，新贴纸带"新！"标记，播抽贴纸音效。
- 首页新增"🌟 我的贴纸 12/93"按钮，进入 `StickerBookActivity`（新）：网格展示，未获得的显示灰色剪影，遥控器方向键可选，焦点放大沿用 `Widgets` 里现有的按钮风格。
- 内存：电视 4A 内存紧。网格用已有的 `Emoji.bitmap` LruCache（8MB 上限），一屏只解码可见的几十张。**实现后要在实机上看内存**。

### 3.6 F 手机端周报

**数据**：`answers.json` 只保留最近 1000 条，不够做周报，也没有按天汇总。新增 `daily.json`：

```json
{ "2026-11-05": { "answered": 12, "correct": 10, "rounds": 3, "studyRounds": 1,
                  "bySubject": {"MATH": [6, 5], "HANZI": [6, 5]} } }
```

在 `Store.appendLog` 里同时更新（改名 `recordAnswer`，保留旧入口也可）；一轮结束调 `recordRound`。保留 90 天。

**接口**：`GET /api/report?days=7` 返回：

```
{ days: [{date, answered, correct, rounds}...],  // 含没学习的日子（全 0）
  bySubject: {MATH: {answered, correct}...},
  weak: [{display, subject, wrong, total}...],     // 最近 7 天答错 ≥ 2 次且没连续答对 3 次的前 10 个
  activeDays: 5, stickers: 14, earnedMinutes: 40 }
```

聚合逻辑写成纯函数 `data/Report.kt`。最弱的题从 `answers.json` 统计，key 前缀给出科目，不需要在日志里加字段。

**页面**：`app.js` / `index.html` 加"周报"标签页。柱状图用纯 CSS，不引入图表库（现有网页是零依赖原生 JS）。

**手机端设置页**新增"学习奖励"区块：`reviewWrong`、`soundEffects`、`studyQuestionCount`、`rewardMinutesPerRound`、`rewardDailyCapMinutes`，数学区块加 `adaptive`。

## 4. 兼容性与迁移

- `Settings`、`MathConfig` 新字段全部有默认值，`merge` 里缺失时取旧值，旧 `settings.json` 无需迁移。
- 新增 `daily.json`、`rewards.json`；缺文件视为空。老用户升级后周报从升级当天开始积累，**之前的数据补不回来**。需要在周报页说明。
- 默认值取舍：`reviewWrong`、`soundEffects` 默认开；`adaptive` 默认关；加时默认 5 分钟/轮、每天上限 15 分钟。**加时默认开意味着升级后孩子就能赚时间**，是否默认开见 §7。
- `QuizView` 构造参数 `preview: Boolean` 改为 `mode: QuizMode`，调用点只有 `LockController` 和 `QuizActivity`。
- API 只新增 `GET report`，不改已有接口。

## 5. 分批与版本

| 批次 | 内容 | 版本 | 说明 |
|---|---|---|---|
| 1 | A 错题优先 + B 难度自适应 + C 音效 + `daily.json` 开始记录 | 0.2.0 | 不新增界面，孩子几乎无感，风险最低。先开始积累周报数据 |
| 2 | D 主动学习入口 + 加时 + E 贴纸册 | 0.3.0 | 改首页、改 `QuizView` 模式、新增 Activity，是主要改动 |
| 3 | F 周报 + 手机端学习奖励设置页 | 0.4.0 | 纯手机网页和一个只读接口，批次 1 的数据已经积累了一段时间 |

每批单独提交、更新 `CHANGELOG.md`（`[Unreleased]` 章节，按现有 `### ✨ 新功能` 格式），发版由你决定。

## 6. 测试与验证

**单元测试**（沿用现有 JUnit，`tv/src/test`）：
- `ReviewWeightsTest`：权重顺序；30 天外的记录不影响；同题多次记录取最近。
- `QuizTest` 补充：加权抽取下错题更早出现（固定 `Random` 种子）；`reviewWrong = false` 与现状一致；同轮回炉最多一次。
- `AdaptiveMathTest`：样本不足不调；上下限；`max < 10` 不调。
- `RewardsTest`：每日上限、跨天清零、`rewardMinutesPerRound = 0`、`paused` 时不发加时；抽取概率分布用固定种子断言各档都能抽到。
- `StickerCatalogTest`：图鉴码位都在 `assets/emoji/`、无重复。
- `ReportTest`：没学习的日子补 0；弱项筛选；跨天边界。
- `SettingsTest` 补充：旧 JSON（无新字段）读取后等于默认值。

**实机验证**（小米电视 4A，单测覆盖不到）：
- 音效和 TTS 是否叠音；音效是否被识别收进录音。
- 首页遥控器焦点顺序；贴纸册网格滚动和焦点。
- 内存（对比现状 ~85MB）。
- STUDY 模式返回键能退出，GATE 模式不能；`CountMode.ALL` 下 STUDY 中途不会触发锁屏。

## 7. 待你确认的决定

1. **加时默认开还是关？** 默认开更能体现转型，但升级后孩子立刻能"赚时间"，家长可能没注意到。建议默认开，并在更新说明里写清楚。
2. **STUDY 每轮题数**默认 5，是否合适？GATE 的 `questionCount` 默认是 3。
3. **贴纸抽取是否对 GATE 也发？** 设计里是发。好处是锁屏答题也有惊喜；缺点是孩子可能故意不守规矩、多次触发锁屏来抽贴纸（但锁屏由计时触发，孩子无法自己触发）。
4. **音效先用代码合成**，听了不满意再换录音素材，可以接受吗？
5. **同轮回炉**是否要做？它属于 A 的增强，不做的话 A 只影响跨轮选题。
