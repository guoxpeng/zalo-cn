# -*- coding: utf-8 -*-
"""Translate the Vietnamese UI strings of Zalo PC to Simplified Chinese.

Strategy (user-approved scheme B: Chinese replaces the built-in Vietnamese
language slot; English stays untouched):
  1. Exact seed: reuse curated Android translations (src_map_compact.json vi map,
     with the en map as concept fallback when the key's English text is known).
  2. Remaining: batch machine translation via translate.googleapis.com (gtx).
     Placeholders ($0$, {0}, %s, <b>, &nbsp;, \\n ...) are masked with %N%
     markers first so they survive translation, then restored.
     Cache on disk (resumable), polite rate limit + backoff on 429/5xx.
  3. Hand overrides (overrides.json, keyed by exact vi text) win over everything.

Outputs:
  zh_dict.json     : {dictionaryKey: zh}
  zh_text.json     : {vi_text: zh} for inline {en,vi} objects (vi side only)
  trans_cache.json : {vi_text: {"zh": zh}} MT cache
"""
import io, sys, os, re, json, time, urllib.request, urllib.parse

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = HERE
# seed file: bundled seed/src_map_compact.json first, then the Android project's copy
SEED_CANDIDATES = [
    os.path.join(WORK, "seed", "src_map_compact.json"),
    os.path.join(WORK, "..", "..", "publish", "com.zalocn", "data", "src_map_compact.json"),
]
SRC_MAP = next((p for p in SEED_CANDIDATES if os.path.exists(p)), SEED_CANDIDATES[0])
OVERRIDES = os.path.join(WORK, "overrides.json")

TOKEN_RE = re.compile(
    r"\$[0-9]+\$"                                  # $0$ $1$
    r"|\{[^{}]*\}"                                 # {0} {name}
    r"|%(?:[-+ 0-9#]*\.?[0-9]*)?[a-zA-Z%]"         # %s %d %1$s %%
    r"|<[^>]{0,80}>"                               # <b> </b> ...
    r"|&(?:[a-zA-Z0-9#]+);"                        # &nbsp; &#39;
    r"|\n"                                         # line breaks
)
MARKER_RE = re.compile(r"%(\d+)%")

def mask(text):
    """Replace placeholder tokens/newlines with %N% markers.
    Returns (masked, [original_token,...]) with marker i -> original_token[i]."""
    toks = []
    out = []
    pos = 0
    for m in TOKEN_RE.finditer(text):
        out.append(text[pos:m.start()])
        toks.append(m.group(0))
        out.append("%%%d%%" % (len(toks) - 1))
        pos = m.end()
    out.append(text[pos:])
    return "".join(out), toks

def unmask(masked, toks):
    def rep(m):
        i = int(m.group(1))
        return toks[i] if i < len(toks) else m.group(0)
    return MARKER_RE.sub(rep, masked)

def gt_batch(texts, sl="vi", tl="zh-CN"):
    """Translate a list of single-line texts (joined with \\n). Returns aligned list."""
    payload = "\n".join(texts)
    data = urllib.parse.urlencode({"q": payload, "sl": sl, "tl": tl,
                                   "client": "gtx", "dt": "t"}).encode()
    req = urllib.request.Request(
        "https://translate.googleapis.com/translate_a/single", data=data,
        headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})
    r = json.load(urllib.request.urlopen(req, timeout=40))
    segs = "".join(x[0] for x in r[0]).split("\n")
    if len(segs) != len(texts):
        r = json.load(urllib.request.urlopen(req, timeout=40))
        segs = "".join(x[0] for x in r[0]).split("\n")
    return segs

