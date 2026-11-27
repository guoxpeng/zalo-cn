package de.robv.android.xposed;

import java.lang.reflect.Member;

public final class XposedBridge {

    // 编译期桩：HookUtil 沿父类链找到 Method 后直接用真实框架的 hookMethod。
    //
    // ⚠ 参数必须是 Member，不能是 Method：
    // 真实 API（api-82 / LSPosed / Vector）签名是 hookMethod(Member, XC_MethodHook)，
    // 这里若写成 Method，编译出的调用点描述符是 (Ljava/lang/reflect/Method;...)，
    // 运行时会抛 NoSuchMethodError，导致 Resources.getText 等 hook 全部静默失效。
    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        return null;
    }

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }
}
