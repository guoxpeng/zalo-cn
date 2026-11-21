package com.zalocn;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
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
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;


/**
 * 翻译功能设置界面（模块 APK 自带的启动器入口）。
 *
 * 纯代码写 UI，不依赖 XML 布局资源；图标走 res/mipmap（构建脚本用 aapt2 打包）。
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
    private Spinner spTargetLang;
    private Spinner spProfile;
    private Button btnNewProfile;
    private Button btnDeleteProfile;
    private EditText etCustomPrompt;
    private CheckBox cbInputKeepOriginal;
    private TextView tvStatus;
    private Button btnNotif;
    private Button btnTestApi;
    private TranslateConfig lastCfg;

    /** 大模型模板：下拉框显示的模板就是当前生效的大模型，各存各的 API 数据。 */
    private List<ModelProfile> profiles;
    private int activeProfileIdx;
    /** 刷新模板下拉框时屏蔽 onItemSelected，避免 setAdapter/setSelection 误触发切换。 */
    private boolean updatingProfileSpinner;

    /** 通知权限请求码（安卓 13+ 提醒通知要运行时申请）。 */
    private static final int REQ_POST_NOTIFICATIONS = 1001;

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
        addHint(root, "翻译走你自己填的大模型 API，密钥只发往你填的地址；收到的消息与上下文会发到该地址。");

        addTitle(root, "翻译开关");
        cbEnabled = addCheck(root, "启用消息翻译");
        cbAutoIncoming = addCheck(root, "收到外语消息，自动翻译");
        addHint(root, "打开后，别人发来的消息会在原文下方多一行译文。");

        addTitle(root, "翻译引擎");
        addHint(root, "大模型模板：每个模板独立保存地址、密钥、模型和提示词");
        spProfile = new Spinner(this);
        root.addView(spProfile, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout rowProfileBtns = new LinearLayout(this);
        rowProfileBtns.setOrientation(LinearLayout.HORIZONTAL);
        btnNewProfile = new Button(this);
        btnNewProfile.setText("新建模板");
        btnNewProfile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNewProfileDialog();
            }
        });
        btnDeleteProfile = new Button(this);
        btnDeleteProfile.setText("删除当前模板");
        btnDeleteProfile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteCurrentProfile();
            }
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half.rightMargin = dp(8);
        rowProfileBtns.addView(btnNewProfile, half);
        rowProfileBtns.addView(btnDeleteProfile, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(rowProfileBtns, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        etBaseUrl = addField(root, "API 地址", "例如 https://api.openai.com/v1");
        etApiKey = addField(root, "API 密钥", "sk-…（免密钥的公开 API 可空着）");
        etApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etModel = addField(root, "模型", "例如 gpt-4o-mini");
        addHint(root, "自定义大模型 API（OpenAI 兼容接口）。免费引擎不需要填这些，直接就能用。");
        etCustomPrompt = new EditText(this);
        etCustomPrompt.setMinLines(2);
        etCustomPrompt.setGravity(Gravity.TOP);
        etCustomPrompt.setHint("翻译提示词（可选）：例如地名用越南官方译名，不要直译");
        root.addView(etCustomPrompt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addTitle(root, "译文语言");
        addHint(root, "把收到的消息翻译成");
        spTargetLang = new Spinner(this);
        String[] targetNames = new String[TranslateConfig.TARGET_LANGS.length];
        for (int i = 0; i < targetNames.length; i++) {
            targetNames[i] = TranslateConfig.TARGET_LANGS[i][1];
        }
        ArrayAdapter<String> targetAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, targetNames);
        targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spTargetLang.setAdapter(targetAdapter);
        root.addView(spTargetLang, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addHint(root, "新消息立即按新语言翻；已显示的旧消息重进会话后重翻。");

        addTitle(root, "输入框“译”按钮");
        cbInputKeepOriginal = addCheck(root, "同时保留中文原文");
        addHint(root, "勾选：点“译”后输入框里是中文一行 + 越南语一行；不勾只填越南语。填好后自己检查再点发送。");

        addTitle(root, "历史记录翻译（可选）");
        etHistBaseUrl = addField(root, "历史翻译 API 地址（空=用上面）", "");
        etHistApiKey = addField(root, "历史翻译 API 密钥（空=用上面）", "");
        etHistApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etHistModel = addField(root, "历史翻译模型（空=用上面）", "");
        addHint(root, "某一项空着就用上面对应的项，三项都空=完全用主配置。");

        addTitle(root, "待办提醒");
        addHint(root, "提醒到点弹窗，需要通知权限；重启手机后首次打开 Zalo 会自动重设未过期的提醒。");

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

        addTitle(root, "调试");
        btnTestApi = new Button(this);
        btnTestApi.setText("测试当前 API");
        btnTestApi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testApi();
            }
        });
        root.addView(btnTestApi, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addHint(root, "点一下会用当前 API 翻一句测试文本，成功显示译文，失败显示原因。");

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
        attachAutoSave();

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

    /** 当前模板是否是免费网页翻译引擎（不用填 API 地址/密钥/模型）。 */
    private boolean activeProfileIsFree() {
        return profiles != null && activeProfileIdx >= 0 && activeProfileIdx < profiles.size()
                && profiles.get(activeProfileIdx).engine != null
                && !"ai".equals(profiles.get(activeProfileIdx).engine);
    }

    /** 免费引擎时禁用 API 输入框（不需要填），大模型模板时启用。 */
    private void refreshEngineFields() {
        boolean free = activeProfileIsFree();
        etBaseUrl.setEnabled(!free);
        etApiKey.setEnabled(!free);
        etModel.setEnabled(!free);
        etCustomPrompt.setEnabled(!free);
    }

    /** 把一个模板的 API 数据填进输入框。 */
    private void fillApiFields(ModelProfile p) {
        etBaseUrl.setText(p.baseUrl);
        etApiKey.setText(p.apiKey);
        etModel.setText(p.model);
        etCustomPrompt.setText(p.prompt);
    }

    /** 切换模板：先把当前界面上的值存回旧模板，再换新模板并立即生效。 */
    private void switchProfile(int pos) {
        if (profiles == null || pos < 0 || pos >= profiles.size() || pos == activeProfileIdx) {
            return;
        }
        saveQuietly(); // 当前值先存回旧模板
        activeProfileIdx = pos;
        ModelProfile.saveActiveName(this, profiles.get(pos).name);
        fillApiFields(profiles.get(pos));
        refreshEngineFields();
        refreshDeleteButton();
        saveQuietly(); // 新模板的值写进扁平配置，Zalo 侧立即按新引擎翻
        Toast.makeText(this, "已切换到“" + profiles.get(pos).name + "”",
                Toast.LENGTH_SHORT).show();
    }

    /** 重建模板下拉框（新建/删除模板后调）；刷新期间屏蔽 onItemSelected。 */
    private void refreshProfileSpinner() {
        updatingProfileSpinner = true;
        String[] names = new String[profiles.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = profiles.get(i).name;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spProfile.setAdapter(adapter);
        spProfile.setSelection(activeProfileIdx);
        updatingProfileSpinner = false;
    }

    /** 只有用户新建的模板能删；系统预设隐藏删除按钮。 */
    private void refreshDeleteButton() {
        boolean custom = profiles != null && activeProfileIdx >= 0
                && activeProfileIdx < profiles.size()
                && !profiles.get(activeProfileIdx).builtin;
        btnDeleteProfile.setVisibility(custom ? View.VISIBLE : View.GONE);
    }

    /** 新建模板：弹框输名字，用当前界面上的 API 数据另存一个模板。 */
    private void showNewProfileDialog() {
        final EditText etName = new EditText(this);
        etName.setHint("例如：我的 DeepSeek");
        new AlertDialog.Builder(this)
                .setTitle("新建模板")
                .setView(etName)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        createProfile(etName.getText().toString().trim());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void createProfile(String name) {
        if (name.length() == 0) {
            Toast.makeText(this, "先填个名字", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ModelProfile.indexOfName(profiles, name) >= 0) {
            Toast.makeText(this, "这个名字已经有了", Toast.LENGTH_SHORT).show();
            return;
        }
        saveQuietly(); // 当前值先存回旧模板
        ModelProfile p = new ModelProfile(name,
                etBaseUrl.getText().toString().trim(),
                etApiKey.getText().toString().trim(),
                etModel.getText().toString().trim(),
                etCustomPrompt.getText().toString().trim(), "ai", false);
        profiles.add(p);
        ModelProfile.save(this, profiles);
        activeProfileIdx = profiles.size() - 1;
        ModelProfile.saveActiveName(this, name);
        refreshProfileSpinner();
        refreshDeleteButton();
        refreshEngineFields();
        saveQuietly();
        Toast.makeText(this, "模板“" + name + "”已保存", Toast.LENGTH_SHORT).show();
    }

    private void deleteCurrentProfile() {
        if (profiles == null || profiles.isEmpty()) {
            return;
        }
        ModelProfile p = profiles.get(activeProfileIdx);
        if (p.builtin) {
            Toast.makeText(this, "系统预设不能删", Toast.LENGTH_SHORT).show();
            return;
        }
        profiles.remove(activeProfileIdx);
        ModelProfile.save(this, profiles);
        activeProfileIdx = 0;
        ModelProfile.saveActiveName(this, profiles.get(0).name);
        fillApiFields(profiles.get(0));
        refreshProfileSpinner();
        refreshDeleteButton();
        refreshEngineFields();
        saveQuietly();
        Toast.makeText(this, "已删除，切回“" + profiles.get(0).name + "”",
                Toast.LENGTH_SHORT).show();
    }

    /** 目标语代码 -> 下拉框位置，找不到默认 0（中文）。 */
    private static int indexOfTargetLang(String code) {
        if (code != null) {
            for (int i = 0; i < TranslateConfig.TARGET_LANGS.length; i++) {
                if (TranslateConfig.TARGET_LANGS[i][0].equals(code)) {
                    return i;
                }
            }
        }
        return 0;
    }

    private void load() {
        TranslateConfig cfg = TranslateConfig.loadForSettings(this);
        profiles = ModelProfile.load(this);
        if (profiles.isEmpty()) {
            // 首次升级：建系统预设，并把当前已保存的扁平配置认领进去。
            profiles = ModelProfile.defaults();
            int match = ModelProfile.indexOfValue(profiles, cfg.baseUrl, cfg.model);
            if (match >= 0 && "ai".equals(profiles.get(match).engine)) {
                activeProfileIdx = match;
                ModelProfile p = profiles.get(match);
                p.apiKey = cfg.apiKey;
                p.prompt = cfg.customPrompt;
            } else {
                profiles.add(new ModelProfile("我的配置", cfg.baseUrl, cfg.apiKey,
                        cfg.model, cfg.customPrompt, "ai", false));
                activeProfileIdx = profiles.size() - 1;
            }
            ModelProfile.save(this, profiles);
            ModelProfile.saveActiveName(this, profiles.get(activeProfileIdx).name);
        } else {
            activeProfileIdx = ModelProfile.indexOfName(profiles,
                    ModelProfile.loadActiveName(this));
            if (activeProfileIdx < 0) {
                activeProfileIdx = 0;
            }
        }
        fillApiFields(profiles.get(activeProfileIdx));
        etHistBaseUrl.setText(cfg.histBaseUrl);
        etHistApiKey.setText(cfg.histApiKey);
        etHistModel.setText(cfg.histModel);
        cbEnabled.setChecked(cfg.enabled);
        cbAutoIncoming.setChecked(cfg.autoIncoming);
        spTargetLang.setSelection(indexOfTargetLang(cfg.incomingTargetLang));
        cbInputKeepOriginal.setChecked(cfg.inputKeepOriginal);
        // 首次打开设置页即生成提醒校验 token（之后 ReminderReceiver 强制校验）。
        cfg.reminderToken = TranslateConfig.ensureReminderToken(this);
        lastCfg = cfg;
        refreshProfileSpinner();
        refreshDeleteButton();
        refreshEngineFields();
        refreshStatus(cfg);
        saveQuietly(); // 把当前模板的值同步写进扁平配置，保证 hook 侧读到的是当前模板
    }

    /**
     * 勾选/下拉改完立即保存（不用再翻到底点保存；文字框失焦时也保存）。
     * 必须在 load() 之后挂，否则 load() 里的 setChecked/setSelection
     * 会触发监听，把还没填好的值写回去。
     */
    private void attachAutoSave() {
        android.widget.CompoundButton.OnCheckedChangeListener autoSaveCheck =
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                        saveQuietly();
                    }
                };
        cbEnabled.setOnCheckedChangeListener(autoSaveCheck);
        cbAutoIncoming.setOnCheckedChangeListener(autoSaveCheck);
        cbInputKeepOriginal.setOnCheckedChangeListener(autoSaveCheck);
        spTargetLang.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                saveQuietly();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });
        spProfile.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (!updatingProfileSpinner) {
                    switchProfile(pos);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });
        View.OnFocusChangeListener autoSaveText = new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    saveQuietly();
                }
            }
        };
        etBaseUrl.setOnFocusChangeListener(autoSaveText);
        etApiKey.setOnFocusChangeListener(autoSaveText);
        etModel.setOnFocusChangeListener(autoSaveText);
        etCustomPrompt.setOnFocusChangeListener(autoSaveText);
        etHistBaseUrl.setOnFocusChangeListener(autoSaveText);
        etHistApiKey.setOnFocusChangeListener(autoSaveText);
        etHistModel.setOnFocusChangeListener(autoSaveText);
    }

    /** 静默保存：写配置 + 刷新状态，不弹 Toast（供勾选/失焦自动保存用）。 */
    private void saveQuietly() {
        saveInternal(true);
    }

    /** “测试当前 API”：用界面上当前填的值，后台调一次翻译，弹结果。 */
    private void testApi() {
        final TranslateConfig cfg = new TranslateConfig();
        cfg.baseUrl = etBaseUrl.getText().toString().trim();
        cfg.apiKey = etApiKey.getText().toString().trim();
        cfg.model = etModel.getText().toString().trim();
        cfg.customPrompt = etCustomPrompt.getText().toString().trim();
        // 引擎随当前模板走：免费引擎不检查 API 配置，直接测。
        if (profiles != null && activeProfileIdx >= 0 && activeProfileIdx < profiles.size()) {
            cfg.engine = profiles.get(activeProfileIdx).engine;
        }
        if ("ai".equals(cfg.engine) && !cfg.hasApi()) {
            Toast.makeText(this, "先填 API 地址和模型", Toast.LENGTH_SHORT).show();
            return;
        }
        btnTestApi.setEnabled(false);
        btnTestApi.setText("测试中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String out = LlmClient.translate(
                        cfg, "你好，请问这个多少钱？", "zh", "vi", null);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnTestApi.setEnabled(true);
                        btnTestApi.setText("测试当前 API");
                        if (out != null) {
                            Toast.makeText(TranslateSettingsActivity.this,
                                    "测试成功：" + out, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(TranslateSettingsActivity.this,
                                    "测试失败：请检查 API 地址、密钥、模型与网络",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void save() {
        saveInternal(false);
    }

    private void saveInternal(boolean quiet) {
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
        int targetPos = spTargetLang.getSelectedItemPosition();
        if (targetPos >= 0 && targetPos < TranslateConfig.TARGET_LANGS.length) {
            cfg.incomingTargetLang = TranslateConfig.TARGET_LANGS[targetPos][0];
        }
        cfg.customPrompt = etCustomPrompt.getText().toString().trim();
        cfg.inputKeepOriginal = cbInputKeepOriginal.isChecked();
        if (cfg.reminderToken == null || cfg.reminderToken.length() == 0) {
            cfg.reminderToken = TranslateConfig.ensureReminderToken(this);
        }
        // 引擎随当前模板走；同时把界面上的值存回当前模板（各模板独立保存自己的 API 数据）。
        if (profiles != null && activeProfileIdx >= 0 && activeProfileIdx < profiles.size()) {
            ModelProfile p = profiles.get(activeProfileIdx);
            cfg.engine = p.engine;
            p.baseUrl = cfg.baseUrl;
            p.apiKey = cfg.apiKey;
            p.model = cfg.model;
            p.prompt = cfg.customPrompt;
            ModelProfile.save(this, profiles);
        }
        cfg.saveForSettings(this);
        lastCfg = cfg;
        refreshStatus(cfg);
        if (quiet) {
            return;
        }
        if (cfg.baseUrl.length() > 0 && !cfg.baseUrl.startsWith("https://")) {
            Toast.makeText(this, "注意：API 地址不是 https，密钥将明文传输", Toast.LENGTH_LONG).show();
        }
        Toast.makeText(this, "已保存，Zalo 里的翻译即刻生效（无需重启）", Toast.LENGTH_LONG).show();
    }

    private void refreshStatus(TranslateConfig cfg) {
        StringBuilder sb = new StringBuilder();
        String engineName = (profiles != null && activeProfileIdx >= 0
                && activeProfileIdx < profiles.size())
                ? profiles.get(activeProfileIdx).name : null;
        if (cfg.engine != null && !"ai".equals(cfg.engine)) {
            sb.append("状态：当前引擎 ").append(engineName != null ? engineName : cfg.engine)
              .append("（免费，不用配置），")
              .append(cfg.autoIncoming ? "收到的自动翻成" + TranslateConfig.targetLangName(cfg.incomingTargetLang) + "；" : "收到的不自动翻；")
              .append("发出：输入框点“译”翻译后自己点发送。");
        } else if (!cfg.hasApi()) {
            sb.append("状态：未填 API 地址或密钥，翻译功能暂不可用。");
        } else {
            sb.append("状态：已配置 ").append(cfg.model).append("，")
              .append(cfg.autoIncoming ? "收到的自动翻成" + TranslateConfig.targetLangName(cfg.incomingTargetLang) + "；" : "收到的不自动翻；")
              .append("发出：输入框点“译”翻译后自己点发送。");
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
