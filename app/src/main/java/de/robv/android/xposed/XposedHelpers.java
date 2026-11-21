package de.robv.android.xposed;

public final class XposedHelpers {

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    // 编译期桩：运行时由 LSPosed 的真实实现提供（与 findAndHookMethod 同理）。
    public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> clazz, Object... parameterTypesAndCallback) {
        return null;
    }
}
