// Zalo PC 汉化+翻译一键安装器（Windows exe，Go 交叉编译）
//
// 双击运行，自动完成：
//   1. 找 Zalo 安装目录（%LOCALAPPDATA%\Programs\Zalo 下的 Zalo-* 版本文件夹；
//      找不到就弹文件夹选择框，让使用者自己指定 Zalo 所在文件夹）
//   2. 找 work 文件夹（exe 自身目录 / D:\project\zalo\zalopc\work /
//      %LOCALAPPDATA%\Zalocn\work；新电脑没有就新建一个）
//   3. 把内嵌的完整汉化工程（auto_patch.py + 汉化脚本 + 词库 + translate/）
//      覆盖进 work 文件夹
//   4. 用系统 Python 跑 auto_patch.py（未汉化走全流程汉化+翻译；
//      已汉化只补翻译注入；替换 asar→重启 Zalo）
//   5. 询问是否安装开机守护（Zalo 更新后自动重打）
//   6. 显示结果，按回车退出
//
// 依赖（目标电脑上需要）：
//   - Python（python 命令在 PATH 里）
//   - Node.js + npx（auto_patch.py 用 npx --yes @electron/asar 解包/打包）
//   - Zalo 已安装
//
// 构建（Linux 沙盒交叉编译出 Windows exe）：
//   cd pc/installer && ./build-installer.sh
// 构建脚本先把 pc/work 与 pc/watch_zh.vbs 同步进 files/，
// 再 GOOS=windows GOARCH=amd64 go build。
package main

import (
	"embed"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

//go:embed files
var embeddedFS embed.FS

const defaultWorkRel = `project\zalo\zalopc\work`

func fileExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}

func dirExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && st.IsDir()
}

