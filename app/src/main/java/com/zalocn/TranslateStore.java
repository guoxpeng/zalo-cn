package com.zalocn;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * 翻译记忆 / 翻译缓存的本地存储。
 *
 * 运行在 Zalo 进程内，用 Zalo 自身的 SharedPreferences（"zalocn_tm"，MODE_PRIVATE），
 * 不依赖模块进程、不联网、不碰 Zalo 自己的数据表。
 *
 * - 翻译缓存：原文 SHA1（区分翻译方向）-> "时间戳|译文"。同一句话第二次出现
 *   直接取缓存，不再调大模型。
 *
 * 缓存风险（写明）：缓存键是原文 SHA1（原文本身不存），值是译文明文，存在
 * Zalo 自身 prefs 里——因为 Zalo 进程只能写自己的数据目录，换模块自身存储
 * 在现有跨进程机制下做不到。上限 2000 条，超量时按时间戳删最旧的。
 * - 翻译记忆：按联系人记语言偏好（"lang:<对方标识>" -> "vi"/"zh"），以及用户确认过
 *   的译文对（与缓存同一份存储，键带方向，天然就是记忆库）。
 */
public class TranslateStore {

    static final String TAG = "[ZALO-ZH]";
    private static final String PREFS = "zalocn_tm";

    /** 缓存上限条数（LRU 式清理，旧的先删；避免 prefs 无限膨胀）。 */
    private static final int MAX_CACHE_ENTRIES = 2000;

    private final SharedPreferences sp;

    public TranslateStore(Context appContext) {
        this.sp = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- 翻译缓存（同时就是翻译记忆） ----

    private static String cacheKey(String text, String fromLang, String toLang) {
        return "c2:" + fromLang + ">" + toLang + ":" + sha1(text);
    }

    /**
     * 内存里的缓存键集：首次使用时一次 getAll 载入，之后写操作不再 getAll。
     * 只含 "c:" 开头的缓存键；lang:/hide: 等键不在里面。
     */
    private volatile Set<String> cacheKeys = null;

    private synchronized void ensureCacheKeys() {
        if (cacheKeys != null) {
            return;
        }
        Set<String> keys = Collections.synchronizedSet(new HashSet<String>());
        try {
            Map<String, ?> all = sp.getAll();
            for (String k : all.keySet()) {
                if (k.startsWith("c:") || k.startsWith("c2:")) {
                    keys.add(k);
                }
            }
        } catch (Throwable ignored) {
        }
        cacheKeys = keys;
    }

    /** 缓存值里的写入时间戳（"时间戳|译文"；老格式无数字前缀，按 0 处理）。 */
    static long timestampOf(String stored) {
        if (stored == null) {
            return 0;
        }
        int sep = stored.indexOf('|');
        if (sep <= 0) {
            return 0;
        }
        try {
            return Long.parseLong(stored.substring(0, sep));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 去掉缓存值里的时间戳前缀，返回译文本身。 */
    static String stripTimestamp(String stored) {
        if (stored == null) {
            return null;
        }
        int sep = stored.indexOf('|');
        if (sep <= 0) {
            return stored; // 老格式：整条就是译文
        }
        try {
            Long.parseLong(stored.substring(0, sep));
            return stored.substring(sep + 1);
        } catch (Throwable t) {
            return stored;
        }
    }

    public String getCached(String text, String fromLang, String toLang) {
        if (text == null) {
            return null;
        }
        try {
            return stripTimestamp(sp.getString(cacheKey(text, fromLang, toLang), null));
        } catch (Throwable t) {
            return null;
        }
    }

    public synchronized void putCached(String text, String fromLang, String toLang, String translated) {
        if (text == null || translated == null) {
            return;
        }
        try {
            ensureCacheKeys();
            String key = cacheKey(text, fromLang, toLang);
            SharedPreferences.Editor ed = sp.edit();
            ed.putString(key, System.currentTimeMillis() + "|" + translated);
            ed.apply();
            cacheKeys.add(key);
            trimCache();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " putCached fail: " + t);
        }
    }

    /** 超量时按时间戳删最旧的缓存（调用方已持锁；不做全量 getAll）。 */
    /** 删除一条缓存（点“重新翻译”时先清旧译文，强制重新走 API）。 */
    public synchronized void removeCached(String text, String fromLang, String toLang) {
        if (text == null) return;
        try {
            ensureCacheKeys();
            String key = cacheKey(text, fromLang, toLang);
            SharedPreferences.Editor ed = sp.edit();
            ed.remove(key);
            ed.apply();
            cacheKeys.remove(key);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " removeCached fail: " + t);
        }
    }

    private synchronized void trimCache() {
        try {
            ensureCacheKeys();
            if (cacheKeys.size() <= MAX_CACHE_ENTRIES) {
                return;
            }
            List<String> keys = new ArrayList<String>(cacheKeys);
            final Map<String, Long> tsByKey = new java.util.HashMap<String, Long>();
            for (String k : keys) {
                String stored = null;
                try {
                    stored = sp.getString(k, null);
                } catch (Throwable ignored) {
                }
                if (stored == null) {
                    tsByKey.put(k, Long.valueOf(-1)); // 值已不在，先清掉
                } else {
                    tsByKey.put(k, Long.valueOf(timestampOf(stored)));
                }
            }
            Collections.sort(keys, new java.util.Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    long ta = tsByKey.get(a).longValue();
                    long tb = tsByKey.get(b).longValue();
                    return ta < tb ? -1 : (ta > tb ? 1 : 0);
                }
            });
            int over = keys.size() - MAX_CACHE_ENTRIES;
            SharedPreferences.Editor ed = sp.edit();
            for (int i = 0; i < over; i++) {
                ed.remove(keys.get(i));
                cacheKeys.remove(keys.get(i));
            }
            ed.apply();
        } catch (Throwable ignored) {
        }
    }

    /** 原文/译文切换：用户手动隐藏过译文的消息键（hide:<f1()>）。 */
    public boolean isHidden(String msgKey) {
        if (msgKey == null) {
            return false;
        }
        return sp.getBoolean("hide:" + msgKey, false);
    }

    public void setHidden(String msgKey, boolean hidden) {
        if (msgKey == null) {
            return;
        }
        SharedPreferences.Editor e = sp.edit();
        e.putBoolean("hide:" + msgKey, hidden);
        e.apply();
    }

    static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
