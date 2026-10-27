# Zalo 汉化（com.zalocn）

> 把 Zalo 界面从**越南语 / 英文**翻译成**简体中文**的 Xposed 模块。
> 在越南打拼的中国朋友，装上它，界面就顺眼多了。

[![Build APK](https://github.com/guoxpeng/zalo-cn/actions/workflows/build.yml/badge.svg)](https://github.com/guoxpeng/zalo-cn/actions/workflows/build.yml)

> PC 端汉化在同一个仓库的 **`pc-zh` 分支**，切分支查阅。

---

## 一句话原理

Zalo 的界面文本几乎全部来自资源表（`resources.arsc`），读取路径统一经过
`Resources.getText(int)` / `getQuantityText(int,int)`。模块 hook 这几个方法，
按资源 ID 惰性解析出资源名（如 `str_setting_privacy`），命中翻译表就直接返回中文。
服务器下发的动态 UI（ZInstant）和写死在代码里的字符串，则走 `TextView.setText`
与 ZOM 节点文本替换。

**纯显示层替换**：不改 Zalo 本体、不碰网络请求、不读写账号数据、模块自身不联网。

---

## 特性

| | |
|---|---|
| **翻译量** | 12637 条资源字符串（Zalo 资源表 12351 条可译项 **100% 覆盖**）+ 10200 条源码/服务端文本映射 |
| **双语识别** | **越南语 → 保持越南语**；**英文 → 中文**。模块自动跟随 Zalo 内建的语言开关 |
| **服务器文本** | 支持 ZInstant 动态 UI（安全检查页、小程序分类等）的节点文本替换 |
| **离线可用** | 词表打包在 APK assets 内，运行时不联网 |
| **体积** | 约 470 KB |

### 语言行为说明

Zalo 自带「语言」设置（Tiếng Việt / English）。模块的判断逻辑：

- 界面语言 = **Tiếng Việt** → 模块**完全不介入**，界面保持越南语原文
- 界面语言 = **English** → 全部替换为简体中文
- 判断依据是 `Configuration.getLocales()` 的首选语言，只在读取 Zalo 自身资源
  （资源 ID ≥ `0x7f000000`）时更新，避免被系统资源刷掉

---

## 支持版本

- 实测 **Zalo 26.10.01（26.10.1）**，Android 14 真机验证
- hook 的是系统层方法，不依赖具体 Zalo 版本号，26.x 新老版本大概率通用
- 系统要求：**Android 8.1+**，需 Root + **LSPosed / Vector**

---

## 安装

1. 到 [Releases](../../releases) 下载最新的 `zalocn_hanhua.apk` 并安装（包名 `com.zalocn`，应用名「Zalo 汉化」）
2. 打开 LSPosed / Vector Manager → 启用模块 → **作用域勾选 Zalo（`com.zing.zalo`）**
   （这步漏了不生效）
3. **强制停止 Zalo 再重开**（模块只在进程新建时注入）

> 升级模块时用覆盖安装即可，签名固定不变，LSPosed 里的作用域配置不会丢。

---

## 从源码构建

### 依赖

- **JDK 21**（8 以上均可，CI 用 21）
- **Android SDK**：`platforms;android-34` + `build-tools;35.0.0`
  > ⚠️ 别用 build-tools 34：它的 `d8` 有 R8 bug（`MethodParameters` 空名触发 NPE），
  > 编译本模块会直接崩，35 及以上正常
- **python3**（只用来打 zip）

### 本地构建

```bash
# SDK 路径：设 ANDROID_SDK_ROOT，或让脚本自动探测常见安装位置
export ANDROID_SDK_ROOT=/path/to/android-sdk

bash build/build_module.sh
# 产物：./zalocn_hanhua.apk
```

指定版本号：

```bash
VERSION_CODE=18 VERSION_NAME=2.7 bash build/build_module.sh
```

Windows（Git Bash）同样可用，脚本会自动补 `.exe` / `.bat` 后缀。

### 构建流程（脚本做的事）

```
javac  →  d8  →  aapt2 link  →  拼装 dex+assets  →  zip  →  zipalign  →  apksigner
```

1. 把 `AndroidManifest.xml` 的版本号替换成传入值
2. `javac` 编译 `app/src/main/java/**`（`de.robv.*` 只是编译期桩类）
3. `d8` 只把 `com/zalocn/**` 打成 `classes2.dex`
4. `aapt2 link` 由源码 manifest 生成基础 APK
5. 塞入 `classes.dex`（空壳，Xposed 要求存在）、`classes2.dex`、
   `assets/translations.json`、`assets/src_map.json`、`assets/xposed_init`
6. `resources.arsc` 不压缩打包 → `zipalign -p 4` → `apksigner` 签名（v1+v2+v3）

### GitHub Actions 自动打包

`.github/workflows/build.yml` 已配置好，触发条件：

| 触发 | 行为 |
|---|---|
| push 到 `main` / PR | 构建并在 Actions 页面提供 artifact（保留 30 天） |
| push tag `v2.6.1` 这类标签 | 构建 + 自动发布 Release，附带 APK 与 `RELEASE_NOTES_v2.6.1.md` |
| 手动 `workflow_dispatch` | 构建并出 artifact |

发新版：

```bash
git tag v2.6.1 && git push origin v2.6.1
```

### 两个仓库的分工与发布约定

本模块同时发布在两个仓库，**Release 约定不一样**：

| 仓库 | 用途 | Release tag | 附件名 | 发布方式 |
|---|---|---|---|---|
| `guoxpeng/zalo-cn` | 源码主仓、面向用户 | `v2.6.1` | `zalocn_hanhua.apk` | CI 打 tag 自动发 |
| `Xposed-Modules-Repo/com.zalocn` | LSPosed 模块列表收录 | `17-2.6.1`（`versionCode-versionName`） | `Zalo-cn_v2.6.1.apk` | **该仓库未启用 Actions，需手动上传** |

> 第二个仓库的 tag 必须是 `<versionCode>-<versionName>`，模块管理器靠它比对版本、
> 判断要不要提示更新。附件名也保持 `Zalo-cn_v<versionName>.apk` 的历史命名。

> **注意**：第二个仓库的 `README.md` 是**独立的镜像说明**（首屏引导用户回源码仓库，
> 并说明「同步后才更新版本」），**不要**用本仓库的 README 覆盖它。
> 同步源码时记得排除 `README.md`。

一次性把两个仓库都发好：

```bash
bash build/publish.sh
# 或复用已构建好的 APK：
SKIP_BUILD=1 bash build/publish.sh
```

脚本会自动读 `AndroidManifest.xml` 的版本号、按上面的约定创建（或复用）Release、
删掉同名旧附件再上传，令牌默认读 `~/.workbuddy-ai/secrets/github_token`。

---

## 项目结构

```
.
├── app/src/main/
│   ├── AndroidManifest.xml          # Xposed 元数据（xposedmodule / xposedscope）
│   └── java/
│       ├── com/zalocn/MainHook.java  # 模块全部逻辑（资源层 + 文本层 + ZInstant）
│       └── de/robv/android/xposed/   # Xposed API 桩类（仅编译用，不进 dex）
├── data/
│   ├── translations.json             # 资源名 -> 中文（12637 条）
│   └── src_map_compact.json          # 英文原文 -> 中文（10200 条）
├── build/
│   ├── build_module.sh               # 一键构建脚本（跨平台）
│   ├── publish.sh                    # 一键发布到两个仓库的 Release
│   ├── classes_dex_empty.dex         # 空壳主 dex
│   └── zalocn.keystore               # 固定签名证书（见下方说明）
├── .github/workflows/build.yml       # CI：自动打包 + Release
└── RELEASE_NOTES_v*.md               # 各版本更新说明
```

### 翻译数据格式

`data/translations.json` —— 键是 Zalo 的资源名，值是中文：

```json
{
  "label_tab_message": "消息",
  "str_setting_privacy": "隐私",
  "str_noti_N_character": "N"
}
```

`data/src_map_compact.json` —— 键是英文原文（用于写死文本 / 服务器下发文本）：

```json
{
  "en": { "My Documents": "我的文档", "Timeline": "动态" },
  "vi": {}
}
```

> `vi` 表固定留空：越南语模式下模块整体不翻译，不需要越南语词表。

### 翻译约定（改词表前请先读）

1. **单字符 / 纯符号 / 纯格式串一律不翻译**。像 `str_noti_N_character`（值就是 `N`）
   这类角标用图标字体渲染，塞汉字会显示成随机字形（曾出现「N」变成「氮」的严重 bug）。
2. **占位符必须保留**：`%1$s` `%d` `{0}` 原样带过来；`%2$s` 这类英文复数后缀在中文里要删掉。
3. **一词多义要按语境定**，别按字面直译。踩过的坑：
   `Crop`≠庄稼（是裁剪）、`Feed`≠饲料（是动态）、`Live`≠居住（是直播）、
   `Call`≠称呼（是通话）、`Quit`≠辞职（是退出）、`Auto`≠汽车（是自动）、
   `Active Code`≠活动代码（是激活码）、`Event`≠活动（日历里是日程）。
4. **品牌名不译**：Zalo、zBusiness、zCloud、zStyle、GIF、GPS、PDF、OA（译作公众号）等。
5. **URL / 包名 / 语言码不译**：`file:///...`、`com.zing.zalo.*`、`en` 这类必须保持原样。

---

## 签名说明

`build/zalocn.keystore` **有意入库**：

- 这是本项目的固定自签名证书（别名 `zalocn`，口令 `zalocn123`，已写在构建脚本里）
- 目的是让 CI 打出的 APK 与历史版本签名一致，用户可以直接覆盖升级，
  不必卸载重装（卸载会丢 LSPosed 作用域配置）
- 证书指纹：`SHA-256 41:8b:44:25:d2:18:1c:00:a3:bf:f1:3c:dd:8d:97:bd:74:23:19:16:4e:eb:6c:dd:a8:71:ab:bd:09:8e:db:2e`
- 它**只用于本项目自签名分发**，不要复用到任何其他项目；
  也请知悉：证书公开意味着任何人都能签出「同名可覆盖」的包，
  只从本仓库 Releases 下载，或自行从源码构建

---

## 已知限制

- **钱包里的 Activity（交易记录）、Card（卡）页面仍是英文**：这两个页面不是原生界面，
  而是 Zalo 服务器下发的 H5 网页（内容与排版全由服务端渲染），常规资源汉化碰不到。
  要翻得往模块里塞一套网页翻译引擎 + 上万条网页词库，体积翻倍且需在 Zalo 的网页环境
  注入脚本执行，违背「只改显示、不碰执行逻辑」的原则，故不做。
- **少数写死在自定义控件里的文本**（如聊天列表里的「My Documents」会话名）可能漏翻，
  这类文本不走 `TextView.setText`，需要单独定位控件。遇到请提 Issue 附截图。

---

## 常见问题

**装了没效果？**
九成是作用域没勾 Zalo，或改完没强制停止 Zalo。重启手机基本能解决。
排查可看 LSPosed 日志里有没有 `[ZALO-ZH] module loaded, translations: 12637`。

**切到越南语后界面还有中文？**
不应出现。若出现，看日志里是否有 `[ZALO-ZH] vi mode detected -> translation disabled`；
没有的话说明语言判断没生效，请提 Issue 附机型与 Zalo 版本。

**会封号吗？**
风险极低但不是零 —— 模块只改显示文本，不发修改请求、不碰服务器通信，
跟改协议的外挂不是一回事。介意就用小号先试几天。

**影响登录、收消息吗？**
不影响，它只是「换皮」。

**有页面还是英文？**
把页面名或截图发到 Issue，针对性补翻译。

---

## 免责声明

- 仅供个人学习交流，请勿用于商业用途；使用修改版客户端可能违反 Zalo 服务条款，**风险自负**
- 本项目与 Zalo（VNG Corporation）无任何关联
- 作者不对使用本模块造成的任何直接或间接损失负责

## 开源协议

[MIT](LICENSE) —— 放心用，放心改。

---

有问题欢迎提 Issue。祝在越南的朋友们用得顺心 🙂
