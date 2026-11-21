package com.zalocn;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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
    private EditText etHistBaseUrl;
    private EditText etHistApiKey;
    private EditText etHistModel;
    private CheckBox cbEnabled;
    private CheckBox cbAutoIncoming;
    private CheckBox cbAutoOutgoing;
    private CheckBox cbReviewBeforeSend;
    private EditText etPhrases;
    private TextView tvStatus;
    private Button btnNotif;
    private TranslateConfig lastCfg;

    /** 通知权限请求码（安卓 13+ 提醒通知要运行时申请）。 */
    private static final int REQ_POST_NOTIFICATIONS = 1001;

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

        addTitle(root, "历史记录翻译（可选）");
        addHint(root, "接收/历史翻译用这里，输入框发送用上面的主配置。某一项空着就用上面对应的项，三项都空=完全用主配置。");
        etHistBaseUrl = addField(root, "历史翻译 API 地址（空=用上面）", "");
        etHistApiKey = addField(root, "历史翻译 API 密钥（空=用上面）", "");
        etHistApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etHistModel = addField(root, "历史翻译模型（空=用上面）", "");

        addTitle(root, "快捷短语");
        addHint(root, "每行一条，格式：中文 | 越南语。越南语留空时，插入时按配置现翻。点输入框长按可调出短语。");
        etPhrases = new EditText(this);
        etPhrases.setMinLines(5);
        etPhrases.setGravity(Gravity.TOP);
        root.addView(etPhrases, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addTitle(root, "待办提醒");
        addHint(root, "精确闹钟取决于 Zalo 有没有精确闹钟权限（系统设置 → 应用 → Zalo → 闹钟与提醒）；没给的话提醒仍会响，但可能晚几分钟。提醒通知由本模块发，需要下面的通知权限。重启手机后，首次打开 Zalo 会自动重设未过期的提醒。");

        tvStatus = new TextView(this);
        tvStatus.setPadding(0, dp(12), 0, 0);
        root.addView(tvStatus);

        btnNotif = new Button(this);
        btnNotif.setText("打开通知设置");
        btnNotif.setVisibility(View.GONE);
        btnNotif.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openNotifSettings();
            }
        });
        root.addView(btnNotif, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

        // 提醒通知由模块自身进程发：安卓 13+ 必须运行时申请通知权限，
        // 没权限到点什么都不显示。只在未授权时申请一次；用户拒绝后靠
        // 状态栏的“打开通知设置”按钮手动去开。
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_POST_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_POST_NOTIFICATIONS && lastCfg != null) {
            refreshStatus(lastCfg);
        }
    }

    /** 打开系统通知设置页（用户拒绝过权限申请后，手动开启的入口）。 */
    private void openNotifSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Throwable t) {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Throwable t2) {
                Toast.makeText(this, "请手动打开：系统设置 → 应用 → Zalo 汉化 → 通知",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 系统通知总开关（安卓 13+ 含运行时权限状态；24+ 含用户手动关闭状态）。 */
    private boolean areNotificationsEnabled() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                NotificationManager nm = (NotificationManager)
                        getSystemService(Context.NOTIFICATION_SERVICE);
                return nm != null && nm.areNotificationsEnabled();
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    private void load() {
        TranslateConfig cfg = TranslateConfig.loadForSettings(this);
        etBaseUrl.setText(cfg.baseUrl);
        etApiKey.setText(cfg.apiKey);
        etModel.setText(cfg.model);
        etHistBaseUrl.setText(cfg.histBaseUrl);
        etHistApiKey.setText(cfg.histApiKey);
        etHistModel.setText(cfg.histModel);
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
        // 首次打开设置页即生成提醒校验 token（之后 ReminderReceiver 强制校验）。
        cfg.reminderToken = TranslateConfig.ensureReminderToken(this);
        lastCfg = cfg;
        refreshStatus(cfg);
    }

    private void save() {
        TranslateConfig cfg = new TranslateConfig();
        cfg.baseUrl = etBaseUrl.getText().toString().trim();
        cfg.apiKey = etApiKey.getText().toString().trim();
        cfg.model = etModel.getText().toString().trim();
        cfg.histBaseUrl = etHistBaseUrl.getText().toString().trim();
        cfg.histApiKey = etHistApiKey.getText().toString().trim();
        cfg.histModel = etHistModel.getText().toString().trim();
        if (cfg.model.length() == 0) {
            cfg.model = TranslateConfig.DEFAULT_MODEL;
        }
        cfg.enabled = cbEnabled.isChecked();
        cfg.autoIncoming = cbAutoIncoming.isChecked();
        cfg.autoOutgoing = cbAutoOutgoing.isChecked();
        cfg.reviewBeforeSend = cbReviewBeforeSend.isChecked();
        cfg.phrases = TranslateConfig.parsePhrases(etPhrases.getText().toString());
        if (cfg.reminderToken == null || cfg.reminderToken.length() == 0) {
            cfg.reminderToken = TranslateConfig.ensureReminderToken(this);
        }
        cfg.saveForSettings(this);
        lastCfg = cfg;
        refreshStatus(cfg);
        if (cfg.baseUrl.length() > 0 && !cfg.baseUrl.startsWith("https://")) {
            Toast.makeText(this, "注意：API 地址不是 https，密钥将明文传输", Toast.LENGTH_LONG).show();
        }
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
        // 通知权限：提醒通知由模块自身进程发，安卓 13+ 没权限到点不显示。
        boolean notifOn = areNotificationsEnabled();
        sb.append("\n通知权限：").append(notifOn ? "已开" : "未开");
        if (!notifOn) {
            sb.append("（提醒到点不会弹窗。手动开启：系统设置 → 应用 → Zalo 汉化 → 通知）");
        }
        // API 地址非 https：密钥明文传输，警告但不强制拦截。
        if (cfg.baseUrl != null && cfg.baseUrl.length() > 0
                && !cfg.baseUrl.startsWith("https://")) {
            sb.append("\n⚠ API 地址不是 https，密钥将明文传输，建议改成 https 地址。");
        }
        if (cfg.histBaseUrl != null && cfg.histBaseUrl.length() > 0
                && !cfg.histBaseUrl.startsWith("https://")) {
            sb.append("\n⚠ 历史翻译 API 地址不是 https，密钥将明文传输。");
        }
        tvStatus.setText(sb.toString());
        if (btnNotif != null) {
            btnNotif.setVisibility(notifOn ? View.GONE : View.VISIBLE);
        }
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
