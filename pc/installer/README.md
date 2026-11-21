# Zalo PC 翻译注入一键安装器

双击 `Zalocn-PC-Installer.exe`，自动完成全部安装，不用再手动拷文件、不用再敲命令。

## 它自动做什么（共 4 步，屏幕上会逐行显示）

1. **找 work 文件夹**：先看 exe 自身目录有没有 `auto_patch.py`；没有就找 `D:\project\zalo\zalopc\work`；再没有就全盘搜索 `\project\zalo\zalopc\work\auto_patch.py`；实在没有就在 D 盘新建一个。
2. **找 Zalo 安装目录**：扫 `%LOCALAPPDATA%\Programs\Zalo` 下的 `Zalo-*` 版本文件夹（取最高版本）；找不到就看正在运行的 `Zalo.exe` 在哪；还找不到就用 `auto_patch.py` 内置默认路径。
3. **覆盖文件**：把 exe 里内嵌的最新 `auto_patch.py` + `translate/`（翻译脚本、安装/恢复脚本、说明）写进 work 文件夹，旧文件直接覆盖。
4. **跑补丁**：用系统 Python 跑 `auto_patch.py`（检测→补翻译注入→替换 asar→重启 Zalo），输出实时显示在窗口里。跑完按回车退出。

## 需要你电脑上已有的东西

- Python（`python` 命令在 PATH 里；你一直在手动跑 `auto_patch.py`，所以已经有了）
- Node.js + npx（`auto_patch.py` 用 `npx --yes @electron/asar` 解包/打包 asar；同样已经有了）
- Zalo 已安装且已汉化

注意：本 exe 只带 `auto_patch.py` + `translate/`。Zalo 未汉化时走全流程重打，需要 work 文件夹里原有的种子词库；所以首次汉化仍需你原有的完整 work 文件夹。日常更新翻译注入（v1.0.x 这种）只用本 exe 就够了。

## 构建（开发者）

```bash
cd pc/installer && ./build-installer.sh
```

构建脚本先把 `pc/work/auto_patch.py` 与 `pc/work/translate/*` 同步进 `files/`，再用 Go 交叉编译出 Windows exe（`GOOS=windows GOARCH=amd64`，纯静态单文件，无依赖）。Go 工具链在 `~/workspace/.toolchains/go`。

源码：`main.go`（`go:embed` 内嵌 `files/`）。改完 Go 代码后重新跑构建脚本，再把新 exe 发给用户。
