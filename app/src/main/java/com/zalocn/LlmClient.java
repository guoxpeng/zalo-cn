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

    /** 最近一次翻译失败的原因（供设置页“测试当前 API”显示真实原因，Zalo 进程里不读它）。
     * LlmClient 从不抛异常，只返回 null；不记原因的话，调用方分不清是配置错、
     * 网络不通还是 API 报错，只能一律说“配置有误”（之前测试按钮的误判就是这么来的）。 */
    static String lastError = null;

    /** 一次翻译调用的超时（毫秒）：连接 10 秒、读取 30 秒。 */
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 60000;

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
    /** 翻译入口：单次请求。失败重试由 MessageHook 层统一处理，避免多层重试叠加。 */
    public static String translate(TranslateConfig cfg, String text,
                                   String fromLang, String toLang, String context) {
        return translateOnce(cfg, text, fromLang, toLang, context);
    }

    /** 单次翻译请求。 */
    private static String translateOnce(TranslateConfig cfg, String text,
                                   String fromLang, String toLang, String context) {
        if (cfg == null || text == null || text.trim().length() == 0) {
            return null;
        }
        lastError = null;
        // 免费网页翻译引擎：不走大模型 API，不用密钥、不读地址/模型。
        if (cfg.engine != null && !"ai".equals(cfg.engine)) {
            String r = FreeTranslateClient.translate(cfg.engine, text, fromLang, toLang);
            if (r == null) {
                lastError = "免费引擎翻译失败，请检查网络";
            }
            return r;
        }
        if (!cfg.hasApi()) {
            lastError = "未填 API 地址或模型";
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
            // 密钥可空：免密钥的公开 API 不发 Authorization 头（空 Bearer 会被某些服务端拒绝）。
            if (cfg.apiKey != null && cfg.apiKey.length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey);
            }

            String toName = TranslateConfig.targetLangName(toLang);
            StringBuilder sys = new StringBuilder();
            if ("auto".equals(fromLang)) {
                sys.append("你是一名翻译。自动识别用户给出的消息的语言，")
                   .append("翻译成").append(toName).append("。");
            } else {
                String fromName = TranslateConfig.targetLangName(fromLang);
                sys.append("你是一名翻译。把用户给出的").append(fromName)
                   .append("翻译成").append(toName).append("。");
            }
            sys.append("只输出" + toName + "译文，绝对不要重复或输出原文，不要解释、不要加引号、")
               .append("不要补充原文没有的内容；人名、地名、品牌名保留原文；语气保持口语自然。");
            // 用户自定义提示词（设置页填）：追加到系统提示词后，纠正直译错误
            // （如地名按越南官方译名）。空 = 不用。
            if (cfg.customPrompt != null && cfg.customPrompt.trim().length() > 0) {
                String cp = cfg.customPrompt.trim();
                // 过滤会与"翻译成目标语言"冲突的格式/方向指令（如"格式 中文-越南语"），
                // 这类句子会让模型误以为要输出原文语言，导致重复原文。
                StringBuilder safe = new StringBuilder();
                for (String seg : cp.split("[。\n；;]")) {
                    if (seg.contains("中文-越南语") || seg.contains("保留输入的中文原文")
                            || seg.contains("换行输出") || seg.contains("全局保留")) continue;
                    if (seg.trim().length() > 0) safe.append(seg.trim()).append("。");
                }
                if (safe.length() > 0) {
                    sys.append("\n另外，请遵守以下翻译要求：\n").append(safe);
                }
            }
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
                lastError = classifyHttpError(code, resp, cfg.model);
                return null;
            }
            JSONObject obj = new JSONObject(resp);
            JSONArray choices = obj.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                lastError = "API 返回内容为空";
                return null;
            }
            JSONObject msg = choices.optJSONObject(0).optJSONObject("message");
            if (msg == null) {
                lastError = "API 返回内容为空";
                return null;
            }
            String out = msg.optString("content", null);
            if (out == null) {
                lastError = "API 返回内容为空";
                return null;
            }
            out = out.trim();
            if (out.length() == 0) {
                lastError = "API 返回内容为空";
                return null;
            }
            return out;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " LLM translate fail: " + t);
            lastError = classifyNetworkError(t);
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

    /** HTTP 报错分类：先看是不是模型名不对，再看是不是地址不对，其他一律算 API 错误。
     * 测试按钮直接显示这里的结果，不再一律说“配置有误”。 */
    private static String classifyHttpError(int code, String resp, String model) {
        String body = resp != null ? resp.toLowerCase() : "";
        // 模型名不对：OpenAI 兼容接口通常在 404/400 里写 model not found / does not exist
        if (body.contains("model") && (body.contains("not found") || body.contains("does not exist")
                || body.contains("unknown model") || body.contains("invalid model")
                || body.contains("no such model"))) {
            return "找不到模型 " + model + "：请检查模型名称是否写对";
        }
        if (code == 404) {
            return "API 地址有误：接口返回 404，请检查 API 地址";
        }
        if (code == 401 || code == 403) {
            return "API 错误：HTTP " + code + "（密钥可能不对或无权限）";
        }
        return "API 错误：HTTP " + code + "（" + safeHead(resp) + "）";
    }

    /** 网络异常分类：连接失败 / 地址有误 / 其他。 */
    private static String classifyNetworkError(Throwable t) {
        String msg = t.getMessage() != null ? t.getMessage() : "";
        if (t instanceof java.net.UnknownHostException) {
            return "API 地址有误：域名解析失败（" + msg + "）";
        }
        if (t instanceof java.net.MalformedURLException) {
            return "API 地址有误：地址格式不对（" + msg + "）";
        }
        if (t instanceof java.net.SocketTimeoutException) {
            return "连接失败：连接超时（" + msg + "）";
        }
        if (t instanceof java.net.ConnectException) {
            return "连接失败：服务器连不上（" + msg + "）";
        }
        if (t instanceof SecurityException) {
            return "连接失败：没有联网权限（" + msg + "）";
        }
        if (t instanceof java.io.IOException) {
            return "连接失败：" + msg;
        }
        return "网络请求失败：" + t;
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
