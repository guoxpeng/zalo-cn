package com.zalocn;

import android.content.Context;
import android.content.SharedPreferences;

import de.robv.android.xposed.XposedBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 大模型模板：每个模板独立保存一套 API 数据（地址/密钥/模型）。
 * 下拉框显示的模板就是当前生效的大模型；切换下拉框即切换生效模型，
 * 不用重新填写——各模板的数据存在同一 prefs 文件的 modelProfiles JSON 里。
 * 提示词是全局的，所有大模型模板共用一个，存在扁平键 customPrompt 里，
 * 不跟模板走；hook 侧（Zalo 进程）只读扁平的 baseUrl/apiKey/model/customPrompt，
 * 设置页每次切换/编辑后都把当前模板的值同步写回扁平键。
 */
public class ModelProfile {
    public String name;
    public String baseUrl;
    public String apiKey;
    public String model;
    /**
     * 引擎："ai" = 大模型 API（OpenAI 兼容，用上面三项）；
     * "google" / "google2" / "bing" / "mymemory" = 免费网页翻译引擎
     * （不用密钥，上面三项留空）。
     */
    public String engine = "ai";
    /** 系统预设不能删、不能改名；用户新建的模板可以删、可以改名。 */
    public boolean builtin;

    public ModelProfile() {
    }

    public ModelProfile(String name, String baseUrl, String apiKey,
                        String model, boolean builtin) {
        this(name, baseUrl, apiKey, model, "ai", builtin);
    }

    public ModelProfile(String name, String baseUrl, String apiKey,
                        String model, String engine, boolean builtin) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.engine = engine;
        this.builtin = builtin;
    }

    /** 系统预设：免费引擎在前（照 WATranslate 截图的顺序），大模型在后。 */
    public static List<ModelProfile> defaults() {
        List<ModelProfile> out = new ArrayList<ModelProfile>();
        out.add(new ModelProfile("Google 翻译（免密钥，推荐）",
                "", "", "", "google", true));
        out.add(new ModelProfile("Google 翻译·备用通道（免密钥）",
                "", "", "", "google2", true));
        out.add(new ModelProfile("Bing 翻译（免密钥）",
                "", "", "", "bing", true));
        out.add(new ModelProfile("MyMemory 翻译记忆库（免密钥）",
                "", "", "", "mymemory", true));
        out.add(new ModelProfile("bilibili 免密钥翻译",
                "https://index-translate.bilibili.com/v1", "",
                "Index-Translate-35B-A3B", "ai", true));
        out.add(new ModelProfile("OpenAI",
                "https://api.openai.com/v1", "", "gpt-4o-mini", "ai", true));
        out.add(new ModelProfile("DeepSeek",
                "https://api.deepseek.com/v1", "", "deepseek-chat", "ai", true));
        return out;
    }

    public static List<ModelProfile> load(Context ctx) {
        List<ModelProfile> out = new ArrayList<ModelProfile>();
        try {
            SharedPreferences sp = TranslateConfig.openPrefs(ctx);
            String json = sp.getString("modelProfiles", null);
            if (json == null || json.length() == 0) {
                return out;
            }
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                ModelProfile p = new ModelProfile();
                p.name = o.optString("name", "");
                p.baseUrl = o.optString("baseUrl", "");
                p.apiKey = o.optString("apiKey", "");
                p.model = o.optString("model", "");
                p.engine = o.optString("engine", "ai");
                p.builtin = o.optBoolean("builtin", false);
                if (p.name.length() > 0) {
                    out.add(p);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("ZALO-ZH load profiles fail: " + t);
        }
        return out;
    }

    public static void save(Context ctx, List<ModelProfile> profiles) {
        try {
            JSONArray arr = new JSONArray();
            for (ModelProfile p : profiles) {
                JSONObject o = new JSONObject();
                o.put("name", p.name);
                o.put("baseUrl", p.baseUrl);
                o.put("apiKey", p.apiKey);
                o.put("model", p.model);
                o.put("engine", p.engine);
                o.put("builtin", p.builtin);
                arr.put(o);
            }
            SharedPreferences sp = TranslateConfig.openPrefs(ctx);
            sp.edit().putString("modelProfiles", arr.toString()).commit();
        } catch (Throwable t) {
            XposedBridge.log("ZALO-ZH save profiles fail: " + t);
        }
    }

    public static String loadActiveName(Context ctx) {
        try {
            return TranslateConfig.openPrefs(ctx).getString("activeProfile", null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void saveActiveName(Context ctx, String name) {
        try {
            TranslateConfig.openPrefs(ctx).edit().putString("activeProfile", name).commit();
        } catch (Throwable t) {
            XposedBridge.log("ZALO-ZH save active profile fail: " + t);
        }
    }

    /** 按名字找模板，找不到返回 -1。 */
    public static int indexOfName(List<ModelProfile> profiles, String name) {
        if (profiles == null || name == null) {
            return -1;
        }
        for (int i = 0; i < profiles.size(); i++) {
            if (name.equals(profiles.get(i).name)) {
                return i;
            }
        }
        return -1;
    }

    /** 按地址+模型找模板（首次升级时认领旧配置用），找不到返回 -1。 */
    public static int indexOfValue(List<ModelProfile> profiles, String baseUrl, String model) {
        if (profiles == null || baseUrl == null || model == null) {
            return -1;
        }
        for (int i = 0; i < profiles.size(); i++) {
            ModelProfile p = profiles.get(i);
            if (baseUrl.equals(p.baseUrl) && model.equals(p.model)) {
                return i;
            }
        }
        return -1;
    }
}
