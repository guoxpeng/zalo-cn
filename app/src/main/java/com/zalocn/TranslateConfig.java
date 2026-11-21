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

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * 翻译功能配置（自定义大模型 API 等）。
 *
 * 两个进程各管一头：
 * - 设置界面（模块自身进程 com.zalocn）：用 SharedPreferences 写配置。
 *   保存时走 MODE_WORLD_READABLE（LSPosed 在 xposedsharedprefs 开启时 hook
 *   ContextImpl.checkMode 放行该模式；抛 SecurityException 说明 LSPosed
 *   不支持该机制，回落 MODE_PRIVATE 并记日志）。
 * - Hook（Zalo 进程 com.zing.zalo）：走 LSPosed API 93+ 的 XSharedPreferences
 *   读模块配置（Manifest 声明 xposedsharedprefs 后，LSPosed 把模块 prefs
 *   重定向到共享位置，仅 Zalo 进程可读，普通 App 读不到）。
 *
 * 隐私：配置存在模块自身 prefs 里，LSPosed 重定向后仅 Zalo 进程可读；
 * API 密钥只发往用户自己填的 API 地址，不发往任何其他地方。全部收到的
 * 消息与上下文都会发到你填的 API 地址——用中转服务的话，中转方能看到
 * 所有聊天内容。
 *
 * 兼容回退：老设备/老 LSPosed 上 XSharedPreferences 不可用时，尽力用旧
 * 硬编码路径 /data/data/com.zalocn/shared_prefs/zalocn_translate.xml
 * 直接解析（安卓 16 上该路径会被 SELinux 拦，表现为 load config fail，
 * 此时必须升级 LSPosed 到支持 xposedsharedprefs 的版本）。
 *
 * 配置项全部由用户自己在设置界面填（API 地址、密钥、模型），模块不内置任何密钥。
 */
public class TranslateConfig {

    static final String TAG = "[ZALO-ZH]";
    static final String PREFS_NAME = "zalocn_translate";
    /** 旧跨进程路径（仅作尽力回退；安卓 16 上 SELinux 会拦）。 */
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
    /** 发出中文消息自动翻成越语再发（有“发送前先检查译文”兜底，默认开）。 */
    public boolean autoOutgoing = true;
    /** 翻译开关总闸。 */
    public boolean enabled = true;
    /** 发送前先检查译文：译文填回输入框，确认后点发送（默认开）。 */
    public boolean reviewBeforeSend = true;

    /** 历史记录翻译专用配置（空 = 沿用主配置，按项生效）。 */
    public String histBaseUrl = "";
    public String histApiKey = "";
    public String histModel = "";

    /** 快捷短语：每条 "中文" 或 "中文|越语"（越语为空时发送时现翻）。 */
    public List<String[]> phrases = new ArrayList<String[]>();

    // ---- Zalo 进程侧：缓存 ----
    private static volatile TranslateConfig cached;
    private static volatile long cachedMtime = -1;
    private static volatile XSharedPreferences xsp;
    private static volatile boolean xspTried = false;
    private static volatile boolean xspLogged = false;

    /** 历史/接收翻译用的生效配置（历史三项空着就沿用主配置，按项覆盖）。 */
    public TranslateConfig resolveForHistory() {
        if ((histBaseUrl == null || histBaseUrl.length() == 0)
                && (histApiKey == null || histApiKey.length() == 0)
                && (histModel == null || histModel.length() == 0)) {
            return this;
        }
        TranslateConfig c = copyOf(this);
        if (histBaseUrl != null && histBaseUrl.length() > 0) {
            c.baseUrl = histBaseUrl;
        }
        if (histApiKey != null && histApiKey.length() > 0) {
            c.apiKey = histApiKey;
        }
        if (histModel != null && histModel.length() > 0) {
            c.model = histModel;
        }
        return c;
    }

    static TranslateConfig copyOf(TranslateConfig src) {
        TranslateConfig c = new TranslateConfig();
        c.baseUrl = src.baseUrl;
        c.apiKey = src.apiKey;
        c.model = src.model;
        c.autoIncoming = src.autoIncoming;
        c.autoOutgoing = src.autoOutgoing;
        c.enabled = src.enabled;
        c.reviewBeforeSend = src.reviewBeforeSend;
        c.histBaseUrl = src.histBaseUrl;
        c.histApiKey = src.histApiKey;
        c.histModel = src.histModel;
        c.phrases = src.phrases;
        return c;
    }

