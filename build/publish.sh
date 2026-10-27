#!/usr/bin/env bash
# ============================================================================
# 一键发布：构建 -> 上传 APK 到两个仓库的 Release
#
# 用法：
#   bash build/publish.sh              # 构建并发布
#   SKIP_BUILD=1 bash build/publish.sh # 用现成的 zalocn_hanhua.apk 发布
#
# 令牌：默认读 ~/.workbuddy-ai/secrets/github_token，可用 GITHUB_TOKEN 覆盖
#
# 两个仓库的 Release 约定不同：
#   1) guoxpeng/zalo-cn            tag = v<versionName>        附件名 zalocn_hanhua.apk
#      —— 面向普通用户，CI 打 tag 时自动发布
#   2) Xposed-Modules-Repo/com.zalocn  tag = <versionCode>-<versionName>  附件名 Zalo-cn_v<versionName>.apk
#      —— 面向 LSPosed 模块列表，仓库未启用 Actions，必须手动上传
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

TOKEN="${GITHUB_TOKEN:-$(cat "$HOME/.workbuddy-ai/secrets/github_token" 2>/dev/null || true)}"
if [ -z "$TOKEN" ]; then
  echo "!! 缺少 GitHub 令牌：放到 ~/.workbuddy-ai/secrets/github_token 或设 GITHUB_TOKEN" >&2
  exit 1
fi

API="https://api.github.com"
UP="https://uploads.github.com"
USER_REPO="guoxpeng/zalo-cn"
XPOSED_REPO="Xposed-Modules-Repo/com.zalocn"

APK="$ROOT/zalocn_hanhua.apk"

# ---------------------------------------------------------------- 1. 构建
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  bash build/build_module.sh
fi
[ -f "$APK" ] || { echo "!! 找不到 $APK" >&2; exit 1; }

# ---------------------------------------------------------------- 2. 读版本
VCODE=$(grep -o 'android:versionCode="[0-9]*"' app/src/main/AndroidManifest.xml | grep -o '[0-9]*')
VNAME=$(grep -o 'android:versionName="[^"]*"' app/src/main/AndroidManifest.xml | sed 's/.*="//;s/"//')
echo "== 版本：$VNAME (versionCode $VCODE)"

NOTES="RELEASE_NOTES_v${VNAME}.md"
[ -f "$NOTES" ] || NOTES=""

# ---------------------------------------------------------------- 3. 发布
publish() { # publish <repo> <tag> <release_name> <asset_name>
  local repo="$1" tag="$2" rname="$3" aname="$4"
  echo "== 发布 $repo  tag=$tag  asset=$aname"

  local body
  if [ -n "$NOTES" ]; then
    body=$(python3 -c "import json,sys;print(json.dumps(open(sys.argv[1],encoding='utf-8').read()))" "$NOTES")
  else
    body='""'
  fi

  # 已存在则复用，否则创建
  local rid
  rid=$(curl -s -H "Authorization: token $TOKEN" "$API/repos/$repo/releases/tags/$tag" \
        | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('id',''))" 2>/dev/null || true)

  if [ -z "$rid" ]; then
    rid=$(curl -s -X POST -H "Authorization: token $TOKEN" -H "Accept: application/vnd.github+json" \
      "$API/repos/$repo/releases" \
      -d "{\"tag_name\":\"$tag\",\"target_commitish\":\"main\",\"name\":\"$rname\",\"body\":$body,\"draft\":false,\"prerelease\":false}" \
      | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('id',''))")
  else
    echo "   Release 已存在（id=$rid），直接补传附件"
  fi
  [ -n "$rid" ] || { echo "!! 创建 Release 失败" >&2; return 1; }

  # 删同名旧附件（避免重复）
  curl -s -H "Authorization: token $TOKEN" "$API/repos/$repo/releases/$rid/assets" \
    | python3 -c "
import sys,json
for a in json.load(sys.stdin):
    if a['name']=='$aname': print(a['id'])
" | while read -r aid; do
      [ -n "$aid" ] && curl -s -o /dev/null -X DELETE -H "Authorization: token $TOKEN" \
        "$API/repos/$repo/releases/assets/$aid"
    done

  curl -s -X POST -H "Authorization: token $TOKEN" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary "@$APK" \
    "$UP/repos/$repo/releases/$rid/assets?name=$aname" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print('   已上传:',d.get('name'),d.get('size'),d.get('state'))"
}

publish "$USER_REPO"    "v$VNAME"            "Zalo 汉化 $VNAME"  "zalocn_hanhua.apk"
publish "$XPOSED_REPO"  "${VCODE}-${VNAME}"  "v$VNAME"           "Zalo-cn_v${VNAME}.apk"

echo "== 发布完成 =="
echo "   https://github.com/$USER_REPO/releases/tag/v$VNAME"
echo "   https://github.com/$XPOSED_REPO/releases/tag/${VCODE}-${VNAME}"
