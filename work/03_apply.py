# -*- coding: utf-8 -*-
"""Apply the Chinese patch (scheme B) to extracted Zalo PC files.

B = "中文 replaces the built-in Vietnamese slot; English stays real":
  1. lang-vi webpack chunk: every dictionary value becomes the Chinese
     translation (zh_dict.json), key order preserved.
  2. Every inline `{...en:..,vi:..}` object in the bundles: the vi-field
     string value becomes Chinese (zh_text.json), the en field untouched.
  3. The hard-coded language switch menu label `text:"Tiếng Việt"` becomes
     `text:"中文"` (language name shown for the Chinese option).

Files are copied from zalopc/extract into zalopc/build_app, patched there,
so the pristine extraction stays untouched. Prints a summary and writes
zalopc/work/patched_files.json for the repack step.
"""
import io, sys, os, re, json, glob, shutil

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
EXTRACT = os.path.join(HERE, "..", "extract")
BUILD = os.path.join(HERE, "..", "build_app")

CHUNK_FILE = "pc-dist/lazy/lang-vi.88db564c02d731a9af93.js"
LANG_VI_SRC = os.path.join(HERE, "lang_vi.json")

# files known to embed inline bilingual {en,vi} objects (from 01_parse)
INLINE_FILES = [
    "pc-dist/sync-v2-sub-worker.96f2410660e9f7b98dfd.js",
    "pc-dist/search-worker.96f2410660e9f7b98dfd.js",
    "pc-dist/lazy/default-login-main-startup-shared-worker-znotification.c328b26bacf4d868a59a.js",
    "pc-dist/compact-app-pc.96f2410660e9f7b98dfd.js",
    "pc-dist/lazy/main-startup.d6040e7ac34cd3cdca4b.js",
    "main-dist/preload-sqlite.js",
]

LABEL_SRC = 'text:"Tiếng Việt"'
LABEL_DST = 'text:"中文"'

FLAT = re.compile(r"\{[^{}]*\}")
PROP = re.compile(r"\b(vi)\b\s*:\s*(\"(?:[^\"\\]|\\.)*\")")
# simple unescape of a JS double-quoted string literal content
def js_unescape(s):
    out = []
    i = 0
    mp = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\", "/": "/", "b": "\b", "f": "\f", "0": "\0"}
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            if n == "u":
                try:
                    out.append(chr(int(s[i + 2:i + 6], 16))); i += 6; continue
                except ValueError:
                    pass
            out.append(mp.get(n, n)); i += 2; continue
        out.append(c); i += 1
    return "".join(out)

def js_quote(content, quote='"'):
    """Quote content for use inside a JS double-quoted string literal."""
    s = content.replace("\\", "\\\\").replace('"', '\\"')
    s = s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    s = s.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    return quote + s + quote

def main():
    zh_dict = json.load(open(os.path.join(HERE, "zh_dict.json"), encoding="utf-8"))
    zh_text = json.load(open(os.path.join(HERE, "zh_text.json"), encoding="utf-8"))
    vi_dict = json.load(open(LANG_VI_SRC, encoding="utf-8"))

    # 1) ---- rebuild lang-vi chunk inner JSON ----
    src_path = os.path.join(EXTRACT, CHUNK_FILE)
    src = open(src_path, encoding="utf-8").read()
    marker = "JSON.parse('"
    i = src.index(marker) + len(marker)
    j = i
    while True:
        if src[j] == "\\":
            j += 2; continue
        if src[j] == "'":
            break
        j += 1
    inner = src[i:j]

    new_dict = {}
    changed_keys = 0
    for k, v in vi_dict.items():
        zh = zh_dict.get(k)
        if zh is not None and zh != v:
            changed_keys += 1
        new_dict[k] = zh if zh is not None else v
    print("lang-vi keys:", len(new_dict), "changed:", changed_keys)

    new_json = json.dumps(new_dict, ensure_ascii=False, separators=(",", ":"))
    # JS-escape for single-quoted literal: backslashes doubled, apostrophes escaped
    esc = new_json.replace("\\", "\\\\").replace("'", "\\'")
    assert js_unescape(esc) == new_json, "escaping round-trip failed"
    assert len(esc) >= 2

    # copy tree first
    if os.path.exists(BUILD):
        shutil.rmtree(BUILD)
    shutil.copytree(EXTRACT, BUILD)

    out_path = os.path.join(BUILD, CHUNK_FILE)
    patched_src = src[:i] + esc + src[j:]
    open(out_path, "w", encoding="utf-8", newline="").write(patched_src)
    patched = [CHUNK_FILE]

    # 2) ---- inline vi fields ----
    total_vi_repl = 0
    for rel in INLINE_FILES:
        p = os.path.join(BUILD, rel)
        if not os.path.exists(p):
            print("missing file", rel)
            continue
        s = open(p, encoding="utf-8").read()
        repl = []
        for om in FLAT.finditer(s):
            region = om.group(0)
            for m in PROP.finditer(region):
                raw_val = m.group(2)
                val = js_unescape(raw_val[1:-1])
                if val in zh_text and zh_text[val] != val:
                    # replace only the literal content between the quotes
                    q0 = m.start(2) + 1
                    q1 = m.end(2) - 1
                    repl.append((om.start() + q0, om.start() + q1, zh_text[val]))
        # apply in reverse offset order
        for a, b, zh in sorted(repl, reverse=True):
            s = s[:a] + js_quote(zh, '"')[1:-1] + s[b:]
        open(p, "w", encoding="utf-8", newline="").write(s)
        print("%-70s vi-field replaced: %-4d" % (rel, len(repl)))
        total_vi_repl += len(repl)
        if repl:
            patched.append(rel)

    # 3) ---- hard-coded language-switch labels across the whole tree ----
    label_hits = []
    for base in ("pc-dist", "main-dist"):
        root = os.path.join(BUILD, base)
        for dp, _dn, fns in os.walk(root):
            for fn in fns:
                if not fn.endswith(".js"):
                    continue
                p = os.path.join(dp, fn)
                s = open(p, encoding="utf-8").read()
                n = s.count(LABEL_SRC)
                if n:
                    s = s.replace(LABEL_SRC, LABEL_DST)
                    open(p, "w", encoding="utf-8", newline="").write(s)
                    rel = os.path.relpath(p, BUILD).replace("\\", "/")
                    label_hits.append((rel, n))
                    patched.append(rel)
    for rel, n in label_hits:
        print("label renamed: %-70s x%d" % (rel, n))

    print("total vi-field replacements:", total_vi_repl)
    json.dump({"patched": patched}, open(os.path.join(HERE, "patched_files.json"), "w"), indent=1)
    print("wrote build_app tree + patched_files.json")

if __name__ == "__main__":
    main()
