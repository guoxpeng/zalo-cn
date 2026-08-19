# Zalo 汉化 v1.9

## 🧹 精简：移除 WebView H5 注入（钱包 Activity/Card 页面暂不汉化）

- 钱包的 Activity（交易记录）/ Card（卡）页面是 H5 网页，v1.7/v1.8 尝试的 WebView 注入方案实测无法生效（Zalo 小程序容器渲染机制特殊），**按用户要求移除该方案，保持模块代码简洁**
- 移除内容：setWebViewClient 包装、WebViewClient.onPageFinished、WebBaseView.H7 hook、web_inject.js 注入脚本（APK 从 1.08MB 回到 660KB）
- 模块回到稳定的核心功能：资源翻译 + 原文映射 + ZInstant + setText/setHint

## 其他

- 翻译表 12165 条、原文映射 en 9509 / vi 9360（不变）
- 模拟器实测：加载正常、登录页全中文、无崩溃
- 兼容 Zalo 26.08.x（26.8.1 / 26.8.2）

## 安装

覆盖安装后，**强制停止 Zalo 再重开**。
