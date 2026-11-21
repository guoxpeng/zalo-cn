package com.zalocn;

import de.robv.android.xposed.XposedBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 免费网页翻译引擎（不需要密钥、不走大模型 API）。
 * 引擎 id 与设置页模板下拉框对应：
 * google = Google 翻译（translate.googleapis.com，client=gtx）
 * google2 = Google 翻译·备用通道（clients5.google.com，dict-chrome-ex）
 * bing = Bing 翻译（www.bing.com/ttranslatev3，需先拿 translator 会话的 IG+Cookie）
 * mymemory = MyMemory 翻译记忆库（api.mymemory.translated.net）
 * 全部在后台线程调用（翻译本来就在 TRANSLATE_POOL / 测试线程里跑）。
 * 任何一步失败返回 null，调用方按“翻译失败”处理。
 * 验证状态（2026-11-21 沙盒实测）：google、google2、mymemory 中越双向都通；
 * bing 在沙盒里三轮都报 400（疑似出口 IP 被拦），实现是标准会话流，
 * 需在真机上验证——真机上不通就从模板里去掉。
 */
public class FreeTranslateClient {

    private static final String TAG = "[ZALO-ZH]";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    public static String translate(String engine, String text, String fromLang, String toLang) {
        if (engine == null || text == null || text.trim().length() == 0) {
            return null;
        }
        try {
            if ("google".equals(engine)) {
                return google(text, fromLang, toLang, false);
            } else if ("google2".equals(engine)) {
                return google(text, fromLang, toLang, true);
            } else if ("bing".equals(engine)) {
                return bing(text, fromLang, toLang);
            } else if ("mymemory".equals(engine)) {
                return myMemory(text, fromLang, toLang);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " free translate " + engine + " fail: " + t);
        }
        return null;
    }

    // ---- Google（主 + 备用） ----

    private static String google(String text, String from, String to, boolean backup) throws Exception {
        String base = backup
                ? "https://clients5.google.com/translate_a/t"
                : "https://translate.googleapis.com/translate_a/single";
        String client = backup ? "dict-chrome-ex" : "gtx";
        String url = base + "?client=" + client
                + "&sl=" + URLEncoder.encode(normGoogle(from), "UTF-8")
                + "&tl=" + URLEncoder.encode(normGoogle(to), "UTF-8")
                + "&dt=t&q=" + URLEncoder.encode(text, "UTF-8");
        String resp = httpGet(url);
        if (resp == null) {
            return null;
        }
        JSONArray arr = new JSONArray(resp);
        StringBuilder sb = new StringBuilder();
        if (backup) {
            // 备用通道返回平铺字符串数组：["译文", ...]
            for (int i = 0; i < arr.length(); i++) {
                Object o = arr.opt(i);
                if (o instanceof String) {
                    sb.append((String) o);
                } else if (o instanceof JSONArray) {
                    JSONArray seg = (JSONArray) o;
                    if (seg.length() > 0 && seg.opt(0) instanceof String) {
                        sb.append(seg.optString(0));
                    }
                }
            }
        } else {
            // 主通道：[[["译文","原文",...],...],...]，取每段的 [0]
            JSONArray segs = arr.optJSONArray(0);
            if (segs == null) {
                return null;
            }
            for (int i = 0; i < segs.length(); i++) {
                JSONArray seg = segs.optJSONArray(i);
                if (seg != null && seg.length() > 0) {
                    sb.append(seg.optString(0));
                }
            }
        }
        String out = sb.toString().trim();
        return out.length() > 0 ? out : null;
    }

    private static String normGoogle(String lang) {
        if (lang == null || lang.length() == 0) {
            return "auto";
        }
        return lang;
    }

    // ---- Bing（会话流：先拿 translator 页的 IG + Cookie，再调 ttranslatev3） ----

