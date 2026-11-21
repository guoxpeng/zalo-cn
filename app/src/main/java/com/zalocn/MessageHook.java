package com.zalocn;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 消息实时翻译 hook（第一批 6 个功能的核心）。
 *
 * 依据：~/workspace/goals/zalo-localization/hidden_files/msg-render-rev/msg-render-path.md
 * （Zalo 26.11.01 静态逆向，只读分析）。
 *
 * 关键事实（不要按直觉改）：
 * - 气泡不是 TextView，是自绘 Canvas（ChatRowText extends ChatRowHasCaption extends
 *   ChatRow extends ViewGroup），文本经 StaticLayout 直接 onDraw。TextView.setText
 *   hook 对气泡正文无效。
 * - 混淆类都在默认包（无包名）：vv3 / p7g / o7g / mm4 / glk / lkk / oik / xt3 / u56
 *   等，Class.forName 用裸名；jadx 显示的 "defpackage" 只是反编译器的合成包名。
 * - Zalo 自带原生翻译：译文以 p7g 对象挂在消息 vv3.o3 上；I2() 读 N0()，有译文时主文本
 *   显示译文、原文进抽屉。模块在 I2() 之后把 n7/w7 对调，改成原文主文 + 译文进抽屉
 *   （原文在上、译文在下）。模块注入自己的译文走同一槽位，不碰原文 vv3.f；
 *   对调只改行上的 n7/w7/x7/s7/r7，不改消息对象。
 * - 翻译是异步的：回包时必须核对行当前消息 id（row.getMessage().b），行已被复用就丢弃。
 * - 长按菜单项是公开 ArrayList（MessageContextMenuView.X1），被按消息在 V1。
 * - 输入框是 ChatView.U1（ActionEditText）；发送键在 ChatInputBar，id 为
 *   glk.new_chat_input_btn_chat_send，点发送走 k000.v(-10, "csc_chatbar")。
 *
 * 安全：所有 hook 体都有 try/catch，任何异常只记日志，绝不让 Zalo 崩溃；
 * 翻译失败 = 显示原文，不改消息内容；无 API 配置时翻译功能静默不启用。
 */
public class MessageHook {

    static final String TAG = "[ZALO-ZH]";

    /** 长按菜单自定义项 id（0x7E 段，避开 Zalo 资源 id 的 0x7F 段与原生菜单 id）。 */
    static final int MENU_SHOW_ORIGINAL = 0x7E100001;
    static final int MENU_SHOW_TRANSLATED = 0x7E100002;
    static final int MENU_ADD_REMINDER = 0x7E100003;
    static final int MENU_REMEMBER_LANG = 0x7E100004;

    /** 每会话保留的最近原文条数（供 AI 上下文精确翻译）。 */
    private static final int CONTEXT_SIZE = 5;
    /** 待办提醒到期检查节流（毫秒）。 */
    private static final long REMINDER_CHECK_INTERVAL_MS = 60000;

    // ---- 反射缓存（init 时解析，失败则对应功能不装） ----
    private static volatile Class<?> clsVv3;
    private static volatile Class<?> clsP7g;
    private static volatile Class<?> clsO7g;
    private static volatile Class<?> clsChatRow;
    private static volatile Class<?> clsChatRowText;
    private static volatile Class<?> clsChatView;
    private static volatile Class<?> clsMenuView;
    private static volatile Class<?> clsChatInputBar;
    private static volatile Class<?> clsU56;
    private static volatile Constructor<?> ctorP7g;
    private static volatile Constructor<?> ctorO7g;
    private static volatile Method mU56b;
    private static volatile int sendBtnId = -1;
    private static volatile int iconTranslate = 0;
    private static volatile int iconClock = 0;
    private static volatile int colorB60 = 0;
    private static volatile int colorR70 = 0;
    /**
     * 本机 uid：唯一可靠来源是 CoreUtility.i（ChatView.Na 用它与 vv3.c 比较
     * 判是不是自己发的；发送者字段另有 MessageId.d）。逆向文档与 jadx_out
     * 里没有更可靠的自身 uid 来源，也没有 vv3 的方向/是否自己发的标志字段，
     * 所以获取链只有这一环；拿不到时靠下方的发送回显启发式兜底。
     */
    private static volatile String selfUid;

    private static volatile TranslateStore store;
    private static volatile Handler mainHandler;

