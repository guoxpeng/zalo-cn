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

echo "== 4b. 生成 web_inject.js（WebView H5 页面 DOM 翻译脚本）=="
python - <<'PYEOF'
import json
base = '../../../data'
with open(base + '/src_map_compact.json', encoding='utf-8') as f:
    m = json.load(f)
D = {}
for lang in ('en', 'vi'):
    for k, v in m.get(lang, {}).items():
        if not k or not v or k == v:
            continue
        if len(k) < 2:
            continue
        # 跳过含 HTML/占位符/换行的键（DOM 文本节点不会出现这些）
        if '<' in k or '>' in k or '{' in k or '}' in k or '\n' in k or '\r' in k:
            continue
        D.setdefault(k, v)
js_dict = json.dumps(D, ensure_ascii=False, separators=(',', ':'))
js = '''(function(){
if(window.__ZH_DICT_INJECTED){return;}window.__ZH_DICT_INJECTED=true;
var D=__DICT__;
function tr(n){
  var v=n.nodeValue;if(!v)return;
  var t=v.replace(/^[\s\u00a0]+|[\s\u00a0]+$/g,'');
  if(!t||t.length<2)return;
  var r=D[t];
  if(r&&r!==t){n.nodeValue=v.replace(t,r);}
}
function walk(root){
  var w=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null);
  var n,c=0;
  while(n=w.nextNode()){
    var p=n.parentNode;
    if(p&&(p.tagName==='SCRIPT'||p.tagName==='STYLE'||p.tagName==='TEXTAREA'||p.tagName==='INPUT'||p.tagName==='NOSCRIPT'||p.tagName==='SELECT'))continue;
    tr(n);
    if(++c>8000)break;
  }
}
function init(){
  if(!document.body)return;
  walk(document.body);
  var phs=document.querySelectorAll('input[placeholder],textarea[placeholder]');
  for(var i=0;i<phs.length;i++){
    var ph=phs[i].getAttribute('placeholder');
    if(ph&&D[ph])phs[i].setAttribute('placeholder',D[ph]);
  }
  if(window.MutationObserver){
    try{
      var mo=new MutationObserver(function(muts){
        for(var i=0;i<muts.length;i++){
          var m=muts[i];
          if(m.type!=='childList'||!m.addedNodes)continue;
          for(var j=0;j<m.addedNodes.length;j++){
            var nd=m.addedNodes[j];
            if(nd.nodeType===3){tr(nd);}
            else if(nd.nodeType===1){walk(nd);}
          }
        }
      });
      mo.observe(document.body,{childList:true,subtree:true});
    }catch(e){}
  }
}
if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',init);}else{init();}
})();'''.replace('__DICT__', js_dict)
with open('assets/web_inject.js', 'w', encoding='utf-8') as f:
    f.write(js)
print('web_inject entries:', len(D), 'size:', len(js.encode('utf-8')))
PYEOF
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
