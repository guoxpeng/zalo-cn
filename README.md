# Zalo PC 汉化（中文 / English 双语切换）

> 把 Windows 版 Zalo（Electron 应用）的越南语界面整体换成简体中文的小工具。
> 与 [Zalo 安卓版汉化](https://github.com/guoxpeng/zalo-cn/tree/main)（Xposed 模块，见 `main` 分支）出自同一个项目，原则也一样：**只改界面文字，不碰登录、账号、加密和网络逻辑。**

## 效果

- 启动即为简体中文界面（默认语言槽位已换成中文）；
- 设置 → 语言里可在 **中文 / English** 之间随时切换，互不影响；
- 词库 **6475 条全部汉化**（含登录页、设置、聊天、群组、云盘等），另覆盖 7 个文件里
  1267 处内嵌双语文案；
- zCloud 推广弹窗强制走本地中文，不受服务端远端配置覆盖（`05_force_promo_zh.py`）；
- 原生模块、登录凭证、聊天数据一概不碰（打包时自动校验 70 个原生文件字节级一致）。

## 翻译功能（听 / 说）

在汉化基础上再加实时翻译，和安卓模块一样（见 `work/translate/`）：

- **听**：收到的消息自动翻成中文（原文在上、译文在下框）；
- **说**：输入框边上加「译」按钮，中文翻成越南语填回输入框，不自动发；
- 翻译走 bilibili 公开 API（免密钥），失败回落 Google；不改 Zalo 逻辑、不自动发消息。

安装：把 `work/translate/` 里的三个文件放同一文件夹，关掉 Zalo，右键 `install-translate.ps1` 用 PowerShell 运行（自动备份、打补丁、重启 Zalo）。出问题跑 `restore-translate.ps1` 一键恢复。Zalo 自动更新后重跑一次即可。

## 适配版本

- **当前支持：Zalo PC 26.11.12**（2026-11-17 实测，6475 词全汉化，正常运行）。
- 历史支持：26.10.20、26.9.10、26.8.20。
- **其他 26.x 版本：装好开机守护后无需手动适配**——`auto_patch.py` 会自动解包新版、
  按前缀/内容特征探测所有带哈希的文件，重打并替换（见下方「自动更新」）。

## 使用（重新生成汉化包）

需要：Python 3.8+、Node.js（npx，用于 @electron/asar）。全程不需要 API key。

```bash
# 0) 准备：从官方安装目录解包 app.asar 到 extract/，
#    并把 pc-dist/lazy/lang-en.*.js 与 lang-vi.*.js 两个文件拷到 raw/
npx --yes @electron/asar extract "…\Zalo-26.11.12\resources\app.asar" ../extract

# 1) 解析词库与内嵌双语对象
python work/01_parse.py

# 2) 翻译（优先复用 work/seed/ 精校词库与 trans_cache.json 缓存，缺的才联网）
cd work && python 02_translate.py   # 默认 Google gtx；额度受限：ZALOPC_ENGINE=mm 或 =bing

# 3) 生成打了补丁的完整文件树 build_app/
python 03_apply.py

# 3.5) 强制 zCloud 推广弹窗走本地中文（去掉服务端远端覆盖）
python 05_force_promo_zh.py

# 4) 重打包 + 校验（产出 dist_v2/app_zh.asar；原生文件必须 70/70 字节级一致）
ZALOPC_INSTALL="C:/Users/laogu/AppData/Local/Programs/Zalo/Zalo-26.11.12/resources" python 04_pack.py
```

安装：关闭 Zalo → 备份 `resources\app.asar` → 用 `work/dist_v2/app_zh.asar` 覆盖 → 重新打开 Zalo。

**回退**：把备份的原版 `app.asar`（`resources\app.asar.bak.<版本>_<时间>`）复制回 `resources\app.asar` 即可。

## 自动更新（推荐，已启用）

Zalo 会自动更新并新建版本目录，导致旧汉化包失效。已配置开机守护全自动处理：

- `work/auto_patch.py`：检测最新版本是否已汉化（读 asar 里的 `text:"中文"`/`text:"Tiếng Việt"` 标记），
  未汉化则自动重打全流程、等 Zalo 退出后备份替换、同步 SHA1、重启；
- `watch_zh.vbs`：每 10 分钟静默检查一次（隐藏窗口）；
- 开机自启项：`%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\Zalo汉化守护.vbs`。

日常无需任何操作。需要时手动排查：

```bash
cd work
python auto_patch.py --check   # 查看版本/汉化状态/Zalo 是否运行
python auto_patch.py           # 立即检测并按需重打安装
python auto_patch.py --force   # 强制重打
```

日志见 `work/auto_patch.log`，状态见 `work/auto_patch_state.json`。
脚本已去除所有写死的哈希文件名与安装路径，新版 Zalo 无需改代码。

> 迁移电脑 / 改了项目路径：编辑 Startup 里的 `Zalo汉化守护.vbs`，把里面的
> `target = "D:\project\zalo\zalopc\watch_zh.vbs"` 改成新路径即可。

## 手工修正词条

机翻总有几句不顺眼。改 `work/overrides.json`（按越南语原文 → 中文），
重跑 `02 → 03 → 04` 即可生效，不用重新翻译其他词条。

## 目录说明

```
├── README.md / PC_STATE.md   使用说明 / 支线交接文档（接手必读）
├── work/                     四步流水线脚本 + 全部翻译数据（缓存可复用）
│   ├── 01_parse.py … 05_force_promo_zh.py
│   ├── seed/src_map_compact.json   安卓版精校词库（种子翻译）
│   ├── trans_cache.json / zh_dict.json / zh_text.json
│   ├── overrides.json        人工修正词条
│   └── bing/                 MyMemory/Google 额度受限时的备用翻译引擎
├── raw/                      官方语言包 chunk 原件（26.11.12）
├── extract/  build_app/  backup/  work/dist_v2/   本地生成/备份，不入库（见 .gitignore）
```

## 已知限制

- 服务器下发的动态文案（个别系统弹窗）不在本地词库，无法静态汉化；
- 语言菜单里「中文」旁的图标仍是越南国旗（改图标要动渲染代码，为了安全底线不做）；
- Zalo 自动更新会覆盖 `app.asar`——已由开机守护自动重打解决（见上节），无需手动重跑。

## 安全说明

- 汉化只替换 `app.asar` 里的语言包字符串数据；不修改任何 JS 逻辑、网络代码、登录流程；
- `app.asar.unpacked`（原生 .node 模块）在打包脚本里强制保持原样并逐字节校验；
- 不需要登录 Zalo 账号做任何授权，不收集任何数据，翻译缓存全部保存在本地。

## 许可

MIT（见 LICENSE）。Zalo 是 Zalo Group 的商标，本工具与之无关。