// findWorkDir 找 work 文件夹：
//  1. exe 自身目录（含 auto_patch.py 时）
//  2. D:\project\zalo\zalopc\work（老郭的工程目录）
//  3. 全盘搜索 \project\zalo\zalopc\work\auto_patch.py
//  4. %LOCALAPPDATA%\Zalocn\work（新电脑：稳定位置，供守护长期用）
//  5. 都没有：在 %LOCALAPPDATA%\Zalocn\work 新建一个
func findWorkDir() string {
	exe, err := os.Executable()
	if err == nil {
		d := filepath.Dir(exe)
		if fileExists(filepath.Join(d, "auto_patch.py")) {
			return d
		}
	}
	for _, drive := range []string{"D", "C", "E", "F"} {
		p := drive + `:\` + defaultWorkRel
		if fileExists(filepath.Join(p, "auto_patch.py")) {
			return p
		}
	}
	for _, drive := range []string{"C", "D", "E", "F", "G", "H"} {
		root := drive + `:\`
		if !dirExists(root) {
			continue
		}
		var found string
		_ = filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
			if err != nil || found != "" {
				return nil
			}
			if info.IsDir() {
				depth := strings.Count(strings.TrimPrefix(path, root), string(os.PathSeparator))
				if depth > 4 {
					return filepath.SkipDir
				}
				return nil
			}
			if info.Name() == "auto_patch.py" && strings.HasSuffix(strings.ToLower(path), `\project\zalo\zalopc\work\auto_patch.py`) {
				found = filepath.Dir(path)
			}
			return nil
		})
		if found != "" {
			return found
		}
	}
	localAppData := os.Getenv("LOCALAPPDATA")
	if localAppData != "" {
		p := filepath.Join(localAppData, "Zalocn", "work")
		_ = os.MkdirAll(p, 0755)
		return p
	}
	p := `D:\` + defaultWorkRel
	_ = os.MkdirAll(filepath.Join(p, "translate"), 0755)
	return p
}

var zaloVerRe = regexp.MustCompile(`(?i)Zalo-(\d+)\.(\d+)\.(\d+)$`)

// scanZaloRoot 在给定的 Programs\Zalo 目录下找含 resources\app.asar 的最高版本，
// 找到返回安装根目录（Programs\Zalo），找不到返回 ""。
func scanZaloRoot(programsZalo string) string {
	entries, err := os.ReadDir(programsZalo)
	if err != nil {
		return ""
	}
	type ver struct {
		maj, min, pat int
	}
	var vers []ver
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		m := zaloVerRe.FindStringSubmatch(e.Name())
		if m == nil {
			continue
		}
		if !fileExists(filepath.Join(programsZalo, e.Name(), "resources", "app.asar")) {
			continue
		}
		var v ver
		fmt.Sscanf(m[1], "%d", &v.maj)
		fmt.Sscanf(m[2], "%d", &v.min)
		fmt.Sscanf(m[3], "%d", &v.pat)
		vers = append(vers, v)
	}
	if len(vers) == 0 {
		return ""
	}
	sort.Slice(vers, func(i, j int) bool {
		if vers[i].maj != vers[j].maj {
			return vers[i].maj < vers[j].maj
		}
		if vers[i].min != vers[j].min {
			return vers[i].min < vers[j].min
		}
		return vers[i].pat < vers[j].pat
	})
	return programsZalo
}

// pickFolderViaPowerShell 弹系统文件夹选择框，让使用者自己指定 Zalo 所在文件夹。
// 选好返回该文件夹路径；取消返回 ""。
func pickFolderViaPowerShell() string {
	ps := `$f = New-Object System.Windows.Forms.FolderBrowserDialog; ` +
		`$f.Description = '请选择 Zalo 安装文件夹（里面有 Zalo-26.11.12 这类版本文件夹）'; ` +
		`$f.ShowNewFolderButton = $false; ` +
		`if ($f.ShowDialog() -eq 'OK') { $f.SelectedPath }`
	out, err := exec.Command("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
		"Add-Type -AssemblyName System.Windows.Forms; "+ps).Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

// findZaloRoot 找 Zalo 安装目录：
//  1. %LOCALAPPDATA%\Programs\Zalo（标准位置）
//  2. 正在运行的 Zalo.exe 所在目录
//  3. 弹文件夹选择框，让使用者自己指定
//  找不到返回 ""（auto_patch.py 用内置默认路径，会报错提示）。
func findZaloRoot() string {
	localAppData := os.Getenv("LOCALAPPDATA")
	if localAppData == "" {
		if home := os.Getenv("USERPROFILE"); home != "" {
			localAppData = filepath.Join(home, "AppData", "Local")
		}
	}
	if localAppData != "" {
		if r := scanZaloRoot(filepath.Join(localAppData, "Programs", "Zalo")); r != "" {
			return r
		}
	}
	// 正在运行的 Zalo.exe：wmic 取可执行路径
	out, err := exec.Command("wmic", "process", "where", "name='Zalo.exe'", "get", "ExecutablePath", "/value").Output()
	if err == nil {
		for _, line := range strings.Split(string(out), "\n") {
			line = strings.TrimSpace(line)
			if strings.HasPrefix(line, "ExecutablePath=") {
				p := strings.TrimSpace(strings.TrimPrefix(line, "ExecutablePath="))
				if p != "" {
					d := filepath.Dir(p)
					if r := scanZaloRoot(filepath.Dir(d)); r != "" {
						return r
					}
				}
			}
		}
	}
	// 自动找不到：让使用者自己指定
	fmt.Println("  没自动找到 Zalo 安装目录，请在弹出的窗口里选择 Zalo 所在文件夹。")
	picked := pickFolderViaPowerShell()
	if picked == "" {
		return ""
	}
	if r := scanZaloRoot(picked); r != "" {
		return r
	}
	// 使用者选的可能是版本文件夹本身（Zalo-26.11.12），往上一级再试
	if r := scanZaloRoot(filepath.Dir(picked)); r != "" {
		return r
	}
	fmt.Printf("  你选的文件夹里没找到 Zalo（需要里面有 Zalo-26.11.12 这类版本文件夹）：%s\n", picked)
	return ""
}

func findPython() string {
	if p, err := exec.LookPath("python"); err == nil {
		return p
	}
	if p, err := exec.LookPath("python3"); err == nil {
		return p
	}
	for _, cand := range []string{
		`C:\Python312\python.exe`, `C:\Python311\python.exe`, `C:\Python310\python.exe`,
		filepath.Join(os.Getenv("LOCALAPPDATA"), `Programs\Python\Python312\python.exe`),
		filepath.Join(os.Getenv("LOCALAPPDATA"), `Programs\Python\Python311\python.exe`),
	} {
		if fileExists(cand) {
			return cand
		}
	}
	return "python"
}

// extractAll 把内嵌的 files/ 整棵树写进 workDir（覆盖同名文件）。
func extractAll(workDir string) error {
	return fs.WalkDir(embeddedFS, "files", func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel("files", path)
		if err != nil {
			return err
		}
		if rel == "." {
			return nil
		}
		dst := filepath.Join(workDir, rel)
		if d.IsDir() {
			return os.MkdirAll(dst, 0755)
		}
		data, err := embeddedFS.ReadFile(path)
		if err != nil {
			return err
		}
		if err := os.MkdirAll(filepath.Dir(dst), 0755); err != nil {
			return err
		}
		return os.WriteFile(dst, data, 0644)
	})
}

// installDaemon 问使用者是否安装开机守护（Zalo 更新后自动重打）。
// 装：在 Startup 文件夹建 watch_zh.vbs 的快捷方式。
func installDaemon(workDir string) {
	fmt.Print("是否安装开机守护（Zalo 更新后自动重打翻译，推荐装）？[Y/n] ")
	var ans string
	fmt.Scanln(&ans)
	ans = strings.ToLower(strings.TrimSpace(ans))
	if ans != "" && ans != "y" && ans != "yes" {
		fmt.Println("  跳过开机守护。以后想装，手动把 work 文件夹里的 watch_zh.vbs 放进 Startup 即可。")
		return
	}
	vbs := filepath.Join(workDir, "watch_zh.vbs")
	if !fileExists(vbs) {
		fmt.Println("  没找到 watch_zh.vbs，跳过。")
		return
	}
	startup := filepath.Join(os.Getenv("APPDATA"), `Microsoft\Windows\Start Menu\Programs\Startup`)
	ps := `$s = (New-Object -ComObject WScript.Shell).CreateShortcut('` + filepath.Join(startup, "ZalocnWatch.lnk") + `'); ` +
		`$s.TargetPath = 'wscript.exe'; ` +
		`$s.Arguments = '"` + vbs + `"'; ` +
		`$s.WorkingDirectory = '` + workDir + `'; $s.Save()`
	if err := exec.Command("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps).Run(); err != nil {
		fmt.Printf("  开机守护安装失败：%v（可手动把 watch_zh.vbs 放进 Startup）\n", err)
		return
	}
	fmt.Println("  开机守护已安装。")
}

func main() {
	fmt.Println("Zalo PC 汉化+翻译一键安装器")
	fmt.Println("==============================")

	// 1. 找 Zalo 安装目录（找不到就让使用者自己指定）
	zaloRoot := findZaloRoot()
	if zaloRoot != "" {
		fmt.Printf("[1/5] Zalo 安装目录：%s\n", zaloRoot)
	} else {
		fmt.Println("[1/5] 没找到 Zalo 安装目录，将用 auto_patch.py 内置默认路径")
	}

	// 2. 找 work 文件夹
	workDir := findWorkDir()
	fmt.Printf("[2/5] work 文件夹：%s\n", workDir)

	// 3. 覆盖文件（完整汉化工程）
	fmt.Println("[3/5] 正在写入汉化工程文件 …")
	if err := extractAll(workDir); err != nil {
		fmt.Printf("  写文件失败：%v\n", err)
		fmt.Println("按回车退出。")
		fmt.Scanln()
		os.Exit(1)
	}
	fmt.Println("  写入完成。")

	// 4. 跑 auto_patch.py（未汉化走全流程；已汉化只补翻译注入）
	pythonExe := findPython()
	fmt.Printf("[4/5] 正在安装（%s auto_patch.py）…\n", pythonExe)
	fmt.Println("------------------------------")
	cmd := exec.Command(pythonExe, "auto_patch.py")
	cmd.Dir = workDir
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Stdin = os.Stdin
	if zaloRoot != "" {
		cmd.Env = append(os.Environ(), "ZALOPC_INSTALL_ROOT="+zaloRoot)
	}
	if err := cmd.Run(); err != nil {
		fmt.Println("------------------------------")
		fmt.Printf("安装失败：%v\n", err)
		fmt.Println("请把上面的日志发给我。")
		fmt.Println("按回车退出。")
		fmt.Scanln()
		os.Exit(1)
	}
	fmt.Println("------------------------------")
	fmt.Println("安装完成。Zalo 应该已经自动重启并带上汉化+翻译。")

	// 5. 开机守护
	fmt.Println("[5/5] 开机守护：")
	installDaemon(workDir)

	fmt.Println("全部完成，按回车退出。")
	fmt.Scanln()
}
