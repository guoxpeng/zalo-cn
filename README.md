# Zalo中文增强模块（com.zalocn）

> Zalo 界面汉化 + 聊天消息实时翻译的 Xposed 模块。界面越南语/英文一键中文化，越南语消息自动译成中文，输入中文点「译」即可翻成越南语。

[![Build APK](https://github.com/guoxpeng/zalo-cn/actions/workflows/build.yml/badge.svg)](https://github.com/guoxpeng/zalo-cn/actions/workflows/build.yml)

**当前版本：v3.0.11**（完整变更见 [RELEASE_NOTES_v3.0.11.md](RELEASE_NOTES_v3.0.11.md)）

## 功能

- **界面汉化**：12697 条离线词表，设置/聊天/群组/通知/我的文档等全覆盖；跟随 Zalo 内建语言开关，越南语模式不介入。
- **消息翻译**：收到的消息自动翻译，原文在上、译文在下，独立双行显示；11 种目标语言可选（中/英/泰/老/柬/缅/马来/印尼/日/韩/越）。点任意消息可强制重翻。
- **输入框「译」按钮**：中文翻成选定语言后填回输入框，检查后再发送，不自动发。
- **翻译引擎**：内置免费引擎（Google/Bing/MyMemory），也支持自定义 OpenAI 兼容大模型 API；设置页可测试 API 连通性。
- **提示词模板**：每个大模型模板独立保存 API 数据，提示词全局共用。
- **设置面板**：分区卡片、跟随系统暗色模式、改动自动保存。

## 安装

1. 从 [Releases](https://github.com/guoxpeng/zalo-cn/releases) 下载最新 APK 安装（包名 `com.zalocn`）。
2. LSPosed / Vector → 启用模块 → 作用域勾选 Zalo（`com.zing.zalo`）。
3. 强制停止 Zalo 再重开（模块只在进程新建时注入）。

升级覆盖安装即可（同签名）。

## 构建

GitHub Actions 已配置：**每次 push 到 main 都会自动构建并上传 APK 产物**（Actions 页面下载，保留 30 天）；push `v*` tag 会自动发布 Release 附带 APK。

本地构建：JDK 21 + Android SDK（platforms;android-34、build-tools;35.0.0），`bash build/build_module.sh`，签名证书与口令从环境变量传入。

## 已知限制

- Zalo 原生「更改语言」只支持越↔英；模块自己的接收翻译目标语言走大模型 API，不受此限制。
- 钱包 H5 页面（交易记录、Card）为服务端网页，模块汉化不涉及。
- 日志中 `OnScrollListener not found`、`XResources not present` 为非致命告警，不影响使用。

## 协议

MIT。与 Zalo（VNG Corporation）无关。
