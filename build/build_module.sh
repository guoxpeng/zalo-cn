#!/usr/bin/env bash
# ============================================================================
# Zalo 汉化模块（com.zalocn）一键构建脚本 —— 跨平台版
#
# 用法：
#   bash build/build_module.sh
#   VERSION_CODE=18 VERSION_NAME=2.7 bash build/build_module.sh
#
# 依赖：
#   - JDK 8+（推荐 17 / 21）
#   - Android SDK：build-tools 35+（含 aapt2 / d8 / zipalign / apksigner）、platforms/android-34
#     注意：build-tools 34 的 d8 有 R8 bug（MethodParameters 空名导致 NPE），务必用 35 及以上
#   - python3（仅用于打 zip 包）
#
# 环境变量：
#   ANDROID_SDK_ROOT / ANDROID_HOME   SDK 路径（不设则自动探测常见位置）
#   ZALOZH_KEYSTORE                   签名证书路径（默认 ~/.config/zalocn/zalocn-release.keystore）
#   ZALOZH_KS_PASS / ZALOZH_KEY_PASS  证书口令（必填，无默认值；旧默认口令已作废）
#   ZALOZH_KEY_ALIAS                  别名（默认 zalocn）
#   OUT                               产物路径（默认 <repo>/zalocn_hanhua.apk）
#
# 签名安全（2026-11-21 轮换后）：旧 keystore（build/zalocn.keystore，旧默认口令）
# 已公开作废，从仓库与历史中清除。新 keystore 只存放在仓库外
# （~/.config/zalocn/zalocn-release.keystore），口令不进仓库、不进脚本；
# CI 从 GitHub Secrets 读（KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_PASSWORD）。
# 新旧签名不兼容：已装旧签名版的用户必须卸载重装一次。
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SRC_JAVA="app/src/main/java"
MANIFEST_SRC="app/src/main/AndroidManifest.xml"
WORK="build/build_work"
OUT="${OUT:-$ROOT/zalocn_hanhua.apk}"

VERSION_CODE="${VERSION_CODE:-17}"
VERSION_NAME="${VERSION_NAME:-2.6.1}"

# ---------------------------------------------------------------- 工具定位
tool() { # tool <build-tools-dir> <name>
  local bt="$1" name="$2"
  if   [ -x "$bt/$name" ];     then echo "$bt/$name"
  elif [ -x "$bt/$name.exe" ]; then echo "$bt/$name.exe"
  elif [ -x "$bt/$name.bat" ]; then echo "$bt/$name.bat"
  else echo ""; fi
}

# ---------------------------------------------------------------- 定位 SDK
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  for c in \
    "$HOME/AppData/Local/Android/Sdk" \
    "$HOME/Library/Android/sdk" \
    "$HOME/Android/Sdk" \
    "/usr/local/lib/android/sdk" \
    "/opt/android-sdk" ; do
    if [ -d "$c" ]; then SDK="$c"; break; fi
  done
fi
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  echo "!! 找不到 Android SDK，请设置 ANDROID_SDK_ROOT" >&2
  exit 1
fi
echo "== SDK: $SDK"

# build-tools：取版本号最大的一个
BT=""
if [ -d "$SDK/build-tools" ]; then
  BT_NAME="$(ls -1 "$SDK/build-tools" | sort -V | tail -1)"
  BT="$SDK/build-tools/$BT_NAME"
fi
if [ -z "$BT" ] || [ ! -d "$BT" ]; then
  echo "!! 找不到 build-tools" >&2
  exit 1
fi
echo "== build-tools: $BT_NAME"

# platform：优先 android-34，否则取最高版本
PLATFORM=""
for p in 34 35 33 36 32; do
  if [ -f "$SDK/platforms/android-$p/android.jar" ]; then
    PLATFORM="$SDK/platforms/android-$p/android.jar"; break
  fi
done
if [ -z "$PLATFORM" ]; then
  PLATFORM="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
fi
if [ -z "$PLATFORM" ] || [ ! -f "$PLATFORM" ]; then
  echo "!! 找不到 platforms/android-XX/android.jar（需要 android-34）" >&2
  exit 1
fi
echo "== platform: $PLATFORM"

AAPT2="$(tool "$BT" aapt2)"
D8="$(tool "$BT" d8)"
ZIPALIGN="$(tool "$BT" zipalign)"
APKSIGNER="$(tool "$BT" apksigner)"
for t in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER"; do
  if [ -z "$t" ]; then
    echo "!! build-tools 缺少组件（aapt2 / d8 / zipalign / apksigner）" >&2
    exit 1
  fi
done

# ---------------------------------------------------------------- 清理
rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/apk/assets"

