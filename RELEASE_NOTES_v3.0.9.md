# v3.0.9 —— 修「界面汉化只生效一半」

发布日期：2026-11-27
适用：Zalo 26.11.02（实测），Android 16 真机

---

## 这个版本修了什么

有朋友反馈：**界面一半中文一半英文**——搜索页 `My assets`、我的文档页 `All / Photos / Files`、
输入框提示 `Message` 全是英文，但聊天里发消息又能翻译。

查下来不是词表不够，是模块自己的一个低级错误：

> 模块里有一份「编译期桩」`de/robv/android/xposed/XposedBridge.java`，
> 里面把 `hookMethod` 的参数类型写成了 `Method`，
> 而真实的 Xposed API（api-82 / LSPosed / Vector）是 `hookMethod(Member, XC_MethodHook)`。

参数类型不同 → 编译出来的调用点描述符对不上 → 运行时抛
`NoSuchMethodError: No static method hookMethod(...)`，又被兜底逻辑静默吞掉。
结果就是：**所有直接调用 `hookMethod` 的 hook 全部失效**，包括

- `Resources.getText(int)` / `getQuantityText(int,int)` / `getString(int)` ← 资源表汉化的主通道
- `ChatRow.setMessage` ← 聊天消息翻译
- `Application.attachBaseContext`

而走 `XposedHelpers.findAndHookMethod` 的那批（`TextView.setText`、`setHint`、ZInstant、长按菜单）
一直是好的——所以就出现了「一半中文一半英文、聊天却能翻」的怪现象。

## 修法

1. 桩改成 `hookMethod(Member, ...)`，与真实 API 完全一致；
2. `HookUtil` 增加失败日志（打印 modifiers / classloader / 异常），并在直连失败时用
   `findAndHookMethod` 兜底（varargs 要展开成 `Object[]{Class..., XC_MethodHook}`，
   直接传 `Class[]` 会被当成一个参数，报 "parameter type must either be specified as Class or String"）；
3. 构建脚本 `build/build_module.sh` 的 `tool()` 在 Git Bash 下用 `-x` 判断 `.bat` 会误判成缺失，
   改为 `-f` 兜底。

## 装完的效果

- 界面汉化恢复：搜索框「搜索」、输入框「发消息」、底部「消息」、聊天内「模块翻译 · 更改语言」等
  资源表里的 12697 条重新生效；
- 聊天消息翻译照常（之前能用是因为它走的是另一条路径）；
- 日志里不再出现 `hook skipped (method absent)`（除 Android 16 确实不存在的
  `Resources.getText(int, Theme)` 重载，属正常）。

## 仍然存在的非致命告警（不影响使用）

| 日志 | 说明 |
|---|---|
| `Resources.getText(int, Theme)` method really absent | Android 16 没有这个重载，其余三个已挂上 |
| `XResources not present` | LSPosed 资源钩子没开，走基础 Resources hook 即可 |
| `staticInt lkk.zds_ic_translator_line_32 fail` | 长按菜单翻译项的图标资源 ID 变了，功能在、图标可能缺 |
| `OnScrollListener not found, idle-scroll disabled` | 摸不到 `androidx...OnScrollListener`，只影响「滚动停下后补翻」 |

## 升级方式

- 与 3.0.8 同签名，**覆盖安装即可**，LSPosed 作用域配置不丢；
- 装完记得**强制停止 Zalo 再重开**（模块只在进程新建时注入）——这是最常见的「装了没反应」原因。

## 已知问题（下个版本处理）

- `data/src_map_compact.json` 的**越南语侧为空**（应为 9360 条），
  即 Zalo 界面切成 Tiếng Việt 时，服务器下发/写死的文本无法汉化。
  需要按 `zalozh_extract/add_common_words.py` 流程重新生成词表。
