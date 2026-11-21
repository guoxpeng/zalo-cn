package com.zalocn;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * 翻译记忆 / 翻译缓存 / 待办提醒的本地存储。
 *
 * 运行在 Zalo 进程内，用 Zalo 自身的 SharedPreferences（"zalocn_tm"，MODE_PRIVATE），
 * 不依赖模块进程、不联网、不碰 Zalo 自己的数据表。
 *
 * - 翻译缓存：原文 SHA1（区分翻译方向）-> 译文。同一句话第二次出现直接取缓存，
 *   不再调大模型。
 * - 翻译记忆：按联系人记语言偏好（"lang:<对方标识>" -> "vi"/"zh"），以及用户确认过
 *   的译文对（与缓存同一份存储，键带方向，天然就是记忆库）。
 * - 待办提醒：每条提醒一条 JSON，字段 id / text / dueAt（毫秒）/ thread（会话标识，可空）。
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
        return "c:" + fromLang + ">" + toLang + ":" + sha1(text);
    }

    public String getCached(String text, String fromLang, String toLang) {
        if (text == null) {
            return null;
        }
        try {
            return sp.getString(cacheKey(text, fromLang, toLang), null);
        } catch (Throwable t) {
            return null;
        }
    }

    public void putCached(String text, String fromLang, String toLang, String translated) {
        if (text == null || translated == null) {
            return;
        }
        try {
            SharedPreferences.Editor ed = sp.edit();
            ed.putString(cacheKey(text, fromLang, toLang), translated);
            ed.apply();
            trimCache(ed);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " putCached fail: " + t);
        }
    }

    /** 超量时删最老的一批缓存键（键无时间戳，按插入顺序近似：删前 200 条）。 */
    private void trimCache(SharedPreferences.Editor ed) {
        try {
            java.util.Map<String, ?> all = sp.getAll();
            int cacheCount = 0;
            for (String k : all.keySet()) {
                if (k.startsWith("c:")) {
                    cacheCount++;
                }
            }
            if (cacheCount <= MAX_CACHE_ENTRIES) {
                return;
            }
            List<String> keys = new ArrayList<String>();
            for (String k : all.keySet()) {
                if (k.startsWith("c:")) {
                    keys.add(k);
                }
            }
            java.util.Collections.sort(keys);
            for (int i = 0; i < keys.size() - MAX_CACHE_ENTRIES; i++) {
                ed.remove(keys.get(i));
            }
            ed.apply();
        } catch (Throwable ignored) {
        }
    }

    // ---- 按联系人的语言偏好 ----

    public String getContactLang(String contactKey) {
        if (contactKey == null) {
            return null;
        }
        try {
            return sp.getString("lang:" + contactKey, null);
        } catch (Throwable t) {
            return null;
        }
    }

    public void setContactLang(String contactKey, String lang) {
        if (contactKey == null || lang == null) {
            return;
        }
        try {
            sp.edit().putString("lang:" + contactKey, lang).apply();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " setContactLang fail: " + t);
        }
    }

    // ---- 待办提醒 ----

    public static class Reminder {
        public String id;
        public String text;
        public long dueAt;
        public String thread;

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("text", text);
            o.put("dueAt", dueAt);
            o.put("thread", thread == null ? "" : thread);
            return o;
        }

        public static Reminder fromJson(JSONObject o) {
            Reminder r = new Reminder();
            r.id = o.optString("id");
            r.text = o.optString("text");
            r.dueAt = o.optLong("dueAt");
            r.thread = o.optString("thread");
            return r;
        }
    }

    /** 新增一条提醒并返回它（调用方随即用它排精确闹钟；失败返回 null）。 */
    public Reminder addReminder(String text, long dueAt, String thread) {
        try {
            List<Reminder> list = getReminders();
            Reminder r = new Reminder();
            r.id = "r" + System.currentTimeMillis();
            r.text = text;
            r.dueAt = dueAt;
            r.thread = thread;
            list.add(r);
            saveReminders(list);
            return r;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " addReminder fail: " + t);
            return null;
        }
    }

    public List<Reminder> getReminders() {
        List<Reminder> out = new ArrayList<Reminder>();
        try {
            String raw = sp.getString("reminders", "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(Reminder.fromJson(arr.getJSONObject(i)));
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " getReminders fail: " + t);
        }
        return out;
    }

    public void saveReminders(List<Reminder> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Reminder r : list) {
                arr.put(r.toJson());
            }
            sp.edit().putString("reminders", arr.toString()).apply();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " saveReminders fail: " + t);
        }
    }

    /** 取出所有已到期的提醒并从库里删除（调用方负责展示）。 */
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

    public List<Reminder> popDueReminders(long now) {
        List<Reminder> due = new ArrayList<Reminder>();
        List<Reminder> keep = new ArrayList<Reminder>();
        for (Reminder r : getReminders()) {
            if (r.dueAt <= now) {
                due.add(r);
            } else {
                keep.add(r);
            }
        }
        if (!due.isEmpty()) {
            saveReminders(keep);
        }
        return due;
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
