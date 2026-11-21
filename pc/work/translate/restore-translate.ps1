# Zalo PC 翻译注入一键恢复
# 把 app.asar 恢复成打补丁前的备份（汉化版还在，翻译注入去掉）。
# 用法：右键 → 使用 PowerShell 运行；或在 PowerShell 里 .\restore-translate.ps1

$ErrorActionPreference = "Stop"
$base = "C:\Users\$env:USERNAME\AppData\Local\Programs\Zalo"
$vers = Get-ChildItem $base -Directory -Filter "Zalo-*" | Sort-Object Name -Descending
if (-not $vers) { throw "找不到 Zalo 安装目录" }
$ResDir = Join-Path $vers[0].FullName "resources"
$Asar = Join-Path $ResDir "app.asar"
$bak = Get-ChildItem $ResDir -Filter "app.asar.bak.zalocn_*" | Sort-Object Name -Descending | Select-Object -First 1
if (-not $bak) { throw "没找到备份（app.asar.bak.zalocn_*），无法恢复。" }

Write-Host "正在关 Zalo…"
Get-Process -Name "Zalo" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 3
$still = Get-Process -Name "Zalo" -ErrorAction SilentlyContinue
if ($still) { throw "Zalo 还在运行，请手动关掉 Zalo 再跑。" }

Copy-Item $bak.FullName $Asar -Force
Write-Host "已恢复：$($bak.Name) -> app.asar"
$exe = Get-ChildItem (Split-Path $ResDir -Parent) -Filter "Zalo.exe" | Select-Object -First 1
if ($exe) { Start-Process "explorer.exe" -ArgumentList "`"$($exe.FullName)`""; Write-Host "Zalo 已重启（恢复成汉化版，无翻译注入）。"; }
