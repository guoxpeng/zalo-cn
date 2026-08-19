#!/bin/bash
# Zalo 汉化（com.zalocn）一键构建脚本
# 依赖：JDK 21、Android SDK build-tools 36.0.0、platforms android-34、git-bash
# 用法：bash build_module.sh   -> 产物 ../zalocn_hanhua.apk
set -e

SDK="$HOME/AppData/Local/Android/Sdk"
BT="$SDK/build-tools/36.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"

SRC_JAVA="../app/src/main/java"
MANIFEST="../app/src/main/AndroidManifest.xml"
WORK="build_work"
OUT="../zalocn_hanhua.apk"

rm -rf "$WORK"
mkdir -p "$WORK/classes"

echo "== 1. javac =="
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$PLATFORM" \
  -d "$WORK/classes" $(find "$SRC_JAVA" -name "*.java")

echo "== 2. d8 -> classes2.dex（只打包 com/zalocn，de/robv 桩类仅编译用）=="
"$BT/d8.bat" --min-api 27 --lib "$PLATFORM" --output "$WORK" \
  $(find "$WORK/classes/com" -name "*.class")
mv "$WORK/classes.dex" "$WORK/classes2.dex"

echo "== 3. aapt2 link（源码 manifest -> 基础 APK）=="
"$BT/aapt2.exe" link -o "$WORK/base.apk" \
  --manifest "$MANIFEST" \
  -I "$PLATFORM" \
  --min-sdk-version 27 --target-sdk-version 34

echo "== 4. 组装：dex + assets =="
rm -rf "$WORK/apk"
mkdir -p "$WORK/apk/assets"
cp "$WORK/base.apk" "$WORK/apk/tmp_base.apk"
cd "$WORK/apk"
unzip -o -q tmp_base.apk
rm -f tmp_base.apk
cp ../../classes_dex_empty.dex ./classes.dex   # 空壳主 dex（必须存在）
cp ../classes2.dex ./classes2.dex              # 实际 hook 代码
cp ../../../data/translations.json ./assets/translations.json
cp ../../../data/src_map_compact.json ./assets/src_map.json
printf 'com.zalocn.MainHook\n' > ./assets/xposed_init
cd ../..

echo "== 5. zip（resources.arsc 不压缩）+ zipalign + sign =="
python - <<PYEOF
import os, zipfile
apk_dir = os.path.abspath('$WORK/apk')
out = os.path.abspath('$WORK/unsigned.zip')
with zipfile.ZipFile(out, 'w') as z:
    for root, dirs, files in os.walk(apk_dir):
        for f in files:
            full = os.path.join(root, f)
            rel = os.path.relpath(full, apk_dir).split(os.sep)
            rel = '/'.join(rel)
            if rel == 'resources.arsc':
                z.write(full, rel, zipfile.ZIP_STORED)
            else:
                z.write(full, rel, zipfile.ZIP_DEFLATED)
print('zipped')
PYEOF

"$BT/zipalign.exe" -f 4 "$WORK/unsigned.zip" "$WORK/aligned.apk"

KEYSTORE="${ZALOZH_KEYSTORE:-zalocn.keystore}"
if [ ! -f "$KEYSTORE" ]; then
  echo "keystore not found, generating $KEYSTORE ..."
  keytool -genkeypair -v -keystore "$KEYSTORE" -alias zalocn -keyalg RSA -keysize 2048 -validity 3650 \
    -storepass zalocn123 -keypass zalocn123 -dname "CN=ZaloCN, OU=Dev, O=Dev, L=City, ST=State, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner.bat" sign --ks "$KEYSTORE" --ks-key-alias zalocn --ks-pass pass:zalocn123 --key-pass pass:zalocn123 \
  --out "$OUT" "$WORK/aligned.apk"

echo "== done =="
ls -la "$OUT"
