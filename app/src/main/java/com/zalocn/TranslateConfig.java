package com.zalocn;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * 翻译功能配置（自定义大模型 API 等）。
 *
 * 两个进程各管一头：
 * - 设置界面（模块自身进程 com.zalocn）：用 SharedPreferences 写配置，
 *   必须走 MODE_WORLD_READABLE（openPrefs）。LSPosed 新版
 *   XSharedPreferences（xposedsharedprefs）只有在模块自己用这个模式
 *   打开时，才把文件重定向到它管理的目录并放行，Zalo 进程才能读到；
 *   MODE_PRIVATE 写出来的文件对 Zalo 的 UID 不可读，Zalo 侧只会拿到
 *   默认配置（无 API key、不翻译）。
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

    /**
     * 设置界面进程里不一定存在 XposedBridge（模块 APP 单独启动时没有被框架注入），
     * 直接调用会抛 NoClassDefFoundError 导致设置界面闪退，这里兜底退回 android.util.Log。
     */
    static void logSafe(String msg) {
        try {
            XposedBridge.log(msg);
        } catch (Throwable ignored) {
            android.util.Log.w("ZALO-ZH", msg);
        }
    }
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
    /**
     * 当前生效的翻译引擎："ai" = 走上面这套大模型 API（OpenAI 兼容）；
     * "google" / "google2" / "bing" / "mymemory" = 免费网页翻译引擎
     * （FreeTranslateClient，不用密钥、不读上面三项）。
     */
    public String engine = "ai";
    /** 信息自动翻译：收到的消息自动翻（目标语见 incomingTargetLang）。 */
    public boolean enabled = true;
    /** 自定义翻译提示词：追加到每次翻译的系统提示词后（如地名按越南官方译名、
     *  不要直译人名地名）。空 = 不用。 */
    public String customPrompt = "";
    /** 输入框“译”按钮：保留原文发送（中文一行 + 译文一行）。关 = 只填译文。 */
    public boolean inputKeepOriginal = true;

    /** 接收翻译的目标语言代码（面板“默认译文语言”），默认 "zh" 中文。 */
    public String incomingTargetLang = "zh";

    /** 输入框“译”按钮的默认翻译语言代码（面板“‘译’按钮默认翻译”），默认 "vi" 越南语。 */
    public String outgoingTargetLang = "vi";

    /** 界面汉化：Zalo 界面文字显示中文。关 = 保持越文原样（消息翻译不受影响）。
     * 默认开（模块原来的汉化功能一直是开着的，老用户升级后行为不变）。 */
    public boolean uiLocalize = true;

    /** 接收翻译可选的目标语言：{代码, 显示名}。代码也是 LLM 提示词与 p7g/o7g 的键。 */
    public static final String[][] TARGET_LANGS = {
            {"zh", "中文"}, {"en", "英语"}, {"th", "泰语"}, {"lo", "老挝语"},
            {"km", "高棉语"}, {"my", "缅甸语"}, {"ms", "马来语"}, {"id", "印尼语"},
            {"ja", "日语"}, {"ko", "韩语"}, {"vi", "越南语"}
    };

    /** 语言代码 -> 显示名（LLM 提示词用），未知代码默认中文。 */
    public static String targetLangName(String code) {
        if (code != null) {
            for (String[] pair : TARGET_LANGS) {
                if (pair[0].equals(code)) {
                    return pair[1];
                }
            }
        }
        return "中文";
    }

    // ---- Zalo 进程侧：缓存 ----
    private static volatile TranslateConfig cached;
    private static volatile long cachedMtime = -1;
    private static volatile XSharedPreferences xsp;
    private static volatile boolean xspTried = false;
    private static volatile boolean xspLogged = false;

    static TranslateConfig copyOf(TranslateConfig src) {
        TranslateConfig c = new TranslateConfig();
        c.baseUrl = src.baseUrl;
        c.apiKey = src.apiKey;
        c.model = src.model;
        c.engine = src.engine;
        c.enabled = src.enabled;
        c.customPrompt = src.customPrompt;
        c.inputKeepOriginal = src.inputKeepOriginal;
        c.incomingTargetLang = src.incomingTargetLang;
        c.outgoingTargetLang = src.outgoingTargetLang;
        c.uiLocalize = src.uiLocalize;
        return c;
    }

    /** 强制重读配置（不走缓存；“译”按钮、测试按钮这类用户主动操作用，保证读到最新保存值）。 */
    public static TranslateConfig loadForHookFresh() {
        try {
            if (!xspTried) {
                loadForHook(); // 还没建过 xsp：先走一次正常加载把它建起来
            }
            if (xsp != null) {
                xsp.reload();
                cached = fromXsp(xsp);
                cachedMtime = -1;
                return cached;
            }
            // xsp 不可用：清掉文件缓存后走文件路径重读
            cachedMtime = -1;
            cached = null;
            return loadForHook();
        } catch (Throwable t) {
            return cached != null ? cached : new TranslateConfig();
        }
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
                    logSafe(TAG + " XSharedPreferences unavailable, fallback to file path: " + t);
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
                        logSafe(TAG + " XSharedPreferences read fail, fallback to file path: " + t);
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
            logSafe(TAG + " load config fail: " + t);
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
        cfg.engine = nonEmpty(kv.get("engine"), "ai");
        cfg.enabled = !"false".equals(kv.get("enabled"))
                && !"false".equals(kv.get("autoIncoming")); // 旧版双开关合并：任一关过即关
        cfg.customPrompt = nonEmpty(kv.get("customPrompt"), "");
        cfg.inputKeepOriginal = !"false".equals(kv.get("inputKeepOriginal"));
        cfg.incomingTargetLang = nonEmpty(kv.get("incomingTargetLang"), "zh");
        cfg.outgoingTargetLang = nonEmpty(kv.get("outgoingTargetLang"), "vi");
        cfg.uiLocalize = !"false".equals(kv.get("uiLocalize"));
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
            cfg.engine = nonEmpty(sp.getString("engine", null), "ai");
            cfg.enabled = sp.getBoolean("enabled", true)
                    && sp.getBoolean("autoIncoming", true); // 旧版双开关合并：任一关过即关
            cfg.customPrompt = nonEmpty(sp.getString("customPrompt", null), "");
            cfg.inputKeepOriginal = sp.getBoolean("inputKeepOriginal", true);
            cfg.incomingTargetLang = nonEmpty(sp.getString("incomingTargetLang", null), "zh");
            cfg.outgoingTargetLang = nonEmpty(sp.getString("outgoingTargetLang", null), "vi");
            cfg.uiLocalize = sp.getBoolean("uiLocalize", true);
        } catch (Throwable t) {
            logSafe(TAG + " load settings config fail: " + t);
        }
        return cfg;
    }

    /**
     * 打开模块自身 prefs：优先 MODE_WORLD_READABLE。LSPosed 新版
     * XSharedPreferences（xposedsharedprefs）要求模块自己用
     * MODE_WORLD_READABLE 打开 prefs，LSPosed 才把文件重定向到它管理的
     * 目录并放行该模式，被 hook 的 App（Zalo）才能读到；MODE_PRIVATE
     * 写出来的文件对 Zalo 的 UID 不可读，Zalo 侧会拿到默认配置（无 API
     * key、不翻译）。Android 13+ 上
     * 该模式可能抛 SecurityException，回落 MODE_PRIVATE。注意：不要加
     * 回 setReadable，那是另一回事。
     */
    static SharedPreferences openPrefs(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            logSafe(TAG + " MODE_WORLD_READABLE not supported, fallback MODE_PRIVATE: " + e);
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
            ed.putString("engine", engine);
            ed.putBoolean("enabled", enabled);
            ed.remove("autoIncoming"); // 旧版双开关已合并，旧键清掉
            ed.putString("customPrompt", customPrompt);
            ed.putBoolean("inputKeepOriginal", inputKeepOriginal);
            ed.putString("incomingTargetLang", incomingTargetLang);
            ed.putString("outgoingTargetLang", outgoingTargetLang);
            ed.putBoolean("uiLocalize", uiLocalize);
            ed.commit();
        } catch (Throwable t) {
            logSafe(TAG + " save config fail: " + t);
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

    static String nonEmpty(String v, String def) {
        return (v == null || v.length() == 0) ? def : v;
    }

    /** 未配 API 地址时翻译功能不可用（界面会提示去设置里填）。密钥可空：免密钥的公开 API（如 bilibili Index-Translate）不需要密钥。 */
    public boolean hasApi() {
        return baseUrl != null && baseUrl.length() > 0
                && model != null && model.length() > 0;
    }
}
