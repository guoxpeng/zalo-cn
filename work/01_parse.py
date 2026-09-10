# -*- coding: utf-8 -*-
"""Parse Zalo PC (Electron) language data.

Outputs (into zalopc/work):
  lang_en.json / lang_vi.json   : parsed key->text dictionaries (chunk files)
  inline.json                   : bilingual {en:..,vi:..} objects found in each bundle,
                                  with absolute char offsets inside the decoded JS text
  corpus.json                   : unique source texts to translate + which side they belong to
"""
import io, sys, os, re, json, glob

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "..", "raw")
EXTRACT = os.path.join(HERE, "..", "extract")

# ---------------- JS string literal helpers ----------------

def js_unescape(s):
    out = []
    i = 0
    mp = {"'": "'", '"': '"', "\\": "\\", "n": "\n", "t": "\t",
          "r": "\r", "b": "\b", "f": "\f", "/": "/", "0": "\0"}
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            if n == "u":
                try:
                    out.append(chr(int(s[i + 2:i + 6], 16)))
                    i += 6
                    continue
                except ValueError:
                    pass
            if n in mp:
                out.append(mp[n]); i += 2; continue
            out.append(n); i += 2; continue
        out.append(c); i += 1
    return "".join(out)

def parse_single_quoted_json_parse(filepath):
    """Return json dict stored as JSON.parse('<json>') in the webpack chunk."""
    s = open(filepath, encoding="utf-8", errors="replace").read()
    marker = "JSON.parse('"
    i = s.index(marker) + len(marker)
    buf = []
    j = i
    while True:
        c = s[j]
        if c == "\\":
            buf.append(s[j:j + 2]); j += 2; continue
        if c == "'":
            break
        buf.append(c); j += 1
    return json.loads(js_unescape("".join(buf))), s

def js_escape_for_single_quotes(text):
    """Escape a JSON-text string so it can be embedded in a JS single-quoted literal
    and yield the exact same JSON text after JS parsing."""
    # order matters: first double backslashes, then escape quotes and control chars
    text = text.replace("\\", "\\\\").replace("'", "\\'")
    out = []
    for ch in text:
        o = ord(ch)
        if o == 0x2028: out.append("\\u2028")
        elif o == 0x2029: out.append("\\u2029")
        else: out.append(ch)
    return "".join(out)

def json_text_of(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))

def dump_chunk_file(path_out, obj):
    text = json_text_of(obj)
    inner = js_escape_for_single_quotes(text)
    open(path_out, "w", encoding="utf-8", newline="").write(
        "(this.webpackJsonp=this.webpackJsonp||[]).push([[%s],{%r:function(_){_.exports=JSON.parse('%s')}}]);"
        % ("0", "x", inner))

# ---------------- main ----------------

def find_chunk(prefix):
    """Locate a lang chunk in RAW by prefix (hash suffix may change between versions)."""
    import glob as _g
    hits = _g.glob(os.path.join(RAW, prefix + "*.js"))
    if not hits:
        print("ERROR: place the app's %s*.js into zalopc/raw/ first" % prefix)
        sys.exit(1)
    return hits[0]

def main():
    lang_en_f = find_chunk("lang-en.")
    lang_vi_f = find_chunk("lang-vi.")
    print("using", os.path.basename(lang_en_f), "+", os.path.basename(lang_vi_f))
    en, en_src = parse_single_quoted_json_parse(lang_en_f)
    vi, vi_src = parse_single_quoted_json_parse(lang_vi_f)
    print("lang-en keys", len(en), "lang-vi keys", len(vi))
    assert set(en) == set(vi)
    json.dump(en, open(os.path.join(HERE, "lang_en.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    json.dump(vi, open(os.path.join(HERE, "lang_vi.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    # round-trip fidelity check for chunk writer: only the inner JSON text must match
    for name, obj, src in (("en", en, en_src), ("vi", vi, vi_src)):
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
        rebuilt_inner = js_escape_for_single_quotes(json_text_of(obj))
        print("roundtrip-inner", name, "identical:", rebuilt_inner == inner,
              "len", len(inner), len(rebuilt_inner))

    # ---- scan bilingual {en:..,vi:..} objects in all pc-dist & main-dist js ----
    # strategy: every flat object region `{...}` (no nested braces) that contains BOTH
    # an `en` and a `vi` property with string values is a display-language object.
    files = sorted(glob.glob(os.path.join(EXTRACT, "pc-dist", "**", "*.js"), recursive=True)) \
          + sorted(glob.glob(os.path.join(EXTRACT, "main-dist", "**", "*.js"), recursive=True))
    inline = {}
    corpus = set()
    per_file = []
    flat_obj = re.compile(r'\{[^{}]*\}')
    prop = re.compile(r'(\b(?:en|vi)\b)\s*:\s*("(?:[^"\\]|\\.)*")')
    for f in files:
        s = open(f, encoding="utf-8", errors="replace").read()
        hits = []
        for om in flat_obj.finditer(s):
            region = om.group(0)
            props = [(m.group(1), m.group(2), m.start(), m.end()) for m in prop.finditer(region)]
            by = {}
            for key, raw, pst, pend in props:
                by.setdefault(key, []).append((raw, pst, pend))
            if "en" in by and "vi" in by:
                en_raw, en_st, en_en = by["en"][0]
                vi_raw, vi_st, vi_en = by["vi"][0]
                en_val = js_unescape(en_raw[1:-1])
                vi_val = js_unescape(vi_raw[1:-1])
                hits.append({
                    "obj_start": om.start(), "obj_end": om.end(),
                    "en_val": en_val, "vi_val": vi_val,
                    "en_range": [om.start() + en_st, om.start() + en_en],
                    "vi_range": [om.start() + vi_st, om.start() + vi_en],
                    "region": region[:160],
                })
                if en_val.strip():
                    corpus.add((en_val, "en"))
                if vi_val.strip():
                    corpus.add((vi_val, "vi"))
        if hits:
            rel = os.path.relpath(f, EXTRACT).replace("\\", "/")
            # de-dup: identical object region repeated across duplicated module copies is kept,
            # but drop exact duplicate (same region text) within one file
            seen = set()
            uniq = []
            for h in hits:
                key = (h["region"], h["en_val"], h["vi_val"])
                if key in seen:
                    continue
                seen.add(key)
                uniq.append(h)
            inline[rel] = uniq
            per_file.append((len(uniq), rel))
    per_file.sort(reverse=True)
    print("files with inline pairs:", len(per_file))
    for c, f in per_file[:20]:
        print("  ", c, f)
    total_pairs = sum(c for c, _ in per_file)
    print("total unique inline object pairs:", total_pairs)

    json.dump(inline, open(os.path.join(HERE, "inline.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)

    # ---- corpus of dict values ----
    dict_sources = set()
    for k in en:
        if en[k].strip():
            dict_sources.add((en[k], "en", k))
        if vi[k].strip():
            dict_sources.add((vi[k], "vi", k))
    print("unique dict values en+vi:", len(dict_sources))

    corpus_dict = {}
    for text, side, key in dict_sources:
        corpus_dict.setdefault((text, side), []).append(("dict", key))
    for text, side in corpus:
        corpus_dict.setdefault((text, side), []).append(("inline", None))
    print("unique source strings total:", len(corpus_dict))

    # store serializable
    out = []
    for (text, side), origins in corpus_dict.items():
        out.append({"text": text, "side": side, "origins": origins})
    json.dump(out, open(os.path.join(HERE, "corpus.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print("written: lang_en.json lang_vi.json inline.json corpus.json")

if __name__ == "__main__":
    main()
