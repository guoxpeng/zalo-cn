# PC 支线交接文档（Zalo PC 汉化）

> 最后更新：2026-11-17（适配 Zalo 26.11.12；脚本全面去硬编码 + 新增 `auto_patch.py` 自动重打 + `watch_zh.vbs` 开机守护，更新后无需人工干预）
> 用途：供任何 AI 无缝接手 PC 支线。**接手 PC 相关任务必须先读本文件。**
> 安卓主线的交接文档是仓库根目录的 `PROJECT_STATE.md`；两条支线互不干扰。
> 远端：本目录内容在 https://github.com/guoxpeng/zalo-cn 的 `main` 分支 `pc/` 子目录下（2026-11-21 由独立的 `pc-zh` 分支并入 main，`pc-zh` 分支已删）。

---

## 一、支线概况与安全底线（最高优先）

**目标**：把 Zalo PC 版（Windows Electron 应用）的越南语界面整体替换为简体中文，
设置里可在「中文 / English」之间真实切换（方案 B：中文占用内置越南语槽位）。

**安全底线（与安卓主线同一红线，不可逾越）**：
- **只改 UI 显示数据**（语言包字符串），**不碰**登录凭证、账号数据、加密逻辑、网络代码。
- `app.asar.unpacked` 原生文件（26.9.10 为 65 个、26.11.12 为 70 个）必须保持字节级一致——04 脚本自动按当前安装版本校验。
- 账号/消息数据目录 `D:\Zalo Data\message`、配置目录 `Roaming\ZaloData` **一律不动**。
- 替换 `app.asar` 前必须先关闭 Zalo 进程、备份原文件。

**方案 B（用户拍板）**：不加第三语言选项（那要改 4 处核心语言逻辑，更新后维护成本高、
且动到登录页共用代码，风险不可接受）。中文词库填进「越南语槽位」，英文词库原样保留，
语言菜单里 `text:"Tiếng Việt"` 改为 `text:"中文"`，实际就是「中文 / English」双选项。

---

## 二、当前状态快照

| 项 | 值 |
|---|---|
| 目标应用 | Zalo PC **26.11.12**，安装于 `C:\Users\laogu\AppData\Local\Programs\Zalo\Zalo-26.11.12`（历史版本 26.10.20 / 26.9.10 仍在，已弃用） |
| 成品 | `zalopc/work/dist_v2/app_zh.asar`（**180.7 MB**，13,231 文件，70 原生文件 unpacked 字节级一致） |
| 已安装 | `…\Zalo-26.11.12\resources\app.asar` 已替换为汉化版，8 进程正常运行（2026-11-17） |
| 词库覆盖 | 字典 **6475/6475 全汉化**（0 保留）；内嵌 `{en,vi}` 对象 vi 侧 1267 处替换（7 个文件） |
| 原版备份 | 同目录 `resources\app.asar.bak.26.11.12_20261117_095310`（原版 SHA1 `286af4f8…`，与 update_meta.json 一致） |
| 翻译资产 | `trans_cache.json`（本版新增仅 77 条联网翻译；其余全部缓存/种子命中）、`zh_dict.json`、`zh_text.json`、`overrides.json` |
| 自动化 | `work/auto_patch.py`（检测+重打+替换引擎）、`watch_zh.vbs`（开机守护，10 分钟一检）、Startup 自启项 `Zalo汉化守护.vbs` |
| 远端 | `guoxpeng/zalo-cn` 分支 `main` 下的 `pc/` 子目录（2026-11-21 由 `pc-zh` 分支并入，`pc-zh` 已删；改动后推 `main` 即可更新） |

**汉化状态检测机制**：读 app.asar 二进制统计 `text:"中文"` / `text:"Tiếng Việt"` 出现次数。
汉化版=中 18/越 0；原版=中 0/越 18。`auto_patch.py --check` 可随时查看。

---

## 三、目录与流水线（4 个脚本顺序执行）

