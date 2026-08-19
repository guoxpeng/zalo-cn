# Zalo 汉化 v1.6 更新日志

模块包名：`com.zalocn`（应用名：Zalo 汉化）

**v1.6（2026-08-19）**

- **修复 v1.5 回归**：`Popular keywords`、`Suggested OAs`、`Explore by categories` 重新汉化（上次构建重新生成映射时被冲掉，现已固化到构建流程，不再丢失）
- **新增**：`Recommended Official Accounts` → **推荐公众号**
- **修正**：`Newsfeed` → **动态**（原译"信息流"不准确）
- 服务器固定词全部固化到 `add_common_words.py`（热门关键词 / 推荐公众号 / 按分类探索 / 热门小程序 / 动态 / 你今天过得怎么样？等，含越南语对应）
- 原文→中文映射：**en 9494 → 9509，vi 9356 → 9360**
- 兼容 Zalo 26.08.x（26.8.1 / 26.8.2）

---

**v1.5**

- Popular Mini Apps → 热门小程序；How are you today? → 你今天过得怎么样？

**v1.4**

- Popular keywords → 热门关键词、Suggested OAs → 推荐公众号、Explore by categories → 按分类探索、Shopping → 购物

**v1.3**

- 补齐 100+ 小程序分类词与通用 UI 词；Utilities → 工具

**v1.2**

- 修复日记（Timeline）页 169 条带 HTML 标签/占位符文本

**v1.1**

- 真机实测 Zalo 26.08.01（26.8.1）运行稳定

**v1.0（首版）**

- 11996 条翻译 + 原文映射 + Hook 全家桶