    /** 正在翻译中的消息键（防重复发请求）。 */
    private static final Set<String> inFlight = Collections.synchronizedSet(new HashSet<String>());
    /** 全模块共用的固定翻译线程池（接收/发送/短语现翻都走它，不再 new Thread）。 */
    private static final ExecutorService TRANSLATE_POOL = Executors.newFixedThreadPool(3);
    /** 同时在途的翻译请求上限：超过时本次不发起，等用户滚动回来重新绑定时再触发。 */
    private static final int MAX_IN_FLIGHT = 8;
    /** 正在自动发送中的 ChatView 身份（防 performClick 循环；只在自动重发路径使用）。 */
    private static final Set<Integer> sendingGuards = Collections.synchronizedSet(new HashSet<Integer>());
    /** 正在执行自动重发的瞬间标志（performClick 同步重入时放行，区别于用户连点）。 */
    private static final Set<Integer> resending =
            Collections.synchronizedSet(new HashSet<Integer>());
    /** 检查路径下已填入输入框、待用户确认发送的译文（按 ChatView 身份记）。 */
    private static final Map<Integer, String> reviewedFilled =
            Collections.synchronizedMap(new HashMap<Integer, String>());
    /** 最近发出的文本（发送侧记下，接收侧回显判定用；60 秒过期）。 */
    private static final List<SentEcho> recentSent =
            Collections.synchronizedList(new ArrayList<SentEcho>());
    private static final long SENT_ECHO_TTL_MS = 60000;
    /** 检查模式下正在翻译中的 ChatView 身份（防连点：翻译中再点发送直接吞掉）。 */
    private static final Set<Integer> translatingSends =
            Collections.synchronizedSet(new HashSet<Integer>());
    /** 已挂上“译”按钮的输入栏（防重复加按钮；弱引用，输入栏回收即丢）。 */
    private static final Map<Object, Boolean> translateBtnDone =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    /** “译”按钮正在翻译中的输入栏身份（防连点：翻译中再点直接吞掉）。 */
    private static final Set<Integer> translatingInputs =
            Collections.synchronizedSet(new HashSet<Integer>());
    /** 行 -> 消息映射（滚动停下后补翻用；弱引用，行回收即丢）。 */
    private static final Map<View, RowInfo> rowByView =
            Collections.synchronizedMap(new WeakHashMap<View, RowInfo>());
    /** 聊天列表是否正在滚动：滚动中只读缓存、不发新翻译请求。 */
    private static volatile boolean listScrolling = false;
    /** 滚动代号：每次停下 +1，旧代号的排队任务执行前直接丢弃。 */
    private static final AtomicLong scrollGeneration = new AtomicLong(0);
    /** 已挂滚动监听的列表（防重复挂）。 */
    private static final Map<Object, Boolean> scrollListenersDone =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    /** 滚动监听接口类（找不到就降级回现有 setMessage 行为）。 */
    private static volatile Class<?> clsOnScrollListener;
    private static volatile boolean scrollListenerTried = false;
    /** 防抖中的停下补翻任务（新滚动事件到来时取消）。 */
    private static volatile Runnable pendingIdleRun = null;
    /** 最近一次滚动的列表（弱引用；翻完一条后补翻下一批用）。 */
    private static volatile WeakReference<Object> lastRvRef = null;
    /** 进程启动后是否已重设过未过期提醒（重启恢复用，一次即可）。 */
    private static volatile boolean remindersRescheduled = false;
    /** 已加过自定义项的长按菜单（防重复追加）。 */
    private static final Map<Object, Boolean> menusDone =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    /** 每会话最近原文：threadId -> (消息键 -> 条目)，按消息键去重。 */
    private static final Map<String, LinkedHashMap<String, CtxEntry>> recentByThread =
            Collections.synchronizedMap(new HashMap<String, LinkedHashMap<String, CtxEntry>>());
    /** 上下文条目全局递增序号（取最近 N 条时按序号取，不只依赖队列顺序）。 */
    private static final AtomicLong ctxSeq = new AtomicLong(0);
    /** ChatView -> 当前会话 threadId（发送侧翻译取上下文用；弱引用，视图回收即丢）。 */
    private static final Map<Object, String> threadByChatView =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());
    private static volatile long lastReminderCheck = 0;

    private static volatile ClassLoader zaloCl;

    public static void install(final XC_LoadPackage.LoadPackageParam lpparam) {
        zaloCl = lpparam.classLoader;
        try {
            installRowHook(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " installRowHook fail: " + t);
        }
        try {
            installMenuHook(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " installMenuHook fail: " + t);
        }
        try {
            installSendHook(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " installSendHook fail: " + t);
        }
        try {
            installTranslateButton(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " installTranslateButton fail: " + t);
        }
    }

    // ============================================================
    // 1. 接收翻译：ChatRow.setMessage(vv3) before
    // ============================================================

    private static void installRowHook(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        final ClassLoader cl = lpparam.classLoader;
        clsVv3 = Class.forName("vv3", false, cl);
        clsP7g = Class.forName("p7g", false, cl);
        clsO7g = Class.forName("o7g", false, cl);
        clsChatRow = Class.forName("com.zing.zalo.ui.chat.chatrow.ChatRow", false, cl);
        clsChatRowText = Class.forName("com.zing.zalo.ui.chat.chatrow.ChatRowText", false, cl);
        ctorP7g = clsP7g.getConstructor(String.class, String.class, boolean.class, HashMap.class);
        ctorO7g = clsO7g.getConstructor(String.class, String.class);
        try {
            Class<?> core = Class.forName("com.zing.zalocore.CoreUtility", false, cl);
            Field f = core.getField("i");
            Object v = f.get(null);
            if (v instanceof String) {
                selfUid = (String) v;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " selfUid unknown, own-message echo heuristic active: " + t);
        }

        XposedHelpers.findAndHookMethod(clsChatRow, "setMessage", clsVv3,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            onBeforeSetMessage(param);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " onBeforeSetMessage error: " + t);
                        }
                    }
                });
        XposedBridge.log(TAG + " ChatRow.setMessage hook installed");

        // 原文在上、译文在下：原生 I2() 的逻辑是“有译文时主文本 = 译文、抽屉 = 原文”，
        // 用户要的是反过来。在 I2() 算完之后、z2() 建布局之前把 n7/w7 对调。
        // I2() 每次重算都从 N0()/B2() 读新值，对调幂等；f2() 调 I2() 在先、i2()/z2() 建布局在后，
        // 同一次绑定内一定赶得上。
        Class<?> clsChatRowHasCaption = Class.forName(
                "com.zing.zalo.ui.chat.chatrow.ChatRowHasCaption", false, cl);
        XposedHelpers.findAndHookMethod(clsChatRowHasCaption, "I2",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            swapTranslationLayout(param.thisObject);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " swapTranslationLayout error: " + t);
                        }
                    }
                });
        XposedBridge.log(TAG + " ChatRowHasCaption.I2 hook installed");
    }

    private static void onBeforeSetMessage(XC_MethodHook.MethodHookParam param) throws Exception {
        Object row = param.thisObject;
        // v1 只做纯文本气泡（图片/文件等 caption 以后再说）。
        if (!clsChatRowText.isInstance(row)) {
            return;
        }
        Object vv3 = param.args[0];
        if (vv3 == null) {
            return;
        }
        // 进程启动后首次绑定：重设重启后被系统清掉的未过期提醒闹钟（见
        // maybeRescheduleReminders 的注释：恢复点只能放在 Zalo 进程内）。
        maybeRescheduleReminders(row);
        TranslateConfig cfg = TranslateConfig.loadForHook();
        if (!cfg.enabled || !cfg.autoIncoming) {
            return;
        }
        // 已有译文（原生或已注入）——直接跳过，也是重绑定的天然防循环闸。
        if (hasTranslation(vv3)) {
            return;
        }
        String text = (String) getField(vv3, "f");
        if (text == null || text.trim().length() < 2) {
            return;
        }
        // 已是中文，不用翻。
        if (containsHan(text)) {
            return;
        }
        Object msgId = getField(vv3, "b");
        if (msgId == null) {
            return;
        }
        String key = messageKey(msgId);
        if (key == null) {
            return;
        }
        String threadId = (String) getField(msgId, "c");
        recordRecent(threadId, key, text);
        // 记下这行所属 ChatView 对应的会话，供发送侧翻译取上下文
        if (threadId != null) {
            try {
                Object cv = findChatViewAncestor((View) row);
                if (cv != null) {
                    threadByChatView.put(cv, threadId);
                }
            } catch (Throwable ignored) {
            }
        }
        // 用户手动隐藏过译文的消息不再自动翻。
        TranslateStore st = storeFor(row);
        if (st != null && st.isHidden(key)) {
            return;
        }
        // 自己发的消息不做接收翻译（发送侧翻译走发送键拦截）。
        String sender = (String) getField(vv3, "c");
        // 记下行 -> 消息映射，供滚动停下后的补翻遍历用；同时给聊天列表挂
        // 滚动监听（找不到列表就降级回现有 setMessage 行为）。
        rowByView.put((View) row, new RowInfo(key, text, threadId, sender, vv3, msgId));
        ensureScrollListener((View) row);
        if (selfUid != null && selfUid.length() > 0 && selfUid.equals(sender)) {
            return;
        }
        // 启发式兜底：拿不到 selfUid 时，自己刚发出的译文回到列表里会被当成
        // 收到的消息；发送侧记下的文本在 60 秒内原样出现，即判定为自己消息
        // 的回显，跳过翻译。
        if (isSelfEcho(text)) {
            return;
        }
        // 缓存命中：before-hook 阶段注入，绑定布局直接按译文算。
        if (st != null) {
            String cached = st.getCached(text, "auto", "zh");
            if (cached != null) {
                injectTranslation(vv3, cached, detectedLang(vv3));
                return;
            }
        }
        if (!cfg.resolveForHistory().hasApi()) {
            return;
        }
        if (listScrolling) {
            return; // 滑动中只读缓存，不发新请求；停下后由滚动监听补翻
        }
        enqueueTranslation(row, vv3, msgId, key, text, threadId, detectedLang(vv3), -1);
        maybeCheckReminders(row);
    }

    // ============================================================
    // 1b. 滚动跟随翻译：停下再翻，只翻当前可见行
    // ============================================================

    /** 行 -> 消息映射条目（滚动停下后补翻时用）。 */
    private static class RowInfo {
        final String key;
        final String text;
        final String threadId;
        final String sender;
        final Object vv3;
        final Object msgId;

        RowInfo(String key, String text, String threadId, String sender, Object vv3, Object msgId) {
            this.key = key;
            this.text = text;
            this.threadId = threadId;
            this.sender = sender;
            this.vv3 = vv3;
            this.msgId = msgId;
        }
    }

    /**
     * 提交一条翻译请求（绑定路径与停下补翻路径共用）。
     * gen < 0 = 绑定路径，不做代号检查；gen >= 0 = 停下补翻路径，任务开始
     * 执行前核对代号，过期（用户又滑走了）直接丢弃，不调 API。
     * 返回 false = 在途已满或已在翻译，本次不发起（停下后自动补翻）。
     */
    private static boolean enqueueTranslation(final Object rowRef, final Object vv3Ref,
            final Object msgIdRef, final String keyRef, final String textRef,
            final String threadRef, final String detected, final long gen) {
        if (inFlight.size() >= MAX_IN_FLIGHT) {
            return false;
        }
        if (!inFlight.add(keyRef)) {
            return false;
        }
        final String context = recentContext(threadRef, textRef);
        TRANSLATE_POOL.execute(new Runnable() {
            @Override
            public void run() {
                if (gen >= 0 && gen != scrollGeneration.get()) {
                    inFlight.remove(keyRef); // 过期任务直接丢弃，不调 API
                    return;
                }
                TranslateConfig c2 = TranslateConfig.loadForHook().resolveForHistory();
                String translated = LlmClient.translate(c2, textRef, "auto", "zh", context);
                if (translated != null && store != null) {
                    store.putCached(textRef, "auto", "zh", translated);
                    if (threadRef != null && store.getContactLang(threadRef) == null) {
                        // 只在没有记录时自动记为越语；手动设过的（中文/越语）不动，
                        // 否则对方偶尔发句越语就会把“不翻译”覆盖掉。
                        store.setContactLang(threadRef, "vi");
                    }
                }
                final String result = translated;
                mainHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            applyTranslation(rowRef, vv3Ref, msgIdRef, result);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " applyTranslation error: " + t);
                        } finally {
                            inFlight.remove(keyRef);
                            // 翻完一条腾出在途名额：若停下时因满员漏翻了可见行，
                            // 再扫一遍补上。只在成功且缓存可用时触发——失败
                            // 的行不等（避免失败重试死循环），下次停下再试。
                            if (result != null && store != null) {
                                scheduleIdleRescan();
                            }
                        }
                    }
                });
            }
        });
        return true;
    }

    /** 滚动停下后防抖约 300ms，再扫一遍当前可见行（代号 +1，旧任务作废）。 */
    private static void scheduleIdlePass(final Object rv) {
        try {
            final long gen = scrollGeneration.incrementAndGet();
            if (pendingIdleRun != null) {
                mainHandler().removeCallbacks(pendingIdleRun);
            }
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    runIdlePass(rv, gen);
                }
            };
            pendingIdleRun = r;
            mainHandler().postDelayed(r, 300);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " scheduleIdlePass fail: " + t);
        }
    }

    /** 翻完一条后补扫（只在未滚动、列表已知时）。 */
    private static void scheduleIdleRescan() {
        try {
            if (listScrolling) {
                return;
            }
            Object rv = lastRvRef == null ? null : lastRvRef.get();
            if (rv == null) {
                return;
            }
            scheduleIdlePass(rv);
        } catch (Throwable ignored) {
        }
    }

    /** 停下补翻：遍历当前可见子 View（预加载的屏幕外一两行也算可见），
     *  没有译文、没有缓存的行发请求；滑动过程中只读缓存。 */
    private static void runIdlePass(Object rv, long gen) {
        try {
            if (rv == null || !(rv instanceof ViewGroup)) {
                return;
            }
            if (gen != scrollGeneration.get()) {
                return; // 已被更新的停下取代
            }
            TranslateConfig cfg = TranslateConfig.loadForHook();
            if (!cfg.enabled || !cfg.autoIncoming) {
                return;
            }
            if (!cfg.resolveForHistory().hasApi()) {
                return;
            }
            TranslateStore st = storeFor(rv);
            ViewGroup vg = (ViewGroup) rv;
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) {
                try {
                    View child = vg.getChildAt(i);
                    if (child == null) {
                        continue;
                    }
                    RowInfo info = rowByView.get(child);
                    if (info == null || info.vv3 == null || info.text == null) {
                        continue;
                    }
                    if (hasTranslation(info.vv3)) {
                        continue;
                    }
                    if (containsHan(info.text)) {
                        continue;
                    }
                    if (st != null && st.isHidden(info.key)) {
                        continue;
                    }
                    if (selfUid != null && selfUid.length() > 0 && selfUid.equals(info.sender)) {
                        continue;
                    }
                    if (isSelfEcho(info.text)) {
                        continue;
                    }
                    if (st != null) {
                        String cached = st.getCached(info.text, "auto", "zh");
                        if (cached != null) {
                            // 缓存命中立刻显示（走与异步回包同一校验+重绑定）。
                            try {
                                applyTranslation(child, info.vv3, info.msgId, cached);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " idle cache apply fail: " + t);
                            }
                            continue;
                        }
                    }
                    enqueueTranslation(child, info.vv3, info.msgId, info.key,
                            info.text, info.threadId, detectedLang(info.vv3), gen);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " idle row fail: " + t);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " runIdlePass fail: " + t);
        }
    }

    /** 滚动状态变化：停下（IDLE=0）时安排补翻；否则只记“在滚动”。 */
    private static void onListScrollStateChanged(Object rv, int newState) {
        try {
            lastRvRef = new WeakReference<Object>(rv);
            if (newState == 0) { // SCROLL_STATE_IDLE
                listScrolling = false;
                scheduleIdlePass(rv);
            } else {
                listScrolling = true;
                if (pendingIdleRun != null) {
                    mainHandler().removeCallbacks(pendingIdleRun);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " scroll state fail: " + t);
        }
    }

    /** 正在滚动：只记状态，并取消待执行的补翻（停下后重新安排）。 */
    private static void onListScrolled() {
        listScrolling = true;
        try {
            if (pendingIdleRun != null) {
                mainHandler().removeCallbacks(pendingIdleRun);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 给行所在的聊天列表挂一次滚动监听（找不到就静默降级）。 */
    private static void ensureScrollListener(View row) {
        try {
            if (clsOnScrollListener == null && !scrollListenerTried) {
                scrollListenerTried = true;
                try {
                    clsOnScrollListener = Class.forName(
                            "androidx.recyclerview.widget.RecyclerView$OnScrollListener",
                            false, zaloCl);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " OnScrollListener not found, idle-scroll disabled: " + t);
                }
            }
            if (clsOnScrollListener == null) {
                return;
            }
            Object rv = findRecyclerViewAncestor(row);
            if (rv == null) {
                return;
            }
            if (scrollListenersDone.containsKey(rv)) {
                return;
            }
            scrollListenersDone.put(rv, Boolean.TRUE);
            lastRvRef = new WeakReference<Object>(rv);
            Object listener = Proxy.newProxyInstance(zaloCl,
                    new Class<?>[]{clsOnScrollListener},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            try {
                                String name = method.getName();
                                if ("onScrollStateChanged".equals(name)
                                        && args != null && args.length == 2) {
                                    onListScrollStateChanged(args[0],
                                            ((Integer) args[1]).intValue());
                                } else if ("onScrolled".equals(name)) {
                                    onListScrolled();
                                } else if ("hashCode".equals(name)) {
                                    return Integer.valueOf(System.identityHashCode(proxy));
                                } else if ("equals".equals(name)) {
                                    return Boolean.valueOf(proxy == args[0]);
                                } else if ("toString".equals(name)) {
                                    return "ZaloZhScrollListener";
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " scroll listener fail: " + t);
                            }
                            return null;
                        }
                    });
            callMethod(rv, "addOnScrollListener", listener);
            XposedBridge.log(TAG + " scroll listener attached to chat list");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " ensureScrollListener fail: " + t);
        }
    }

    /** 沿父链找 RecyclerView（按类名逐级比对，子类也认；找不到返回 null）。 */
    private static Object findRecyclerViewAncestor(View v) {
        try {
            View p = v;
            for (int i = 0; i < 12 && p != null; i++) {
                if (isRecyclerView(p)) {
                    return p;
                }
                Object parent = p.getParent();
                p = parent instanceof View ? (View) parent : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isRecyclerView(View v) {
        try {
            Class<?> c = v.getClass();
            while (c != null) {
                if ("androidx.recyclerview.widget.RecyclerView".equals(c.getName())) {
                    return true;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 重启恢复：进程启动后首次绑定时，把未过期的待办提醒重新设闹钟。
     * 提醒创建时已持久化在 Zalo 自身 prefs（TranslateStore.addReminder），
     * 这里只重设、过期（dueAt <= now）的不再重设。
     *
     * 注意：BOOT_COMPLETED 接收器跑在模块自身进程（com.zalocn），读不到
     * Zalo 私有 prefs 里的提醒数据（跨 UID + SELinux），加了也是空壳，
     * 所以恢复点放在 Zalo 进程启动后的首次绑定——重启后首次打开 Zalo
     * 即恢复；重启后没开过 Zalo，提醒不会响（见测试指南）。
     */
    private static void maybeRescheduleReminders(Object row) {
        try {
            if (remindersRescheduled) {
                return;
            }
            remindersRescheduled = true;
            TranslateStore st = storeFor(row);
            if (st == null) {
                return;
            }
            Context ctx = ((View) row).getContext();
            if (ctx == null) {
                return;
            }
            long now = System.currentTimeMillis();
            int n = 0;
            for (TranslateStore.Reminder r : st.getReminders()) {
                if (r.dueAt > now) {
                    scheduleReminderAlarm(ctx, r);
                    n++;
                }
            }
            if (n > 0) {
                XposedBridge.log(TAG + " rescheduled " + n + " pending reminders after process start");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " reschedule reminders fail: " + t);
        }
    }

    /** 异步回包应用：先核对行当前消息仍是目标消息（防 RecyclerView 复用串位）。 */
    private static void applyTranslation(Object row, Object vv3, Object msgId, String translated) throws Exception {
        if (row == null || translated == null) {
            return;
        }
        Object cur = callMethod(row, "getMessage");
        if (cur == null) {
            return;
        }
        Object curId = callMethod(cur, "getItemId");
        if (curId == null || !curId.equals(msgId)) {
            return;
        }
        // 原生翻译已存在就丢弃自己的注入，绝不覆盖原生 o3（before-hook 路径
        // 已有此检查，这里补上异步路径：回包期间原生翻译可能刚到）。
        if (hasTranslation(vv3)) {
            return;
        }
        injectTranslation(vv3, translated, detectedLang(vv3));
        // 走一次完整重绑定，让 I2()/z2() 按新 o3 重算 n7/w7/e8。
        callMethod(row, "setMessage", vv3);
    }

    /**
     * 原文在上、译文在下：I2() 算完后 n7 = 译文（主文本）、w7 = 原文（进抽屉），
     * 这里对调成 n7 = 原文、w7 = 译文。x7 是按原文算出的 span 段落，套到译文上会错位，
     * 抽屉缓存 s7/r7 是按旧 w7 建的，都清掉让抽屉按译文重建。q7 保持为译文
     * （ChatRowHasCaption.L() 靠它判断译文是否变了）；o7 不变（n7 仍非空）。
     * 无译文时 u7 为 false，直接返回；“隐藏译文”（o3.c=false）时 N0() 为 null，
     * I2() 走无译文分支，同样不动，只显示原文。
     */
    private static void swapTranslationLayout(Object row) throws Exception {
        if (row == null) {
            return;
        }
        Boolean u7 = (Boolean) getField(row, "u7");
        if (u7 == null || !u7.booleanValue()) {
            return;
        }
        String n7 = (String) getField(row, "n7"); // 译文
        String w7 = (String) getField(row, "w7"); // 原文
        if (n7 == null || w7 == null) {
            return;
        }
        setField(row, "n7", w7);
        setField(row, "w7", n7);
        setField(row, "x7", null);
        setField(row, "s7", null);
        setField(row, "r7", null);
    }

    /** 注入译文到原生槽位 vv3.o3（p7g），不碰原文 vv3.f。 */
    private static void injectTranslation(Object vv3, String translated, String detected) throws Exception {
        HashMap<String, Object> map = new HashMap<String, Object>();
        map.put("zh", ctorO7g.newInstance(translated, null));
        Object p7g = ctorP7g.newInstance(
                detected == null ? "vi" : detected, "zh", Boolean.TRUE, map);
        setField(vv3, "o3", p7g);
    }

    private static boolean hasTranslation(Object vv3) {
        try {
            Object r = callMethod(vv3, "K3");
            return r instanceof Boolean && ((Boolean) r).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String detectedLang(Object vv3) {
        try {
            Object r = callMethod(vv3, "z1");
            if (r instanceof String && ((String) r).length() > 0) {
                return (String) r;
            }
        } catch (Throwable ignored) {
        }
        return "vi";
    }

    private static String messageKey(Object msgId) {
        try {
            Object r = callMethod(msgId, "f1");
            if (r instanceof String && ((String) r).length() > 0) {
                return (String) r;
            }
        } catch (Throwable ignored) {
        }
        try {
            String a = (String) getField(msgId, "a");
            String c = (String) getField(msgId, "c");
            if (a != null && c != null) {
                return c + "|" + a;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ============================================================
    // 2. 长按菜单：显示原文/显示译文、设为待办提醒
    // ============================================================

    private static void installMenuHook(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        final ClassLoader cl = lpparam.classLoader;
        clsMenuView = Class.forName("com.zing.zalo.ui.chat.contextmenu.MessageContextMenuView", false, cl);
        clsU56 = Class.forName("u56", false, cl);
        try {
            mU56b = clsU56.getMethod("b", int.class, int.class, int.class, String.class);
        } catch (Throwable t) {
            try {
                mU56b = clsU56.getMethod("b", int.class, int.class, int.class, CharSequence.class);
            } catch (Throwable t2) {
                XposedBridge.log(TAG + " u56.b not found, menu items disabled: " + t2);
                mU56b = null;
            }
        }
        iconTranslate = staticInt("lkk", "zds_ic_translator_line_32", 0);
        iconClock = staticInt("lkk", "zds_ic_clock_1_line_32", 0);
        colorB60 = staticInt("oik", "b60", 0);
        colorR70 = staticInt("oik", "r70", 0);

        // G6() 渲染前追加自定义项（X1 此时已由 w6() 填好原生项）。
        XposedHelpers.findAndHookMethod(clsMenuView, "G6",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            onBeforeMenuRender(param);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " onBeforeMenuRender error: " + t);
                        }
                    }
                });
        // C6(int, vv3, xt3, boolean) 点击分发：拦截自定 id。
        Class<?> clsXt3 = Class.forName("xt3", false, cl);
        XposedHelpers.findAndHookMethod(clsMenuView, "C6",
                int.class, clsVv3, clsXt3, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (onMenuClick(param)) {
                                param.setResult(null);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " onMenuClick error: " + t);
                        }
                    }
                });
        XposedBridge.log(TAG + " MessageContextMenuView hooks installed");
    }

    private static void onBeforeMenuRender(XC_MethodHook.MethodHookParam param) throws Exception {
        if (mU56b == null) {
            return;
        }
        Object menu = param.thisObject;
        if (menusDone.containsKey(menu)) {
            return;
        }
        Object vv3 = getField(menu, "V1");
        if (vv3 == null) {
            return;
        }
        String text = (String) getField(vv3, "f");
        if (text == null || text.length() == 0) {
            return;
        }
        Object o3 = getField(vv3, "o3");
        boolean shown = hasTranslation(vv3);
        List<Object> items = new ArrayList<Object>();
        if (o3 != null) {
            // 显示布局已改为“原文在上、译文在下”：译文显示中 -> 给“隐藏译文”
            // （点后 o3.c=false，只显示原文）；被隐藏 -> 给“显示译文”。
            int id = shown ? MENU_SHOW_ORIGINAL : MENU_SHOW_TRANSLATED;
            String label = shown ? "隐藏译文" : "显示译文";
            Object item = mU56b.invoke(null, id, iconTranslate, colorB60, label);
            if (item != null) {
                items.add(item);
            }
        }
        Object rem = mU56b.invoke(null, MENU_ADD_REMINDER, iconClock, colorR70, "设为待办提醒");
        if (rem != null) {
            items.add(rem);
        }
        // 记住对方语言：在“中文/越语”之间切换该会话的记录（标签显示切换后的目标）。
        TranslateStore menuStore = storeFor(menu);
        String langThread = null;
        Object menuMsgId = getField(vv3, "b");
        if (menuMsgId != null) {
            langThread = (String) getField(menuMsgId, "c");
        }
        String curLang = (menuStore != null && langThread != null)
                ? menuStore.getContactLang(langThread) : null;
        String langLabel = "zh".equals(curLang) ? "记住对方语言：越语" : "记住对方语言：中文";
        Object langItem = mU56b.invoke(null, MENU_REMEMBER_LANG, iconTranslate, colorB60, langLabel);
        if (langItem != null) {
            items.add(langItem);
        }
        if (!items.isEmpty()) {
            Object x1 = getField(menu, "X1");
            if (x1 instanceof List) {
                ((List) x1).addAll(items);
                menusDone.put(menu, Boolean.TRUE);
            }
        }
    }

    /** 返回 true = 已处理，拦截原生分发。 */
    private static boolean onMenuClick(XC_MethodHook.MethodHookParam param) throws Exception {
        int id = ((Integer) param.args[0]).intValue();
        if (id != MENU_SHOW_ORIGINAL && id != MENU_SHOW_TRANSLATED
                && id != MENU_ADD_REMINDER && id != MENU_REMEMBER_LANG) {
            return false;
        }
        Object menu = param.thisObject;
        Object vv3 = param.args[1];
        if (vv3 == null) {
            return true;
        }
        TranslateStore st = storeFor(menu);
        String key = null;
        Object msgId = getField(vv3, "b");
        if (msgId != null) {
            key = messageKey(msgId);
        }
        if (id == MENU_SHOW_ORIGINAL) {
            Object o3 = getField(vv3, "o3");
            if (o3 != null) {
                setField(o3, "c", Boolean.FALSE);
            }
            if (st != null && key != null) {
                st.setHidden(key, true);
            }
            rebindChat(menu);
            return true;
        }
        if (id == MENU_SHOW_TRANSLATED) {
            Object o3 = getField(vv3, "o3");
            if (o3 != null) {
                setField(o3, "c", Boolean.TRUE);
            }
            if (st != null && key != null) {
                st.setHidden(key, false);
            }
            rebindChat(menu);
            return true;
        }
        if (id == MENU_REMEMBER_LANG) {
            Object mId = getField(vv3, "b");
            String thread = mId == null ? null : (String) getField(mId, "c");
            if (st != null && thread != null) {
                Context mctx = ((View) menu).getContext();
                String cur = st.getContactLang(thread);
                if ("zh".equals(cur)) {
                    st.setContactLang(thread, "vi");
                    Toast.makeText(mctx, "已记为对方说越语", Toast.LENGTH_SHORT).show();
                } else {
                    st.setContactLang(thread, "zh");
                    Toast.makeText(mctx, "已记为对方说中文，发出时不再自动翻译", Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        // 待办提醒：选时间 -> 存在模块本地。
        showReminderDialog(menu, vv3);
        return true;
    }

    /** 让列表重绑可见行（切换原文/译文后刷新显示）。 */
    private static void rebindChat(final Object menu) {
        mainHandler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Object chatView = getField(menu, "T1");
                    if (chatView == null) {
                        return;
                    }
                    Object adapter = getField(chatView, "J1");
                    if (adapter != null) {
                        callMethod(adapter, "notifyDataSetChanged");
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " rebindChat fail: " + t);
                }
            }
        });
    }

    private static void showReminderDialog(final Object menu, final Object vv3) {
        try {
            Context ctx = ((View) menu).getContext();
            final String text = (String) getField(vv3, "f");
            Object msgId = getField(vv3, "b");
            final String threadId = msgId == null ? null : (String) getField(msgId, "c");
            final TranslateStore st = storeFor(menu);
            if (ctx == null || text == null || st == null) {
                return;
            }
            final String[] labels = {"15 分钟后", "1 小时后", "今晚 21:00", "明天 9:00"};
            new AlertDialog.Builder(ctx)
                    .setTitle("设为待办提醒")
                    .setItems(labels, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                long now = System.currentTimeMillis();
                                java.util.Calendar cal = java.util.Calendar.getInstance();
                                long due;
                                if (which == 0) {
                                    due = now + 15 * 60 * 1000L;
                                } else if (which == 1) {
                                    due = now + 60 * 60 * 1000L;
                                } else if (which == 2) {
                                    cal.set(java.util.Calendar.HOUR_OF_DAY, 21);
                                    cal.set(java.util.Calendar.MINUTE, 0);
                                    cal.set(java.util.Calendar.SECOND, 0);
                                    due = cal.getTimeInMillis();
                                    if (due <= now) {
                                        due += 24 * 60 * 60 * 1000L;
                                    }
                                } else {
                                    cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
                                    cal.set(java.util.Calendar.HOUR_OF_DAY, 9);
                                    cal.set(java.util.Calendar.MINUTE, 0);
                                    cal.set(java.util.Calendar.SECOND, 0);
                                    due = cal.getTimeInMillis();
                                }
                                TranslateStore.Reminder created = st.addReminder(text, due, threadId);
                                if (created != null) {
                                    scheduleReminderAlarm(ctx, created);
                                }
                                Toast.makeText(ctx, "已设置提醒：" + labels[which], Toast.LENGTH_SHORT).show();
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " addReminder fail: " + t);
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " showReminderDialog fail: " + t);
        }
    }

    // ============================================================
    // 3. 发送侧：中文自动翻成越语再发；长按发送键调快捷短语
    // ============================================================

    private static void installSendHook(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        final ClassLoader cl = lpparam.classLoader;
        clsChatView = Class.forName("com.zing.zalo.ui.chat.ChatView", false, cl);
        clsChatInputBar = Class.forName("com.zing.zalo.ui.chat.widget.inputbar.ChatInputBar", false, cl);
        sendBtnId = staticInt("glk", "new_chat_input_btn_chat_send", -1);
        if (sendBtnId < 0) {
            XposedBridge.log(TAG + " send button id not found, send hook disabled");
            return;
        }
        XposedHelpers.findAndHookMethod(clsChatInputBar, "onClick", View.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (onBeforeSendClick(param)) {
                                param.setResult(null);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " onBeforeSendClick error: " + t);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(clsChatInputBar, "onLongClick", View.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (onSendLongClick(param)) {
                                param.setResult(Boolean.TRUE);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " onSendLongClick error: " + t);
                        }
                    }
                });
        XposedBridge.log(TAG + " ChatInputBar send hooks installed, sendBtnId=" + sendBtnId);
    }

    // ============================================================
    // 4. 输入框边“译”按钮：点按把中文翻成越语填回输入框，不自动发送
    //    （WATransChat 式样：翻译先在输入框里看得见、改得了，发送就是普通一次点击，
    //    模块不再拦截、不后台代发，从根上规避错发。）
    // ============================================================

    private static void installTranslateButton(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        final ClassLoader cl = lpparam.classLoader;
        if (clsChatView == null) {
            clsChatView = Class.forName("com.zing.zalo.ui.chat.ChatView", false, cl);
        }
        Class<?> clsInputBar = Class.forName("com.zing.zalo.ui.chat.widget.inputbar.ChatInputBar", false, cl);
        XposedHelpers.findAndHookMethod(clsInputBar, "onAttachedToWindow",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            attachTranslateButton(param.thisObject);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " attachTranslateButton error: " + t);
                        }
                    }
                });
        XposedBridge.log(TAG + " ChatInputBar translate button hook installed");
    }

    /** 输入栏挂上后加一个“译”字按钮（只加一次；进出会话重复 attach 时靠表去重）。 */
    private static void attachTranslateButton(Object bar) throws Exception {
        if (bar == null || translateBtnDone.containsKey(bar)) {
            return;
        }
        Object container = getField(bar, "a");   // FrameLayout 行容器
        Object edit = getField(bar, "i");        // 输入框 EditText
        Object sendBtn = getField(bar, "q");     // 发送按钮
        if (!(container instanceof ViewGroup) || !(edit instanceof View) || !(sendBtn instanceof View)) {
            return;
        }
        final ViewGroup row = (ViewGroup) container;
        final View input = (View) edit;
        final View send = (View) sendBtn;
        final Context ctx = row.getContext();
        // 发送按钮宽度（构造时写在 LayoutParams 里）= 右边距，让按钮落在发送键左边、不盖输入文字。
        int sendW = 0;
        try {
            ViewGroup.LayoutParams lp = send.getLayoutParams();
            if (lp != null) {
                sendW = lp.width;
            }
        } catch (Throwable ignored) {
        }
        if (sendW <= 0) {
            sendW = dp(ctx, 48);
        }
        final int gap = dp(ctx, 8);
        final int btnW = dp(ctx, 40);
        final int btnH = dp(ctx, 32);

        final TextView btn = new TextView(ctx);
        btn.setText("译");
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setTextColor(0xFFFFFFFF);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0068FF);
        bg.setCornerRadius(dp(ctx, 16));
        btn.setBackground(bg);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                btnW, btnH, Gravity.END | Gravity.CENTER_VERTICAL);
        blp.rightMargin = sendW + gap;
        row.addView(btn, blp);
        // 输入框右内边距让出按钮位置，避免文字被按钮盖住（只加一次，靠 translateBtnDone 去重）。
        input.setPadding(input.getPaddingLeft(), input.getPaddingTop(),
                input.getPaddingRight() + btnW + gap * 2, input.getPaddingBottom());
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onTranslateButtonClick(input, btn);
            }
        });
        translateBtnDone.put(bar, Boolean.TRUE);
    }

    /** “译”按钮点击：中文翻成越语填回输入框，不发送；发送拦截看到已填回的内容直接放行。 */
    private static void onTranslateButtonClick(final View input, final TextView btn) {
        try {
            TranslateConfig cfg = TranslateConfig.loadForHook();
            if (!cfg.enabled) {
                Toast.makeText(input.getContext(), "请先开启“启用消息翻译”", Toast.LENGTH_SHORT).show();
                return;
            }
            CharSequence cs = (CharSequence) callMethod(input, "getText");
            final String text = cs == null ? "" : cs.toString();
            if (text.trim().length() == 0) {
                Toast.makeText(input.getContext(), "输入框是空的", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!containsHan(text)) {
                Toast.makeText(input.getContext(), "输入已是越南语，无需翻译", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!cfg.hasApi()) {
                Toast.makeText(input.getContext(), "请先在“Zalo 汉化设置”里填 API 地址和模型", Toast.LENGTH_LONG).show();
                return;
            }
            final Object chatView = findChatViewAncestor(input);
            final int guardKey = chatView != null
                    ? System.identityHashCode(chatView) : System.identityHashCode(input);
            if (translatingInputs.contains(Integer.valueOf(guardKey))) {
                return;
            }
            translatingInputs.add(Integer.valueOf(guardKey));
            btn.setEnabled(false);
            btn.setText("…");
            final Context ctx = input.getContext();
            final String threadId = chatView != null ? threadByChatView.get(chatView) : null;
            TRANSLATE_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    TranslateConfig c2 = TranslateConfig.loadForHook();
                    final String ctxStr = recentContext(threadId, text);
                    final String vi = LlmClient.translate(c2, text, "zh", "vi", ctxStr);
                    mainHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            translatingInputs.remove(Integer.valueOf(guardKey));
                            btn.setEnabled(true);
                            btn.setText("译");
                            try {
                                CharSequence cur = (CharSequence) callMethod(input, "getText");
                                String curText = cur == null ? "" : cur.toString();
                                if (!text.equals(curText)) {
                                    return; // 用户已改输入，不动
                                }
                                if (vi == null) {
                                    Toast.makeText(ctx, "翻译失败，请重试", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                TranslateConfig c3 = TranslateConfig.loadForHook();
                                String filled = c3.inputKeepOriginal ? text + "\n" + vi : vi;
                                replaceInputText(input, filled);
                                // 记为已检查过的填回内容：发送拦截看到它直接放行，不再重复翻译。
                                if (chatView != null) {
                                    reviewedFilled.put(Integer.valueOf(System.identityHashCode(chatView)), filled);
                                }
                                TranslateStore st = storeFor(input);
                                if (st != null) {
                                    st.putCached(text, "zh", "vi", vi);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " translate-button apply error: " + t);
                            }
                        }
                    });
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " onTranslateButtonClick error: " + t);
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    /** 返回 true = 吞掉这次点击（等翻译完再重发）。 */
    private static boolean onBeforeSendClick(XC_MethodHook.MethodHookParam param) throws Exception {
        View view = (View) param.args[0];
        if (view.getId() != sendBtnId) {
            return false;
        }
        TranslateConfig cfg = TranslateConfig.loadForHook();
        if (!cfg.enabled || !cfg.autoOutgoing) {
            return false;
        }
        Object chatView = findChatViewAncestor(view);
        if (chatView == null) {
            return false;
        }
        int guardKey = System.identityHashCode(chatView);
        // 自动重发（performClick 同步重入）：直接放行。必须先于防连点检查，
        // 否则重发会被自己的守卫吞掉。
        if (resending.contains(Integer.valueOf(guardKey))) {
            return false;
        }
        // 防连点：翻译中用户再点发送，直接吞掉（不重复请求）。
        if (translatingSends.contains(Integer.valueOf(guardKey))) {
            Toast.makeText(view.getContext(), "正在翻译，请稍候…", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (sendingGuards.contains(guardKey)) {
            return false; // 翻译后重发的点击，直接放行（兼容旧路径）
        }
        final TranslateStore st = storeFor(view);
        // 同语不译：该会话对方语言记为中文时，发出中文直接发，不翻译。
        String sendThreadId = threadByChatView.get(chatView);
        if (st != null && sendThreadId != null && "zh".equals(st.getContactLang(sendThreadId))) {
            return false;
        }
        Object edit = getField(chatView, "U1");
        if (edit == null) {
            return false;
        }
        CharSequence cs = (CharSequence) callMethod(edit, "getText");
        final String text = cs == null ? "" : cs.toString();
        if (text.trim().length() == 0) {
            return false;
        }
        // 用户已确认过的译文（检查路径填回的）：即使译文里还留着汉字（如人名），
        // 也是用户看过的内容，直接放行，不再重复翻译。
        String filled = reviewedFilled.get(Integer.valueOf(guardKey));
        if (filled != null && filled.equals(text)) {
            reviewedFilled.remove(Integer.valueOf(guardKey));
            recordSentText(text);
            return false;
        }
        if (!containsHan(text)) {
            recordSentText(text);
            return false;
        }
        if (st != null) {
            String cached = st.getCached(text, "zh", "vi");
            if (cached != null) {
                replaceInputText(edit, cached);
                if (cfg.reviewBeforeSend) {
                    // 检查路径：译文填回输入框，等用户自己点发送。
                    reviewedFilled.put(Integer.valueOf(guardKey), cached);
                    Toast.makeText(view.getContext(), "译文已填入输入框，请检查后点发送", Toast.LENGTH_SHORT).show();
                    return true;
                }
                recordSentText(cached);
                return false; // 检查关着：直接放行，发送越语
            }
        }
        if (!cfg.hasApi()) {
            recordSentText(text);
            return false; // 没配 API，原文照发
        }
        // 检查路径不加 guard（不自动重发）；只在自动重发路径（开关关时）用 guard。
        // 防连点守卫两种路径都要加：翻译中再点发送直接吞掉，翻译完才放开。
        final boolean autoResend = !cfg.reviewBeforeSend;
        if (autoResend) {
            sendingGuards.add(guardKey);
        }
        translatingSends.add(Integer.valueOf(guardKey));
        Toast.makeText(view.getContext(), "正在翻译成越语…", Toast.LENGTH_SHORT).show();
        final View sendBtn = view;
        final Object editRef = edit;
        final Object chatViewRef = chatView;
        final int guardKeyRef = guardKey;
        TRANSLATE_POOL.execute(new Runnable() {
            @Override
            public void run() {
                TranslateConfig c2 = TranslateConfig.loadForHook();
                final String sendCtx = recentContext(threadByChatView.get(chatViewRef), text);
                final String translated = LlmClient.translate(c2, text, "zh", "vi", sendCtx);
                mainHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            sendingGuards.remove(Integer.valueOf(System.identityHashCode(chatViewRef)));
                            CharSequence cur = (CharSequence) callMethod(editRef, "getText");
                            String curText = cur == null ? "" : cur.toString();
                            if (!text.equals(curText)) {
                                // 用户已改输入，不再替发
                                return;
                            }
                            if (translated != null) {
                                if (store != null) {
                                    store.putCached(text, "zh", "vi", translated);
                                }
                                replaceInputText(editRef, translated);
                                if (autoResend) {
                                    recordSentText(translated);
                                    resending.add(Integer.valueOf(guardKeyRef));
                                    try {
                                        sendBtn.performClick();
                                    } finally {
                                        resending.remove(Integer.valueOf(guardKeyRef));
                                    }
                                } else {
                                    // 检查路径：译文填回输入框，等用户自己点发送。
                                    reviewedFilled.put(Integer.valueOf(guardKeyRef), translated);
                                    Toast.makeText(sendBtn.getContext(), "译文已填入输入框，请检查后点发送", Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                // 翻译失败：绝不发中文原文，只 Toast，原文留在输入框等用户处理。
                                Toast.makeText(sendBtn.getContext(), "翻译失败，已保留原文，请检查后发送", Toast.LENGTH_SHORT).show();
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " send-after-translate error: " + t);
                            sendingGuards.remove(Integer.valueOf(System.identityHashCode(chatViewRef)));
                        } finally {
                            // 防连点守卫任何出口都要放开（含用户已改输入、异常）。
                            translatingSends.remove(Integer.valueOf(guardKeyRef));
                        }
                    }
                });
            }
        });
        return true;
    }

    /** 长按发送键 -> 快捷短语选择框（返回 true = 已处理）。 */
    private static boolean onSendLongClick(XC_MethodHook.MethodHookParam param) throws Exception {
        View view = (View) param.args[0];
        if (view.getId() != sendBtnId) {
            return false;
        }
        TranslateConfig cfg = TranslateConfig.loadForHook();
        if (!cfg.enabled || cfg.phrases == null || cfg.phrases.isEmpty()) {
            return false;
        }
        final Object chatView = findChatViewAncestor(view);
        if (chatView == null) {
            return false;
        }
        final Object edit = getField(chatView, "U1");
        if (edit == null) {
            return false;
        }
        final Context ctx = view.getContext();
        final List<String[]> phrases = cfg.phrases;
        String[] labels = new String[phrases.size()];
        for (int i = 0; i < phrases.size(); i++) {
            labels[i] = phrases.get(i)[0];
        }
        new AlertDialog.Builder(ctx)
                .setTitle("快捷短语（点一下插入输入框）")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            final String[] ph = phrases.get(which);
                            String vi = ph.length > 1 ? ph[1] : "";
                            if (vi != null && vi.length() > 0) {
                                insertIntoInput(edit, vi);
                                return;
                            }
                            // 越语为空：现翻再插。
                            final String zh = ph[0];
                            Toast.makeText(ctx, "正在翻译短语…", Toast.LENGTH_SHORT).show();
                            TRANSLATE_POOL.execute(new Runnable() {
                                @Override
                                public void run() {
                                    TranslateConfig c2 = TranslateConfig.loadForHook();
                                    String phCtx = recentContext(threadByChatView.get(chatView), zh);
                                    final String tr = LlmClient.translate(c2, zh, "zh", "vi", phCtx);
                                    mainHandler().post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (tr != null) {
                                                insertIntoInput(edit, tr);
                                            } else {
                                                Toast.makeText(ctx, "翻译失败", Toast.LENGTH_SHORT).show();
                                            }
                                        }
                                    });
                                }
                            });
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " phrase insert fail: " + t);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        return true;
    }

    /** 光标处插入（直接改 Editable，不走 setText，避免触发汉化 hook 误翻）。 */
    private static void insertIntoInput(Object edit, String text) {
        try {
            android.text.Editable e = (android.text.Editable) callMethod(edit, "getText");
            int pos = 0;
            try {
                pos = ((Integer) callMethod(edit, "getSelectionStart")).intValue();
            } catch (Throwable ignored) {
            }
            if (pos < 0 || pos > e.length()) {
                pos = e.length();
            }
            e.insert(pos, text);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " insertIntoInput fail: " + t);
        }
    }

    /** 整段替换输入框内容（直接改 Editable，不走 setText）。 */
    private static void replaceInputText(Object edit, String text) {
        try {
            android.text.Editable e = (android.text.Editable) callMethod(edit, "getText");
            e.replace(0, e.length(), text);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " replaceInputText fail: " + t);
        }
    }

    /**
     * 给一条提醒排精确闹钟（到点系统发广播给 ReminderReceiver 发通知）。
     * 提醒内容放 Intent extras 里，另带校验 token（接收器核对）。
     * 精确闹钟权限检查的是调用者（Zalo 进程）的权限，不是模块清单里的
     * 声明：Zalo 没给精确闹钟权限时 canScheduleExactAlarms() 为 false，
     * 直接走不精确闹钟（仍会响，但可能晚几分钟），不再靠抛异常回落。
     */
    static void scheduleReminderAlarm(Context ctx, TranslateStore.Reminder r) {        try {
            if (ctx == null || r == null) {
                return;
            }
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            Intent intent = new Intent(ReminderReceiver.ACTION_REMINDER);
            intent.setClassName("com.zalocn", "com.zalocn.ReminderReceiver");
            intent.putExtra(ReminderReceiver.EXTRA_TEXT, r.text);
            intent.putExtra(ReminderReceiver.EXTRA_ID, r.id);
            // 校验 token：设置页生成，经 XSharedPreferences 同步到 Zalo 进程。
            try {
                String token = TranslateConfig.loadForHook().reminderToken;
                if (token != null && token.length() > 0) {
                    intent.putExtra(ReminderReceiver.EXTRA_TOKEN, token);
                }
            } catch (Throwable ignored) {
            }
            int requestCode = r.id == null ? 0 : r.id.hashCode();
            PendingIntent pi = PendingIntent.getBroadcast(ctx, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            boolean canExact = true;
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    canExact = am.canScheduleExactAlarms();
                } catch (Throwable ignored) {
                    canExact = false;
                }
            }
            if (canExact) {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.dueAt, pi);
                    return;
                } catch (SecurityException e) {
                    XposedBridge.log(TAG + " exact alarm denied despite canScheduleExactAlarms, fallback to inexact: " + e);
                }
            } else {
                XposedBridge.log(TAG + " Zalo has no exact-alarm permission, fallback to inexact (may be minutes late)");
            }
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.dueAt, pi);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " scheduleReminderAlarm fail: " + t);
        }
    }

    // ============================================================
    // 4. 待办提醒到期检查（在绑定时节流触发）
    // ============================================================

    /**
     * 兜底：闹钟被拒/未触发时，有新消息绑定仍会顺带弹出已到期提醒。
     * 正常路径靠 AlarmManager + ReminderReceiver 准时触发，不依赖这里。
     */
    private static void maybeCheckReminders(Object anyView) {
        try {
            long now = System.currentTimeMillis();
            if (now - lastReminderCheck < REMINDER_CHECK_INTERVAL_MS) {
                return;
            }
            lastReminderCheck = now;
            TranslateStore st = storeFor(anyView);
            if (st == null) {
                return;
            }
            List<TranslateStore.Reminder> due = st.popDueReminders(now);
            if (due.isEmpty()) {
                return;
            }
            Context ctx = ((View) anyView).getContext();
            for (TranslateStore.Reminder r : due) {
                try {
                    Toast.makeText(ctx, "待办提醒：" + r.text, Toast.LENGTH_LONG).show();
                    postReminderNotification(ctx, r.text);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " maybeCheckReminders error: " + t);
        }
    }

    private static void postReminderNotification(Context ctx, String text) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            String channelId = "zalocn_reminder";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        channelId, "Zalo 汉化待办提醒", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(ctx, channelId);
            } else {
                b = new Notification.Builder(ctx);
            }
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Zalo 汉化 · 待办提醒")
                    .setContentText(text)
                    .setAutoCancel(true);
            nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), b.build());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " postReminderNotification fail: " + t);
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    private static Handler mainHandler() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        return mainHandler;
    }

    /** 懒取模块本地存储（用宿主进程的 Context，存在 Zalo 自身 prefs 里）。 */
    private static TranslateStore storeFor(Object anyView) {
        try {
            if (store == null) {
                Context ctx = ((View) anyView).getContext();
                if (ctx != null) {
                    store = new TranslateStore(ctx.getApplicationContext());
                }
            }
            return store;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " storeFor fail: " + t);
            return null;
        }
    }

    /** 沿父链找 ChatView（输入框/发送键的宿主）。 */
    private static Object findChatViewAncestor(View v) {
        try {
            View p = v;
            for (int i = 0; i < 12 && p != null; i++) {
                if (clsChatView != null && clsChatView.isInstance(p)) {
                    return p;
                }
                Object parent = p.getParent();
                p = parent instanceof View ? (View) parent : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 上下文条目：seq 为全局递增序号，text 为原文。 */
    private static class CtxEntry {
        final long seq;
        final String text;

        CtxEntry(long seq, String text) {
            this.seq = seq;
            this.text = text;
        }
    }

    /**
     * 记录一条原文进会话上下文。
     * 按消息键去重：同一条消息重复绑定（RecyclerView 复用/重绑）不再重复记录。
     *
     * 排序说明（局限）：逆向文档里没有可用的消息时间戳字段——vv3.k 是否为
     * 时间戳未锁定（仅一处区间比较证据），MessageId.u()/w() 是 clientMsgId/
     * globalMsgId 的数字解析、不是时间戳。故用全局递增序号排序；上翻历史
     * 混进旧消息时，序号仍能保证取到的是最近绑定的 N 条，而不是队列顺序。
     */
    private static void recordRecent(String threadId, String msgKey, String text) {
        if (threadId == null || msgKey == null || text == null) {
            return;
        }
        LinkedHashMap<String, CtxEntry> m = recentByThread.get(threadId);
        if (m == null) {
            if (recentByThread.size() >= 8) {
                String first = null;
                for (String k : recentByThread.keySet()) {
                    first = k;
                    break;
                }
                if (first != null) {
                    recentByThread.remove(first);
                }
            }
            m = new LinkedHashMap<String, CtxEntry>();
            recentByThread.put(threadId, m);
        }
        synchronized (m) {
            if (m.containsKey(msgKey)) {
                return; // 同一条消息重复绑定，不重复记录
            }
            m.put(msgKey, new CtxEntry(ctxSeq.incrementAndGet(), text));
            while (m.size() > CONTEXT_SIZE) {
                String eldest = null;
                for (String k : m.keySet()) {
                    eldest = k;
                    break;
                }
                if (eldest == null) {
                    break;
                }
                m.remove(eldest);
            }
        }
    }

    /** 取同一会话最近 CONTEXT_SIZE 条原文（按序号取最近的，排除当前句）。 */
    private static String recentContext(String threadId, String current) {
        if (threadId == null) {
            return null;
        }
        LinkedHashMap<String, CtxEntry> m = recentByThread.get(threadId);
        if (m == null || m.isEmpty()) {
            return null;
        }
        List<CtxEntry> entries;
        synchronized (m) {
            entries = new ArrayList<CtxEntry>(m.values());
        }
        // 按序号排序（不只依赖队列顺序），取最近 N 条
        Collections.sort(entries, new java.util.Comparator<CtxEntry>() {
            @Override
            public int compare(CtxEntry a, CtxEntry b) {
                return a.seq < b.seq ? -1 : (a.seq > b.seq ? 1 : 0);
            }
        });
        int from = Math.max(0, entries.size() - CONTEXT_SIZE);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < entries.size(); i++) {
            String s = entries.get(i).text;
            if (current != null && current.equals(s)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 最近发出的一条文本（带发出时间，供回显判定）。 */
    private static class SentEcho {
        final String text;
        final long at;

        SentEcho(String text, long at) {
            this.text = text;
            this.at = at;
        }
    }

    /** 发送侧记下刚发出的文本（译文或原文都记；回显判定时只看 60 秒内）。 */
    private static void recordSentText(String text) {
        if (text == null || text.length() == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (recentSent) {
            Iterator<SentEcho> it = recentSent.iterator();
            while (it.hasNext()) {
                if (now - it.next().at > SENT_ECHO_TTL_MS) {
                    it.remove();
                }
            }
            while (recentSent.size() >= 10) {
                recentSent.remove(0);
            }
            recentSent.add(new SentEcho(text, now));
        }
    }

    /** 文本是否与 60 秒内发出的某条一致（是 = 自己消息的回显）。 */
    private static boolean isSelfEcho(String text) {
        if (text == null || text.length() == 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        synchronized (recentSent) {
            Iterator<SentEcho> it = recentSent.iterator();
            while (it.hasNext()) {
                SentEcho e = it.next();
                if (now - e.at > SENT_ECHO_TTL_MS) {
                    it.remove();
                } else if (e.text.equals(text)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 是否含汉字（CJK 统一表意文字区）。 */
    static boolean containsHan(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if ((cp >= 0x4E00 && cp <= 0x9FFF)
                    || (cp >= 0x3400 && cp <= 0x4DBF)
                    || (cp >= 0xF900 && cp <= 0xFAFF)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    // ---- 反射小工具（全部内部 try/catch，失败返回 null/默认值） ----

    /** 反射调方法（不用 XposedHelpers.callMethod：模块自带 stub 里没有它，
     *  运行时框架版签名也可能因版本而异；java.lang.reflect 永远可用）。 */
    static Object callMethod(Object obj, String name, Object... args) throws Exception {
        Class<?> c = obj.getClass();
        Method m = null;
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] == null ? Object.class : args[i].getClass();
            }
            m = c.getMethod(name, types);
        } catch (NoSuchMethodException e) {
            for (Method dm : c.getDeclaredMethods()) {
                if (dm.getName().equals(name) && dm.getParameterTypes().length == args.length) {
                    m = dm;
                    break;
                }
            }
            if (m == null) {
                throw e;
            }
        }
        m.setAccessible(true);
        return m.invoke(obj, args);
    }

    static Object getField(Object obj, String name) {
        try {
            Field f = obj.getClass().getField(name);
            return f.get(obj);
        } catch (Throwable t) {
            try {
                Field f = obj.getClass().getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    static void setField(Object obj, String name, Object value) throws Exception {
        try {
            Field f = obj.getClass().getField(name);
            f.set(obj, value);
        } catch (Throwable t) {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(obj, value);
        }
    }

    static int staticInt(String clsName, String field, int def) {
        try {
            Class<?> c = Class.forName(clsName, false, zaloCl);
            Field f = c.getField(field);
            return f.getInt(null);
        } catch (Throwable t) {
            try {
                Class<?> c = Class.forName(clsName, false, zaloCl);
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                return f.getInt(null);
            } catch (Throwable t2) {
                XposedBridge.log(TAG + " staticInt " + clsName + "." + field + " fail");
                return def;
            }
        }
    }
}
