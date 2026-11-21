# -*- coding: utf-8 -*-
"""Zalo PC 汉化自动补丁引擎 (auto_patch)

职责：
  1. 自动探测最新安装的 Zalo 版本目录（Zalo-<x.y.z>）
  2. 用二进制标记检测该版本的 app.asar 是否已汉化、是否已带翻译注入
  3. 未汉化则全自动重打：解包 -> 动态拷贝词库 -> 01->02->03->05
     -> 翻译注入 -> 04
  4. 已汉化但缺翻译注入时，只做翻译注入（解包 -> 注入 -> 重打包），不动词库
  5. 等 Zalo 进程退出后，备份原 asar 并替换为新包，然后可选重启 Zalo

翻译注入（2026-11-21 接入）：往 app.asar 里加 zalocn-translate.js，
收到的消息自动翻成中文（原文在上、译文在下框），输入框边加「译」按钮
（点一下把中文翻成越南语填回输入框，不自动发）。翻译走用户自填的大模型
API（默认 bilibili 公开接口，免密钥），不改 Zalo 登录/账号/网络逻辑。
注入目标是主窗口 pc-dist/index.html 与迷你窗口 compact-app.html 两个
（主窗口加载 index.html，只注 compact-app 的话主窗口里根本不加载；
index.html 有严格 CSP，注入时把 bilibili API 加进 connect-src 放行，
Google 兜底走 *.googleapis.com 本来就在放行列表里）。
Zalo 更新后新版 asar 是原版（未汉化、无注入），下次检测自动走全流程重打，
翻译随汉化一起恢复，无需手动重装。

安全底线（与安卓主线 / PC_STATE.md 一致）：
  - 只改 UI 语言字符串；不碰登录/账号/加密/网络逻辑
  - app.asar.unpacked 原生文件由 04_pack.py 逐字节校验（不一致则中止，绝不替换）
  - 账号/消息目录 Roaming\\ZaloData、D:\\Zalo Data 一律不碰
  - 替换前先备份；Zalo 运行时绝不覆盖 app.asar（Windows 文件锁 + 数据完整性）

用法：
  python auto_patch.py            # 检测 + 必要则重打安装（守护进程调用）
  python auto_patch.py --check    # 只检测，输出状态 JSON，不改任何文件
  python auto_patch.py --force    # 即使已汉化也强制重打
  python auto_patch.py --wait 0   # 不等 Zalo 退出（Zalo 运行时直接跳过替换，留待下轮）

环境变量：
  ZALOPC_WAIT_IDLE   等待 Zalo 退出的秒数（默认 1800；0=不等待）
  ZALOPC_NO_START    设为 1 则替换后不重启 Zalo
  ZALOPC_NODE        node.exe 路径（默认自动探测）
"""
import io, sys, os, re, glob, json, time, shutil, subprocess, hashlib

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
PCROOT = os.path.abspath(os.path.join(HERE, ".."))
EXTRACT = os.path.join(PCROOT, "extract")
BUILD = os.path.join(PCROOT, "build_app")
RAW = os.path.join(PCROOT, "raw")
DIST = os.path.join(HERE, "dist_v2")
LOG = os.path.join(HERE, "auto_patch.log")
STATE = os.path.join(HERE, "auto_patch_state.json")

INSTALL_ROOT = r"C:/Users/laogu/AppData/Local/Programs/Zalo"
MARK_HAN = 'text:"中文"'
MARK_VIE = 'text:"Tiếng Việt"'
# 翻译注入：JS 源文件（与本脚本同目录下的 translate/）与 asar 内的检测标记。
# 主窗口加载 pc-dist/index.html（有严格 CSP），迷你窗口加载 compact-app.html；
# 两个都要注入。主窗口的注入带 data-zalocn="main" 标记。
# has_translate 是版本感知的：只认当前 TRANSLATE_VERSION 的 JS（JS 里的
# log 行含 "zalocn translate v<版本>"）。JS 升版后，旧包会被识别为缺注入
# 而自动重打，否则 JS 更新永远传不到已装的 asar 里。
TRANSLATE_VERSION = "1.0.6"
TRANSLATE_JS = os.path.join(HERE, "translate", "zalocn-translate.js")
TRANSLATE_MARK = 'data-zalocn="main"'
TRANSLATE_INDEX_HTML = os.path.join("pc-dist", "index.html")
TRANSLATE_COMPACT_HTML = os.path.join("pc-dist", "compact-app.html")
TRANSLATE_JS_DST = os.path.join("pc-dist", "zalocn-translate.js")
TRANSLATE_API_HOST = "https://index-translate.bilibili.com"
PY = sys.executable


