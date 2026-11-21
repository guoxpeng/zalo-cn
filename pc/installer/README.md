# Zalo PC 汉化+翻译一键安装器

双击 `Zalocn-PC-Installer.exe`，自动完成全部安装，不用再手动拷文件、不用再敲命令。**别人的电脑也能用**——exe 里内嵌了完整汉化工程（脚本+词库），新电脑从零开始也能一次装完汉化+翻译。

## 它自动做什么（共 5 步，屏幕上会逐行显示）

1. **找 Zalo 安装目录**：先扫 `%LOCALAPPDATA%\Programs\Zalo` 下的 `Zalo-*` 版本文件夹（取最高版本）；找不到就看正在运行的 `Zalo.exe` 在哪；还找不到就弹文件夹选择框，**让使用者自己指定 Zalo 所在文件夹**（选里面有 `Zalo-26.11.12` 这类版本文件夹的那个）。
2. **找 work 文件夹**：先看 exe 自身目录有没有 `auto_patch.py`；没有就找 `D:\project\zalo\zalopc\work`（老郭的工程目录）；再没有就全盘搜索；新电脑都没有，就在 `%LOCALAPPDATA%\Zalocn\work` 新建一个（稳定位置，供守护长期用）。
3. **写入汉化工程文件**：把 exe 里内嵌的完整工程（`auto_patch.py` + 汉化脚本 01–05 + 词库 + `translate/` + 守护脚本）写进 work 文件夹，旧文件直接覆盖。
4. **跑安装**：用系统 Python 跑 `auto_patch.py`。Zalo 没汉化就走全流程（汉化+翻译一次装完）；已汉化就只补翻译注入；替换 asar→重启 Zalo。输出实时显示在窗口里。
5. **开机守护**：问要不要安装开机守护（Zalo 更新后自动重打，推荐装）。装就在 Startup 文件夹建 `watch_zh.vbs` 的快捷方式。

跑完按回车退出。

## 需要目标电脑上已有的东西

安装器启动时会先检查，缺了就提示下载链接、退出，等装好再重跑：

- **Python**：缺了提示去 https://www.python.org/downloads/ 下载，安装时务必勾选 "Add Python to PATH"（把 Python 加进系统路径）
- **Node.js（LTS 长期支持版）**：缺了提示去 https://nodejs.org/ 下载（自带 npx）
- **Zalo 已安装**

## 构建（开发者）

```bash
cd pc/installer && ./build-installer.sh
```

构建脚本先把 `pc/work`（全部脚本+词库+translate）与 `pc/watch_zh.vbs` 同步进 `files/`，再用 Go 交叉编译出 Windows exe（`GOOS=windows GOARCH=amd64`，纯静态单文件，约 8MB，无依赖）。Go 工具链在 `~/workspace/.toolchains/go`。

源码：`main.go`（`go:embed` 内嵌整个 `files/` 目录树）。改完 Go 代码或 work 文件后重新跑构建脚本，再把新 exe 发给用户。