def translate_with_cache(texts, cache, log):
    budget = int(os.environ.get("ZALOPC_BUDGET", "0") or 0)  # texts to translate this run (0 = all)
    todo = [t for t in texts if not (cache.get(t) or {}).get("zh")]  # retry None entries too
    if budget > 0:
        todo = todo[:budget]
    done_total = len(cache)
    BATCH = int(os.environ.get("ZALOPC_BATCH", "24"))
    printed = 0
    consecutive_429 = 0
    while todo:
        batch = todo[:BATCH]
        todo = todo[BATCH:]
        printed += len(batch)
        print("[%s] translating %d..%d / %d (total cached %d)" % (
            time.strftime("%H:%M:%S"), printed - len(batch), printed,
            len(cache) + len(todo) + printed, done_total), flush=True)
        ok = False
        for attempt in range(6):
            try:
                masked_all, tok_all = [], []
                for t in batch:
                    m, toks = mask(t)
                    masked_all.append(m)
                    tok_all.append(toks)
                segs = gt_batch(masked_all)
                if len(segs) != len(masked_all):
                    raise RuntimeError("segment count mismatch")
                for t, m, toks, zh in zip(batch, masked_all, tok_all, segs):
                    have = set(int(x.group(1)) for x in MARKER_RE.finditer(zh))
                    want = set(int(x.group(1)) for x in MARKER_RE.finditer(m))
                    if want - have:
                        cache[t] = {"zh": None, "lost": sorted(want - have)}
                        log.write("MARKER LOST for %r -> %r\n" % (t[:80], zh[:80]))
                    else:
                        cache[t] = {"zh": unmask(zh, toks)}
                ok = True
                break
            except Exception as e:
                is429 = "429" in str(e)
                if is429:
                    wait = 25 + 15 * attempt
                else:
                    wait = 3 + 2 * attempt
                log.write("[retry %d] %s (%s)\n" % (attempt, e, batch[0][:50]))
                log.flush()
                time.sleep(wait)
        if not ok:
            consecutive_429 += 1
            log.write("BATCH FAILED (no cache written), %d consecutive 429s. first: %r\n"
                      % (consecutive_429, batch[0][:70]))
            log.flush()
            if consecutive_429 >= 2:
                log.write("=== persistent 429 - stopping run, %d texts remain ===\n" % len(todo))
                log.flush()
                break
            time.sleep(60)   # cooldown before trying the next batch
        else:
            consecutive_429 = 0
        done_total += len(batch)
        if done_total % 168 == 0:
            log.write("progress: %d cached\n" % done_total)
            log.flush()
            with open(os.path.join(WORK, "trans_cache.json"), "w", encoding="utf-8") as f:
                json.dump(cache, f, ensure_ascii=False)
        time.sleep(float(os.environ.get("ZALOPC_SLEEP", "0.5")))
    with open(os.path.join(WORK, "trans_cache.json"), "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)
    return cache

def translate_with_cache_my(texts, cache, log):
    """Translate via MyMemory (parallel, mask-aware, quota-aware)."""
    from concurrent.futures import ThreadPoolExecutor, as_completed
    import threading
    budget = int(os.environ.get("ZALOPC_BUDGET", "0") or 0)
    todo = [t for t in texts if not (cache.get(t) or {}).get("zh")]  # retry None entries too
    if budget > 0:
        todo = todo[:budget]
    de = os.environ.get("ZALOPC_MM_EMAIL", "zalopc.hanhua@gmail.com")
    lock = threading.Lock()
    stop = {"quota": False}
    done = [0]

    def one(text):
        m, toks = mask(text)
        try:
            url = "https://api.mymemory.translated.net/get?q=%s&langpair=vi|zh-CN&de=%s" % (
                urllib.parse.quote(m), de)
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            r = json.load(urllib.request.urlopen(req, timeout=40))
            details = r.get("responseDetails", "") or ""
            if "LIMIT" in details.upper() or "EXCEED" in details.upper() or "ALL AVAILABLE" in details.upper():
                with lock:
                    stop["quota"] = True
                    log.write("QUOTA: %s\n" % details)
                return text, None
            zh = (r.get("responseData") or {}).get("translatedText")
            if zh is None:
                return text, None
            have = set(int(x.group(1)) for x in MARKER_RE.finditer(zh))
            want = set(int(x.group(1)) for x in MARKER_RE.finditer(m))
            if want - have:
                log.write("MARKER LOST (mm) %r -> %r\n" % (text[:70], zh[:70]))
                return text, None
            return text, unmask(zh, toks)
        except Exception as e:
            log.write("mm error %s (%s)\n" % (e, text[:50]))
            return text, None

    while todo and not stop["quota"]:
        chunk = todo[:200]
        todo = todo[200:]
        with ThreadPoolExecutor(max_workers=6) as ex:
            futs = [ex.submit(one, t) for t in chunk]
            for fut in as_completed(futs):
                t, zh = fut.result()
                with lock:
                    if zh is not None:
                        cache[t] = {"zh": zh}
                    done[0] += 1
                    if done[0] % 300 == 0:
                        log.write("mm progress: %d done\n" % done[0]); log.flush()
                        json.dump(cache, open(os.path.join(WORK, "trans_cache.json"), "w", encoding="utf-8"),
                                  ensure_ascii=False)
        print("[%s] mymemory batch done, cached total %d" % (time.strftime("%H:%M:%S"), len(cache)), flush=True)
    json.dump(cache, open(os.path.join(WORK, "trans_cache.json"), "w", encoding="utf-8"),
              ensure_ascii=False)
    if stop["quota"]:
        log.write("stopped due to quota with %d texts remaining\n" % len(todo))
    return cache

def translate_with_cache_bing(texts, cache, log):
    """Translate via bing-translate-api npm package (node worker)."""
    import shutil
    import subprocess
    budget = int(os.environ.get("ZALOPC_BUDGET", "0") or 0)
    todo = [t for t in texts if not (cache.get(t) or {}).get("zh")]  # retry None entries too
    if budget > 0:
        todo = todo[:budget]
    node = os.environ.get("NODE") or shutil.which("node") or r"D:\Program Files\nodejs\node.exe"
    worker = os.path.join(WORK, "bing", "worker.js")
    done = 0
    while todo:
        chunk = todo[:160]
        todo = todo[160:]
        in_json = os.path.join(WORK, "_bing_in.json")
        out_json = os.path.join(WORK, "_bing_out.json")
        data = []
        for t in chunk:
            m, _toks = mask(t)
            data.append(m)
        json.dump(data, open(in_json, "w", encoding="utf-8"), ensure_ascii=False)
        if os.path.exists(out_json):
            os.remove(out_json)
        r = subprocess.run([node, worker, in_json, out_json], capture_output=True, text=True, timeout=400)
        if r.returncode != 0 or not os.path.exists(out_json):
            log.write("bing worker error: %s\n" % r.stderr[-400:])
            break
        results = json.load(open(out_json, encoding="utf-8"))
        assert len(results) == len(chunk)
        for t, m, res in zip(chunk, data, results):
            zh = res.get("zh")
            if not zh:
                continue
            _, toks = mask(t)
            have = set(int(x.group(1)) for x in MARKER_RE.finditer(zh))
            want = set(int(x.group(1)) for x in MARKER_RE.finditer(m))
            if want - have:
                log.write("MARKER LOST (bing) %r -> %r\n" % (t[:70], zh[:70]))
                continue
            cache[t] = {"zh": unmask(zh, toks)}
        done += len(chunk)
        print("[%s] bing batch done, new cached total %d" % (time.strftime("%H:%M:%S"), len(cache)), flush=True)
        json.dump(cache, open(os.path.join(WORK, "trans_cache.json"), "w", encoding="utf-8"), ensure_ascii=False)
        time.sleep(0.6)
    return cache

def should_translate(v):
    if not v or not v.strip():
        return False
    if not re.search(r"[A-Za-z\u00C0-\u1EF9]", v):      # no language letters -> keep as-is
        return False
    if v.strip().startswith("http://") or v.strip().startswith("https://"):
        return False
    return True

def main():
    en = json.load(open(os.path.join(WORK, "lang_en.json"), encoding="utf-8"))
    vi = json.load(open(os.path.join(WORK, "lang_vi.json"), encoding="utf-8"))
    inline = json.load(open(os.path.join(WORK, "inline.json"), encoding="utf-8"))
    # reverse map vi-text -> en-text (first key that has both sides), used to
    # resolve translations offline through the en-side cache entry
    en_by_vi = {}
    for k in vi:
        v, e = vi[k], en.get(k, "")
        if v and e and v not in en_by_vi:
            en_by_vi[v] = e
    sm = json.load(open(SRC_MAP, encoding="utf-8"))
    mvi = sm.get("vi", {})
    men = sm.get("en", {})
    ov = json.load(open(OVERRIDES, encoding="utf-8")) if os.path.exists(OVERRIDES) else {}

    cache_path = os.path.join(WORK, "trans_cache.json")
    cache = {}
    if os.path.exists(cache_path):
        cache = json.load(open(cache_path, encoding="utf-8"))
    for k in list(cache):                        # normalize older entries
        if isinstance(cache[k], str):
            cache[k] = {"zh": cache[k]}

    log = open(os.path.join(WORK, "translate.log"), "a", encoding="utf-8")
    log.write("=== run %s ===\n" % time.strftime("%Y-%m-%d %H:%M:%S"))
    log.flush()

    # ---- dictionary keys ----
    dict_seed = 0
    dict_mt = []
    for k in vi:
        v = vi[k]
        e = en.get(k, "")
        if not should_translate(v):
            continue
        zh = mvi.get(v)
        if zh is None and e and should_translate(e):
            zh = men.get(e)
        if zh is not None:
            dict_seed += 1
        else:
            dict_mt.append(v)

    # ---- inline vi fields ----
    inline_pairs = []
    inline_seed = 0
    inline_mt = []
    for rel, objs in inline.items():
        for o in objs:
            v = o["vi_val"]
            e = o["en_val"]
            if not should_translate(v):
                continue
            zh = mvi.get(v)
            if zh is None and e and should_translate(e):
                zh = men.get(e)
            if zh is not None:
                inline_seed += 1
                inline_pairs.append((rel, o, v, zh))
            else:
                inline_pairs.append((rel, o, v, None))
                inline_mt.append(v)

    unique = sorted(set(dict_mt) | set(inline_mt))
    # texts whose translation can already be resolved offline via the cache
    # (e.g. the en-side text of the same key is cached) don't need the network
    def _offline(v, e):
        if v in ov:
            return True
        c = cache.get(v)
        if c and c.get("zh"):
            return True
        return bool(e and should_translate(e) and cache.get(e) and cache[e].get("zh"))
    need_net = [t for t in unique if not _offline(t, en_by_vi.get(t))]
    print("dictionary keys to translate:", len(vi),
          "| seeded:", dict_seed, "| need MT:", len(set(dict_mt)))
    print("inline vi fields:", len(inline_pairs),
          "| seeded:", inline_seed, "| need MT:", len(set(inline_mt)))
    print("unique MT source texts:", len(unique),
          "| resolvable offline via cache:", len(unique) - len(need_net),
          "| really need network:", len(need_net))

    engine = os.environ.get("ZALOPC_ENGINE", "gtx")
    if engine == "mm":
        cache = translate_with_cache_my(need_net, cache, log)
    elif engine == "bing":
        cache = translate_with_cache_bing(need_net, cache, log)
    else:
        cache = translate_with_cache(need_net, cache, log)

    def zh_for(text):
        if text in ov:
            return ov[text]
        c = cache.get(text)
        if c and c.get("zh"):
            return c["zh"]
        return None

    # ---- dict result ----
    zh_dict = {}
    miss = 0
    kept = 0
    for k in vi:
        v = vi[k]
        if not should_translate(v):
            zh_dict[k] = v
            kept += 1
            continue
        zh = mvi.get(v)
        e = en.get(k, "")
        if zh is None and e and should_translate(e):
            zh = men.get(e)
        if zh is None:
            zh = zh_for(v)
        if zh is None:
            zh = zh_for(e) if should_translate(e) else None
        if zh is None:
            miss += 1
            zh_dict[k] = v
        else:
            zh_dict[k] = zh

    # ---- inline text map ----
    zh_text = {}
    miss_inline = 0
    for rel, o, v, zh in inline_pairs:
        if zh is None:
            zh = zh_for(v)
        if zh is None:
            zh = zh_for(o["en_val"])
        if zh is None:
            miss_inline += 1
            continue
        zh_text[v] = zh

    print("dict: translated/kept", len(zh_dict) - miss, "untranslated-kept", miss)
    print("inline map size:", len(zh_text), "| inline missing:", miss_inline)
    json.dump(zh_dict, open(os.path.join(WORK, "zh_dict.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=0)
    json.dump(zh_text, open(os.path.join(WORK, "zh_text.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=0)
    log.close()
    print("done -> zh_dict.json zh_text.json")

if __name__ == "__main__":
    main()
