# Zalo中文增强模块（com.zalocn）

> 把 Zalo 界面从**越南语 / 英文**翻译成**简体中文**的 Xposed 模块。
> 在越南打拼的中国朋友，装上它，界面就顺眼多了。

[![Build APK](https://github.com/guoxpeng/zalo-cn/actions/workflows/build.yml/badge.svg)](https://github.com/guoxpeng/zalo-cn/actions/workflows/build.yml)

> PC 端汉化在同一个仓库的 **`pc/` 目录**，见 `pc/README.md`。

---

## 🎉 v3.0.0 重磅更新：消息也能翻了

> 完整更新说明见 [RELEASE_NOTES_v3.0.0.md](RELEASE_NOTES_v3.0.0.md)。

这一版模块从「界面汉化工具」变成了「在越南用 Zalo 跟当地人沟通的中文助手」：

- **收到的消息实时翻译**：越南语自动翻成中文，原文在上、译文在下，11 种目标语言可选
- **输入框「译」按钮**：输中文点一下，翻成越南语（或你选的语言）填回输入框，检查完自己点发送
- **自定义大模型 API**：OpenAI 兼容接口，免密钥的公开 API 也能用（比如 bilibili 公开翻译接口）
- **模板系统**：每个大模型独立保存自己的 API 数据，拉下切换就换生效模型
- **免费引擎**：Google / Google 备用 / Bing / MyMemory，免密钥，选上直接用
- **测试当前 API**：点一下验证，成功显示「当前api配置能正常使用」，失败直接显示原因
  （连接失败 / API 地址有误 / 找不到模型 / API 错误）
- **设置页改版**：分区卡片，全部自动保存，不用再点保存按钮

界面汉化还是离线词表；消息翻译走你自己的 API 或免费引擎，需要联网。

---

## 一句话原理

Zalo 的界面文本几乎都来自资源表，模块在读取时按资源名换成中文；
服务器下发的动态界面和写死在代码里的文本，走 `TextView.setText` 替换。

**只改显示，不碰账号和网络请求**。界面汉化是离线词表，不联网；
**消息翻译**走你自己填的大模型 API 或免费翻译引擎（设置页的「测试当前 API」
在模块自己的进程里发请求，所以声明了联网权限）。

---

## 特性

| | |
|---|---|
| **翻译量** | 12697 条资源字符串（Zalo 26.11.01 实测 12658 条，词表覆盖 **99.3%**）+ 10239 条源码/服务端文本映射 |
| **消息翻译** | 收到的消息自动翻成你选的语言（11 种），原文在上、译文在下；输入框「译」按钮把中文翻好填回，不自动发 |
| **大模型 API** | 自定义 OpenAI 兼容接口；模板系统（每个模板独立保存 API 数据）；免密钥公开 API 也能用 |
| **免费引擎** | Google / Google 备用 / Bing / MyMemory，免密钥，选上直接用 |
| **双语识别** | **越南语 → 保持越南语**；**英文 → 中文**。模块自动跟随 Zalo 内建的语言开关 |
| **服务器文本** | 支持 ZInstant 动态 UI（安全检查页、小程序分类等）的节点文本替换 |
| **离线可用** | 界面汉化词表打包在 APK assets 内，运行时不联网；消息翻译需要联网（走你自己的 API） |
| **体积** | 约 510 KB |

### 语言行为说明

Zalo 自带「语言」设置（Tiếng Việt / English），模块跟它走：

- 界面语言 = **Tiếng Việt** → 模块**完全不介入**，界面保持越南语原文
- 界面语言 = **English** → 全部替换为简体中文

---

## 支持版本

- 实测 **Zalo 26.11.01**，Android 16 真机（联想 Y70 新一代）验证
- hook 的是系统层方法，不依赖具体 Zalo 版本号，26.x 新老版本大概率通用
- 系统要求：**Android 8.1+**，需 Root + **LSPosed / Vector**

---

## 安装

1. 到 [Releases](../../releases) 下载最新的 `Zalocn-E.apk` 并安装（包名 `com.zalocn`，应用名「Zalo中文增强模块」）
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
# 产物：./Zalocn-E.apk
```

指定版本号：

```bash
VERSION_CODE=33 VERSION_NAME=3.0.0 bash build/build_module.sh
```

Windows（Git Bash）同样可用，脚本会自动补 `.exe` / `.bat` 后缀。

### 构建流程

`javac → d8 → aapt2 link → 拼装 dex+assets → zip → zipalign → apksigner`，
版本号由传入值替换进 `AndroidManifest.xml`。

### GitHub Actions 自动打包

`.github/workflows/build.yml` 已配置好，触发条件：

| 触发 | 行为 |
|---|---|
| push 到 `main` / PR | 构建并在 Actions 页面提供 artifact（保留 30 天） |
| push tag `v3.0.0` 这类标签 | 构建 + 自动发布 Release，附带 APK 与 `RELEASE_NOTES_v3.0.0.md` |
| 手动 `workflow_dispatch` | 构建并出 artifact |

发新版：

```bash
git tag v3.0.0 && git push origin v3.0.0
```

### 两个仓库的分工与发布约定

本模块同时发布在两个仓库，**Release 约定不一样**：

| 仓库 | 用途 | Release tag | 附件名 | 发布方式 |
|---|---|---|---|---|
| `guoxpeng/zalo-cn` | 源码主仓、面向用户 | `v3.0.0` | `Zalocn-E.apk` | CI 打 tag 自动发 |
| `Xposed-Modules-Repo/com.zalocn` | LSPosed 模块列表收录 | `33-3.0.0`（`versionCode-versionName`） | `Zalocn-E_v3.0.0.apk` | **该仓库未启用 Actions，需手动上传** |

> 第二个仓库的 tag 必须是 `<versionCode>-<versionName>`，模块管理器靠它判断要不要提示更新。
> 它的 `README.md` 是独立的镜像说明，**不要**用本仓库的 README 覆盖它，同步源码时排除。

一次性把两个仓库都发好：

```bash
bash build/publish.sh
# 或复用已构建好的 APK：
SKIP_BUILD=1 bash build/publish.sh
```

脚本自动读版本号、创建（或复用）Release、删掉同名旧附件再上传，
令牌默认读 `~/.workbuddy-ai/secrets/github_token`。

---

## 项目结构

```
.
├── app/src/main/
│   ├── AndroidManifest.xml          # Xposed 元数据
│   └── java/
│       ├── com/zalocn/              # MainHook（汉化入口）、MessageHook（消息翻译）、
│       │                            # LlmClient（大模型）、FreeTranslateClient（免费引擎）、
│       │                            # TranslateConfig / TranslateStore / ModelProfile（配置、缓存、模板）、
│       │                            # TranslateSettingsActivity（设置页）
│       └── de/robv/android/xposed/   # Xposed API 桩类（仅编译用，不进 dex）
├── data/
│   ├── translations.json             # 资源名 -> 中文（12697 条）
│   └── src_map_compact.json          # 英文原文 -> 中文（10239 条）
├── build/
│   ├── build_module.sh               # 一键构建脚本（跨平台）
│   ├── publish.sh                    # 一键发布到两个仓库的 Release
│   └── classes_dex_empty.dex         # 空壳主 dex
│       # 签名证书不在仓库里：只存 ~/.config/zalocn/zalocn-release.keystore
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

1. **单字符 / 纯符号 / 纯格式串一律不翻译**（角标用图标字体渲染，塞汉字会显示成随机字形，曾出现「N」变成「氮」）。
2. **占位符必须保留**：`%1$s` `%d` `{0}` 原样带过来；英文复数后缀 `%2$s` 在中文里删掉。
3. **一词多义按语境定**，别按字面直译：`Crop` 是裁剪不是庄稼、`Feed` 是动态不是饲料、
   `Live` 是直播不是居住、`Call` 是通话不是称呼、`Quit` 是退出不是辞职、
   `Auto` 是自动不是汽车、`Active Code` 是激活码、`Event` 在日历里是日程。
4. **品牌名不译**：Zalo、zBusiness、zCloud、zStyle、GIF、GPS、PDF、OA（译作公众号）等。
5. **URL / 包名 / 语言码不译**：`file:///...`、`com.zing.zalo.*`、`en` 这类保持原样。

> `data/translations.json` 的键是 Zalo 资源名、值是中文；`data/src_map_compact.json`
> 的键是英文原文（用于写死文本 / 服务器下发文本），`vi` 表固定留空
> （越南语模式下模块整体不翻译）。

---

## 签名说明

旧证书已于 2026-11-21 作废（曾公开入库，任何人都能用它签出同名可覆盖安装的包，
已从仓库和分支历史里清除，不要再用）。

- 新证书只存仓库外（`~/.config/zalocn/zalocn-release.keystore`），口令不进仓库、不进脚本；
  CI 从 GitHub Secrets 读。指纹：`SHA-256 7F:CC:08:55:44:42:F3:BE:99:1E:14:73:6D:DC:FE:76:F4:32:E6:C1:8D:F1:E2:ED:AC:70:AC:21:D8:20:65:16`（RSA 4096）
- 新旧签名不兼容：装 2.7.0 测试版要先卸载旧版（卸载会丢 LSPosed 作用域配置，装完要重新勾 Zalo）
- 证书**只用于本项目自签名分发**，不要复用到其他项目；只从本仓库 Releases 下载，或自行从源码构建

---

## 已知限制

- **消息气泡上会显示「模块翻译」**：这是 Zalo 原生的翻译标签，p7g 里没有按消息
  区分来源的字段，改回去会反过来错，暂时保留。
- **Zalo 原生「更改语言」只支持越↔英互译**：服务端目前只支持这两对，
  模块自己做了接收翻译目标语言选择，走你自己的大模型 API，不受这个限制。
- **钱包里的 Activity（交易记录）、Card（卡）页面仍是英文**：这两个页面是 Zalo
  服务器下发的 H5 网页，常规资源汉化碰不到；要翻得塞一套网页翻译引擎 + 上万条
  网页词库，体积翻倍且违背「只改显示、不碰执行逻辑」的原则，故不做。
- **少数写死在自定义控件里的文本**（如聊天列表里的「My Documents」会话名）可能漏翻，
  这类文本不走 `TextView.setText`，需要单独定位控件。遇到请提 Issue 附截图。

---

## 常见问题

**装了没效果？**
九成是作用域没勾 Zalo，或改完没强制停止 Zalo。重启手机基本能解决。
排查可看 LSPosed 日志里有没有 `[ZALO-ZH] module loaded, translations: 12697`。

**消息不翻译？**
先看设置页：桌面图标「Zalo中文增强模块设置」→ 翻译开关里「信息自动翻译」
有没有打开；再看有没有填 API（或选了免费引擎）。点「测试当前 API」，
成功弹「当前api配置能正常使用」说明 API 没问题；失败会直接显示原因
（连接失败 / API 地址有误 / 找不到模型 / API 错误），按原因改就行。还不行的话，
确认 Zalo 是不是强停重开过（模块只在进程新建时注入，覆盖装 APK 后不重启 Zalo 跑的还是旧代码）。

**没有 API 密钥能用吗？**
能。模板下拉框里选 Google 翻译 / MyMemory 这类免费引擎，不用填密钥直接用；
或者用 bilibili 公开翻译接口（地址 `https://index-translate.bilibili.com/v1`，
模型 `Index-Translate-35B-A3B`），密钥框空着就行。

**「译」按钮翻成什么语言？**
看「‘译’按钮默认翻译」那一节，默认越南语，改成别的就翻成别的；
收到的消息走最上面「默认译文语言」，两边互不影响。

**测试按钮报「当前api配置有误」，但 API 明明能用？**
2.7.0 之前是 bug：设置页进程没声明联网权限，请求发不出去还一律说配置错。
2.7.0 已补权限，失败时会直接显示原因（连接失败 / API 地址有误 / 找不到模型 / API 错误）。

**切到越南语后界面还有中文？**
不应出现。若出现，看日志里是否有 `[ZALO-ZH] vi mode detected -> translation disabled`；
没有的话说明语言判断没生效，请提 Issue 附机型与 Zalo 版本。

**会封号吗？**
风险极低但不是零 —— 模块只改显示文本和调你自己的翻译 API，不发修改请求、
不碰服务器通信，跟改协议的外挂不是一回事。介意就用小号先试几天。

**影响登录、收消息吗？**
不影响，它只是「换皮」+ 翻译显示。

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
