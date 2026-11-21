// Zalo PC 翻译注入一键安装器（Windows exe，Go 交叉编译）
//
// 双击运行，自动完成：
//   1. 找 work 文件夹（exe 自身目录 / D:\project\zalo\zalopc\work / 全盘搜索 / 新建）
//   2. 找 Zalo 安装目录（%LOCALAPPDATA%\Programs\Zalo 下的 Zalo-* 版本文件夹）
//   3. 把内嵌的 auto_patch.py + translate/ 覆盖进 work 文件夹
//   4. 用系统 Python 跑 auto_patch.py（检测→补翻译注入→替换 asar→重启 Zalo）
//   5. 显示结果，按回车退出
//
// 依赖（用户机器上已有，他一直在手动跑 auto_patch.py）：
//   - Python（python 命令在 PATH 里）
//   - Node.js + npx（auto_patch.py 用 npx --yes @electron/asar 解包/打包）
//   - Zalo 已安装且已汉化（未汉化时走全流程重打，需要 work 里的种子词库；
//     本 exe 只带 auto_patch.py + translate/，种子词库不在里面，
//     所以首次汉化仍需他原有的完整 work 文件夹）
//
// 构建（Linux 沙盒交叉编译出 Windows exe）：
//   cd pc/installer && ./build-installer.sh
// 构建脚本先把 pc/work/auto_patch.py 与 pc/work/translate/* 拷进 files/，
// 再 GOOS=windows GOARCH=amd64 go build。
package main

import (
	_ "embed"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

//go:embed files/auto_patch.py
var embeddedAutoPatch []byte

//go:embed files/translate/zalocn-translate.js
var embeddedTranslateJS []byte

//go:embed files/translate/install-translate.ps1
var embeddedInstallPS1 []byte

//go:embed files/translate/restore-translate.ps1
var embeddedRestorePS1 []byte

//go:embed files/translate/README.md
var embeddedTranslateReadme []byte

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
//  2. D:\project\zalo\zalopc\work
//  3. 全盘搜索 \project\zalo\zalopc\work\auto_patch.py
//  4. 都没有：在 D 盘新建一个（只够跑翻译单补丁；全流程重打仍需原有种子词库）
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
	// 全盘搜索（只查固定盘符，跳过光驱/网络盘）
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
				// 剪枝：只进 project/zalo 相关的目录，别的目录不钻
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
	// 都没有：新建默认位置
	p := `D:\` + defaultWorkRel
	_ = os.MkdirAll(filepath.Join(p, "translate"), 0755)
	return p
}

var zaloVerRe = regexp.MustCompile(`(?i)Zalo-(\d+)\.(\d+)\.(\d+)$`)

// findZaloRoot 找 Zalo 安装目录：
//  1. %LOCALAPPDATA%\Programs\Zalo 下含 resources\app.asar 的 Zalo-* 文件夹（取最高版本）
//  2. 正在运行的 Zalo.exe 所在目录（tasklist + wmic 取路径）
//  3. 找不到返回 ""（auto_patch.py 用内置默认路径）
func findZaloRoot() string {
	localAppData := os.Getenv("LOCALAPPDATA")
	if localAppData == "" {
		if home := os.Getenv("USERPROFILE"); home != "" {
			localAppData = filepath.Join(home, "AppData", "Local")
		}
	}
	if localAppData != "" {
		root := filepath.Join(localAppData, "Programs", "Zalo")
		entries, err := os.ReadDir(root)
		if err == nil {
			type ver struct {
				dir string
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
				if !fileExists(filepath.Join(root, e.Name(), "resources", "app.asar")) {
					continue
				}
				var v ver
				fmt.Sscanf(m[1], "%d", &v.maj)
				fmt.Sscanf(m[2], "%d", &v.min)
				fmt.Sscanf(m[3], "%d", &v.pat)
				v.dir = filepath.Join(root, e.Name())
				vers = append(vers, v)
			}
			if len(vers) > 0 {
				sort.Slice(vers, func(i, j int) bool {
					if vers[i].maj != vers[j].maj {
						return vers[i].maj < vers[j].maj
					}
					if vers[i].min != vers[j].min {
						return vers[i].min < vers[j].min
					}
					return vers[i].pat < vers[j].pat
				})
				return filepath.Join(localAppData, "Programs", "Zalo")
			}
		}
	}
	// 正在运行的 Zalo.exe：wmic 取可执行路径
	out, err := exec.Command("wmic", "process", "where", "name='Zalo.exe'", "get", "ExecutablePath", "/value").Output()
	if err == nil {
		for _, line := range strings.Split(string(out), "\n") {
			line = strings.TrimSpace(line)
			if strings.HasPrefix(line, "ExecutablePath=") {
				p := strings.TrimPrefix(line, "ExecutablePath=")
				p = strings.TrimSpace(p)
				if p != "" {
					// Zalo.exe 在 <版本>\Zalo.exe，安装根目录是其上两级
					d := filepath.Dir(p)
					root := filepath.Dir(d)
					if dirExists(filepath.Join(d, "resources", "app.asar")) {
						return root
					}
					if dirExists(filepath.Join(root, "resources")) {
						return root
					}
				}
			}
		}
	}
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

func writeFile(path string, data []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	return os.WriteFile(path, data, 0644)
}

func main() {
	fmt.Println("Zalo PC 翻译注入一键安装器")
	fmt.Println("==============================")

	// 1. 找 work 文件夹
	workDir := findWorkDir()
	fmt.Printf("[1/4] work 文件夹：%s\n", workDir)

	// 2. 找 Zalo 安装目录
	zaloRoot := findZaloRoot()
	if zaloRoot != "" {
		fmt.Printf("[2/4] Zalo 安装目录：%s\n", zaloRoot)
	} else {
		fmt.Println("[2/4] 没找到 Zalo 安装目录，用 auto_patch.py 内置默认路径")
	}

	// 3. 覆盖文件
	fmt.Println("[3/4] 正在覆盖 auto_patch.py 与 translate/ …")
	files := map[string][]byte{
		"auto_patch.py":                embeddedAutoPatch,
		"translate/zalocn-translate.js": embeddedTranslateJS,
		"translate/install-translate.ps1": embeddedInstallPS1,
		"translate/restore-translate.ps1": embeddedRestorePS1,
		"translate/README.md":            embeddedTranslateReadme,
	}
	for rel, data := range files {
		if err := writeFile(filepath.Join(workDir, rel), data); err != nil {
			fmt.Printf("  写文件失败 %s：%v\n", rel, err)
			fmt.Println("按回车退出。")
			fmt.Scanln()
			os.Exit(1)
		}
	}
	fmt.Println("  覆盖完成。")

	// 4. 跑 auto_patch.py
	pythonExe := findPython()
	fmt.Printf("[4/4] 正在跑补丁（%s auto_patch.py）…\n", pythonExe)
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
		fmt.Printf("补丁运行失败：%v\n", err)
		fmt.Println("请把上面的日志发给我。")
	} else {
		fmt.Println("------------------------------")
		fmt.Println("完成。Zalo 应该已经自动重启并带上翻译。")
	}
	fmt.Println("按回车退出。")
	fmt.Scanln()
}
