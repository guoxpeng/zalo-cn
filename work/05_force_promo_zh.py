# -*- coding: utf-8 -*-
"""Force zCloud promo popover text to local Chinese, bypassing remote override.

Remote config (a.default.zalo_cloud.promo.popover.*) may return Vietnamese and
shadow the local default (o.a / r.a) that we translated to Chinese. Rewrite the
three getters so they always return the local (Chinese) value.
"""
import io, sys, os

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(HERE, "..", "build_app")


def variants(var):
    return [
        (
            'getPopoverTitle:(e="vi")=>c(a.default.zalo_cloud.promo.popover.title[e],%s.zalo_cloud.promo.popover.title[e],"string")' % var,
            'getPopoverTitle:(e="vi")=>%s.zalo_cloud.promo.popover.title[e]' % var,
        ),
        (
            'getPopoverDescription:(e="vi")=>c(a.default.zalo_cloud.promo.popover.description[e],%s.zalo_cloud.promo.popover.description[e],"string")' % var,
            'getPopoverDescription:(e="vi")=>%s.zalo_cloud.promo.popover.description[e]' % var,
        ),
        (
            'getPopoverCTA:(e="vi")=>c(a.default.zalo_cloud.promo.popover.cta[e],%s.zalo_cloud.promo.popover.cta[e],"string")' % var,
            'getPopoverCTA:(e="vi")=>%s.zalo_cloud.promo.popover.cta[e]' % var,
        ),
    ]


def main():
    root = os.path.join(BUILD, "pc-dist")
    total = 0
    for dp, _dn, fns in os.walk(root):
        for fn in fns:
            if not fn.endswith(".js"):
                continue
            p = os.path.join(dp, fn)
            s = open(p, encoding="utf-8", errors="ignore").read()
            if "getPopoverTitle" not in s:
                continue
            orig = s
            hits = 0
            for var in ("o.a", "r.a"):
                for old, new in variants(var):
                    n = s.count(old)
                    if n:
                        s = s.replace(old, new)
                        hits += n
            if s != orig:
                open(p, "w", encoding="utf-8", newline="").write(s)
                rel = os.path.relpath(p, BUILD).replace("\\", "/")
                print("patched %-70s x%d" % (rel, hits))
                total += hits
    print("total promo-force patches:", total)


if __name__ == "__main__":
    main()
