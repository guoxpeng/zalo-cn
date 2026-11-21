package de.robv.android.xposed;

import android.content.SharedPreferences;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * XSharedPreferences 编译 stub（与 LSPosed 运行时 API 同名同签名）。
 *
 * 运行时 LSPosed 会用它自己的实现（API 93+ 新版：读模块声明了
 * xposedsharedprefs 后由 LSPosed 重定向的共享 prefs，不走旧硬编码路径）。
 * 本 stub 只为编译存在：内部用直接读 XML 解析实现，供老设备/老 LSPosed
 * 下运行时类缺失时尽力回退；正常情况不会走到这里。
 *
 * 注意：不要在这里加业务逻辑；跨进程配置读取的唯一入口是
 * com.zalocn.TranslateConfig.loadForHook()。
 */
public class XSharedPreferences implements SharedPreferences {

    private final File mFile;
    private Map<String, Object> mMap = new HashMap<String, Object>();
    private long mMtime = -1;
    private boolean mLoaded = false;
    private final Set<OnSharedPreferenceChangeListener> mListeners =
            new HashSet<OnSharedPreferenceChangeListener>();

    public XSharedPreferences(String packageName) {
        this(packageName, packageName + "_preferences");
    }

    public XSharedPreferences(String packageName, String prefFileName) {
        mFile = new File("/data/data/" + packageName + "/shared_prefs/" + prefFileName + ".xml");
    }

    /** prefs 文件对象（与运行时 API 同名；stub 下就是硬编码路径的文件）。 */
    public File getFile() {
        return mFile;
    }

    /** 文件自上次 reload 后是否变化（mtime 比较）。 */
    public boolean hasFileChanged() {
        if (!mLoaded) {
            return true;
        }
        long mtime = mFile.exists() ? mFile.lastModified() : -1;
        return mtime != mMtime;
    }

    /** 重新从磁盘读一遍 prefs 文件。 */
    public synchronized void reload() {
        Map<String, Object> map = new HashMap<String, Object>();
        if (mFile.exists()) {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(mFile);
                XmlPullParser p = Xml.newPullParser();
                p.setInput(fis, "UTF-8");
                int ev = p.getEventType();
                String curName = null;
                String curTag = null;
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        String tag = p.getName();
                        if ("string".equals(tag) || "boolean".equals(tag)
                                || "int".equals(tag) || "long".equals(tag)
                                || "float".equals(tag)) {
                            curName = p.getAttributeValue(null, "name");
                            curTag = tag;
                            if ("boolean".equals(tag)) {
                                map.put(curName, Boolean.valueOf(p.getAttributeValue(null, "value")));
                                curName = null;
                                curTag = null;
                            } else if ("int".equals(tag)) {
                                map.put(curName, Integer.valueOf(p.getAttributeValue(null, "value")));
                                curName = null;
                                curTag = null;
                            } else if ("long".equals(tag)) {
                                map.put(curName, Long.valueOf(p.getAttributeValue(null, "value")));
                                curName = null;
                                curTag = null;
                            } else if ("float".equals(tag)) {
                                map.put(curName, Float.valueOf(p.getAttributeValue(null, "value")));
                                curName = null;
                                curTag = null;
                            }
                        } else {
                            curName = null;
                            curTag = null;
                        }
                    } else if (ev == XmlPullParser.TEXT) {
                        if (curName != null && "string".equals(curTag)) {
                            map.put(curName, p.getText());
                            curName = null;
                            curTag = null;
                        }
                    }
                    ev = p.next();
                }
            } catch (Throwable ignored) {
            } finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        mMap = map;
        mMtime = mFile.exists() ? mFile.lastModified() : -1;
        mLoaded = true;
    }

    private synchronized void ensureLoaded() {
        if (!mLoaded) {
            reload();
        }
    }

    @Override
    public Map<String, ?> getAll() {
        ensureLoaded();
        return new HashMap<String, Object>(mMap);
    }

    @Override
    public String getString(String key, String defValue) {
        ensureLoaded();
        Object v = mMap.get(key);
        return v instanceof String ? (String) v : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        ensureLoaded();
        Object v = mMap.get(key);
        return v instanceof Boolean ? ((Boolean) v).booleanValue() : defValue;
    }

    @Override
    public int getInt(String key, int defValue) {
        ensureLoaded();
        Object v = mMap.get(key);
        return v instanceof Integer ? ((Integer) v).intValue() : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        ensureLoaded();
        Object v = mMap.get(key);
        return v instanceof Long ? ((Long) v).longValue() : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        ensureLoaded();
        Object v = mMap.get(key);
        return v instanceof Float ? ((Float) v).floatValue() : defValue;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, Set<String> defValues) {
        ensureLoaded();
        Object v = mMap.get(key);
        return v instanceof Set ? (Set<String>) v : defValues;
    }

    @Override
    public boolean contains(String key) {
        ensureLoaded();
        return mMap.containsKey(key);
    }

    @Override
    public Editor edit() {
        throw new UnsupportedOperationException("XSharedPreferences is read-only");
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        if (listener != null) {
            mListeners.add(listener);
        }
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        mListeners.remove(listener);
    }
}
