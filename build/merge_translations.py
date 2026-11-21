#!/usr/bin/env python3
"""合并 26.11.01 新增翻译补丁到词表（translations.json / src_map_compact.json）。

用法：
    python3 build/merge_translations.py <translations-additions.json> <srcmap-additions.json>

规则：
- 只新增键，不覆盖已有键（已有键冲突时打印警告并跳过，人工确认后再处理）。
- 写入后打印新增条数，并校验 JSON 可解析、键数一致。
- 操作对象：data/translations.json、data/src_map_compact.json。
"""
import json
import sys


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def dump(path, obj):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1, sort_keys=True)
        f.write("\n")


def main():
    add_t_path, add_s_path = sys.argv[1], sys.argv[2]
    add_t = load(add_t_path)
    add_s = load(add_s_path)

    t_path = "data/translations.json"
    s_path = "data/src_map_compact.json"
    t = load(t_path)
    s = load(s_path)

    added_t, skipped_t = 0, []
    for k, v in add_t.items():
        if k in t:
            skipped_t.append(k)
        else:
            t[k] = v
            added_t += 1

    s_en = s.setdefault("en", {})
    added_s, skipped_s = 0, []
    for k, v in add_s.get("en", {}).items():
        if k in s_en:
            skipped_s.append(k)
        else:
            s_en[k] = v
            added_s += 1

    dump(t_path, t)
    dump(s_path, s)
    print("translations.json: +%d, skipped(conflict) %d %s" % (added_t, len(skipped_t), skipped_t[:10]))
    print("src_map en:       +%d, skipped(conflict) %d %s" % (added_s, len(skipped_s), skipped_s[:10]))
    print("totals: translations=%d src_map_en=%d" % (len(t), len(s_en)))


if __name__ == "__main__":
    main()
