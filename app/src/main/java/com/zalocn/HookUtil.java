package com.zalocn;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * hook 辅助。
 *
 * XposedHelpers.findAndHookMethod 在部分框架（如 Vector 的 legacy 桥）里只在目标类自身
 * 上找方法，不搜索父类，导致 hook Application#attachBaseContext、View#onAttachedToWindow
 * 这类「声明在父类」的方法时抛 NoSuchMethodError，进而让整段安装逻辑中断。
 * 这里自己沿父类链查找，找到就 hook，找不到返回 false 交给调用方决定。
 *
 * 3.0.9：把失败原因打出来（此前静默 return false，Resources 四个 hook 全报
 * "method absent" 却查不到原因），并在 XposedBridge.hookMethod 失败时用
 * XposedHelpers.findAndHookMethod 兜底再试一次。
 */
final class HookUtil {

    private static final String TAG = "[ZALO-ZH] ";

    private HookUtil() {
    }

    static boolean hookDeep(Class<?> clazz, String name, Class<?>[] paramTypes, XC_MethodHook callback) {
        if (clazz == null) {
            return false;
        }
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            Method m;
            try {
                m = c.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                continue;
            } catch (Throwable t) {
                XposedBridge.log(TAG + "getDeclaredMethod error " + c.getName() + "." + name + ": " + t);
                continue;
            }
            if (m == null) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, callback);
                return true;
            } catch (Throwable t) {
                XposedBridge.log(TAG + "hookMethod failed " + c.getName() + "." + name
                        + " modifiers=0x" + Integer.toHexString(m.getModifiers())
                        + " cl=" + c.getClassLoader() + " err=" + t);
                try {
                    // findAndHookMethod 是 varargs(Object...)，直接传 Class[] 会被当成
                    // 一个参数，必须展开成 Object[]{Class, Class, ..., XC_MethodHook}。
                    Object[] args = new Object[paramTypes.length + 1];
                    System.arraycopy(paramTypes, 0, args, 0, paramTypes.length);
                    args[paramTypes.length] = callback;
                    XposedHelpers.findAndHookMethod(c, name, args);
                    XposedBridge.log(TAG + "findAndHookMethod fallback OK " + c.getName() + "." + name);
                    return true;
                } catch (Throwable t2) {
                    XposedBridge.log(TAG + "findAndHookMethod fallback failed " + c.getName() + "." + name + ": " + t2);
                    return false;
                }
            }
        }
        XposedBridge.log(TAG + "method really absent: " + clazz.getName() + "." + name
                + " params=" + java.util.Arrays.toString(paramTypes));
        return false;
    }
}
