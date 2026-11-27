#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
surgical asar patch —— 只替换 app.asar 里一个文件的字节，其它文件原样搬运。

为什么要自己写：整套 asar 重打包（13k 文件、158MB）在 Windows 上要跑几十分钟，
而我们只需要换 /pc-dist/zalocn-translate.js 这 30KB。

asar 布局：
  [u32 = 4][u32 = json_len+8][u32 = json_len+4][u32 = json_len]
  [json][0 填充到 4 字节对齐]
  [所有文件数据按 offset 顺序拼接；offset 相对 base = 16 + json_len_padded]
"""
import json
import struct
import hashlib
import os
import sys

def read_asar(path):
    f = open(path, "rb")
    prefix = f.read(16)
    _a, _b, _c, json_len = struct.unpack("<IIII", prefix)
    header = json.loads(f.read(json_len).decode("utf-8"))
    base = 16 + json_len
    return header, base, f

def walk(node, path=""):
    for name, meta in node.get("files", {}).items():
        cur = path + "/" + name
        if "files" in meta:
            yield from walk(meta, cur)
        else:
            yield cur, meta

def patch(src, dst, target, new_bytes):
    header, base, f = read_asar(src)

    # 收集要写的数据（保持原有 offset 顺序）
    entries = []
    for path, meta in walk(header):
        if meta.get("unpacked"):
            continue          # 在 app.asar.unpacked 里，不含数据段
        entries.append((path, meta))
    entries.sort(key=lambda kv: int(kv[1]["offset"]))

    out = bytearray()
    for path, meta in entries:
        size = int(meta["size"])
        f.seek(base + int(meta["offset"]))
        data = f.read(size)
        if path == target:
            data = new_bytes
            meta["size"] = len(data)
            integ = meta.get("integrity")
            if integ:
                integ["hash"] = hashlib.sha256(data).hexdigest()
                bs = int(integ.get("blockSize") or 0)
                if "blocks" in integ:
                    blocks = []
                    if bs:
                        for i in range(0, len(data), bs):
                            blocks.append(hashlib.sha256(data[i:i + bs]).hexdigest())
                    integ["blocks"] = blocks
            print("replaced %s -> %d bytes" % (path, len(data)))
        meta["offset"] = str(len(out))
        out += data
    f.close()

    js = json.dumps(header, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    pad = (-len(js)) % 4
    js_padded = js + b" " * pad  # 用空格补齐（JSON 合法），零字节会让解析器报 Extra data
    prefix = struct.pack("<IIII", 4, len(js_padded) + 8, len(js_padded) + 4, len(js_padded))

    with open(dst, "wb") as g:
        g.write(prefix)
        g.write(js_padded)
        g.write(out)
    print("written", dst, os.path.getsize(dst))

if __name__ == "__main__":
    src, dst, target, payload = sys.argv[1:5]
    patch(src, dst, target, open(payload, "rb").read())
