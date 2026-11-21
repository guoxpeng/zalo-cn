package com.zalocn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
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
    private CheckBox cbEnabled;
    private CheckBox cbUiLocalize;
    private Spinner spTargetLang;
    private Spinner spOutgoingLang;
    private Spinner spProfile;
    private Button btnSaveProfile;
    private Button btnRenameProfile;
    private Button btnDeleteProfile;
    private EditText etCustomPrompt;
    private CheckBox cbInputKeepOriginal;
    private TextView tvStatus;
    private Button btnTestApi;
    private TranslateConfig lastCfg;

    /** 大模型模板：下拉框显示的模板就是当前生效的大模型，各存各的 API 数据。 */
    private List<ModelProfile> profiles;
    private int activeProfileIdx;
    /** 刷新模板下拉框时屏蔽 onItemSelected，避免 setAdapter/setSelection 误触发切换。 */
    private boolean updatingProfileSpinner;

    /** 文字框“每次修改自动保存”：输入停 500ms 后静默保存一次。 */
    private final Handler autoSaveHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoSaveRunnable = new Runnable() {
        @Override
        public void run() {
            saveQuietly();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#F5F5F5"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView tvPageTitle = new TextView(this);
        tvPageTitle.setText("Zalo中文增强模块 · 翻译设置");
        tvPageTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tvPageTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvPageTitle.setTextColor(Color.parseColor("#222222"));
        tvPageTitle.setPadding(0, 0, 0, dp(4));
        root.addView(tvPageTitle);
        // 版面设计思路（一行说明，放在所有板块前面）。
        addHint(root, "看是收到的消息和 Zalo 系统汉化，说是发出去的消息；下面是引擎、提示词、调试。");
        addHint(root, "翻译走你自己填的大模型 API，密钥只发往你填的地址；收到的消息与上下文会发到该地址。");

        // 看：收到的消息翻成自己能看懂的语言；Zalo 系统汉化（功能界面）也属于看。
        LinearLayout cardSee = newCard(root);
        addBigTitle(cardSee, "看");
        addHint(cardSee, "将您看到的信息翻译为需要的语言。");
        addSubLabel(cardSee, "默认译文语言");
        spTargetLang = new Spinner(this);
        String[] targetNames = new String[TranslateConfig.TARGET_LANGS.length];
        for (int i = 0; i < targetNames.length; i++) {
            targetNames[i] = TranslateConfig.TARGET_LANGS[i][1];
        }
        ArrayAdapter<String> targetAdapter = dropdownAdapter(targetNames);
        spTargetLang.setAdapter(targetAdapter);
        cardSee.addView(borderedWrap(spTargetLang), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addHint(cardSee, "新消息立即按新语言翻；已显示的旧消息重进会话后重翻。");
        cbEnabled = addCheck(cardSee, "信息自动翻译");
        addHint(cardSee, "打开后，别人发来的消息会在原文下方多一行译文。");
        cbUiLocalize = addCheck(cardSee, "Zalo系统汉化");
        addHint(cardSee, "打开后，Zalo 的界面文字显示中文；关掉恢复越文原样。改完要强停 Zalo 再重开才生效；消息翻译不受影响。");

        // 说：把自己要说的话翻成对方的语言。
        LinearLayout cardSay = newCard(root);
        addBigTitle(cardSay, "说");
        addHint(cardSay, "将您发送的内容翻译为其他语言（输入框触发）。");
        addSubLabel(cardSay, "“译”按钮默认翻译");
        spOutgoingLang = new Spinner(this);
        spOutgoingLang.setAdapter(targetAdapter);
        cardSay.addView(borderedWrap(spOutgoingLang), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addHint(cardSay, "输入框点“译”，把中文翻成这里选的语言；只影响“译”按钮。");
        cbInputKeepOriginal = addCheck(cardSay, "保留原文发送");
        addHint(cardSay, "勾选：点“译”后输入框里是中文一行 + 译文一行，发送时两行一起发；不勾只填译文。");

        LinearLayout cardEngine = newCard(root);
        addTitle(cardEngine, "翻译引擎");
        addHint(cardEngine, "大模型模板：每个模板独立保存地址、密钥和模型；提示词所有模板共用");
        spProfile = new Spinner(this);
        cardEngine.addView(borderedWrap(spProfile), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout rowProfileBtns = new LinearLayout(this);
        rowProfileBtns.setOrientation(LinearLayout.HORIZONTAL);
        btnSaveProfile = new Button(this);
        btnSaveProfile.setText("保存当前模板");
        styleSecondary(btnSaveProfile);
        btnSaveProfile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveCurrentProfile();
            }
        });
        btnRenameProfile = new Button(this);
        btnRenameProfile.setText("改名");
        styleSecondary(btnRenameProfile);
        btnRenameProfile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRenameProfileDialog();
            }
        });
        btnDeleteProfile = new Button(this);
        btnDeleteProfile.setText("删除当前模板");
        styleSecondary(btnDeleteProfile);
        btnDeleteProfile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteCurrentProfile();
            }
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half.rightMargin = dp(8);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half2.rightMargin = dp(8);
        rowProfileBtns.addView(btnSaveProfile, half);
        rowProfileBtns.addView(btnDeleteProfile, half2);
        rowProfileBtns.addView(btnRenameProfile, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cardEngine.addView(rowProfileBtns, topMarginLp(10));
        etBaseUrl = addField(cardEngine, "API 地址", "例如 https://api.openai.com/v1");
        etApiKey = addField(cardEngine, "API 密钥", "sk-…（免密钥的公开 API 可空着）");
        etApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etModel = addField(cardEngine, "模型", "例如 gpt-4o-mini");
        addHint(cardEngine, "自定义大模型 API（OpenAI 兼容接口）。免费引擎不需要填这些，直接就能用。");

        LinearLayout cardPrompt = newCard(root);
        addTitle(cardPrompt, "翻译提示词");
        etCustomPrompt = new EditText(this);
        etCustomPrompt.setMinLines(4);
        etCustomPrompt.setGravity(Gravity.TOP);
        etCustomPrompt.setHint("可选。地名对照写在一起，如：老街-Lào Cai，河内-Hà Nội；也可写要求，如：地名按越南官方译名，不要直译");
        styleField(etCustomPrompt);
        cardPrompt.addView(etCustomPrompt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addHint(cardPrompt, "所有大模型模板共用一个提示词；免费引擎（Google/Bing/MyMemory）不用提示词。");

        LinearLayout cardDebug = newCard(root);
        addTitle(cardDebug, "调试");
        // 状态文字放进浅灰底的状态框里，和按钮、说明分开，一眼能看清。
        tvStatus = new TextView(this);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvStatus.setTextColor(Color.parseColor("#333333"));
        cardDebug.addView(statusBox(tvStatus), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        btnTestApi = new Button(this);
        btnTestApi.setText("测试当前 API");
        stylePrimary(btnTestApi);
        btnTestApi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    testApi();
                } catch (Throwable t) {
                    // 点下按钮本身就出问题：只弹提示、恢复按钮，绝不退出页面
                    Log.e("ZALO-ZH", "test button click fail", t);
                    try {
                        btnTestApi.setEnabled(true);
                        btnTestApi.setText("测试当前 API");
                        Toast.makeText(TranslateSettingsActivity.this,
                                "测试失败：" + t, Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
        cardDebug.addView(btnTestApi, topMarginLp(10));
        addHint(cardDebug, "测试当前配置是否正常，能不能用。");

        load();
        attachAutoSave();
    }

    /** 当前模板是否是免费网页翻译引擎（不用填 API 地址/密钥/模型）。 */
    private boolean activeProfileIsFree() {
        return profiles != null && activeProfileIdx >= 0 && activeProfileIdx < profiles.size()
                && profiles.get(activeProfileIdx).engine != null
                && !"ai".equals(profiles.get(activeProfileIdx).engine);
    }

    /** 免费引擎时禁用 API 输入框（不需要填），大模型模板时启用。提示词全局共用，始终可填。 */
    private void refreshEngineFields() {
        boolean free = activeProfileIsFree();
        etBaseUrl.setEnabled(!free);
        etApiKey.setEnabled(!free);
        etModel.setEnabled(!free);
    }

    /** 把一个模板的 API 数据填进输入框（提示词不跟模板走，切换时不动）。 */
    private void fillApiFields(ModelProfile p) {
        etBaseUrl.setText(p.baseUrl);
        etApiKey.setText(p.apiKey);
        etModel.setText(p.model);
    }

    /** 切换模板：先把当前界面上的值存回旧模板，再换新模板并立即生效。 */
    private void switchProfile(int pos) {
        try {
            switchProfileInner(pos);
        } catch (Throwable t) {
            Log.e("ZALO-ZH", "switchProfile fail", t);
        }
    }

    private void switchProfileInner(int pos) {
        if (profiles == null || pos < 0 || pos >= profiles.size() || pos == activeProfileIdx) {
            return;
        }
        autoSaveHandler.removeCallbacks(autoSaveRunnable); // 挂起的防抖保存作废，避免切模板后多写一次
        saveQuietly(); // 当前值先存回旧模板
        activeProfileIdx = pos;
        ModelProfile.saveActiveName(this, profiles.get(pos).name);
        fillApiFields(profiles.get(pos));
        refreshEngineFields();
        refreshProfileButtons();
        saveQuietly(); // 新模板的值写进扁平配置，Zalo 侧立即按新引擎翻
        Toast.makeText(this, "已切换到“" + profiles.get(pos).name + "”",
                Toast.LENGTH_SHORT).show();
    }

    /** 下拉菜单适配器：三个下拉菜单共用。弹出的选项加宽（左右 16dp、上下 12dp 内边距，
     * 16sp 字）+ 淡灰底色（#F5F5F5），比系统默认的白底窄选项好点、不容易误触。
     * 收起来的选中项不变（simple_spinner_item）。 */
    private ArrayAdapter<String> dropdownAdapter(final String[] items) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView tv = new TextView(TranslateSettingsActivity.this);
                tv.setText(getItem(position));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                tv.setTextColor(Color.parseColor("#222222"));
                tv.setPadding(dp(16), dp(12), dp(16), dp(12));
                tv.setBackgroundColor(Color.parseColor("#F5F5F5"));
                return tv;
            }
        };
    }

    /** 重建模板下拉框（新建/删除模板后调）；刷新期间屏蔽 onItemSelected。 */
    private void refreshProfileSpinner() {
        updatingProfileSpinner = true;
        String[] names = new String[profiles.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = profiles.get(i).name;
        }
        ArrayAdapter<String> adapter = dropdownAdapter(names);
        spProfile.setAdapter(adapter);
        spProfile.setSelection(activeProfileIdx);
        updatingProfileSpinner = false;
    }

    /** 只有用户新建的模板能删、能改名；系统预设隐藏这两个按钮。 */
    private void refreshProfileButtons() {
        boolean custom = profiles != null && activeProfileIdx >= 0
                && activeProfileIdx < profiles.size()
                && !profiles.get(activeProfileIdx).builtin;
        btnRenameProfile.setVisibility(custom ? View.VISIBLE : View.GONE);
        btnDeleteProfile.setVisibility(custom ? View.VISIBLE : View.GONE);
    }

    /** 保存当前模板：把界面上当前填的 API 数据存进正在用的模板（改完本来也会
     * 自动保存，这个按钮是手动确认一次，并弹个提示）。 */
    private void saveCurrentProfile() {
        try {
            saveQuietly();
            String name = (profiles != null && activeProfileIdx >= 0
                    && activeProfileIdx < profiles.size())
                    ? profiles.get(activeProfileIdx).name : "";
            Toast.makeText(this, "已保存当前模板" + (name.length() > 0 ? "“" + name + "”" : ""),
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.e("ZALO-ZH", "saveCurrentProfile fail", t);
        }
    }

    /** 改名：只有自定义模板能改名；系统预设改不了。 */
    private void showRenameProfileDialog() {
        if (profiles == null || profiles.isEmpty()) {
            return;
        }
        final ModelProfile p = profiles.get(activeProfileIdx);
        if (p.builtin) {
            Toast.makeText(this, "系统预设不能改名", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText etName = new EditText(this);
        etName.setText(p.name);
        etName.setSelection(p.name.length());
        new AlertDialog.Builder(this)
                .setTitle("改模板名字")
                .setView(etName)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        renameCurrentProfile(etName.getText().toString().trim());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void renameCurrentProfile(String name) {
        if (name.length() == 0) {
            Toast.makeText(this, "先填个名字", Toast.LENGTH_SHORT).show();
            return;
        }
        ModelProfile p = profiles.get(activeProfileIdx);
        if (!name.equals(p.name) && ModelProfile.indexOfName(profiles, name) >= 0) {
            Toast.makeText(this, "这个名字已经有了", Toast.LENGTH_SHORT).show();
            return;
        }
        p.name = name;
        ModelProfile.save(this, profiles);
        ModelProfile.saveActiveName(this, name);
        refreshProfileSpinner();
        refreshProfileButtons();
        Toast.makeText(this, "已改名“" + name + "”", Toast.LENGTH_SHORT).show();
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
        refreshProfileButtons();
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
            } else {
                profiles.add(new ModelProfile("我的配置", cfg.baseUrl, cfg.apiKey,
                        cfg.model, "ai", false));
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
        // 提示词全局共用：从扁平配置读，切换模板时不动。
        etCustomPrompt.setText(cfg.customPrompt);
        cbEnabled.setChecked(cfg.enabled);
        cbUiLocalize.setChecked(cfg.uiLocalize);
        spTargetLang.setSelection(indexOfTargetLang(cfg.incomingTargetLang));
        spOutgoingLang.setSelection(indexOfTargetLang(cfg.outgoingTargetLang));
        cbInputKeepOriginal.setChecked(cfg.inputKeepOriginal);
        lastCfg = cfg;
        refreshProfileSpinner();
        refreshProfileButtons();
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
        cbUiLocalize.setOnCheckedChangeListener(autoSaveCheck);
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
        spOutgoingLang.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
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
        // 文字框“每次修改自动保存”：输入停 500ms 后静默保存一次（失焦保存仍保留）。
        // 监听挂在 load() 之后，load 时的 setText 不会误触发。
        TextWatcher autoSaveWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                scheduleAutoSave();
            }
        };
        etBaseUrl.addTextChangedListener(autoSaveWatcher);
        etApiKey.addTextChangedListener(autoSaveWatcher);
        etModel.addTextChangedListener(autoSaveWatcher);
        etCustomPrompt.addTextChangedListener(autoSaveWatcher);
    }

    /** 安排一次防抖自动保存：500ms 内再有输入就顺延，避免每敲一字写一次磁盘。 */
    private void scheduleAutoSave() {
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        autoSaveHandler.postDelayed(autoSaveRunnable, 500);
    }

    /** 静默保存：写配置 + 刷新状态，不弹 Toast（供勾选/失焦自动保存用）。
     * 绝不抛异常：自动保存由失焦/输入/切换等多处触发，任何一步出问题都只记
     * 日志，不能把设置页弄崩（调试按钮点一下就退出页面，疑似就是某次自动
     * 保存抛了未捕获异常）。设置页进程里 XposedBridge.log 是空实现，
     * 所以这里用 android.util.Log，崩溃栈才能进 logcat。 */
    private void saveQuietly() {
        try {
            saveInternal();
        } catch (Throwable t) {
            Log.e("ZALO-ZH", "saveQuietly fail", t);
        }
    }

    /**
     * “测试当前 API”：用界面上当前填的值，后台调一次翻译。
     * 成功显示“当前api配置能正常使用”，失败直接显示原因（连接失败 / API 地址有误 / 找不到模型 / API 错误）。
     * 全程 try/catch：测试绝不能把设置页弄崩——点一下就退出页面是 bug，
     * 无论哪一步抛异常都只弹提示、恢复按钮，不退出。
     */
    private void testApi() {
        try {
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
                Toast.makeText(this, "请先填 API 地址和模型",
                        Toast.LENGTH_LONG).show();
                return;
            }
            btnTestApi.setEnabled(false);
            btnTestApi.setText("测试中…");
            Log.d("ZALO-ZH", "testApi thread start, engine=" + cfg.engine);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String out = null;
                    try {
                        out = LlmClient.translate(
                                cfg, "你好，请问这个多少钱？", "zh", "vi", null);
                    } catch (Throwable t) {
                        // LlmClient 本身不抛异常，这里只是兜底
                        Log.e("ZALO-ZH", "testApi translate fail", t);
                    }
                    final String fOut = out;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Log.d("ZALO-ZH", "testApi result out="
                                        + (fOut != null ? "ok" : "null")
                                        + " lastError=" + LlmClient.lastError);
                                btnTestApi.setEnabled(true);
                                btnTestApi.setText("测试当前 API");
                                if (fOut != null) {
                                    Toast.makeText(TranslateSettingsActivity.this,
                                            "当前api配置能正常使用", Toast.LENGTH_LONG).show();
                                } else {
                                    // 失败直接报原因（连接失败 / API 地址有误 / 找不到模型 / API 错误），
                                    // 别一律说“配置有误”。
                                    String reason = LlmClient.lastError;
                                    Toast.makeText(TranslateSettingsActivity.this,
                                            reason != null ? reason
                                                    : "请检查 API 地址、密钥、模型与网络",
                                            Toast.LENGTH_LONG).show();
                                }
                            } catch (Throwable t) {
                                // 回 UI 线程还出问题：只恢复按钮，绝不崩页面
                                try {
                                    btnTestApi.setEnabled(true);
                                    btnTestApi.setText("测试当前 API");
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    });
                }
            }).start();
        } catch (Throwable t) {
            // 点下按钮的瞬间就出问题：恢复按钮并直接报原因，绝不退出页面
            try {
                btnTestApi.setEnabled(true);
                btnTestApi.setText("测试当前 API");
            } catch (Throwable ignored) {
            }
            try {
                Toast.makeText(this, "测试失败：" + t, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
            Log.e("ZALO-ZH", "testApi fail", t);
        }
    }

    private void saveInternal() {
        TranslateConfig cfg = new TranslateConfig();
        cfg.baseUrl = etBaseUrl.getText().toString().trim();
        cfg.apiKey = etApiKey.getText().toString().trim();
        cfg.model = etModel.getText().toString().trim();
        if (cfg.model.length() == 0) {
            cfg.model = TranslateConfig.DEFAULT_MODEL;
        }
        cfg.enabled = cbEnabled.isChecked();
        cfg.uiLocalize = cbUiLocalize.isChecked();
        int targetPos = spTargetLang.getSelectedItemPosition();
        if (targetPos >= 0 && targetPos < TranslateConfig.TARGET_LANGS.length) {
            cfg.incomingTargetLang = TranslateConfig.TARGET_LANGS[targetPos][0];
        }
        int outgoingPos = spOutgoingLang.getSelectedItemPosition();
        if (outgoingPos >= 0 && outgoingPos < TranslateConfig.TARGET_LANGS.length) {
            cfg.outgoingTargetLang = TranslateConfig.TARGET_LANGS[outgoingPos][0];
        }
        cfg.customPrompt = etCustomPrompt.getText().toString().trim();
        cfg.inputKeepOriginal = cbInputKeepOriginal.isChecked();
        // 引擎随当前模板走；同时把界面上的值存回当前模板（各模板独立保存自己的 API 数据）。
        if (profiles != null && activeProfileIdx >= 0 && activeProfileIdx < profiles.size()) {
            ModelProfile p = profiles.get(activeProfileIdx);
            cfg.engine = p.engine;
            p.baseUrl = cfg.baseUrl;
            p.apiKey = cfg.apiKey;
            p.model = cfg.model;
            ModelProfile.save(this, profiles);
        }
        cfg.saveForSettings(this);
        lastCfg = cfg;
        refreshStatus(cfg);
    }

    /** 状态栏：当前引擎/翻译状态。绝不抛异常（由自动保存多处调用）。 */
    private void refreshStatus(TranslateConfig cfg) {
        try {
            refreshStatusInner(cfg);
        } catch (Throwable t) {
            Log.e("ZALO-ZH", "refreshStatus fail", t);
        }
    }

    private void refreshStatusInner(TranslateConfig cfg) {
        StringBuilder sb = new StringBuilder();
        String engineName = (profiles != null && activeProfileIdx >= 0
                && activeProfileIdx < profiles.size())
                ? profiles.get(activeProfileIdx).name : null;
        if (cfg.engine != null && !"ai".equals(cfg.engine)) {
            sb.append("状态：当前引擎 ").append(engineName != null ? engineName : cfg.engine)
              .append("（免费，不用配置），")
              .append(cfg.enabled ? "收到的自动翻成" + TranslateConfig.targetLangName(cfg.incomingTargetLang) + "；" : "收到的不自动翻；")
              .append("输入框点“译”，翻译为默认语言。");
        } else if (!cfg.hasApi()) {
            sb.append("状态：未填 API 地址或密钥，翻译功能暂不可用。");
        } else {
            sb.append("状态：已配置 ").append(cfg.model).append("，")
              .append(cfg.enabled ? "收到的自动翻成" + TranslateConfig.targetLangName(cfg.incomingTargetLang) + "；" : "收到的不自动翻；")
              .append("输入框点“译”，翻译为默认语言。");
        }
        // API 地址非 https：密钥明文传输，警告但不强制拦截。
        if (cfg.baseUrl != null && cfg.baseUrl.length() > 0
                && !cfg.baseUrl.startsWith("https://")) {
            sb.append("\n⚠ API 地址不是 https，密钥将明文传输，建议改成 https 地址。");
        }
        sb.append("\nZalo系统汉化").append(cfg.uiLocalize ? "开。" : "关。");
        tvStatus.setText(sb.toString());
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /** 分区卡片：白底 + 浅灰边框 + 圆角，替代原来光秃秃的平铺。 */
    private LinearLayout newCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setStroke(dp(1), Color.parseColor("#E0E0E0"));
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        root.addView(card, lp);
        return card;
    }

    /** 下拉框外框：白底 + 浅灰边框 + 圆角，让下拉菜单在卡片里一眼能看到。
     * 只包外框，下拉框本身保持默认样式（小箭头还在）。 */
    private LinearLayout borderedWrap(View child) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setStroke(dp(1), Color.parseColor("#CCCCCC"));
        bg.setCornerRadius(dp(8));
        wrap.setBackground(bg);
        wrap.setPadding(dp(4), dp(2), dp(4), dp(2));
        wrap.addView(child, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** 输入框统一样式：白底 + 浅灰边框 + 圆角，文字 16sp（和下拉菜单、卡片标题一致；
     * 之前没指定字号，提示词框的文字比页面其他地方小，看着不匹配）。 */
    private void styleField(EditText et) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setStroke(dp(1), Color.parseColor("#CCCCCC"));
        bg.setCornerRadius(dp(8));
        et.setBackground(bg);
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
    }

    /** 主按钮：Zalo 蓝底白字 + 圆角（测试当前 API）。
     * 默认 Button 有 48dp 最小高度，太高；去掉最小高度、收窄上下内边距。 */
    private void stylePrimary(Button b) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#0068FF"));
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
    }

    /** 次按钮：白底 + 灰边框 + 圆角（保存当前模板、改名、删除）。
     * 默认 Button 有 48dp 最小高度，比上面的下拉菜单高出一截、还贴得太近容易误触；
     * 这里去掉最小高度、收窄上下内边距，让三个按钮和下拉菜单一样高。 */
    private void styleSecondary(Button b) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setStroke(dp(1), Color.parseColor("#CCCCCC"));
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        b.setTextColor(Color.parseColor("#333333"));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(8), dp(6), dp(8), dp(6));
    }

    /** 状态框：浅灰底 + 浅灰边框 + 圆角。调试卡里的状态文字放这里，和按钮分开。 */
    private LinearLayout statusBox(View child) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F5F5F5"));
        bg.setStroke(dp(1), Color.parseColor("#CCCCCC"));
        bg.setCornerRadius(dp(8));
        wrap.setBackground(bg);
        wrap.setPadding(dp(12), dp(10), dp(12), dp(10));
        wrap.addView(child, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** MATCH_PARENT + WRAP_CONTENT，带上边距（下拉菜单和下面一排按钮之间留空防误触）。 */
    private LinearLayout.LayoutParams topMarginLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private void addTitle(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.parseColor("#0068FF"));
        tv.setPadding(0, dp(8), 0, dp(4));
        root.addView(tv);
    }

    /** 卡片内的小节标题（语言卡里“默认译文语言”“‘译’按钮默认翻译”两节用）。 */
    private void addSubLabel(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.parseColor("#333333"));
        tv.setPadding(0, dp(8), 0, dp(2));
        root.addView(tv);
    }

    /** 大号卡片标题（看/说两张卡用，20sp，比其他卡片标题大一号）。 */
    private void addBigTitle(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.parseColor("#0068FF"));
        tv.setPadding(0, dp(8), 0, dp(4));
        root.addView(tv);
    }

    private void addHint(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(Color.parseColor("#757575"));
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
        styleField(et);
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
