# Zalo PC 翻译注入 v1.0.1 — 安装说明

## 这是什么
给 Zalo PC（26.11.12）加的翻译注入，和安卓模块一样的功能：
- **听**：收到的消息自动翻成中文（原文在上、译文在下框）
- **说**：输入框边上加「译」按钮，中文翻成越南语填回输入框，不自动发

翻译走 bilibili 公开 API（免密钥），失败回落 Google。不改 Zalo 逻辑、不自动发消息。

**v1.0.1 修正**：v1.0.0 只注入了迷你窗口的 compact-app.html，主窗口（加载 index.html）里根本不加载脚本，所以按钮和自动翻译都不出现。v1.0.1 同时注入 index.html 与 compact-app.html，并给 index.html 的 CSP 放行 bilibili API。

## 文件
- `zalocn-translate.js` — 注入脚本（不要单独改它）
- `install-translate.ps1` — 安装器
- `restore-translate.ps1` — 一键恢复

## 安装（在你电脑上）
1. 把这三个文件放进同一个文件夹。
2. 关掉 Zalo（任务栏右键退出）。
3. 右键 `install-translate.ps1` → 使用 PowerShell 运行。
   脚本会：备份原版 app.asar → 解包 → 加注入脚本 → 重打包 → 替换 → 重启 Zalo。
4. 打开一个越南人会话：收到的消息自动翻成中文；输入框边上有「译」按钮。

## 恢复
出问题跑 `restore-translate.ps1`，一键恢复成打补丁前的汉化版。

## 注意
- v1 原型，第一次实机测试。译文位置、按钮位置、文字提取如果不对，把现象（截图/描述）发我，我再调。
- 翻译默认：收 → 中文，译按钮 → 越南语。改目标语言目前要改 `zalocn-translate.js` 里的 DEFAULTS（incomingTarget / outgoingTarget），后续版本再做设置界面。
- Zalo 自动更新会覆盖 app.asar，更新后重跑一次安装器即可（备份机制会先恢复再重打，不会重复注入）。已接进 `auto_patch.py` 自动重打的，更新后守护会自动恢复，不用手动重跑。
