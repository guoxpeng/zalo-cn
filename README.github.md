# Zalo PC 汉化（中文 / English 双语切换）

> 把 Windows 版 Zalo（Electron 应用）的越南语界面整体换成简体中文的小工具。
> 与 [Zalo 安卓版汉化](https://github.com/guoxpeng/zalo-cn/tree/main)（Xposed 模块，见 `main` 分支）出自同一个项目，原则也一样：**只改界面文字，不碰登录、账号、加密和网络逻辑。**

## 效果

- 启动即为简体中文界面（默认语言槽位已换成中文）；
- 设置 → 语言里可在 **中文 / English** 之间随时切换，互不影响；
- 词库 **6279 条全部汉化**（含登录页、设置、聊天、群组、云盘等），另覆盖 4 个打包文件
  与 preload 脚本里 243 组内嵌双语文案；
- 原生模块、登录凭证、聊天数据一概不碰（打包时自动校验 65 个原生文件字节级一致）。

## 适配版本

实测 **Zalo PC 26.8.20**（安装目录 `…\Programs\Zalo\Zalo-26.8.20`）。
其他 26.x 版本大概率可用：重新解包新版的 `app.asar`，按下面四步重跑即可
（词库文件名带哈希，脚本会按 `lang-en.*` / `lang-vi.*` 前缀自动探测）。

## 使用（重新生成汉化包）

需要：Python 3.8+、Node.js（npx，用于 @electron/asar）。全程不需要 API key。

```bash
# 0) 准备：从官方安装目录解包 app.asar 到 extract/，
#    并把 pc-dist/lazy/lang-en.*.js 与 lang-vi.*.js 两个文件拷到 raw/
npx --yes @electron/asar extract "…\Zalo-26.8.20\resources\app.asar" ../extract

# 1) 解析词库与内嵌双语对象
python 01_parse.py

# 2) 翻译（优先复用 seed/ 精校词库与 trans_cache.json 缓存，缺的才联网）
python 02_translate.py          # 默认 Google gtx；额度受限：ZALOPC_ENGINE=mm 或 =bing

# 3) 生成打了补丁的完整文件树 build_app/
python 03_apply.py

# 4) 重打包 + 校验（产出 dist/app_zh.asar；原生文件必须 65/65 字节级一致）
ZALOPC_INSTALL="C:/Users/laogu/AppData/Local/Programs/Zalo/Zalo-26.8.20/resources" python 04_pack.py
```

安装：关闭 Zalo → 备份 `resources\app.asar` → 用 `dist/app_zh.asar` 覆盖 → 重新打开 Zalo。

**回退**：把备份的原版 `app.asar` 复制回去即可，恢复如初。

> 如果新版 Zalo 的语言包文件名变了，把 `03_apply.py` 顶部的 `CHUNK_FILE`
> 和 `INLINE_FILES` 里的哈希文件名换成新版实际文件名即可（01 会打印探测到的文件名）。

## 手工修正词条

机翻总有几句不顺眼。改 `overrides.json`（按越南语原文 → 中文），
重跑 `02 → 03 → 04` 即可生效，不用重新翻译其他词条。

## 已知限制

- 服务器下发的动态文案（个别系统弹窗）不在本地词库，无法静态汉化；
- 语言菜单里「中文」旁的图标仍是越南国旗（改图标要动渲染代码，为了安全底线不做）；
- Zalo 自动更新会覆盖 `app.asar`，更新后需重跑上面四步（翻译缓存可复用，几分钟搞定）。

## 安全说明

- 汉化只替换 `app.asar` 里的语言包字符串数据；不修改任何 JS 逻辑、网络代码、登录流程；
- `app.asar.unpacked`（原生 .node 模块）在打包脚本里强制保持原样并逐字节校验；
- 不需要登录 Zalo 账号做任何授权，不收集任何数据，翻译缓存全部保存在本地。

## 许可

MIT（见 LICENSE）。Zalo 是 Zalo Group 的商标，本工具与之无关。