    private static String bing(String text, String from, String to) throws Exception {
        // ① 拿会话：translator 首页的 IG 与 Cookie。
        HttpURLConnection pageConn = null;
        String ig = null;
        List<String> cookies = new ArrayList<String>();
        try {
            URL pageUrl = new URL("https://www.bing.com/translator");
            pageConn = (HttpURLConnection) pageUrl.openConnection();
            pageConn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            pageConn.setReadTimeout(READ_TIMEOUT_MS);
            pageConn.setRequestProperty("User-Agent", UA);
            int code = pageConn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            List<String> setCookie = pageConn.getHeaderFields().get("Set-Cookie");
            if (setCookie != null) {
                for (String c : setCookie) {
                    int semi = c.indexOf(';');
                    cookies.add(semi >= 0 ? c.substring(0, semi) : c);
                }
            }
            String html = readAll(pageConn.getInputStream());
            Matcher m = Pattern.compile("\"IG\"\\s*:\\s*\"([0-9A-Fa-f]+)\"").matcher(html);
            if (m.find()) {
                ig = m.group(1);
            }
        } finally {
            if (pageConn != null) {
                pageConn.disconnect();
            }
        }
        if (ig == null) {
            return null;
        }
        // ② 调翻译接口。
        String url = "https://www.bing.com/ttranslatev3?fromLang="
                + URLEncoder.encode(normBing(from), "UTF-8")
                + "&to=" + URLEncoder.encode(normBing(to), "UTF-8")
                + "&IG=" + ig;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", "https://www.bing.com/translator");
            if (!cookies.isEmpty()) {
                StringBuilder cb = new StringBuilder();
                for (int i = 0; i < cookies.size(); i++) {
                    if (i > 0) {
                        cb.append("; ");
                    }
                    cb.append(cookies.get(i));
                }
                conn.setRequestProperty("Cookie", cb.toString());
            }
            String body = "text=" + URLEncoder.encode(text, "UTF-8");
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAll(is);
            if (code < 200 || code >= 300) {
                XposedBridge.log(TAG + " bing HTTP " + code);
                return null;
            }
            JSONArray arr = new JSONArray(resp);
            JSONObject first = arr.optJSONObject(0);
            if (first == null) {
                return null;
            }
            JSONArray trans = first.optJSONArray("translations");
            if (trans == null || trans.length() == 0) {
                return null;
            }
            String out = trans.optJSONObject(0).optString("text", "").trim();
            return out.length() > 0 ? out : null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String normBing(String lang) {
        if (lang == null || lang.length() == 0) {
            return "auto";
        }
        if ("zh".equals(lang)) {
            return "zh-Hans";
        }
        return lang;
    }

    // ---- MyMemory（翻译记忆库；不支持 auto，调用方须传检测到的源语） ----

    private static String myMemory(String text, String from, String to) throws Exception {
        String src = from;
        if (src == null || src.length() == 0 || "auto".equals(src)) {
            src = guessLang(text);
        }
        String url = "https://api.mymemory.translated.net/get?q="
                + URLEncoder.encode(text, "UTF-8")
                + "&langpair=" + URLEncoder.encode(src, "UTF-8")
                + "|" + URLEncoder.encode(to, "UTF-8");
        String resp = httpGet(url);
        if (resp == null) {
            return null;
        }
        JSONObject obj = new JSONObject(resp);
        if (obj.optInt("responseStatus", 0) != 200) {
            return null;
        }
        JSONObject data = obj.optJSONObject("responseData");
        if (data == null) {
            return null;
        }
        String out = data.optString("translatedText", "").trim();
        return out.length() > 0 ? out : null;
    }

    /** 兜底语种猜测（只在调用方没给源语时用）：含汉字判中文，否则默认越语。 */
    private static String guessLang(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return "zh-CN";
            }
        }
        return "vi";
    }

    // ---- 小工具 ----

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", UA);
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAll(is);
            if (code < 200 || code >= 300) {
                XposedBridge.log(TAG + " free HTTP " + code + ": " + urlStr.substring(0, Math.min(80, urlStr.length())));
                return null;
            }
            return resp;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = br.read(buf)) >= 0) {
            sb.append(buf, 0, n);
        }
        br.close();
        return sb.toString();
    }
}
