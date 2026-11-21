#!/bin/bash
# Zalo PC 翻译注入一键安装器构建脚本（Linux 沙盒交叉编译出 Windows exe）
# 用法：cd pc/installer && ./build-installer.sh
# 产物：Zalocn-PC-Installer.exe（Windows 64 位，单文件，无依赖）
set -e
cd "$(dirname "$0")"
GO=~/workspace/.toolchains/go/bin/go
if [ ! -x "$GO" ]; then
  echo "Go 不在 ~/workspace/.toolchains/go/bin/go，请先装 Go 1.23+"
  exit 1
fi
# 1. 从 pc/work 同步最新文件进 files/（构建时以 work 为准，files/ 只是构建缓存）
cp ../work/auto_patch.py files/auto_patch.py
cp ../work/translate/zalocn-translate.js files/translate/zalocn-translate.js
cp ../work/translate/install-translate.ps1 files/translate/install-translate.ps1
cp ../work/translate/restore-translate.ps1 files/translate/restore-translate.ps1
cp ../work/translate/README.md files/translate/README.md
# 2. 交叉编译 Windows exe（CGO 关闭，纯静态单文件）
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 "$GO" build -trimpath -ldflags "-s -w" -o Zalocn-PC-Installer.exe .
ls -la Zalocn-PC-Installer.exe
echo "构建完成：pc/installer/Zalocn-PC-Installer.exe"
