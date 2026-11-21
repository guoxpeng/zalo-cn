# Zalo PC 翻译注入安装器 v1.0.3
# 作用：在你现有的（已汉化）app.asar 上加翻译注入，不动汉化词条。
# 听：收到的消息自动翻成中文（原文在上、译文在下框）
# 说：输入框边上加「译」按钮，中文翻成越南语填回输入框，不自动发
# 注入目标：主窗口 pc-dist/index.html（+ CSP 放行 bilibili API）
#           与迷你窗口 pc-dist/compact-app.html 两个都注。
#           只注 compact-app 的话主窗口里根本不加载（v1.0.0 的 bug，v1.0.1 修）。
# v1.0.2：按钮持久化（切换会话重装）、观察器重挂、翻译失败重试 3 次后显示提示。
# 用法：右键 → 使用 PowerShell 运行；或在 PowerShell 里 .\install-translate.ps1
# 要求：已装 Node.js（npx 可用）；Zalo 先关掉（脚本会尝试先关，关不掉会停下等你手动关）

param(
  [string]$ZaloDir = "",
  [string]$ScriptDir = $PSScriptRoot
)

$ErrorActionPreference = "Stop"

function Find-ZaloDir {
  $base = "C:\Users\$env:USERNAME\AppData\Local\Programs\Zalo"
  if ($ZaloDir -and (Test-Path $ZaloDir)) { return $ZaloDir }
  if (-not (Test-Path $base)) { throw "找不到 Zalo 安装目录：$base（可用 -ZaloDir 参数指定）" }
  $vers = Get-ChildItem $base -Directory -Filter "Zalo-*" | Sort-Object Name -Descending
  if (-not $vers) { throw "在 $base 下没找到 Zalo-版本 目录" }
  $dir = Join-Path $vers[0].FullName "resources"
  if (-not (Test-Path (Join-Path $dir "app.asar"))) { throw "在 $dir 下没找到 app.asar" }
  return $dir
}

$ResDir = Find-ZaloDir
$Asar = Join-Path $ResDir "app.asar"
$JsSrc = Join-Path $ScriptDir "zalocn-translate.js"
if (-not (Test-Path $JsSrc)) { throw "找不到 zalocn-translate.js（应与本脚本在同一目录）" }

Write-Host "Zalo 目录：$ResDir"
Write-Host "正在关 Zalo…"
Get-Process -Name "Zalo" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 3
$still = Get-Process -Name "Zalo" -ErrorAction SilentlyContinue
if ($still) { throw "Zalo 还在运行，请手动关掉 Zalo 再跑本脚本（任务栏右键退出）。" }

# 备份（只备一次原版；已打过补丁的再跑会先恢复成备份再重打，避免重复注入）
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$bak = "$Asar.bak.zalocn_$stamp"
$origBak = Get-ChildItem $ResDir -Filter "app.asar.bak.zalocn_*" | Sort-Object Name -Descending | Select-Object -First 1
if ($origBak) {
  Write-Host "已有备份 $($origBak.Name)，先恢复成备份再重打，避免重复注入。"
  Copy-Item $origBak.FullName $Asar -Force
} else {
  Copy-Item $Asar $bak -Force
  Write-Host "已备份原版：$bak"
  $origBak = Get-Item $bak
}

