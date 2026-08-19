# Zalo 汉化 v1.0 更新日志

模块包名：`com.zalocn`（应用名：Zalo 汉化）

**首个公开版本**

- 内置 **11996 条翻译**，覆盖 Zalo 资源表中 99.3% 的可翻译界面文本：
  - 设置、隐私、账户与安全、通知、我的文档等页面 100% 覆盖
  - 聊天"+"附件菜单（发送定位、发送文件、相册、拍摄、投票、提醒等）全覆盖
  - 安全检查等 **ZInstant**（服务器下发 UI）页面文本
- 多种 Hook 路径兜底：
  - `Resources.getText / getString / getQuantityText`（标准资源路径）
  - `TextView.setText` 单参 + 双参（覆盖 Zalo 自定义 zdesign 组件）
  - `TextView.setHint`（输入框提示）
  - `ZOMText / ZOMText2`（ZInstant 服务器文本）
- 内置 **原文→中文全量映射**（en 9283 + vi 9189），绕过资源系统的文本也能翻译
- 纯 UI 层翻译：不触碰网络请求、账号数据、登录态与加密逻辑
