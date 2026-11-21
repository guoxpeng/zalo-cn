' Zalo PC 汉化守护进程 (watch_zh.vbs)
' 作用：开机静默启动，定时检查 Zalo 最新版本是否已汉化；未汉化则自动重打并安装。
' 安装：把本文件的快捷方式（或直接副本）放进 Startup 文件夹即可（见 install_autostart.py）。
' 安全：只调用 auto_patch.py；不碰账号数据；Zalo 运行时不会覆盖 app.asar。

Option Explicit

Dim shell, pyw, script, intervalMs, bootDelayMs
Dim fso, here, logPath

Set fso = CreateObject("Scripting.FileSystemObject")
here = fso.GetParentFolderName(WScript.ScriptFullName)
script = fso.BuildPath(fso.BuildPath(here, "work"), "auto_patch.py")
logPath = fso.BuildPath(fso.BuildPath(here, "work"), "watchdog.log")

' pythonw.exe：优先用系统 Python 3.13，其次 WorkBuddy 内置 Python
pyw = "C:\Users\laogu\AppData\Local\Programs\Python\Python313\pythonw.exe"
If Not fso.FileExists(pyw) Then
    pyw = "C:\Users\laogu\.workbuddy\binaries\python\versions\3.13.12\pythonw.exe"
End If

intervalMs = 600000   ' 每 10 分钟检查一次
bootDelayMs = 20000   ' 开机后先等 20 秒，避开系统启动高峰

Function LogLine(msg)
    On Error Resume Next
    Dim f
    Set f = fso.OpenTextFile(logPath, 8, True)
    f.WriteLine "[" & Now & "] " & msg
    f.Close
End Function

Set shell = CreateObject("WScript.Shell")

' 开机延迟
WScript.Sleep bootDelayMs

Do
    LogLine "watchdog tick -> auto_patch.py"
    On Error Resume Next
    ' 0 = 隐藏窗口, True = 等待完成（避免并发跑多个流水线）
    shell.Run """" & pyw & """ """ & script & """", 0, True
    If Err.Number <> 0 Then
        LogLine "ERROR running auto_patch: " & Err.Description
        Err.Clear
    End If
    On Error GoTo 0
    WScript.Sleep intervalMs
Loop