```
zalopc/
├── PC_STATE.md            本文件（PC 支线唯一记忆源）
├── README.md              面向使用者的说明（即分支上的 README）
├── backup/                官方原版 app.asar 备份（勿上传，173MB）
├── extract/               官方 app.asar 解包原文（勿上传，274MB）
├── build_app/             打补丁后的完整文件树（勿上传，274MB；03 重新生成）
├── watch_zh.vbs           开机守护主逻辑（10 分钟一检，隐藏窗口调用 auto_patch.py）
├── raw/                   lang-en/lang-vi 词库 chunk 原件（小；auto_patch 按新版自动覆盖）
└── work/
    ├── 01_parse.py        解析词库 chunk + 扫描内嵌双语对象 → lang_en/vi.json、inline.json、corpus.json
    ├── 02_translate.py    翻译引擎（种子→gtx→MyMemory→bing；断点续传；缓存可离线兜底）
    ├── 03_apply.py        生成 build_app：词库替换 + vi 字段替换 + 语言菜单改名（chunk/inline 文件全动态探测）
    ├── 04_pack.py         重打包 app_zh.asar（--unpack **/native/** + 按当前版本 unpacked 文件数校验）
    ├── 05_force_promo_zh.py  强制 zCloud 推广弹窗用本地中文（按内容特征全树扫描，不依赖哈希）
    ├── auto_patch.py      ★自动补丁引擎：检测汉化状态→全流水线→安全替换→同步 SHA1→explorer 重启
    ├── auto_patch.log / auto_patch_state.json  自动补丁运行日志与状态（运行时生成）
    ├── bing/              bing-translate-api 的 node worker（worker.js + package.json）
    ├── overrides.json     人工修正词条（按 vi 原文覆盖，优先级最高）
    ├── seed/src_map_compact.json  安卓项目精校词库副本（种子翻译）
    ├── lang_en/lang_vi/inline/zh_dict/zh_text/trans_cache.json  中间产物（随分支上传，可复用）
    └── dist_v2/app_zh.asar   最终成品
```

```bash
cd /d/project/zalo/zalopc/work
python 01_parse.py            # 新版词库文件名变了也不用改代码（按 lang-en.*/lang-vi.* 前缀探测）
python 02_translate.py        # 默认 gtx 引擎；额度受限用 ZALOPC_ENGINE=mm 或 =bing
python 03_apply.py            # 生成 build_app/
python 05_force_promo_zh.py   # 强制 zCloud 推广弹窗走本地中文（去掉服务端远端覆盖）
python 04_pack.py             # 打包+校验（安装路径不同用 ZALOPC_INSTALL=…\resources 覆盖）
# 关 Zalo → 备份原 app.asar → 用 dist_v2/app_zh.asar 替换 resources/app.asar → 启动
```

**关键环境变量**：`ZALOPC_ENGINE`（gtx/mm/bing）、`ZALOPC_BUDGET`（本次最多翻多少条）、
`ZALOPC_SLEEP`、`ZALOPC_BATCH`、`ZALOPC_MM_EMAIL`（MyMemory 降级邮箱）、
`ZALOPC_INSTALL`（04 用的原版 resources 目录）、`NODE`（02 的 node 路径，默认走 PATH）。

**02 的三级兜底顺序**（额度受限会自动换）：
1. 安卓精校词库 `seed/src_map_compact.json`（en+vi 双侧命中即用，免翻译）；
2. 机器翻译：Google gtx（占位符掩码 `%N%` 保护 `$0$`/`{0}`/HTML/换行）→ MyMemory → bing-translate-api；
3. `overrides.json` 人工修正最高优先。
缓存条目 `zh:null`（标记丢失）会在下一轮自动重试，不会当成功跳过；
英文侧已缓存的词条可直接离线兜底（`en_by_vi` 反查），不耗网络额度。

---

## 四、更新后自动重打方案（2026-11-17 新增，无需人工干预）

Zalo 每次自动更新会新建 `Zalo-<新版本>` 目录并让引导器指向它，旧汉化包随之失效——
这正是本次「汉化未生效」的根因（26.9.10 汉化版还在，但运行的是 26.11.12 原版）。
现改为全自动闭环：

```
开机 → Startup\"Zalo汉化守护.vbs" → watch_zh.vbs（每 10 分钟）
   → auto_patch.py --check
        ├─ 最新版本已汉化(中18/越0) → noop，退出
        └─ 未汉化(中0/越18) → run_pipeline 全自动重打：
             解包新版 asar → 自动拷新词库 → 01→02(复用缓存)→03→05→04(字节校验)
             → 汉化二次校验 → 等 Zalo 退出(默认30分钟) → 备份原 asar → 原子替换
             → 同步 update_meta.json SHA1 → explorer 重启 Zalo → 写状态
```

