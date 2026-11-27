
---

## 📌 2026-11-27 · 发布 v3.0.9（双仓库 + CI 签名）

| 项 | 结果 |
|---|---|
| 主仓库 `guoxpeng/zalo-cn` | main 已推到 `2d9d7fd`(安卓 3.0.9) + `e51b5e4`(PC v1.0.12)；tag `v3.0.9` |
| Release | CI（`.github/workflows/build.yml`）自动构建并发布：https://github.com/guoxpeng/zalo-cn/releases/tag/v3.0.9 ，产物 `Zalocn-E.apk`（521,254 B，versionCode 41） |
| org 仓库 `Xposed-Modules-Repo/com.zalocn` | main 已合并同步（README 保留镜像站推广版）；Release tag **`41-3.0.9`**，资产 `Zalocn-E_v3.0.9.apk`（同 CI 产物） |
| 签名 | **必须走 CI**：官方证书 `SHA-256 7fcc0855…`（CN=zalocn-zh）只在 GitHub Secrets（KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_PASSWORD / KEY_ALIAS），本机没有该 keystore。本机 `build/zalocn-v301.keystore` 是另一把（`9e6758…`），**不能用于发布**（会让老用户无法覆盖升级） |
| 校验 | `apksigner verify --print-certs` = 7fcc0855…；`aapt2 dump badging` = versionCode 41 / 3.0.9 |

**发布流程（以后照做）**：改版本号 → 提交 → `git push origin main` → `git tag vX.Y.Z && git push origin vX.Y.Z`（CI 出包+发 Release）→ 在 org 仓用 API 建 Release（tag 用 `{versionCode}-{versionName}`，资产名 `Zalocn-E_vX.Y.Z.apk`，上传 CI 下的 `Zalocn-E.apk`）。

**踩坑**：本地 clone 落后远端 80 个提交（PC 工作已在 main 的 `pc/` 目录里），推送前必须先 `git fetch && git rebase`；`build/.keystore-pass` 已加入 `.gitignore`，绝不入库。
