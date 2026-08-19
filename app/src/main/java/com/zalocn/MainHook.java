package com.zalocn;

import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Zalo 界面汉化模块（纯 UI 字符串替换，不触碰网络与账号数据）。
 *
 * 原理：Zalo 的界面文本几乎全部来自资源表（resources.arsc），读取路径统一经过
 * Resources.getText(int)/getQuantityText(int,int)。本模块 hook 这些方法，
 * 按资源 ID 惰性解析资源名，命中翻译表则直接返回中文。
 * 兼容 LSPosed 资源钩子开启（XResources 覆写）与关闭（基础 Resources）两种情形。
 */
public class MainHook implements IXposedHookLoadPackage {

    static final String PKG = "com.zing.zalo";
    static final String TAG = "[ZALO-ZH]";
    static final String ASSET = "assets/translations.json";

    /** 资源名 -> 中文 */
    private static volatile Map<String, String> zhByName;

    /** 硬编码界面文本（不走资源系统）原文 -> 中文 */
    private static final Map<String, String> HARDCODED = new HashMap<String, String>();
    static {
        HARDCODED.put("zBusiness", "企业工具");
        HARDCODED.put("zCloud", "云端存储");
        HARDCODED.put("My Documents", "我的文档");
        HARDCODED.put("QR Wallet", "QR 钱包");
        HARDCODED.put("Expand connections with professional account", "连接专业账号，拓展人脉");
        HARDCODED.put("Data storage space on the cloud", "云端数据存储空间");
        HARDCODED.put("Keep important QR codes", "保存重要的 QR 码");
        HARDCODED.put("Account and security", "账户与安全");
        HARDCODED.put("Privacy", "隐私");
        HARDCODED.put("Data on device", "设备上的数据");
        HARDCODED.put("Text message backup", "文字消息备份");
        HARDCODED.put("Notifications", "通知");
        HARDCODED.put("Messages", "消息");
        HARDCODED.put("Calls", "通话");
        HARDCODED.put("Timeline", "动态");
        HARDCODED.put("Contacts", "联系人");
        HARDCODED.put("Theme and language", "主题与语言");
        HARDCODED.put("About Zalo", "关于 Zalo");
        HARDCODED.put("Contact support", "联系支持");
        HARDCODED.put("Switch account", "切换账号");
        HARDCODED.put("Log out", "退出登录");
        HARDCODED.put("Phone number", "手机号");
        HARDCODED.put("Email", "邮箱");
        HARDCODED.put("My QR code", "我的二维码");
        HARDCODED.put("Security", "安全");
        HARDCODED.put("Security checkup", "安全检查");
        HARDCODED.put("Your account is protected", "您的账户已受保护");
        HARDCODED.put("Lock Zalo", "锁定 Zalo");
        HARDCODED.put("Login", "登录");
        HARDCODED.put("Log in", "登录");
        HARDCODED.put("Create new account", "创建新账号");
        HARDCODED.put("REGISTER", "注册");
        HARDCODED.put("SHOW", "显示");
        HARDCODED.put("Password", "密码");
        HARDCODED.put("Recover password", "恢复密码");
        HARDCODED.put("FAQ", "常见问题");
        HARDCODED.put("Phone number or username", "电话号码或用户名");
        HARDCODED.put("Enter password", "输入密码");
        HARDCODED.put("Login with password", "密码登录");
        HARDCODED.put("Continue", "继续");
        HARDCODED.put("2-factor authentication", "两步验证");
        HARDCODED.put("Extra protection layers for your account when logging in on new devices", "在新设备登录时为您提供额外账户保护");
        HARDCODED.put("Logged-in devices", "已登录设备");
        HARDCODED.put("Manage devices you have used to log into Zalo", "管理您用于登录 Zalo 的设备");
        HARDCODED.put("Password", "密码");
        HARDCODED.put("Delete account", "删除账户");

        // ---- 安全检查页（ZInstant 服务器下发 UI）----
        // tab
        HARDCODED.put("Status", "状态");
        HARDCODED.put("History", "历史");
        // 顶部状态卡
        HARDCODED.put("Security status: Strong", "安全状态：强");
        HARDCODED.put("Security status:", "安全状态：");
        HARDCODED.put("Strong", "强");
        HARDCODED.put("No security issues", "没有安全问题");
        HARDCODED.put("Other logged in devices", "其他已登录设备");
        // 检查项
        HARDCODED.put("2-factor authentication", "两步验证");
        HARDCODED.put("Account verification", "账户验证");
        HARDCODED.put("Zalo version", "Zalo 版本");
        HARDCODED.put("Security notice and tips", "安全通知与提示");
        HARDCODED.put("Data processing notice", "数据处理通知");
        HARDCODED.put("It's safer to scan the QR code to log in on Zalo", "扫描二维码登录 Zalo 更安全");
        HARDCODED.put("Security check: Handle cases that can cause account loss", "安全检查：处理可能导致账号丢失的情况");
        HARDCODED.put("Handling warnings about fraudulent links on Zalo", "处理 Zalo 上的欺诈链接警告");
        HARDCODED.put("Data usage settings", "数据使用设置");
        HARDCODED.put("Want to learn more? View on Zalo Help", "想了解更多？在 Zalo 帮助中查看");
        // 展开子项
        HARDCODED.put("On", "已开启");
        HARDCODED.put("Verified", "已验证");
        HARDCODED.put("Zalo will require extra verification when your account is logged in on unknown devices.",
                "当您的账户在新设备上登录时，Zalo 将要求额外验证。");
        HARDCODED.put("You will get priority support for account-related issues", "您将获得账户相关问题的优先支持");
        // 前缀匹配（服务器数据可能带变量/拼接，如 "Phone number +16173759368"）
        HARDCODED.put("Phone number ", "手机号 ");
        HARDCODED.put("Security status: ", "安全状态：");
        // 个人信息子页面
        HARDCODED.put("Personal Information", "个人信息");
        HARDCODED.put("Zalo name", "Zalo 名称");
        HARDCODED.put("Birthday", "生日");
        HARDCODED.put("Gender", "性别");
        HARDCODED.put("Male", "男");
        HARDCODED.put("Edit", "编辑");
    }

