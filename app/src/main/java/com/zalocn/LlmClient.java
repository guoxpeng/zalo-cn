package com.zalocn;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import de.robv.android.xposed.XposedBridge;

/**
 * 自定义大模型翻译客户端（OpenAI 兼容 /chat/completions 协议）。
 *
 * 只在后台线程调用；任何网络异常都返回 null，由调用方按“不翻译、显示原文”处理，
 * 绝不抛异常、不阻塞 UI、不改动消息内容。
 */
public class LlmClient {

    static final String TAG = "[ZALO-ZH]";

    /** 一次翻译调用的超时（毫秒）：连接 10 秒、读取 30 秒。 */
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;

    /**
     * 翻译一段文本。
     *
     * @param cfg      配置（地址/密钥/模型）
     * @param text     待翻译原文
     * @param fromLang 原文语言（"vi" 越南语 / "zh" 中文 / "auto" 自动识别，接收翻译用）
     * @param toLang   目标语言（"zh" 中文 / "vi" 越南语）
     * @param context  上下文（同一会话最近几条消息原文，供大模型消歧；可为空）
     * @return 译文；失败返回 null
     */
    public static String translate(TranslateConfig cfg, String text,
                                   String fromLang, String toLang, String context) {
        if (cfg == null || !cfg.hasApi() || text == null || text.trim().length() == 0) {
            return null;
        }
        HttpURLConnection conn = null;
        try {
            String base = cfg.baseUrl;
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            URL url = new URL(base + "/chat/completions");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey);

            String toName = "zh".equals(toLang) ? "中文" : "越南语";
            StringBuilder sys = new StringBuilder();
            if ("auto".equals(fromLang)) {
                sys.append("你是一名中越双语翻译。自动识别用户给出的消息的语言，")
                   .append("翻译成").append(toName).append("。");
            } else {
                String fromName = "vi".equals(fromLang) ? "越南语" : "中文";
                sys.append("你是一名中越双语翻译。把用户给出的").append(fromName)
                   .append("翻译成").append(toName).append("。");
            }
            sys.append("只输出译文，不要解释、不要加引号、")
               .append("不要补充原文没有的内容；人名、地名、品牌名保留原文；语气保持口语自然。");
            StringBuilder user = new StringBuilder();
            if (context != null && context.length() > 0) {
                user.append("会话上下文（仅供消歧，不要翻译这部分）：\n").append(context).append("\n\n");
            }
            user.append("待翻译：\n").append(text);

            JSONObject body = new JSONObject();
            body.put("model", cfg.model);
            body.put("temperature", 0.3);
            JSONArray messages = new JSONArray();
            JSONObject m1 = new JSONObject();
            m1.put("role", "system");
            m1.put("content", sys.toString());
            messages.put(m1);
            JSONObject m2 = new JSONObject();
            m2.put("role", "user");
            m2.put("content", user.toString());
            messages.put(m2);
            body.put("messages", messages);

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAll(is);
            if (code < 200 || code >= 300) {
                XposedBridge.log(TAG + " LLM HTTP " + code + ": " + safeHead(resp));
                return null;
            }
            JSONObject obj = new JSONObject(resp);
            JSONArray choices = obj.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return null;
            }
            JSONObject msg = choices.optJSONObject(0).optJSONObject("message");
            if (msg == null) {
                return null;
            }
            String out = msg.optString("content", null);
            if (out == null) {
                return null;
            }
            out = out.trim();
            return out.length() == 0 ? null : out;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " LLM translate fail: " + t);
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append('\n');
        }
        br.close();
        return sb.toString();
    }

    /** 日志里只留错误响应前 200 字，且绝不输出密钥。 */
    private static String safeHead(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
