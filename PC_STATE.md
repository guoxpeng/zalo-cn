# PC 支线交接文档（Zalo PC 汉化）

> 最后更新：2026-09-11（成品安装验证通过、源码推送到 guoxpeng/zalo-cn 分支 `pc-zh`）
> 用途：供任何 AI 无缝接手 PC 支线。**接手 PC 相关任务必须先读本文件。**
> 安卓主线的交接文档是仓库根目录的 `PROJECT_STATE.md`；两条支线互不干扰。
> 远端：本目录内容发布在 https://github.com/guoxpeng/zalo-cn 的 **`pc-zh` 分支**（独立历史，orphan 分支，与安卓版 `main` 分支互不相干）。

---

## 一、支线概况与安全底线（最高优先）

**目标**：把 Zalo PC 版（Windows Electron 应用）的越南语界面整体替换为简体中文，
设置里可在「中文 / English」之间真实切换（方案 B：中文占用内置越南语槽位）。

**安全底线（与安卓主线同一红线，不可逾越）**：
- **只改 UI 显示数据**（语言包字符串），**不碰**登录凭证、账号数据、加密逻辑、网络代码。
- `app.asar.unpacked`（65 个原生文件）必须保持字节级一致——04 脚本自动校验。
- 账号/消息数据目录 `D:\Zalo Data\message`、配置目录 `Roaming\ZaloData` **一律不动**。
- 替换 `app.asar` 前必须先关闭 Zalo 进程、备份原文件。

**方案 B（用户拍板）**：不加第三语言选项（那要改 4 处核心语言逻辑，更新后维护成本高、
且动到登录页共用代码，风险不可接受）。中文词库填进「越南语槽位」，英文词库原样保留，
语言菜单里 `text:"Tiếng Việt"` 改为 `text:"中文"`，实际就是「中文 / English」双选项。

---

## 二、当前状态快照

| 项 | 值 |
|---|---|
| 目标应用 | Zalo PC **26.8.20**，安装于 `C:\Users\laogu\AppData\Local\Programs\Zalo\Zalo-26.8.20` |
| 成品 | `zalopc/work/dist/app_zh.asar`（173 MB，13,214 文件，65 原生文件 unpacked 校验一致） |
| 已安装 | `…\Zalo-26.8.20\resources\app.asar` 已替换为汉化版，启动/聊天/设置正常（2026-09-04） |
| 词库覆盖 | 字典 **6279/6279 全汉化**（0 保留）；内嵌 `{en,vi}` 对象 vi 侧 243 组全部就位 |
| 原版备份 | `zalopc/backup/app.asar.orig-26.8.20`（SHA1 与官方 update 元数据一致，纯净） |
| 翻译资产 | `trans_cache.json`（4457 条 MT 缓存）、`zh_dict.json`、`zh_text.json`、`overrides.json` |
| 远端 | `guoxpeng/zalo-cn` 分支 **`pc-zh`**（orphan 独立历史，main 未动） |

---

## 三、目录与流水线（4 个脚本顺序执行）

```
zalopc/
├── PC_STATE.md            本文件（PC 支线唯一记忆源）
├── README.md              面向使用者的说明（分支上的是其扩展版）
├── backup/                官方原版 app.asar 备份（勿上传，173MB）
├── extract/               官方 app.asar 解包原文（勿上传，274MB）
├── build_app/             打补丁后的完整文件树（勿上传，274MB；03 重新生成）
├── raw/                   lang-en/lang-vi 词库 chunk 原件（小，1.2MB，随分支上传）
└── work/
    ├── 01_parse.py        解析词库 chunk + 扫描内嵌双语对象 → lang_en/vi.json、inline.json、corpus.json
    ├── 02_translate.py    翻译引擎（种子→gtx→MyMemory→bing；断点续传；缓存可离线兜底）
    ├── 03_apply.py        生成 build_app：词库替换 + vi 字段替换 + 语言菜单改名
    ├── 04_pack.py         重打包 app_zh.asar（--unpack **/native/** + 65 文件校验）
    ├── bing/              bing-translate-api 的 node worker（worker.js + package.json）
    ├── overrides.json     人工修正词条（按 vi 原文覆盖，优先级最高）
    ├── seed/src_map_compact.json  安卓项目精校词库副本（种子翻译）
    ├── lang_en/lang_vi/inline/zh_dict/zh_text/trans_cache.json  中间产物（随分支上传，可复用）
    └── dist/app_zh.asar   最终成品
```

```bash
cd /d/project/zalo/zalopc/work
python 01_parse.py            # 新版词库文件名变了也不用改代码（按 lang-en.*/lang-vi.* 前缀探测）
python 02_translate.py        # 默认 gtx 引擎；额度受限用 ZALOPC_ENGINE=mm 或 =bing
python 03_apply.py            # 生成 build_app/
python 04_pack.py             # 打包+校验（安装路径不同用 ZALOPC_INSTALL=…\resources 覆盖）
# 关 Zalo → 备份原 app.asar → 用 dist/app_zh.asar 替换 resources/app.asar → 启动
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

## 四、Zalo 更新到新版本后的重新汉化 SOP

1. 让官方更新完成，确认新版本目录（如 `Zalo-26.9.x`）。
2. 从新 `resources/app.asar` 重新解包到 `zalopc/extract/`（asar extract），
   并把新的 `pc-dist/lazy/lang-en.*.js`、`lang-vi.*.js` 拷进 `zalopc/raw/`。
3. 按顺序跑 01→02→03→04（02 会复用 trans_cache.json，老词条不用重翻；新词条走引擎）。
4. 03 里 `CHUNK_FILE` 常量与 `INLINE_FILES` 列表的哈希文件名需要按新版实际文件名更新。
5. 04 校验通过后：关 Zalo → 把新版原 app.asar 备份进 `backup/` → 替换 → 启动验证。
6. 更新本文件时间戳与版本号。

---

## 五、已知限制（定案，勿反复重试）

1. **服务器下发的动态文案**不在本地词库，无法静态汉化（个别系统弹窗等，量少）。
2. 语言菜单「中文」旁的图标仍是越南国旗（纯视觉；换图标要动渲染代码，违背方案 B 原则，不做）。
3. Zalo 强制自动更新会覆盖 app.asar，每次更新需按 §四 重打（缓存可复用，通常几分钟）。

---

## 六、历史版本记录（PC 支线）

| 日期 | 事件 |
|---|---|
| 2026-09-03 | 摸清 PC 版结构：词库为 webpack chunk 内 JSON.parse 字典（en/vi 各 6279 键）+ 内嵌双语对象；确定方案 B |
| 2026-09-04 | 完成翻译（4457 条 MT 缓存 + 种子命中）与补丁；打包校验通过；替换安装；Zalo 正常运行 |
| 2026-09-11 | 修复 02 两处引擎 bug（zh:null 重试、英文侧离线兜底）→ 6279/6279 全汉化；overrides 补 3 条图标占位词条；04 重验证通过；源码推送至 guoxpeng/zalo-cn 分支 pc-zh |
