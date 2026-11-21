package com.zalocn;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * 翻译功能配置（自定义大模型 API 等）。
 *
 * 两个进程各管一头：
 * - 设置界面（模块自身进程 com.zalocn）：用 SharedPreferences 写配置，
 *   保存后把 prefs 文件 chmod 644，让 Zalo 进程能读。
 * - Hook（Zalo 进程 com.zing.zalo）：跨 UID 读不到模块的 SharedPreferences，
 *   直接解析 /data/data/com.zalocn/shared_prefs/zalocn_translate.xml，
 *   按文件 mtime 缓存，配置改了下次读取即生效，无须重启 Zalo。
 *
 * 配置项全部由用户自己在设置界面填（API 地址、密钥、模型），模块不内置任何密钥。
 */
public class TranslateConfig {

    static final String TAG = "[ZALO-ZH]";
    static final String PREFS_NAME = "zalocn_translate";
    static final String PREFS_FILE = "/data/data/com.zalocn/shared_prefs/zalocn_translate.xml";

    /** 默认 API 地址（OpenAI 兼容接口根路径，不带 /chat/completions）。 */
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    /** 默认模型（用户可在设置里改成自己的模型名）。 */
    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    public String baseUrl = DEFAULT_BASE_URL;
    public String apiKey = "";
    public String model = DEFAULT_MODEL;
    /** 收到越语消息自动翻译成中文。 */
    public boolean autoIncoming = true;
    /** 发出中文消息自动翻成越语再发。 */
    public boolean autoOutgoing = false;
    /** 翻译开关总闸。 */
    public boolean enabled = true;

    /** 快捷短语：每条 "中文" 或 "中文|越语"（越语为空时发送时现翻）。 */
    public List<String[]> phrases = new ArrayList<String[]>();

    // ---- Zalo 进程侧：文件缓存 ----
    private static volatile TranslateConfig cached;
    private static volatile long cachedMtime = -1;

    /** Zalo 进程侧读取配置（带 mtime 缓存）。 */
    public static TranslateConfig loadForHook() {
        try {
            File f = new File(PREFS_FILE);
            long mtime = f.exists() ? f.lastModified() : -1;
            if (cached != null && mtime == cachedMtime) {
                return cached;
            }
            TranslateConfig cfg = new TranslateConfig();
            if (f.exists()) {
                Map<String, String> kv = readPrefsXml(f);
                cfg.baseUrl = nonEmpty(kv.get("baseUrl"), DEFAULT_BASE_URL);
                cfg.apiKey = nonEmpty(kv.get("apiKey"), "");
                cfg.model = nonEmpty(kv.get("model"), DEFAULT_MODEL);
                cfg.enabled = !"false".equals(kv.get("enabled"));
                cfg.autoIncoming = !"false".equals(kv.get("autoIncoming"));
                cfg.autoOutgoing = "true".equals(kv.get("autoOutgoing"));
                cfg.phrases = parsePhrases(kv.get("phrases"));
            }
            cached = cfg;
            cachedMtime = mtime;
            return cfg;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " load config fail: " + t);
            return cached != null ? cached : new TranslateConfig();
        }
    }

    /** 设置界面进程侧读取（直接走 SharedPreferences）。 */
    public static TranslateConfig loadForSettings(Context ctx) {
        TranslateConfig cfg = new TranslateConfig();
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            cfg.baseUrl = nonEmpty(sp.getString("baseUrl", null), DEFAULT_BASE_URL);
            cfg.apiKey = nonEmpty(sp.getString("apiKey", null), "");
            cfg.model = nonEmpty(sp.getString("model", null), DEFAULT_MODEL);
            cfg.enabled = sp.getBoolean("enabled", true);
            cfg.autoIncoming = sp.getBoolean("autoIncoming", true);
            cfg.autoOutgoing = sp.getBoolean("autoOutgoing", false);
            cfg.phrases = parsePhrases(sp.getString("phrases", ""));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " load settings config fail: " + t);
        }
        return cfg;
    }

    /** 设置界面进程侧保存，并把 prefs 文件放开给 Zalo 进程读。 */
    public void saveForSettings(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor ed = sp.edit();
            ed.putString("baseUrl", baseUrl);
            ed.putString("apiKey", apiKey);
            ed.putString("model", model);
            ed.putBoolean("enabled", enabled);
            ed.putBoolean("autoIncoming", autoIncoming);
            ed.putBoolean("autoOutgoing", autoOutgoing);
            ed.putString("phrases", joinPhrases(phrases));
            ed.commit();
            // 跨进程可读：Zalo 进程（不同 UID）靠文件权限读这份 XML。
            try {
                File f = new File(ctx.getApplicationInfo().dataDir + "/shared_prefs/" + PREFS_NAME + ".xml");
                if (f.exists()) {
                    f.setReadable(true, false);
                }
                File dir = new File(ctx.getApplicationInfo().dataDir + "/shared_prefs");
                if (dir.exists()) {
                    dir.setExecutable(true, false);
                }
                File dataDir = new File(ctx.getApplicationInfo().dataDir);
                if (dataDir.exists()) {
                    dataDir.setExecutable(true, false);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " save config fail: " + t);
        }
    }

    /** 解析 SharedPreferences XML（只读 string/boolean 两类）。 */
    static Map<String, String> readPrefsXml(File f) throws Exception {
        Map<String, String> kv = new HashMap<String, String>();
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            XmlPullParser p = Xml.newPullParser();
            p.setInput(fis, "UTF-8");
            int ev = p.getEventType();
            String curName = null;
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = p.getName();
                    if ("string".equals(tag) || "boolean".equals(tag)) {
                        curName = p.getAttributeValue(null, "name");
                        if ("boolean".equals(tag)) {
                            kv.put(curName, p.getAttributeValue(null, "value"));
                            curName = null;
                        }
                    } else {
                        curName = null;
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    if (curName != null) {
                        kv.put(curName, p.getText());
                        curName = null;
                    }
                }
                ev = p.next();
            }
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return kv;
    }

    /** 短语序列化：每行 "中文|越语"（越语可空）。 */
    public static String joinPhrases(List<String[]> phrases) {
        StringBuilder sb = new StringBuilder();
        for (String[] ph : phrases) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(ph[0]);
            sb.append('|');
            if (ph.length > 1 && ph[1] != null) {
                sb.append(ph[1]);
            }
        }
        return sb.toString();
    }

    public static List<String[]> parsePhrases(String raw) {
        List<String[]> out = new ArrayList<String[]>();
        if (raw == null || raw.length() == 0) {
            return out;
        }
        String[] lines = raw.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.length() == 0) {
                continue;
            }
            int sep = line.indexOf('|');
            if (sep >= 0) {
                out.add(new String[]{line.substring(0, sep).trim(), line.substring(sep + 1).trim()});
            } else {
                out.add(new String[]{line, ""});
            }
        }
        return out;
    }

    static String nonEmpty(String v, String def) {
        return (v == null || v.length() == 0) ? def : v;
    }

    /** 未配 API 地址或密钥时，翻译功能不可用（界面会提示去设置里填）。 */
    public boolean hasApi() {
        return baseUrl != null && baseUrl.length() > 0
                && apiKey != null && apiKey.length() > 0;
    }
}