    /** Zalo 进程侧读取配置（XSharedPreferences + hasFileChanged/reload 缓存）。 */
    public static TranslateConfig loadForHook() {
        try {
            // ① 首选：LSPosed 新版 XSharedPreferences（xposedsharedprefs 重定向）。
            if (!xspTried) {
                xspTried = true;
                try {
                    xsp = new XSharedPreferences("com.zalocn", PREFS_NAME);
                    xsp.reload();
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " XSharedPreferences unavailable, fallback to file path: " + t);
                    xsp = null;
                }
            }
            if (xsp != null) {
                try {
                    if (cached == null || xsp.hasFileChanged()) {
                        xsp.reload();
                        cached = fromXsp(xsp);
                        cachedMtime = -1;
                    }
                    return cached;
                } catch (Throwable t) {
                    if (!xspLogged) {
                        xspLogged = true;
                        XposedBridge.log(TAG + " XSharedPreferences read fail, fallback to file path: " + t);
                    }
                    xsp = null;
                }
            }
            // ② 尽力回退：旧硬编码路径直接解析（安卓 16 上会被 SELinux 拦）。
            File f = new File(PREFS_FILE);
            long mtime = f.exists() ? f.lastModified() : -1;
            if (cached != null && mtime == cachedMtime) {
                return cached;
            }
            TranslateConfig cfg = new TranslateConfig();
            if (f.exists()) {
                Map<String, String> kv = readPrefsXml(f);
                cfg = fromMap(kv);
            }
            cached = cfg;
            cachedMtime = mtime;
            return cfg;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " load config fail: " + t);
            return cached != null ? cached : new TranslateConfig();
        }
    }

    /** 从 XSharedPreferences 的全部键值构造配置。 */
    private static TranslateConfig fromXsp(XSharedPreferences xsp) {
        Map<String, ?> all = xsp.getAll();
        Map<String, String> kv = new HashMap<String, String>();
        if (all != null) {
            for (Map.Entry<String, ?> e : all.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String) {
                    kv.put(e.getKey(), (String) v);
                } else if (v instanceof Boolean) {
                    kv.put(e.getKey(), ((Boolean) v).booleanValue() ? "true" : "false");
                }
            }
        }
        return fromMap(kv);
    }

    /** 从字符串键值表构造配置（XSharedPreferences 与旧 XML 解析共用）。 */
    private static TranslateConfig fromMap(Map<String, String> kv) {
        TranslateConfig cfg = new TranslateConfig();
        cfg.baseUrl = nonEmpty(kv.get("baseUrl"), DEFAULT_BASE_URL);
        cfg.apiKey = nonEmpty(kv.get("apiKey"), "");
        cfg.model = nonEmpty(kv.get("model"), DEFAULT_MODEL);
        cfg.enabled = !"false".equals(kv.get("enabled"));
        cfg.autoIncoming = !"false".equals(kv.get("autoIncoming"));
        cfg.autoOutgoing = !"false".equals(kv.get("autoOutgoing"));
        cfg.reviewBeforeSend = !"false".equals(kv.get("reviewBeforeSend"));
        cfg.histBaseUrl = nonEmpty(kv.get("histBaseUrl"), "");
        cfg.histApiKey = nonEmpty(kv.get("histApiKey"), "");
        cfg.histModel = nonEmpty(kv.get("histModel"), "");
        cfg.phrases = parsePhrases(kv.get("phrases"));
        return cfg;
    }

    /** 设置界面进程侧读取（直接走 SharedPreferences）。 */
    public static TranslateConfig loadForSettings(Context ctx) {
        TranslateConfig cfg = new TranslateConfig();
        try {
            SharedPreferences sp = openPrefs(ctx);
            cfg.baseUrl = nonEmpty(sp.getString("baseUrl", null), DEFAULT_BASE_URL);
            cfg.apiKey = nonEmpty(sp.getString("apiKey", null), "");
            cfg.model = nonEmpty(sp.getString("model", null), DEFAULT_MODEL);
            cfg.enabled = sp.getBoolean("enabled", true);
            cfg.autoIncoming = sp.getBoolean("autoIncoming", true);
            cfg.autoOutgoing = sp.getBoolean("autoOutgoing", true);
            cfg.reviewBeforeSend = sp.getBoolean("reviewBeforeSend", true);
            cfg.histBaseUrl = nonEmpty(sp.getString("histBaseUrl", null), "");
            cfg.histApiKey = nonEmpty(sp.getString("histApiKey", null), "");
            cfg.histModel = nonEmpty(sp.getString("histModel", null), "");
            cfg.phrases = parsePhrases(sp.getString("phrases", ""));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " load settings config fail: " + t);
        }
        return cfg;
    }

    /**
     * 打开模块自身 prefs：优先 MODE_WORLD_READABLE（LSPosed 在
     * xposedsharedprefs 开启时放行，Zalo 进程才能经重定向读到）；抛
     * SecurityException 说明 LSPosed 不支持该机制，回落 MODE_PRIVATE。
     */
    static SharedPreferences openPrefs(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            XposedBridge.log(TAG + " MODE_WORLD_READABLE not supported (old LSPosed?), fallback MODE_PRIVATE: " + e);
            return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    /** 设置界面进程侧保存（不再改文件权限；跨进程可读由 LSPosed 重定向保证）。 */
    public void saveForSettings(Context ctx) {
        try {
            SharedPreferences sp = openPrefs(ctx);
            SharedPreferences.Editor ed = sp.edit();
            ed.putString("baseUrl", baseUrl);
            ed.putString("apiKey", apiKey);
            ed.putString("model", model);
            ed.putBoolean("enabled", enabled);
            ed.putBoolean("autoIncoming", autoIncoming);
            ed.putBoolean("autoOutgoing", autoOutgoing);
            ed.putBoolean("reviewBeforeSend", reviewBeforeSend);
            ed.putString("histBaseUrl", histBaseUrl);
            ed.putString("histApiKey", histApiKey);
            ed.putString("histModel", histModel);
            ed.putString("phrases", joinPhrases(phrases));
            ed.commit();
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