$tmp = Join-Path $env:TEMP ("zalocn_patch_" + $stamp)
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
try {
  Write-Host "正在解包 app.asar…"
  npx --yes @electron/asar extract $Asar $tmp | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "asar 解包失败" }

  $jsDst = Join-Path $tmp "pc-dist\zalocn-translate.js"
  Copy-Item $JsSrc $jsDst -Force

  # 主窗口 index.html（平时用的那个）：加引用 + CSP 放行 bilibili API
  # 注意：主窗口加载的是 index.html，不是 compact-app.html；
  # 只注入 compact-app.html 的话主窗口里根本不会加载脚本。
  $indexHtml = Join-Path $tmp "pc-dist\index.html"
  if (-not (Test-Path $indexHtml)) { throw "解包后没找到 pc-dist\index.html（版本不对？）" }
  $content = Get-Content $indexHtml -Raw -Encoding UTF8
  if ($content -match 'data-zalocn="main"') {
    Write-Host "index.html 已有翻译注入引用，跳过。"
  } else {
    $tag = '<script src="zalocn-translate.js" data-zalocn="main"></script>'
    $last = $content.LastIndexOf('</script>')
    if ($last -lt 0) { throw "index.html 里没找到 </script>" }
    $end = $last + '</script>'.Length
    $content = $content.Substring(0, $end) + $tag + $content.Substring($end)
    Write-Host "已往 index.html 注入翻译脚本引用。"
  }
  if ($content -notmatch "index-translate.bilibili.com") {
    $key = "connect-src "
    $pos = $content.IndexOf($key)
    if ($pos -lt 0) { throw "index.html 的 CSP 里没找到 connect-src" }
    $pos += $key.Length
    $content = $content.Substring(0, $pos) + "https://index-translate.bilibili.com " + $content.Substring($pos)
    Write-Host "已给 index.html 的 CSP 放行 bilibili 翻译 API。"
  }
  Set-Content $indexHtml $content -Encoding UTF8 -NoNewline

  # 迷你窗口 compact-app.html（--launch-compact-app 启动时用；无 CSP）
  $html = Join-Path $tmp "pc-dist\compact-app.html"
  if (Test-Path $html) {
    $content = Get-Content $html -Raw -Encoding UTF8
    if ($content -notmatch "zalocn-translate.js") {
      $anchor = '<script src="compact-app-pc.'
      $idx = $content.IndexOf($anchor)
      if ($idx -lt 0) { throw "compact-app.html 里没找到主 bundle 的 script 标签" }
      $end = $content.IndexOf('</script>', $idx) + '</script>'.Length
      $content = $content.Substring(0, $end) + '<script src="zalocn-translate.js"></script>' + $content.Substring($end)
      Set-Content $html $content -Encoding UTF8 -NoNewline
      Write-Host "已往 compact-app.html 注入脚本引用。"
    } else {
      Write-Host "compact-app.html 已有注入引用，跳过。"
    }
  } else {
    Write-Host "解包后没有 compact-app.html，跳过迷你窗口注入。"
  }

  Write-Host "正在重打包…"
  $out = Join-Path $env:TEMP ("app_zh_" + $stamp + ".asar")
  npx --yes @electron/asar pack $tmp $out --unpack "**/native/**" | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "asar 打包失败" }

  Copy-Item $out $Asar -Force
  Write-Host "已替换 app.asar。"

  # 同步 update_meta.json 的 SHA1（防更新回滚；有这文件才做）
  $meta = Join-Path (Split-Path $ResDir -Parent) "update_meta.json"
  if (Test-Path $meta) {
    try {
      $sha1 = (Get-FileHash $Asar -Algorithm SHA1).Hash.ToLower()
      $j = Get-Content $meta -Raw | ConvertFrom-Json
      $j.resources.'app.asar'.verify = $sha1
      $j | ConvertTo-Json -Depth 10 | Set-Content $meta -Encoding UTF8
      Write-Host "已同步 update_meta.json SHA1。"
    } catch { Write-Host "update_meta.json 同步失败（不影响使用）: $_" }
  }
} finally {
  Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
  Remove-Item (Join-Path $env:TEMP ("app_zh_" + $stamp + ".asar")) -Force -ErrorAction SilentlyContinue
}

# 重启 Zalo（走 explorer，等价双击）
$exe = Get-ChildItem (Split-Path $ResDir -Parent) -Filter "Zalo.exe" | Select-Object -First 1
if ($exe) {
  Write-Host "正在启动 Zalo…"
  Start-Process "explorer.exe" -ArgumentList "`"$($exe.FullName)`""
} else {
  Write-Host "没找到 Zalo.exe，请手动打开 Zalo。"
}
Write-Host ""
Write-Host "完成。打开一个越南人会话：收到的消息会自动翻成中文（原文在上、译文在下框）；输入框边上有「译」按钮，点它把中文翻成越南语填回输入框（不自动发）。"
Write-Host "出问题：跑同目录的 restore-translate.ps1 可一键恢复原版。"