# ---------------------------------------------------------------- 1. 版本号注入
echo "== 1. 注入版本号 ${VERSION_NAME} (${VERSION_CODE})"
MANIFEST="$WORK/AndroidManifest.xml"
sed -e "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"${VERSION_CODE}\"/" \
    -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"${VERSION_NAME}\"/" \
    "$MANIFEST_SRC" > "$MANIFEST"

# ---------------------------------------------------------------- 2. javac
echo "== 2. javac"
javac -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
  -classpath "$PLATFORM" -d "$WORK/classes" \
  $(find "$SRC_JAVA" -name "*.java")

# ---------------------------------------------------------------- 3. d8
echo "== 3. d8 -> classes2.dex"
"$D8" --min-api 27 --lib "$PLATFORM" --output "$WORK" \
  $(find "$WORK/classes/com" -name "*.class")
mv "$WORK/classes.dex" "$WORK/classes2.dex"

# ---------------------------------------------------------------- 4. aapt2 编译资源 + link
echo "== 4. aapt2 compile res + link"
RES_DIR="app/src/main/res"
COMPILED_RES="$WORK/compiled_res.zip"
if [ -d "$RES_DIR" ]; then
  "$AAPT2" compile --dir "$RES_DIR" -o "$COMPILED_RES"
  "$AAPT2" link -o "$WORK/base.apk" \
    --manifest "$MANIFEST" \
    -I "$PLATFORM" \
    "$COMPILED_RES" \
    --min-sdk-version 27 --target-sdk-version 34
else
  "$AAPT2" link -o "$WORK/base.apk" \
    --manifest "$MANIFEST" \
    -I "$PLATFORM" \
    --min-sdk-version 27 --target-sdk-version 34
fi

# ---------------------------------------------------------------- 5. 组装
echo "== 5. 组装 dex + assets"
cd "$WORK/apk"
unzip -o -q ../base.apk
cp ../classes2.dex ./classes2.dex
cp "$ROOT/build/classes_dex_empty.dex" ./classes.dex   # 空壳主 dex（Xposed 要求存在）
cp "$ROOT/data/translations.json" ./assets/translations.json
cp "$ROOT/data/src_map_compact.json" ./assets/src_map.json
printf 'com.zalocn.MainHook\n' > ./assets/xposed_init
cd "$ROOT"

# ---------------------------------------------------------------- 6. zip
echo "== 6. zip（resources.arsc 不压缩）"
python3 - "$WORK" <<'PYEOF'
import os, sys, zipfile
work = sys.argv[1]
apk_dir = os.path.abspath(os.path.join(work, 'apk'))
out = os.path.abspath(os.path.join(work, 'unsigned.apk'))
with zipfile.ZipFile(out, 'w') as z:
    for root, dirs, files in os.walk(apk_dir):
        for f in sorted(files):
            full = os.path.join(root, f)
            rel = '/'.join(os.path.relpath(full, apk_dir).split(os.sep))
            if rel == 'resources.arsc':
                z.write(full, rel, zipfile.ZIP_STORED)
            else:
                z.write(full, rel, zipfile.ZIP_DEFLATED)
print('  zipped ->', out)
PYEOF

# ---------------------------------------------------------------- 7. 对齐 + 签名
echo "== 7. zipalign + apksigner"
"$ZIPALIGN" -p -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"

KEYSTORE="${ZALOZH_KEYSTORE:-$HOME/.config/zalocn/zalocn-release.keystore}"
KS_PASS="${ZALOZH_KS_PASS:-}"
KEY_PASS="${ZALOZH_KEY_PASS:-}"
KEY_ALIAS="${ZALOZH_KEY_ALIAS:-zalocn}"

if [ ! -f "$KEYSTORE" ]; then
  echo "!! 找不到签名证书 $KEYSTORE" >&2
  echo "   生成方法：keytool -genkeypair -keystore ~/.config/zalocn/zalocn-release.keystore \\" >&2
  echo "     -alias zalocn -keyalg RSA -keysize 4096 -validity 10000" >&2
  echo "   口令用 ZALOZH_KS_PASS / ZALOZH_KEY_PASS 环境变量传入，绝不写进仓库。" >&2
  exit 1
fi
if [ -z "$KS_PASS" ] || [ -z "$KEY_PASS" ]; then
  echo "!! 未设置证书口令：请设 ZALOZH_KS_PASS 与 ZALOZH_KEY_PASS 环境变量" >&2
  echo "   （无默认值；旧默认口令已作废，不要把口令写进脚本或仓库。）" >&2
  exit 1
fi

"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT" "$WORK/aligned.apk"

echo "== 完成 =="
ls -la "$OUT"
"$APKSIGNER" verify --print-certs "$OUT" 2>/dev/null | head -3 || true
