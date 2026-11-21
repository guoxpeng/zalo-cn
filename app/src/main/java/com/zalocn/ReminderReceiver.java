package com.zalocn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import de.robv.android.xposed.XposedBridge;

/**
 * 待办提醒的到点触发器（AlarmManager 到点发广播到这里）。
 *
 * 提醒内容放在 Intent extras 里（text / id），不依赖跨进程存储：
 * 闹钟由 Zalo 进程内的 MessageHook 排期，触发时系统把 Intent 原样送达。
 * 收到后只发一条通知（标题写明是模块提醒），不做其他事。
 */
public class ReminderReceiver extends BroadcastReceiver {

    static final String TAG = "[ZALO-ZH]";
    /** 只有带这个 Action 的广播才处理（闹钟 PendingIntent 里会写上）。 */
    static final String ACTION_REMINDER = "com.zalocn.ACTION_REMINDER";
    static final String EXTRA_TEXT = "text";
    static final String EXTRA_ID = "id";
    static final String CHANNEL_ID = "zalocn_reminder";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null || !ACTION_REMINDER.equals(intent.getAction())) {
                return;
            }
            String text = intent.getStringExtra(EXTRA_TEXT);
            if (text == null || text.length() == 0) {
                return;
            }
            postNotification(context, text);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " ReminderReceiver fail: " + t);
        }
    }

    private static void postNotification(Context ctx, String text) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Zalo 汉化待办提醒", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(ctx, CHANNEL_ID);
            } else {
                b = new Notification.Builder(ctx);
            }
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Zalo 汉化 · 待办提醒")
                    .setContentText(text)
                    .setAutoCancel(true);
            nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), b.build());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " ReminderReceiver notify fail: " + t);
        }
    }
}