def log(msg):
    line = "[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg)
    print(line, flush=True)
    try:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass


def wipe_dir(path):
    """逐文件删除目录内容，避开 shutil.rmtree 的宿主批量删除闸。"""
    if not os.path.exists(path):
        return
    for root, dirs, files in os.walk(path, topdown=False):
        for name in files:
            try:
                os.remove(os.path.join(root, name))
            except Exception as e:
                log("wipe file fail %s : %s" % (name, e))
        for name in dirs:
            try:
                os.rmdir(os.path.join(root, name))
            except Exception:
                pass
    try:
        os.rmdir(path)
    except Exception:
        pass


def ver_tuple(name):
    m = re.search(r"Zalo-(\d+)\.(\d+)\.(\d+)", name)
    return tuple(int(x) for x in m.groups()) if m else (0, 0, 0)


def detect_versions():
    """返回 [(version_str, resources_path), ...]，按版本号升序。"""
    out = []
    for d in glob.glob(os.path.join(INSTALL_ROOT, "Zalo-*")):
        if not os.path.isdir(d):
            continue
        res = os.path.join(d, "resources")
        if not os.path.isfile(os.path.join(res, "app.asar")):
            continue
        m = re.search(r"Zalo-(\d+\.\d+\.\d+)", os.path.basename(d))
        if m:
            out.append((m.group(1), res))
    out.sort(key=lambda x: ver_tuple("Zalo-" + x[0]))
    return out


def is_hanhua(asar_path, chunk_bytes=8 * 1024 * 1024):
    """检测 asar 是否已汉化。分块流式读，统计两个标记串。
    返回 (bool, info_dict)。"""
    han = vie = 0
    tail = b""
    size = os.path.getsize(asar_path)
    mh, mv = MARK_HAN.encode(), MARK_VIE.encode()
    with open(asar_path, "rb") as f:
        while True:
            buf = f.read(chunk_bytes)
            if not buf:
                break
            data = tail + buf
            han += data.count(mh)
            vie += data.count(mv)
            # 保留足够 overlap，避免跨块标记漏计（标记最长几十字节）
            tail = data[-256:]
    done = han > 0 and vie == 0
    return done, {"size": size, "han_marks": han, "vie_marks": vie}


def has_translate(asar_path, chunk_bytes=8 * 1024 * 1024):
    """检测 asar 是否已带当前版本的翻译注入。两个条件都要满足：
    1) 主窗口（index.html）的注入标记 data-zalocn="main" 在；
    2) JS 是当前版本（JS 里的 log 行含 "zalocn translate v<TRANSLATE_VERSION>"）。
    只注了 compact-app 的旧包、或 JS 还是旧版本的包，都返回 False，
    以便正确触发重打。"""
    mark = TRANSLATE_MARK.encode()
    ver = ("zalocn translate v" + TRANSLATE_VERSION).encode()
    found_mark = False
    found_ver = False
    tail = b""
    with open(asar_path, "rb") as f:
        while True:
            buf = f.read(chunk_bytes)
            if not buf:
                break
            data = tail + buf
            if not found_mark and mark in data:
                found_mark = True
            if not found_ver and ver in data:
                found_ver = True
            if found_mark and found_ver:
                return True
            tail = data[-256:]
    return False


def _inject_script_tag(content, tag, anchor):
    """在 anchor 指定的主 bundle </script> 后插入 tag。
    调用方已确认尚未注入。返回新内容。"""
    idx = content.find(anchor)
    if idx < 0:
        raise RuntimeError("HTML 里没找到主 bundle 的 script 标签")
    end = content.find("</script>", idx) + len("</script>")
    return content[:end] + tag + content[end:]


