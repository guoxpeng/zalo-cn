package com.zalocn;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 翻译功能设置界面（模块 APK 自带的启动器入口）。
 *
 * 纯代码写 UI，不依赖 XML 资源（构建脚本只链接 Manifest、不打包 res）。
 * 配置存在模块自身 prefs 里，LSPosed 重定向后仅 Zalo 进程可读，普通 App
 * 读不到；API 密钥只发往用户自己填的 API 地址。全部收到的消息与上下文
 * 都会发到你填的 API 地址——用中转服务的话，中转方能看到所有聊天内容。
 */
public class TranslateSettingsActivity extends Activity {

    private EditText etBaseUrl;
    private EditText etApiKey;
    private EditText etModel;
    private CheckBox cbEnabled;
    private CheckBox cbAutoIncoming;
    private CheckBox cbAutoOutgoing;
    private CheckBox cbReviewBeforeSend;
    private EditText etPhrases;
    private TextView tvStatus;

    private static final String DEFAULT_PHRASES =
            "你好|Xin chào\n" +
            "谢谢|Cảm ơn\n" +
            "请问多少钱？|Cho hỏi giá bao nhiêu?\n" +
            "请稍等，我确认一下|Xin đợi một chút, để tôi kiểm tra\n" +
            "好的，没问题|Được, không vấn đề gì";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        addTitle(root, "Zalo 汉化 · 翻译设置");
        addHint(root, "翻译走你自己填的大模型 API（OpenAI 兼容接口）。配置存在模块自身 prefs，LSPosed 重定向后仅 Zalo 进程可读，普通 App 读不到；API 密钥只发往你自己填的 API 地址。全部收到的消息与上下文会发到你填的 API 地址，用中转服务的话中转方能看到所有聊天内容。");

        etBaseUrl = addField(root, "API 地址", "例如 https://api.openai.com/v1");
        etApiKey = addField(root, "API 密钥", "sk-…（仅发往你填的 API 地址）");
        etApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etModel = addField(root, "模型", "例如 gpt-4o-mini");

        cbEnabled = addCheck(root, "启用消息翻译");
        cbAutoIncoming = addCheck(root, "收到越语消息，自动翻译成中文");
        cbAutoOutgoing = addCheck(root, "发出中文消息，自动翻成越语再发");
        cbReviewBeforeSend = addCheck(root, "发送前先检查译文（译文填回输入框，确认后点发送）");

        addTitle(root, "快捷短语");
        addHint(root, "每行一条，格式：中文 | 越南语。越南语留空时，插入时按配置现翻。点输入框长按可调出短语。");
        etPhrases = new EditText(this);
        etPhrases.setMinLines(5);
        etPhrases.setGravity(Gravity.TOP);
        root.addView(etPhrases, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvStatus = new TextView(this);
        tvStatus.setPadding(0, dp(12), 0, 0);
        root.addView(tvStatus);

        Button btnSave = new Button(this);
        btnSave.setText("保存");
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        root.addView(btnSave, lp);

        load();
    }

    private void load() {
        TranslateConfig cfg = TranslateConfig.loadForSettings(this);
        etBaseUrl.setText(cfg.baseUrl);
        etApiKey.setText(cfg.apiKey);
        etModel.setText(cfg.model);
        cbEnabled.setChecked(cfg.enabled);
        cbAutoIncoming.setChecked(cfg.autoIncoming);
        cbAutoOutgoing.setChecked(cfg.autoOutgoing);
        cbReviewBeforeSend.setChecked(cfg.reviewBeforeSend);
        List<String[]> phrases = cfg.phrases;
        if (phrases == null || phrases.isEmpty()) {
            etPhrases.setText(DEFAULT_PHRASES);
        } else {
            etPhrases.setText(TranslateConfig.joinPhrases(phrases));
        }
        refreshStatus(cfg);
    }

    private void save() {
        TranslateConfig cfg = new TranslateConfig();
        cfg.baseUrl = etBaseUrl.getText().toString().trim();
        cfg.apiKey = etApiKey.getText().toString().trim();
        cfg.model = etModel.getText().toString().trim();
        if (cfg.model.length() == 0) {
            cfg.model = TranslateConfig.DEFAULT_MODEL;
        }
        cfg.enabled = cbEnabled.isChecked();
        cfg.autoIncoming = cbAutoIncoming.isChecked();
        cfg.autoOutgoing = cbAutoOutgoing.isChecked();
        cfg.reviewBeforeSend = cbReviewBeforeSend.isChecked();
        cfg.phrases = TranslateConfig.parsePhrases(etPhrases.getText().toString());
        cfg.saveForSettings(this);
        refreshStatus(cfg);
        Toast.makeText(this, "已保存，Zalo 里的翻译即刻生效（无需重启）", Toast.LENGTH_LONG).show();
    }

    private void refreshStatus(TranslateConfig cfg) {
        StringBuilder sb = new StringBuilder();
        if (!cfg.hasApi()) {
            sb.append("状态：未填 API 地址或密钥，翻译功能暂不可用。");
        } else {
            sb.append("状态：已配置 ").append(cfg.model).append("，")
              .append(cfg.autoIncoming ? "收到的越语自动翻；" : "收到的不自动翻；")
              .append(cfg.autoOutgoing ? "发出的中文自动翻成越语。" : "发出的不自动翻。");
        }
        tvStatus.setText(sb.toString());
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void addTitle(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setPadding(0, dp(8), 0, dp(4));
        root.addView(tv);
    }

    private void addHint(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setPadding(0, 0, 0, dp(8));
        root.addView(tv);
    }

    private EditText addField(LinearLayout root, String label, String hint) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setPadding(0, dp(8), 0, dp(2));
        root.addView(tv);
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setSingleLine(true);
        root.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    private CheckBox addCheck(LinearLayout root, String text) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        root.addView(cb);
        return cb;
    }
}
