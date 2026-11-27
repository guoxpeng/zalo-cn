# 发布流程与签名约束

> 面向维护者。项目完整的交接文档在开发机 `D:/project/zalo/PROJECT_STATE.md`（不入库），
> 这里只留**发布这一步**的操作说明，方便对照执行。

## 一、签名约束（先看这条）

- 官方证书：`CN=zalocn-zh`，`SHA-256 7F:CC:08:55:44:42:F3:BE:99:1E:14:73:6D:DC:FE:76:F4:32:E6:C1:8D:F1:E2:ED:AC:70:AC:21:D8:20:65:16`（RSA 4096）
- **只存在 GitHub Secrets**：`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_PASSWORD` / `KEY_ALIAS`
- 本机 `build/zalocn-v301.keystore` 是**另一把**（`9E:67:58…`），**不能用于发布**：
  签名一变，老用户就装不上（Android 拒绝覆盖不同签名的同包名应用），只能卸载重装、丢配置
- 因此：**APK 一律由 CI 构建**，不要在本地签名后上传

## 二、v3.0.9 的实际发布结果（2026-11-27）

| 项 | 结果 |
|---|---|
| 主仓库 `guoxpeng/zalo-cn` | main 含安卓 3.0.9 源码 + 词表 + `pc/`（PC 翻译注入 v1.0.12）；tag `v3.0.9` |
| 主仓库 Release | CI 自动构建并发布：<https://github.com/guoxpeng/zalo-cn/releases/tag/v3.0.9> ，产物 `Zalocn-E.apk`（521,254 B，versionCode 41） |
| org 仓库 `Xposed-Modules-Repo/com.zalocn` | main 已同步（README 保留镜像站推广版）；Release tag **`41-3.0.9`**，资产 `Zalocn-E_v3.0.9.apk`（同一 CI 产物） |
| 校验 | `apksigner verify --print-certs` = `7fcc0855…`；`aapt2 dump badging` = `versionCode 41 / 3.0.9` |

## 三、以后发新版的步骤

1. 改 `app/src/main/AndroidManifest.xml` 的 `versionCode` / `versionName`，写好 `RELEASE_NOTES_vX.Y.Z.md`；
2. 提交并推送：`git push origin main`；
3. 打 tag 触发 CI：`git tag vX.Y.Z && git push origin vX.Y.Z`
   → `.github/workflows/build.yml` 用 Secrets 里的证书构建，并自动建 Release、附 `Zalocn-E.apk`；
4. 同步 org 镜像：`git fetch org && git checkout -b org-sync org/main && git merge main`
   （冲突时**README 保留 org 侧**的推广版，其余取 main），`git push org org-sync:main`；
5. 在 org 仓库建 Release：tag 用 `{versionCode}-{versionName}`（如 `41-3.0.9`），
   资产名 `Zalocn-E_vX.Y.Z.apk`，上传第 3 步 CI 产出的 `Zalocn-E.apk`；
6. 验证：下载资产跑 `apksigner verify --print-certs`，指纹必须是 `7fcc0855…`。

## 四、踩坑记录

- 本地 clone 可能落后远端（PC 工作已并入 main 的 `pc/` 目录），推送前先 `git fetch && git rebase`；
- `build/.keystore-pass`（本机口令）与 `*.keystore` 已在 `.gitignore`，绝不入库；
- 只换 asar 里一个文件时不要用 `asar pack` 全量重打（158MB/13k 文件要半小时以上），
  用 `pc/work/asar_patch_one.py` 外科式替换，1 秒完成。