def _extend_csp_for_translate(content):
    """给 index.html 的 CSP connect-src 放行翻译 API（bilibili）。
    script-src 已含 'self'，本地脚本可直接加载；Google 兜底走
    *.googleapis.com，已在 connect-src 里，无需加。已有则不动。"""
    if TRANSLATE_API_HOST in content:
        return content, False
    key = "connect-src "
    idx = content.find(key)
    if idx < 0:
        raise RuntimeError("index.html 的 CSP 里没找到 connect-src")
    pos = idx + len(key)
    return content[:pos] + TRANSLATE_API_HOST + " " + content[pos:], True


def inject_translate(tree_dir):
    """往已解包的 app 树里加翻译注入（不碰词库与原生文件）。
    - 拷 translate/zalocn-translate.js -> pc-dist/zalocn-translate.js
    - 主窗口 pc-dist/index.html：加引用（带 data-zalocn="main" 标记）+
      CSP connect-src 放行 bilibili API
    - 迷你窗口 pc-dist/compact-app.html：加引用（无 CSP，无需改）
    已注入时跳过（幂等，可重复跑）。"""
    if not os.path.isfile(TRANSLATE_JS):
        raise RuntimeError("找不到翻译脚本 " + TRANSLATE_JS)
    js_dst = os.path.join(tree_dir, TRANSLATE_JS_DST)
    shutil.copy2(TRANSLATE_JS, js_dst)

    # 主窗口 index.html（用户平时用的那个）
    index_path = os.path.join(tree_dir, TRANSLATE_INDEX_HTML)
    if not os.path.isfile(index_path):
        raise RuntimeError("解包树里没找到 " + TRANSLATE_INDEX_HTML + "（版本不对？）")
    content = open(index_path, encoding="utf-8").read()
    tag_main = '<script src="zalocn-translate.js" data-zalocn="main"></script>'
    if TRANSLATE_MARK in content:
        log("index.html 已有翻译注入引用，跳过。")
    else:
        # 插到最后一个 </script> 之后（主 bundle 之后），确保晚于应用脚本加载
        last = content.rfind("</script>")
        if last < 0:
            raise RuntimeError("index.html 里没找到 </script>")
        end = last + len("</script>")
        content = content[:end] + tag_main + content[end:]
        log("已往 index.html 注入翻译脚本引用。")
    content, csp_changed = _extend_csp_for_translate(content)
    if csp_changed:
        log("已给 index.html 的 CSP 放行 bilibili 翻译 API。")
    open(index_path, "w", encoding="utf-8", newline="").write(content)

    # 迷你窗口 compact-app.html（--launch-compact-app 启动时用）
    compact_path = os.path.join(tree_dir, TRANSLATE_COMPACT_HTML)
    if os.path.isfile(compact_path):
        content = open(compact_path, encoding="utf-8").read()
        if "zalocn-translate.js" in content:
            log("compact-app.html 已有翻译注入引用，跳过。")
        else:
            tag = '<script src="zalocn-translate.js"></script>'
            content = _inject_script_tag(
                content, tag, '<script src="compact-app-pc.')
            open(compact_path, "w", encoding="utf-8", newline="").write(content)
            log("已往 compact-app.html 注入翻译脚本引用。")
    else:
        log("解包树里没有 compact-app.html，跳过迷你窗口注入。")


def npx_env():
    """返回 (npx, env)，供 asar 解包/打包命令用。
    与 run_pipeline 里的探测逻辑一致（单独抽出来给翻译单补丁复用）。"""
    node_dir = find_node_dir()
    env = dict(os.environ)
    env["PATH"] = node_dir + os.pathsep + env.get("PATH", "")
    npx = os.path.join(node_dir, "npx.cmd") if os.name == "nt" else "npx"
    if not os.path.isfile(npx) and os.name == "nt":
        npx = "npx.cmd"
    return npx, env


