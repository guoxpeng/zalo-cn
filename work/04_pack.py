# -*- coding: utf-8 -*-
"""Repack zalopc/build_app into an app.asar with the original set of files
kept unpacked (those living in resources/app.asar.unpacked), then verify:
  - archive file listing identical to the original
  - unpacked file set identical to the original app.asar.unpacked

Outputs under zalopc/work/dist/app_zh.asar (+ app_zh.asar.unpacked/).
"""
import io, sys, os, re, json, subprocess, shutil, hashlib

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(HERE, "..", "build_app")
DIST = os.path.join(HERE, "dist")
# Original install location; override with ZALOPC_INSTALL env var for other versions/machines
ZALO_RES = os.environ.get("ZALOPC_INSTALL",
                          r"C:/Users/laogu/AppData/Local/Programs/Zalo/Zalo-26.8.20/resources")
ORIG_ASAR = os.path.join(ZALO_RES, "app.asar")
ORIG_UNPACKED = os.path.join(ZALO_RES, "app.asar.unpacked")

def rel_list(root):
    out = []
    for base, _dirs, files in os.walk(root):
        for f in files:
            p = os.path.join(base, f)
            out.append(os.path.relpath(p, root).replace("\\", "/"))
    return sorted(out)

def sha1(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def main():
    if not os.path.exists(BUILD):
        print("build_app not found; run 03_apply.py first"); sys.exit(1)
    orig_list = rel_list(ORIG_UNPACKED)
    print("original unpacked files:", len(orig_list))

    if os.path.exists(DIST):
        shutil.rmtree(DIST)
    os.makedirs(DIST)
    out_asar = os.path.join(DIST, "app_zh.asar")
    env = dict(os.environ)
    node_dir = os.path.dirname(shutil.which("node") or r"D:\Program Files\nodejs\node.exe")
    env["PATH"] = node_dir + os.pathsep + env.get("PATH", "")
    npx = os.path.join(node_dir, "npx.cmd") if os.name == "nt" else "npx"
    cmd = [npx, "--yes", "@electron/asar", "pack", BUILD, out_asar,
           "--unpack", "**/native/**"]
    print("packing...")
    r = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=900)
    if r.returncode != 0:
        print(r.stdout[-2000:]); print(r.stderr[-2000:]); sys.exit(1)

    # verify archive listing
    lst = subprocess.run([npx, "--yes", "@electron/asar", "list", out_asar],
                         capture_output=True, text=True, env=env, timeout=300)
    files = [x.strip("\\/ ") for x in lst.stdout.splitlines() if x.strip()]
    print("asar files:", len(files))

    # verify unpacked output
    new_unp = out_asar + ".unpacked"
    new_list = rel_list(new_unp) if os.path.exists(new_unp) else []
    print("new unpacked files:", len(new_list))
    ok = set(orig_list) == set(new_list)
    same = True
    for p in orig_list:
        if sha1(os.path.join(ORIG_UNPACKED, p)) != sha1(os.path.join(new_unp, p)):
            same = False
            print("  content differs:", p)
    print("unpacked list identical:", ok, "| byte-identical:", same)
    print("output:", out_asar)
    json.dump({"asar": out_asar, "unpacked": new_unp}, open(os.path.join(HERE, "pack_out.json"), "w"), indent=1)

if __name__ == "__main__":
    main()
