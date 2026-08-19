package de.robv.android.xposed.callbacks;

public abstract class XCallback implements Comparable<XCallback> {
    public int priority;

    @Override
    public int compareTo(XCallback another) {
        return 0;
    }
}