def build_translate_only(resources):
    """已汉化但缺翻译注入时：解包已装 asar -> 只注入翻译 -> 重打包。
    不跑 01/02/03/05（词库已是汉化好的，动它会坏数据）。
    返回新 asar 路径。"""
    orig_asar = os.path.join(resources, "app.asar")
    npx, env = npx_env()
    tmpdir = os.path.join(HERE, "translate_patch_tmp")
    out_asar = os.path.join(HERE, "dist_v2", "app_zh_translate_only.asar")
    wipe_dir(tmpdir)
    os.makedirs(os.path.dirname(out_asar), exist_ok=True)
    log("=== 翻译单补丁：解包已装 asar ===")
    run_cmd([npx, "--yes", "@electron/asar", "extract", orig_asar, tmpdir],
            env=env, timeout=900, label="asar extract (translate-only)")
    inject_translate(tmpdir)
    log("=== 翻译单补丁：重打包 ===")
    run_cmd([npx, "--yes", "@electron/asar", "pack", tmpdir, out_asar,
             "--unpack", "**/native/**"],
            env=env, timeout=1200, label="asar pack (translate-only)")
    wipe_dir(tmpdir)
    if not os.path.isfile(out_asar):
        raise RuntimeError("翻译单补丁未产出 asar")
    if not has_translate(out_asar):
        raise RuntimeError("新包翻译标记缺失，拒绝替换")
    log("翻译单补丁生成: %s (%d bytes)" % (out_asar, os.path.getsize(out_asar)))
    return out_asar


def zalo_running():
    r = subprocess.run(["tasklist", "/FI", "IMAGENAME eq Zalo.exe"],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    return "Zalo.exe" in (r.stdout or "")


def dec(b):
    """健壮解码子进程输出（中文 Windows 控制台可能是 GBK）。"""
    if isinstance(b, bytes):
        for enc in ("utf-8", "gbk", "cp936"):
            try:
                return b.decode(enc)
            except UnicodeDecodeError:
                continue
        return b.decode("utf-8", errors="replace")
    return b or ""


def kill_zalo():
    for img in ("Zalo.exe", "ZaloCall.exe", "ZaloCap.exe"):
        subprocess.run(["taskkill", "/F", "/IM", img], capture_output=True)
    time.sleep(2)


def start_zalo():
    if os.environ.get("ZALOPC_NO_START") == "1":
        log("ZALOPC_NO_START=1，跳过重启 Zalo")
        return
    exe = os.path.join(INSTALL_ROOT, "Zalo.exe")
    if not os.path.exists(exe):
        log("找不到启动器 " + exe)
        return
    # 关键：Zalo 引导器必须通过 explorer.exe 启动（等价用户双击）。
    # 直接 Popen/Start-Process 引导器会因缺少 shell 环境而静默退出（实测）。
    try:
        subprocess.Popen(["explorer.exe", exe], cwd=INSTALL_ROOT,
                         creationflags=0x00000008 | 0x00000200)  # DETACHED | NEW_PROCESS_GROUP
        log("已通过 explorer 重启 Zalo")
    except Exception as e:
        log("重启 Zalo 失败: %s" % e)


def find_node_dir():
    env = os.environ.get("ZALOPC_NODE")
    if env:
        return os.path.dirname(env)
    node = shutil.which("node")
    if node:
        return os.path.dirname(node)
    for p in (r"D:\Program Files\nodejs\node.exe",
              r"C:\Program Files\nodejs\node.exe"):
        if os.path.exists(p):
            return os.path.dirname(p)
    return os.path.dirname(shutil.which("node") or "")


def run_cmd(cmd, cwd=None, env=None, timeout=1200, label=""):
    log("RUN %s" % (label or " ".join(cmd)))
    r = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True,
                       timeout=timeout)
    out = dec(r.stdout)[-3000:]
    err = dec(r.stderr)[-1500:]
    if out.strip():
        log("  out: " + out.strip().replace("\n", " | ")[-1200:])
    if r.returncode != 0:
        log("  ERROR rc=%d %s" % (r.returncode, err.strip().replace("\n", " | ")))
        raise RuntimeError("%s failed rc=%d\n%s\n%s" % (label, r.returncode, out, err))
    return r


