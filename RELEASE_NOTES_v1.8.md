# Zalo 汉化 v1.8

## 🎯 修复：钱包 Activity / Card 页面 H5 汉化真正生效

**v1.7 的 WebView 注入在钱包页没生效**，v1.8 找到根因并修复：

- 钱包的 Activity（交易记录）/ Card（卡）页面走的是 **Zalo 小程序容器**（`WebBaseView` + `ZWebView`），它**从不调用标准的 `setWebViewClient`**，页面加载完成走的是 Zalo 自定义的回调 `H7(url)`——v1.7 的两个标准 hook 全部落空。
- **v1.8 直接 hook Zalo 自己的回调 `WebBaseView.H7`**，页面加载完成后反射拿到 ZWebView 实例注入翻译脚本，再延迟 1.5 秒补一次覆盖 SPA 异步渲染内容。
- 原有的标准 WebView hook 保留（覆盖政策页等其他 H5）。

## 其他

- 翻译表 12165 条、原文映射 en 9509 / vi 9360、WebView 字典 18576 条（与 v1.7 一致）
- 模拟器实测：`WebBaseView.H7 hook installed`、登录页全中文、无崩溃

## 安装

覆盖安装后，**强制停止 Zalo 再重开**。进钱包 → Activity / Card，页面应显示中文了。
