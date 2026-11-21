# Zalo PC 翻译注入 v1.0.4 — 安装说明

## 这是什么
给 Zalo PC（26.11.12）加的翻译注入，和安卓模块一样的功能：
- **听**：收到的消息自动翻成中文（原文在上、译文在下框）
- **说**：输入框边上加「译」按钮，中文翻成越南语填回输入框，不自动发

翻译走 bilibili 公开 API（免密钥），失败回落 Google。不改 Zalo 逻辑、不自动发消息。

**v1.0.1 修正**：v1.0.0 只注入了迷你窗口的 compact-app.html，主窗口（加载 index.html）里根本不加载脚本，所以按钮和自动翻译都不出现。v1.0.1 同时注入 index.html 与 compact-app.html，并给 index.html 的 CSP 放行 bilibili API。

**v1.0.3 修正**：v1.0.2 的按钮可见、但点「译」没反应、自动翻译也不出、连“翻译失败”提示都不出现。最可能的原因是 fetch 挂起：bilibili/Google API 一直不回、也不报错，fetch 默认没有超时，翻译队列卡死、回调永远不跑、失败计数永远不到 3 次。v1.0.3：两个 API 请求都加 15 秒超时，超时当失败走兜底/重试；boot 各步独立容错（某一步抛错也不影响常驻巡检）；扫描到消息时记一条日志（便于开控制台确认选择器是否命中）。

**v1.0.4 根因修复**：v1.0.3 还是按钮可见、点「译」没反应、无自动翻译、无失败提示。深挖 bundle 源码找到根因：主窗口正常消息走 Frame 布局，容器是 `[data-component="message-content-view"][data-qId]`（id=`message-frame_*`），根本没有 `.chat-item` 类——`chat-item` 只用于非 Frame/非 Custom 布局的消息。旧选择器 `.chat-item.to-me` 一条消息都找不到，所以无自动翻译、无失败提示。v1.0.4 改用 `[data-qId]` 选全部消息容器，按 `me` / `not-me` 类判断收发（Frame 版自己消息带 `me` 类，对方消息没有）。输入框改用 Zalo 自用的 `.zcinput-root` 里的 contenteditable；按钮点击各失败分支给明确提示（找不到输入框 / 先输入文字 / 翻译失败 / 填回失败），不再静默。

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