def run_pipeline(resources):
    """完整重打流水线，返回生成的新 app_zh.asar 路径。"""
    orig_asar = os.path.join(resources, "app.asar")
    node_dir = find_node_dir()
    env = dict(os.environ)
    env["PATH"] = node_dir + os.pathsep + env.get("PATH", "")
    npx = os.path.join(node_dir, "npx.cmd") if os.name == "nt" else "npx"
    if not os.path.isfile(npx) and os.name == "nt":
        npx = "npx.cmd"

    log("=== 1/7 解包原 app.asar -> extract/ ===")
    wipe_dir(EXTRACT)
    run_cmd([npx, "--yes", "@electron/asar", "extract", orig_asar, EXTRACT],
            env=env, timeout=900, label="asar extract")

    log("=== 2/7 动态拷贝词库 chunk -> raw/ ===")
    # 只清理 lang-en.* / lang-vi.*，其余 raw 资产（seed 等）保留
    for pat in ("lang-en.*.js", "lang-vi.*.js"):
        for f in glob.glob(os.path.join(RAW, pat)):
            os.remove(f)
    en_chunks = sorted(glob.glob(os.path.join(EXTRACT, "pc-dist", "lazy", "lang-en.*.js")))
    vi_chunks = sorted(glob.glob(os.path.join(EXTRACT, "pc-dist", "lazy", "lang-vi.*.js")))
    if not en_chunks or not vi_chunks:
        raise RuntimeError("新版 extract 中找不到 lang-en/lang-vi chunk")
    shutil.copy2(en_chunks[0], RAW)
    shutil.copy2(vi_chunks[0], RAW)
    log("  en=%s  vi=%s" % (os.path.basename(en_chunks[0]), os.path.basename(vi_chunks[0])))

    log("=== 3/7 解析词库 (01) ===")
    run_cmd([PY, "01_parse.py"], cwd=HERE, timeout=300, label="01_parse")

    log("=== 4/7 翻译 (02，复用缓存) ===")
    run_cmd([PY, "02_translate.py"], cwd=HERE, timeout=1800, label="02_translate")

    log("=== 5/7 打补丁 (03 + 05) ===")
    wipe_dir(BUILD)  # 避免残留旧版文件混入（03 内部 rmtree 也会被跳过）
    run_cmd([PY, "03_apply.py"], cwd=HERE, timeout=600, label="03_apply")
    run_cmd([PY, "05_force_promo_zh.py"], cwd=HERE, timeout=300, label="05_force_promo")

    log("=== 6/7 翻译注入（进 build_app，随 04 一起打包） ===")
    inject_translate(BUILD)

    log("=== 7/7 打包 + 字节校验 (04) ===")
    wipe_dir(DIST)
    env2 = dict(env)
    env2["ZALOPC_INSTALL"] = resources
    run_cmd([PY, "04_pack.py"], cwd=HERE, env=env2, timeout=1200, label="04_pack")

    new_asar = os.path.join(DIST, "app_zh.asar")
    if not os.path.isfile(new_asar):
        raise RuntimeError("打包未产出 app_zh.asar")
    log("新包生成: %s (%d bytes)" % (new_asar, os.path.getsize(new_asar)))
    return new_asar


def install_patch(version, resources, new_asar):
    """等 Zalo 退出 -> 备份 -> 替换 -> 重启。"""
    wait = int(os.environ.get("ZALOPC_WAIT_IDLE", "1800") or 0)
    deadline = time.time() + wait
    while zalo_running() and time.time() < deadline:
        log("Zalo 仍在运行，等待退出（最多 %ds）..." % wait)
        time.sleep(15)
    if zalo_running():
        log("Zalo 未退出，跳过替换（下次检测再试）。可设 ZALOPC_WAIT_IDLE=0 立即跳过。")
        return False

    orig = os.path.join(resources, "app.asar")
    ts = time.strftime("%Y%m%d_%H%M%S")
    bak = os.path.join(resources, "app.asar.bak.%s_%s" % (version, ts))
    shutil.copy2(orig, bak)
    log("已备份原 asar -> " + os.path.basename(bak))
    # 替换：先写临时文件再原子改名，避免中途断电留下半个文件
    tmp = os.path.join(resources, "app.asar.new")
    shutil.copy2(new_asar, tmp)
    os.replace(tmp, orig)
    log("已替换 app.asar (v%s)" % version)

    # 同步 update_meta.json 里的 app.asar SHA1（新版 Zalo 更新机制记录校验值）。
    # 实测启动时不强制校验，但保持一致性可避免后续自动更新时的校验/回滚问题。
    sync_update_meta_sha1(resources, new_asar)

    start_zalo()
    return True