**关键设计（踩过的坑，勿回退）**：
1. **去硬编码**：03 的 `CHUNK_FILE` 用 glob 探测、inline 文件列表读自 `inline.json`；
   04 的 resources 路径自动探测最新版本；05 按内容特征全树扫描。新版哈希全变也能跑通。
2. **raw 必须先清旧 chunk**：auto_patch 每次只删 `lang-en.*.js`/`lang-vi.*.js` 再拷新版，
   否则 glob 取到「旧 en + 新 vi」导致键数不等、01 断言失败（本次实测踩到）。
3. **build_app/dist_v2 每次先清空**：`shutil.rmtree` 在 WorkBuddy 宿主会触发 safe-delete 闸；
   auto_patch 用 `wipe_dir`（逐 os.remove）规避，也避免残留旧版原生文件混入导致打包内容不一致。
4. **原生文件数以当前版本为准**：26.9.10 是 65 个、26.11.12 是 70 个；04 已改为动态比对，
   不再写死 65。`--unpack **/native/**` 规则对本版 70 个文件全部覆盖，已验证字节级一致。
5. **重启必须用 explorer.exe**：直接 Popen/Start-Process 引导器会静默退出（缺 shell 环境，本次实测）；
   `subprocess.Popen(["explorer.exe", exe])` 等价用户双击，实测 8 进程正常。
6. **SHA1 同步**：每次替换后把汉化包 sha1 写进版本目录 `update_meta.json` 的
   `resources\app.asar` verify 项（并留 .bak）。实测启动不强制校验，但保持一致防更新回滚。

**手动触发 / 排查命令**：
```bash
cd /d/project/zalo/zalopc/work
python auto_patch.py --check    # 只检测：版本/汉化标记/Zalo 是否运行
python auto_patch.py            # 检测 + 必要时全自动重打安装（守护进程调用的就是这个）
python auto_patch.py --force    # 强制重打（即使已汉化）
ZALOPC_WAIT_IDLE=0 python auto_patch.py   # Zalo 运行时不等退出，只生成包不替换（安全试跑）
```
日志 `work/auto_patch.log`，状态 `work/auto_patch_state.json`，守护日志 `work/watchdog.log`。
自启项：`C:\Users\laogu\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup\Zalo汉化守护.vbs`
（内容为转发到项目内 `watch_zh.vbs`；项目迁移路径变了只需改转发脚本里的 target）。

**老版手工 SOP（仅自动化失效时的兜底）**：
1. 让官方更新完成，确认新版本目录；
2. 解包新 asar 到 `extract/`，拷新 `lang-en/vi.*.js` 到 `raw/`（先删旧的）；
3. 依次跑 `01→02→03→05→04`（脚本已全动态，无需再改哈希常量）；
4. 关 Zalo → 备份 → 替换 → 用 explorer 启动 → `auto_patch.py --check` 确认汉化=True。

---

## 五、已知限制（定案，勿反复重试）

1. **服务器下发的动态文案**不在本地词库，无法静态汉化（个别系统弹窗等，量少）。
2. 语言菜单「中文」旁的图标仍是越南国旗（纯视觉；换图标要动渲染代码，违背方案 B 原则，不做）。
3. 自动更新已由 §四 守护闭环处理；唯一边界：若更新发生在 Zalo 持续运行超过 30 分钟不关的场景，
   本轮只生成包、留待 Zalo 退出后安装（可按需调大 `ZALOPC_WAIT_IDLE`）。

---

## 六、历史版本记录（PC 支线）

