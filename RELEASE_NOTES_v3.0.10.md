# v3.0.10 —— 修「越南语资源把消息翻译整体关掉」

发布日期：2026-11-27
适用：Zalo 26.11.02（实测），Android 16

---

## 背景

v3.0.9 修好了资源表汉化之后，有朋友反馈：**界面是中文了，但聊天里的消息译文时有时无**。
日志里能看到一行：

```
[ZALO-ZH] vi mode detected -> translation disabled
```

## 原因

模块里有个全局开关 `viMode`，用来实现「Zalo 界面切成越南语时本模块不介入」。
它原来在 `translate(id, res)` 里更新——只要拿到的资源 id ≥ `0x7f000000` 就认为「这是 Zalo 的资源」，
于是问一下当前 `Resources` 的 locale 是不是 `vi`。

问题在于：Zalo 进程里的**同一个 Resources 也能被 WebView、系统组件复用**，它们的 locale 可能是越南语。
一旦有这样一个资源进来，全局 `viMode` 就被打成 `true`，此后所有文案（包括聊天消息翻译）都被当成
「越南语模式」跳过——但界面汉化此时已经生效了，于是表现为「界面中文、消息不翻」。

## 修法

`translate(id, res)` 里再确认一次**资源归属的包名**：

```java
boolean fromZalo = "com.zing.zalo".equals(res.getResourcePackageName(id));
if (!fromZalo) return null;   // 别人的资源：既不翻，也不动全局语言标志
```

只有真正属于 Zalo 的字符串才允许改写 `viMode`。

## 装完的效果

- 界面汉化不变（资源表 12697 条照旧生效）；
- 聊天消息译文稳定出现，不再被 WebView 的越南语 locale 关掉；
- 日志里 `vi mode detected` 只在 Zalo 自身界面确实为越南语时才出现。

## 升级方式

覆盖安装即可（与 3.0.9 同签名），装完**强制停止 Zalo 再重开**。

## 仍存在的非致命告警

`Resources.getText(int, Theme)` 在 Android 16 不存在、`XResources not present`、
`OnScrollListener not found`（滚动停下补翻不可用）——都不影响正常使用。

## 已知问题（下个版本处理）

`data/src_map_compact.json` 的越南语侧仍为空（应为 9360 条），
把 Zalo 界面切成 Tiếng Việt 时，服务器下发/写死的文本无法汉化。