    /** 资源 ID -> 中文（惰性填充） */
    private static final Map<Integer, String> zhById = new HashMap<Integer, String>();

    /** 已解析但无翻译的资源 ID（避免重复解析） */
    private static final Set<Integer> checkedIds = new HashSet<Integer>();

    /** 英文原文 -> 中文（绕过资源路径的 setText/setHint/ZInstant 兜底） */
    private static volatile Map<String, String> zhByEn;

    /** 越南语原文 -> 中文 */
    private static volatile Map<String, String> zhByVi;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 全局防御：模块任何初始化异常都只记日志，绝不让异常外抛导致
        // LSPosed 崩溃 / Zalo 进程崩溃 / 框架回退未激活状态。
        try {
            initHook(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " initHook failed (isolated): " + t);
        }
    }

    private void initHook(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PKG.equals(lpparam.packageName)) {
            return;
        }
        loadTranslations();

        // hook Application.attachBaseContext 以尽早持有应用 Context（备用）
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attachBaseContext", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " attachBaseContext hook fail: " + t);
        }

        hookTextGetter(Resources.class, "getText", int.class);
        hookTextGetter(Resources.class, "getQuantityText", int.class, int.class);
        hookTextGetter(Resources.class, "getText", int.class, Resources.Theme.class);
        hookTextGetter(Resources.class, "getString", int.class);

        // LSPosed 资源钩子开启时应用内 Resources 实为 XResources，其覆写了 getText/getQuantityText，
        // 基础类 hook 不会命中，需同时对 XResources 挂钩（类存在时才会命中，无副作用）。
        try {
            Class<?> xres = Class.forName("android.content.res.XResources");
            hookTextGetter(xres, "getText", int.class);
            hookTextGetter(xres, "getText", int.class, CharSequence.class);
            hookTextGetter(xres, "getQuantityText", int.class, int.class);
            XposedBridge.log(TAG + " XResources hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " XResources not present, base Resources hooks only: " + t);
        }

        // 兜底：Zalo 部分界面文本（如"我"页 zBusiness/zCloud/My Documents/QR Wallet 主标题）
        // 硬编码在 Java 代码中，不走 Resources.getText。hook TextView.setText(CharSequence)，
        // 精确匹配已知英文原文并替换。仅对完全一致的字符串生效，避免误伤。
        try {
            XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object arg = param.args[0];
                                if (arg != null) {
                                    String s = arg.toString();
                                    String zh = lookupSource(s);
                                    if (zh != null && !zh.equals(s)) {
                                        param.args[0] = zh;
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " setText hook error: " + t);
                            }
                        }
                    });
            // zdesign.Button 覆写的是双参 setText(CharSequence, BufferType)，单参 hook 不命中，补双参。
            XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class, TextView.BufferType.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object arg = param.args[0];
                                if (arg != null) {
                                    String s = arg.toString();
                                    String zh = lookupSource(s);
                                    if (zh != null && !zh.equals(s)) {
                                        param.args[0] = zh;
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " setText2 hook error: " + t);
                            }
                        }
                    });
            // EditText 提示文本（如登录页 "Password"）走 setHint，补 hook。
            XposedHelpers.findAndHookMethod(TextView.class, "setHint", CharSequence.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object arg = param.args[0];
                                if (arg != null) {
                                    String s = arg.toString();
                                    String zh = lookupSource(s);
                                    if (zh != null && !zh.equals(s)) {
                                        param.args[0] = zh;
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " setHint hook error: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + " TextView.setText/setHint hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " TextView.setText hook fail: " + t);
        }

        // 安全检查页等 ZInstant（服务器下发动态 UI）文本：不走 Resources/TextView，
        // 文本存在 ZOMText -> ZOMTextSpan[].text 中。hook prepareTextLayout 在渲染前
        // 遍历 span 并精确替换为中文。仅替换完全一致的字符串，不触碰网络/数据。
        hookZinstantText(lpparam);

        // 钱包 Activity/Card 等 H5（WebView）页面：Chromium 渲染，hook 完全够不着，
        // 改为页面加载完成后注入 JS，按字典精确替换 DOM 文本节点（含动态内容）。
        try {
            hookWebView(lpparam);
            XposedBridge.log(TAG + " WebView hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " WebView hook fail: " + t);
        }

        int size = zhByName == null ? -1 : zhByName.size();
        XposedBridge.log(TAG + " module loaded, translations: " + size);
    }

    /** ZInstant 文本节点替换：ZOMText / ZOMText2 的 prepareTextLayout 入口。 */
    private static void hookZinstantText(XC_LoadPackage.LoadPackageParam lpparam) {
        String[][] targets = {
                {"com.zing.zalo.zinstant.zom.node.ZOMText", "mParagraph"},
                {"com.zing.zalo.zinstant.zom.node.ZOMText2", "mSpans"},
        };
        for (String[] target : targets) {
            final String clsName = target[0];
            final String fieldName = target[1];
            try {
                final Class<?> clazz = Class.forName(clsName, false, lpparam.classLoader);
                XposedHelpers.findAndHookMethod(clazz, "prepareTextLayout",
                        float.class, int.class, float.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    Object self = param.thisObject;
                                    Object[] spans = (Object[]) getFieldValue(self, fieldName);
                                    if (spans != null) {
                                        for (Object span : spans) {
                                            if (span != null) {
                                                translateSpan(span);
                                            }
                                        }
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + " zinstant " + clazz.getSimpleName() + " error: " + t);
                                }
                            }
                        });
                XposedBridge.log(TAG + " ZInstant hook installed: " + clazz.getSimpleName());
            } catch (Throwable t) {
                XposedBridge.log(TAG + " ZInstant hook fail " + clsName + ": " + t);
            }
        }
    }

    /** 原生反射读取字段（桩类不含 getObjectField，故自实现）。 */
    private static Object getFieldValue(Object obj, String name) throws Exception {
        java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    /**
     * 替换 ZInstant 文本 span 的 text 字段。
     * 1) 精确匹配 HARDCODED；
     * 2) 前缀匹配（用于服务器拼接数据，如 "Phone number +16173759368"、"Security status: Strong"）。
     * 仅替换已收录的已知前缀，避免误伤。
     */
    private static void translateSpan(Object span) {
        try {
            java.lang.reflect.Field f = span.getClass().getDeclaredField("text");
            f.setAccessible(true);
            Object v = f.get(span);
            if (v == null) {
                return;
            }
            String s = v.toString();
            String zh = lookupSource(s);
            if (zh == null) {
                zh = prefixMatch(s);
            }
            if (zh != null && !zh.equals(s)) {
                f.set(span, zh);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " translateSpan error: " + t);
        }
    }

    /** 已知前缀（带尾部空格/冒号）替换；命中则替换前缀部分，保留变量。 */
    private static String prefixMatch(String s) {
        String zh = HARDCODED.get("Phone number ");
        if (s.startsWith("Phone number ") && zh != null) {
            return zh + s.substring("Phone number ".length());
        }
        zh = HARDCODED.get("Security status: ");
        if (s.startsWith("Security status: ") && zh != null) {
            return zh + s.substring("Security status: ".length());
        }
        return null;
    }

    private static void hookTextGetter(final Class<?> clazz, final String method, final Class<?>... params) {
        try {
            // params 是完整的方法参数类型列表（如 getText -> int.class；
            // getQuantityText -> int.class, int.class），不再额外拼接。
            final XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        int resid = ((Integer) param.args[0]).intValue();
                        CharSequence zh = translate(resid,
                                (Resources) param.thisObject);
                        if (zh != null) {
                            param.setResult(zh);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " translate(" + method + ") error: " + t);
                    }
                }
            };
            Object[] args = new Object[params.length + 1];
            System.arraycopy(params, 0, args, 0, params.length);
            args[params.length] = hook;
            XposedHelpers.findAndHookMethod(clazz, method, args);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook " + clazz.getName() + "." + method + " fail: " + t);
        }
    }

    private static void loadTranslations() {
        if (zhByName != null) {
            return;
        }
        synchronized (MainHook.class) {
            if (zhByName != null) {
                return;
            }
            Map<String, String> map = new HashMap<String, String>();
            try {
                InputStream is = MainHook.class.getClassLoader().getResourceAsStream(ASSET);
                if (is == null) {
                    XposedBridge.log(TAG + " asset not found: " + ASSET);
                    return;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                is.close();
                JSONObject obj = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
                Iterator<String> it = obj.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    String val = obj.optString(key);
                    if (val != null && val.length() > 0) {
                        map.put(key, val);
                    }
                }
                zhByName = map;
                XposedBridge.log(TAG + " loaded translations: " + map.size());
            } catch (Throwable t) {
                zhByName = map;
                XposedBridge.log(TAG + " load translations fail: " + t);
            }
            // 源码映射（en/vi 原文 -> 中文），供绕过资源路径的 setText/setHint/ZInstant 使用
            try {
                InputStream is = MainHook.class.getClassLoader().getResourceAsStream("assets/src_map.json");
                if (is != null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                    }
                    is.close();
                    JSONObject obj = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
                    Map<String, String> en = new HashMap<String, String>();
                    Map<String, String> vi = new HashMap<String, String>();
                    JSONObject enObj = obj.optJSONObject("en");
                    if (enObj != null) {
                        Iterator<String> it = enObj.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            String v = enObj.optString(k);
                            if (v != null && v.length() > 0) {
                                en.put(k, v);
                            }
                        }
                    }
                    JSONObject viObj = obj.optJSONObject("vi");
                    if (viObj != null) {
                        Iterator<String> it = viObj.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            String v = viObj.optString(k);
                            if (v != null && v.length() > 0) {
                                vi.put(k, v);
                            }
                        }
                    }
                    zhByEn = en;
                    zhByVi = vi;
                    XposedBridge.log(TAG + " loaded src map en=" + en.size() + " vi=" + vi.size());
                } else {
                    XposedBridge.log(TAG + " src_map.json not found, source-map fallback disabled");
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " load src map fail: " + t);
            }
        }
    }

    /** 按英文/越南语原文查找中文（HARDCODED 优先，再查全量源码映射）。 */
    private static String lookupSource(String s) {
        if (s == null || s.length() == 0) {
            return null;
        }
        String zh = HARDCODED.get(s);
        if (zh != null && !zh.equals(s)) {
            return zh;
        }
        if (zhByEn == null) {
            loadTranslations();
        }
        if (zhByEn != null) {
            zh = zhByEn.get(s);
            if (zh != null && !zh.equals(s)) {
                return zh;
            }
        }
        if (zhByVi != null) {
            zh = zhByVi.get(s);
            if (zh != null && !zh.equals(s)) {
                return zh;
            }
        }
        return null;
    }

    /**
     * 根据资源 ID 返回中文翻译；无翻译或非 Zalo 资源时返回 null（保持原样）。
     */
    static String translate(int id, Resources res) {
        if (id < 0x7f000000 || res == null) {
            return null; // framework 资源不处理
        }
        if (zhByName == null) {
            loadTranslations();
        }
        if (zhByName == null || zhByName.isEmpty()) {
            return null;
        }
        synchronized (zhById) {
            String zh = zhById.get(Integer.valueOf(id));
            if (zh != null) {
                return zh;
            }
            if (checkedIds.contains(Integer.valueOf(id))) {
                return null;
            }
        }
        String name;
        try {
            name = res.getResourceEntryName(id);
        } catch (Throwable t) {
            synchronized (checkedIds) {
                checkedIds.add(Integer.valueOf(id));
            }
            return null;
        }
        String zh = zhByName.get(name);
        synchronized (zhById) {
            if (zh != null) {
                zhById.put(Integer.valueOf(id), zh);
            }
            checkedIds.add(Integer.valueOf(id));
        }
        return zh;
    }

    /**
     * WebView H5 页面汉化：
     * 1) hook setWebViewClient，把应用的 client 包装成 ZhWebViewClient，
     *    onPageFinished/onPageCommitVisible 时注入翻译脚本（覆盖普通页面）；
     * 2) 兜底 hook WebViewClient.onPageFinished 基类方法（应用用默认 client 时也能命中）；
     * 3) 关键：Zalo 钱包 H5（Activity/Card）走小程序容器 WebBaseView + ZWebView，
     *    从不调用 setWebViewClient，页面完成回调是自定义的 H7(String url)。
     *    hook WebBaseView.H7，反射找 ZWebView 字段注入（26.08.x 字段名 f40181j1，
     *    按类型匹配不依赖名字）。
     * 注入脚本按字典精确替换文本节点，MutationObserver 覆盖动态内容。
     */
    private static void hookWebView(XC_LoadPackage.LoadPackageParam lpparam) {
        final String js = loadWebInjectJs();
        if (js == null) {
            XposedBridge.log(TAG + " web_inject.js not found, WebView translation disabled");
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(WebView.class, "setWebViewClient", WebViewClient.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                final WebView wv = (WebView) param.thisObject;
                                WebViewClient orig = (WebViewClient) param.args[0];
                                if (orig == null || orig instanceof ZhWebViewClient) {
                                    return;
                                }
                                wv.setWebViewClient(new ZhWebViewClient(orig, js));
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " setWebViewClient wrap error: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook setWebViewClient fail: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(WebViewClient.class, "onPageFinished", WebView.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                WebView view = (WebView) param.args[0];
                                if (view != null) {
                                    view.evaluateJavascript(js, null);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " default-client inject error: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook WebViewClient.onPageFinished fail: " + t);
        }
        // Zalo 小程序/钱包 H5 容器：WebBaseView.H7(url) 是它的页面完成回调（
        // MPWebView 覆写时第一行调 super.H7(str)，hook 基类方法即可命中）。
        try {
            Class<?> wbv = Class.forName("com.zing.zalo.ui.zviews.WebBaseView", false, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(wbv, "H7", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        WebView wv = findZWebView(param.thisObject);
                        if (wv == null) {
                            return;
                        }
                        wv.evaluateJavascript(js, null);
                        // SPA 异步渲染兜底：延迟再补一次
                        wv.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    wv.evaluateJavascript(js, null);
                                } catch (Throwable t) {
                                }
                            }
                        }, 1500L);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " H7 inject error: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + " WebBaseView.H7 hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " WebBaseView.H7 hook fail: " + t);
        }
    }

    /** 反射遍历对象字段，找第一个 WebView 类型字段（Zalo 的 ZWebView）。 */
    private static WebView findZWebView(Object obj) {
        try {
            Class<?> c = obj.getClass();
            while (c != null && c != Object.class) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    try {
                        if (WebView.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            Object v = f.get(obj);
                            if (v instanceof WebView) {
                                return (WebView) v;
                            }
                        }
                    } catch (Throwable t) {
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
        }
        return null;
    }

    /** 包装 WebViewClient：加载完成后注入翻译脚本。 */
    private static class ZhWebViewClient extends WebViewClient {
        private final WebViewClient base;
        private final String js;

        ZhWebViewClient(WebViewClient base, String js) {
            this.base = base;
            this.js = js;
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            try {
                if (base != null) {
                    base.onPageCommitVisible(view, url);
                }
            } catch (Throwable t) {
            }
            inject(view);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            try {
                if (base != null) {
                    base.onPageFinished(view, url);
                }
            } catch (Throwable t) {
            }
            inject(view);
        }

        private void inject(WebView view) {
            try {
                if (view != null && js != null) {
                    view.evaluateJavascript(js, null);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " webview inject error: " + t);
            }
        }
    }

    /** 读取打包进 assets 的 web_inject.js（含合并字典的 DOM 翻译脚本）。 */
    private static String loadWebInjectJs() {
        try {
            InputStream is = MainHook.class.getClassLoader().getResourceAsStream("assets/web_inject.js");
            if (is == null) {
                return null;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            is.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " load web_inject.js fail: " + t);
            return null;
        }
    }
}