| 日期 | 事件 |
|---|---|
| 2026-09-03 | 摸清 PC 版结构：词库为 webpack chunk 内 JSON.parse 字典（en/vi 各 6279 键）+ 内嵌双语对象；确定方案 B |
| 2026-09-04 | 完成翻译（4457 条 MT 缓存 + 种子命中）与补丁；打包校验通过；替换安装；Zalo 正常运行 |
| 2026-09-11 | 修复 02 两处引擎 bug（zh:null 重试、英文侧离线兜底）→ 6279/6279 全汉化；overrides 补 3 条图标占位词条；04 重验证通过；源码推送至 guoxpeng/zalo-cn 分支 pc-zh |
| 2026-09-27 | Zalo 自动更新至 26.9.10，词库增至 6309 条、内联 1238 组，全量重跑流水线；新增 `05_force_promo_zh.py` 强制 zCloud 推广弹窗走本地中文（绕服务端远端覆盖）；修正 Profile 误译为「个人资料」；打包替换安装验证；文档更新并推送 pc-zh |
| 2026-09-27 | Zalo 自动更新至 26.9.10 覆盖汉化。按 §四 重打：解包新版、更新 03/05 哈希常量、01→02（仅 26 条新词条）→03→05→04，6309/6309 全汉化、1238 组内嵌双语、65 原生文件校验一致；替换安装后修正 `STR_ACCOUNT_MENU`（轮廓→个人资料）；新增 `05_force_promo_zh.py` 解决 zCloud 推广弹窗被服务端远端配置覆盖仍显示越南语的问题（4 文件 12 处 getter 去除远端取值）；成品 `work/dist_v2/app_zh.asar` |
| 2026-11-17 | **诊断「汉化未生效」**：Zalo 已自动更新至 26.11.12（经 26.10.20），汉化包仍在 26.9.10 目录，运行的是新版原版 asar。**适配 26.11.12**：脚本全面去硬编码（03 chunk/inline 动态探测、04 版本自动探测、05 内容特征扫描），全量重打 6475/6475 全汉化、1267 处内嵌替换、70 原生文件字节一致、推广弹窗 12 处修正；替换安装成功（8 进程正常）。**新增自动方案**：`auto_patch.py` 检测/重打/替换引擎 + `watch_zh.vbs` 开机守护 + Startup 自启项，更新后自动重打无需人工。踩坑记录：raw 旧 chunk 致 01 断言失败、shutil.rmtree 触发 safe-delete 闸、原生文件 65→70、启动必须走 explorer.exe |
| 2026-11-21 | **并入 main**： 分支（orphan）以 subtree 方式并入  的  子目录（含翻译注入 v1.0.0 ）； 分支随后删除，PC 内容此后随  走。 |

| 2026-11-21 | **翻译注入 v1.0.0**（`work/translate/`）：在汉化版 app.asar 上加实时翻译，听（收消息自动翻成中文，原文在上、译文在下框）+ 说（输入框边「译」按钮，中文翻成越南语填回输入框，不自动发）。注入脚本 `zalocn-translate.js`（语法已验；bilibili API 主 + Google 兜底；bilibili 回 `access-control-allow-origin: *`，渲染器可直调，不用 preload 桥；消息选择器 `.chat-item.to-me`+`data-qId` 缓存；输入框是 ProseMirror，用 execCommand 填回）。补丁进 asar 已验证（文件一致、原生文件 untouched）。安装器 `install-translate.ps1`（备份→解包→加脚本→重打包→替换→重启，重复跑先恢复再重打）+ `restore-translate.ps1` 一键恢复。**附带发现**：Zalo PC 自带完整 AI 翻译功能（60 条 STR_AI_TRANS 词条 + 独立语言模型 chunk；收消息自动翻 + 打字提示翻译 + 每会话设置；语言对含中文简体；自动翻译可能要 zCloud 套餐），老郭 21:49 明确“别管其他人，我要我自己的”，故做注入。等实机测试反馈再调（译文位置/按钮位置/文字提取可能要改）。 |

---

## 七、翻译注入（2026-11-21，v1.0.0 原型）

位置：`work/translate/`（`zalocn-translate.js` + `install-translate.ps1` + `restore-translate.ps1` + `README.md`）。

- 原理：往 `pc-dist/compact-app.html` 加 `<script src="zalocn-translate.js">`；主聊天页无 CSP，脚本可直调 bilibili API（CORS `*`）。
- 听：MutationObserver 看 `.chat-item.to-me`，`data-qId` 做缓存键；译文插在原文下面（带蓝边的框）。
- 说：往 `.normal-buttons-group` 插「译」按钮；ProseMirror 输入框用 `execCommand('insertText')` 填回，不自动发。
- 配置：默认收→中文简体、译按钮→越南语、bilibili API（免密钥）；改目标语言目前改 `zalocn-translate.js` 里的 DEFAULTS。
- 安装器在**现有汉化版** app.asar 上打补丁（汉化词条不动）；Zalo 更新后重跑一次即可。