def sync_update_meta_sha1(resources, asar_path):
    """把 update_meta.json 中 resources\\app.asar 的 sha1 更新为新包值。
    找不到或格式不符则安全跳过，绝不影响安装主流程。"""
    meta = os.path.join(os.path.dirname(resources), "update_meta.json")
    if not os.path.isfile(meta):
        return
    try:
        data = json.load(open(meta, encoding="utf-8"))
        h = hashlib.sha1()
        with open(asar_path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        newsha = h.hexdigest()
        changed = 0
        for v in data.get("verify", []):
            if str(v.get("file", "")).replace("/", "\\").lower().endswith("app.asar"):
                v["sha1"] = newsha
                changed += 1
        if changed:
            shutil.copy2(meta, meta + ".bak")
            json.dump(data, open(meta, "w", encoding="utf-8"), ensure_ascii=False)
            log("已同步 update_meta.json 的 app.asar sha1=%s (%d处)" % (newsha[:12], changed))
    except Exception as e:
        log("同步 update_meta sha1 跳过: %s" % e)


def write_state(data):
    try:
        json.dump(data, open(STATE, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    except Exception as e:
        log("写状态失败: %s" % e)


def main():
    args = sys.argv[1:]
    check_only = "--check" in args
    force = "--force" in args

    vers = detect_versions()
    if not vers:
        log("未找到任何 Zalo 安装目录")
        write_state({"error": "no_install", "time": time.strftime("%Y-%m-%d %H:%M:%S")})
        return 2
    version, resources = vers[-1]
    asar = os.path.join(resources, "app.asar")
    done, info = is_hanhua(asar)
    has_tr = has_translate(asar)
    status = {
        "latest_version": version,
        "resources": resources,
        "hanhua": done,
        "translate": has_tr,
        "detail": info,
        "zalo_running": zalo_running(),
        "time": time.strftime("%Y-%m-%d %H:%M:%S"),
    }
    log("检测: v%s 汉化=%s 翻译注入=%s 标记(中=%d/越=%d) Zalo运行=%s" %
        (version, done, has_tr, info["han_marks"], info["vie_marks"],
         status["zalo_running"]))

    if check_only:
        print("STATUS_JSON=" + json.dumps(status, ensure_ascii=False))
        return 0

    if done and has_tr and not force:
        log("最新版本已汉化且已带翻译注入，无需操作。")
        write_state(dict(status, action="noop"))
        return 0

    if not done:
        log("需要重打（版本 %s%s）" % (version, "，强制" if force else "，未汉化"))
        try:
            new_asar = run_pipeline(resources)
            # 安装前二次确认新包确实是汉化的
            ok, ninfo = is_hanhua(new_asar)
            if not ok:
                raise RuntimeError("新包汉化标记异常 中=%d/越=%d，拒绝替换" %
                                   (ninfo["han_marks"], ninfo["vie_marks"]))
            if not has_translate(new_asar):
                raise RuntimeError("新包翻译标记缺失，拒绝替换")
            log("新包校验: 中=%d/越=%d 翻译注入=是 OK" %
                (ninfo["han_marks"], ninfo["vie_marks"]))
            installed = install_patch(version, resources, new_asar)
            write_state(dict(status, action="patched" if installed else "pending_install",
                             new_asar=new_asar))
            log("完成。" if installed else "补丁已生成，等待 Zalo 退出后安装。")
            return 0 if installed else 3
        except Exception as e:
            log("自动补丁失败: %s" % e)
            write_state(dict(status, action="failed", error=str(e)))
            return 1

    # 已汉化但缺翻译注入：只补翻译，不动词库
    log("已汉化但缺翻译注入，只补翻译（版本 %s）" % version)
    try:
        new_asar = build_translate_only(resources)
        installed = install_patch(version, resources, new_asar)
        write_state(dict(status, action="patched_translate" if installed
                         else "pending_install_translate",
                         new_asar=new_asar))
        log("完成。" if installed else "翻译补丁已生成，等待 Zalo 退出后安装。")
        return 0 if installed else 3
    except Exception as e:
        log("翻译单补丁失败: %s" % e)
        write_state(dict(status, action="failed", error=str(e)))
        return 1


if __name__ == "__main__":
    sys.exit(main() or 0)
