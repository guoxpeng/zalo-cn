#!/bin/bash
# Zalo PC 汉化+翻译一键安装器构建脚本（Linux 沙盒交叉编译出 Windows exe）
# 用法：cd pc/installer && ./build-installer.sh
# 产物：Zalocn-PC-Installer.exe（Windows 64 位，单文件，无依赖，约 8MB）
set -e
cd "$(dirname "$0")"
GO=~/workspace/.toolchains/go/bin/go
if [ ! -x "$GO" ]; then
  echo "Go 不在 ~/workspace/.toolchains/go/bin/go，请先装 Go 1.23+"
  exit 1
fi
# 1. 从 pc/work 与 pc/watch_zh.vbs 同步最新文件进 files/（构建时以 work 为准）
rm -rf files
mkdir -p files/translate files/seed files/bing
cp ../work/auto_patch.py ../work/01_parse.py ../work/02_translate.py ../work/03_apply.py ../work/04_pack.py ../work/05_force_promo_zh.py ../work/corpus.json ../work/inline.json ../work/lang_en.json ../work/lang_vi.json ../work/overrides.json ../work/trans_cache.json ../work/zh_dict.json ../work/zh_text.json files/
cp ../work/translate/* files/translate/
cp ../work/seed/* files/seed/
cp ../work/bing/* files/bing/
cp ../watch_zh.vbs files/watch_zh.vbs
# 2. 交叉编译 Windows exe（CGO 关闭，纯静态单文件）
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 "$GO" build -trimpath -ldflags "-s -w" -o Zalocn-PC-Installer.exe .
ls -la Zalocn-PC-Installer.exe
echo "构建完成：pc/installer/Zalocn-PC-Installer.exe"
