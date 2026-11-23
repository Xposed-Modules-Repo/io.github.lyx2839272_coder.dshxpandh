package io.github.lyx2839272_coder.dshxpandh;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class HookEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String TAG = "core";
    private static final String VER = "v30";
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static int clamp(int v, int lo, int hi, int def) {
        if (v < lo || v > hi) return def;
        return v;
    }
    private static final String[] CAND = {
        "/sdcard/DSH-Output/xhook/cmd.json",
        "/data/local/tmp/cmd.json"
    };

    // 每进程日志总额度，用完即停，防止刷爆 LSPosed 日志
    private static final AtomicInteger LEFT = new AtomicInteger(2000);
    // 采样状态：key -> {windowStart, count}
    private static final Map<String, long[]> CTR = new java.util.concurrent.ConcurrentHashMap<String, long[]>();

    @Override
    public void initZygote(IXposedHookZygoteInit.StartupParam sp) {
        // 无状态，占位以兼容不同框架版本
    }

    private static File findAppCfg(String pkg) {
        String[] cands = {
            "/data/user/0/" + pkg + "/files/xhook.json",
            "/data/data/" + pkg + "/files/xhook.json"
        };
        for (String p : cands) {
            try {
                File f = new File(p);
                if (f.exists() && f.length() > 0) return f;
            } catch (Throwable t) {}
        }
        return null;
    }

    private static File findCmd(String pkg) {
        String[] cands = new String[CAND.length + 2];
        cands[0] = CAND[0];
        cands[1] = "/data/data/" + pkg + "/files/xhook.json";
        cands[2] = "/data/user/0/" + pkg + "/files/xhook.json";
        cands[CAND.length + 1] = CAND[1];
        for (int i = 3; i < CAND.length + 1; i++) cands[i] = CAND[i - 2];
        for (String p : cands) {
            try {
                File f = new File(p);
                if (f.exists() && f.length() > 0) return f;
            } catch (Throwable t) {}
        }
        return null;
    }

    // 采样：同一 key 在 windowMs 内最多 limit 次
    private static boolean allow(String key, int limit, long windowMs) {
        if (limit <= 0) return false;
        long now = System.currentTimeMillis();
        long[] st = CTR.get(key);
        if (st == null) {
            st = new long[]{now, 1L};
            CTR.put(key, st);
            return true;
        }
        synchronized (st) {
            if (now - st[0] >= windowMs) { st[0] = now; st[1] = 1L; return true; }
            if (st[1] < limit) { st[1]++; return true; }
            return false;
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        String pkg = "?";
        try {
            if (lp == null) return;
            pkg = String.valueOf(lp.packageName);
            if (lp.classLoader == null) return;
            emit(pkg, "attached " + safe(lp.processName));
            // 优先级：App 专属外部 > App 专属内置 > 全局外部 > 全局内置
            // 全局 /sdcard 放最后，否则一个文件会盖掉所有 App 的专属配置
            String src = null;
            try {
                String u0 = "/data/user/0/" + pkg + "/files/xhook.json";
                String dd = "/data/data/" + pkg + "/files/xhook.json";
                File fu = new File(u0), fd = new File(dd);
                emit(pkg, "probe u0=" + fu.exists() + "/" + fu.length() + " dd=" + fd.exists() + "/" + fd.length());
            } catch (Throwable t) { emit(pkg, "probe-err " + t.getClass().getSimpleName()); }
            File f = findAppCfg(pkg);
            if (f != null) { String t = readAll(f); if (t != null && t.length() > 0) src = "#ext:" + t; }
            // 用户自己的配置：/sdcard/Download/xhook/<包名>.json，优先于内置
            if (src == null) {
                String[] mine = {
                    "/sdcard/Download/xhook/" + pkg + ".json",
                    "/sdcard/xhook/" + pkg + ".json"
                };
                for (String mp : mine) {
                    try {
                        File mf = new File(mp);
                        if (mf.exists() && mf.length() > 0) {
                            String t = readAll(mf);
                            if (t != null && t.length() > 0) { src = "#usr:" + t; break; }
                            emit(pkg, "usr-noperm " + mp);
                        }
                    } catch (Throwable t) {}
                }
            }
            if (src == null) {
                String a = readAsset("assets/profiles/" + pkg + ".json");
                if (a != null) src = "#own:" + a;
            }
            if (src == null) {
                String[] globals = {
                    "/sdcard/DSH-Output/xhook/config.json",
                    "/sdcard/xhook/config.json",
                    "/sdcard/Download/xhook.json",
                    "/data/local/tmp/xhook.json"
                };
                for (String gp : globals) {
                    try {
                        File gf = new File(gp);
                        if (gf.exists() && gf.length() > 0) {
                            String t = readAll(gf);
                            if (t != null && t.length() > 0) { src = "#glo:" + t; break; }
                        }
                    } catch (Throwable t) {}
                }
            }
            if (src == null) {
                String a = readAsset("assets/profiles/default.json");
                if (a != null) src = "#def:" + a;
            }
            if (src == null) { emit(pkg, "no cfg"); return; }
            String srcTag = src.substring(0, 4);
            Object root = MiniJson.parse(src.substring(5));
            if (!(root instanceof Map)) { emit(pkg, "bad json " + srcTag); return; }
            Map<String, Object> cfg = new java.util.HashMap<String, Object>();
            for (Map.Entry<?, ?> en : ((Map<?, ?>) root).entrySet()) {
                cfg.put(String.valueOf(en.getKey()), en.getValue());
            }
            // 配置里写 "recipe": "名称" 即套用 APK 内置配方

            String recName = s(cfg, "recipe");
            if (recName.length() > 0) {
                String rr = readAsset("assets/recipes/" + recName + ".json");
                if (rr == null) emit(pkg, "recipe-miss " + recName);
                else {
                    Object ro = MiniJson.parse(rr);
                    if (ro instanceof Map) {
                        Object rh = ((Map<?, ?>) ro).get("hooks");
                        if (rh instanceof List) {
                            List<Object> merged = new ArrayList<Object>();
                            Object oh = cfg.get("hooks");
                            if (oh instanceof List) merged.addAll((List<?>) oh);
                            merged.addAll((List<?>) rh);
                            cfg.put("hooks", merged);
                            emit(pkg, "recipe " + recName + " +" + ((List<?>) rh).size());
                        }
                    }
                }
            }
            List<?> targets = list(cfg, "enable_for");
            if (targets != null && targets.size() > 0) {
                boolean hit = false;
                for (Object t : targets) { if (pkg.equals(String.valueOf(t))) { hit = true; break; } }
                if (!hit) { emit(pkg, "not enabled"); return; }
            }
            // 进程过滤：默认只主进程，省掉子进程重复 hook
            String pmode = s(cfg, "process");
            if (pmode.length() == 0) pmode = "main";
            if (pmode.equals("main") && lp.processName != null && !lp.processName.equals(pkg)) {
                emit(pkg, "skip subproc " + lp.processName);
                return;
            }
            int ml = clamp(n(cfg, "max_logs", 2000), 1, 100000, 2000);
            LEFT.set(ml);
            int sample = clamp(n(cfg, "sample", 5), 1, 100, 5);
            int win = clamp(n(cfg, "window_ms", 1000), 100, 60000, 1000);
            // head/tail 优先；旧配置只写 max_len 时按对半折算，保持向后兼容
            int head = n(cfg, "head", -1);
            int tail = n(cfg, "tail", -1);
            int legacy = n(cfg, "max_len", -1);
            if (head < 0) head = legacy > 0 ? legacy / 2 : 200;
            if (tail < 0) tail = legacy > 0 ? legacy / 2 : 200;
            head = clamp(head, 0, 512, 200);
            tail = clamp(tail, 0, 512, 200);
            int nh = 0;
            List<?> hooks = list(cfg, "hooks");
            if (hooks != null) {
                for (Object o : hooks) {
                    if (!(o instanceof Map)) continue;
                    try { installHook(pkg, lp.classLoader, (Map<?, ?>) o, sample, win, head, tail); nh++; }
                    catch (Throwable t) { emit(pkg, "hook-err " + t); }
                }
            }
            // actions 需要 Context（CookieManager 靠它初始化），handleLoadPackage 时 Application 还没建
            final String fpkg = pkg;
            final ClassLoader fcl = lp.classLoader;
            final List<?> acts = list(cfg, "actions");
            if (acts != null && acts.size() > 0) {
                try {
                    XposedHelpers.findAndHookMethod("android.app.Application", lp.classLoader, "onCreate",
                        new XC_MethodHook() {
                            protected void afterHookedMethod(MethodHookParam mp) {
                                try {
                                    for (Object o : acts) {
                                        if (!(o instanceof Map)) continue;
                                        try { runAction(fpkg, fcl, (Map<?, ?>) o, mp.thisObject); }
                                        catch (Throwable t) { emit(fpkg, "act-err " + t); }
                                    }
                                } catch (Throwable t) { emit(fpkg, "act-loop " + t); }
                            }
                        });
                } catch (Throwable t) { emit(pkg, "act-hook-fail " + t.getClass().getSimpleName()); }
            }
            emit(pkg, "ready " + VER + " " + srcTag + " hooks=" + nh + " sample=" + sample + "/" + win + "ms head=" + head + " tail=" + tail);
        } catch (Throwable t) {
            emit(pkg, "fatal " + t);
        }
    }
    private void installHook(final String pkg, ClassLoader cl, Map<?, ?> h,
                            final int sample, final long win, final int head, final int tail) {
        String mtd = s(h, "method");
        if (mtd.length() == 0) return;
        List<?> setList = list(h, "set");
        Map<?, ?> whenMap = m(h, "when");
        List<?> clsList = list(h, "classes");
        if (clsList != null && clsList.size() > 0) {
            for (Object co : clsList) {
                String c = String.valueOf(co);
                if (c.length() > 0) hookOne(pkg, cl, c, mtd, s(h, "mode"), s(h, "return_value"), setList, whenMap, sample, win, head, tail);
            }
            return;
        }
        String cls = s(h, "class");
        if (cls.length() == 0) return;
        hookOne(pkg, cl, cls, mtd, s(h, "mode"), s(h, "return_value"), setList, whenMap, sample, win, head, tail);
    }

    private void hookOne(final String pkg, ClassLoader cl, final String cls, final String mtd,
                         final String mode, final String ret, final List<?> setList, final Map<?, ?> whenMap,
                         final int sample, final long win, final int head, final int tail) {
        Class<?> c = null;
        try { c = XposedHelpers.findClass(cls, cl); }
        catch (Throwable t1) {
            // 降级：按短名在 classLoader 里找
            try { c = Class.forName(cls, false, cl); } catch (Throwable t2) { emit(pkg, "class-miss " + cls); return; }
        }
        final String key = cls + "." + mtd;
        int cnt = 0;
        for (final Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(mtd)) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (mode.equals("args")) { applyArgs(pkg, key, p, setList, sample, win); return; }
                            // when: 只在指定参数含关键词时改写，否则放行
                            if (whenMap != null && whenMap.size() > 0 && !matchWhen(p.args, whenMap)) return;
                            if (!allow(key, sample, win)) return;
                            emit(pkg, "IN " + key + "(" + arr(p.args, head, tail) + ")");
                        } catch (Throwable t) {}
                    }
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (mode.equals("return") && ret.length() > 0) {
                                if (allow(key, sample, win)) {
                                    p.setResult(cast(p.getResult(), ret));
                                    emit(pkg, "OVR " + key + " -> " + ret);
                                }
                                return;
                            }
                            if (!allow(key, sample, win)) return;
                            emit(pkg, "OUT " + key + " -> " + brief(p.getResult(), head, tail));
                        } catch (Throwable t) {}
                    }
                });
                cnt++;
            } catch (Throwable t) {
                emit(pkg, "hook-conflict " + key + " " + t);
            }
        }
        emit(pkg, "hooked " + key + " x" + cnt);
    }

    private void runAction(String pkg, ClassLoader cl, Map<?, ?> a, Object ctx) {
        String type = s(a, "type");
        try {
            if (type.equals("call")) {
                String cls = s(a, "class");
                String mtd = s(a, "method");
                Class<?> c = XposedHelpers.findClass(cls, cl);
                Object v = null;
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(mtd)) continue;
                    if (m.getParameterTypes().length != 0) continue;
                    m.setAccessible(true);
                    v = m.invoke(null);
                    break;
                }
                emit(pkg, "ACT " + cls + "." + mtd + " -> " + brief(v, 300, 300));
            } else if (type.equals("cookie")) {
                String url = s(a, "url");
                if (url.length() == 0) url = "https://www.google.com";
                Class<?> cm = XposedHelpers.findClass("android.webkit.CookieManager", cl);
                Object inst;
                try { inst = XposedHelpers.callStaticMethod(cm, "getInstance", ctx); }
                catch (Throwable t1) { inst = XposedHelpers.callStaticMethod(cm, "getInstance"); }
                Object v = XposedHelpers.callMethod(inst, "getCookie", url);
                emit(pkg, "COOKIE " + url + " -> " + brief(v, 400, 400));
            }
        } catch (Throwable t) {
            emit(pkg, "act-fail " + type + " " + t);
        }
    }

    private static Map<?, ?> m(Map<?, ?> src, String k) {
        Object o = src == null ? null : src.get(k);
        return o instanceof Map ? (Map<?, ?>) o : null;
    }

    private static boolean matchWhen(Object[] args, Map<?, ?> w) {
        if (args == null) return false;
        int i = n(w, "i", -1);
        if (i < 0 || i >= args.length) return false;
        String want = s(w, "contains");
        Object a = args[i];
        String got = a == null ? "" : String.valueOf(a);
        if (want.length() == 0) return true;
        return got.contains(want);
    }

    // 改入参：按 set 列表逐位替换，记录改动前后
    private static void applyArgs(String pkg, String key, XC_MethodHook.MethodHookParam p, List<?> setList, int sample, long win) {
        if (p == null || p.args == null || setList == null) return;
        StringBuilder sb = new StringBuilder();
        for (Object o : setList) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            int i = n(m, "i", -1);
            String v = s(m, "v");
            if (i < 0 || i >= p.args.length) { sb.append("bad-idx").append(i).append(" "); continue; }
            Object old = p.args[i];
            try {
                p.args[i] = cast(old, v);
                sb.append(i).append(":[").append(brief(old, 60, 20)).append("]->[").append(v).append("] ");
            } catch (Throwable t) {
                sb.append(i).append(":ERR ").append(t.getClass().getSimpleName()).append(" ");
            }
        }
        if (allow(key + ":arg", sample, win)) emit(pkg, "ARG " + key + " " + sb.toString());
    }

    private static Object cast(Object old, String v) {
        try {
            if (old instanceof Boolean) return Boolean.valueOf(Boolean.parseBoolean(v));
            if (old instanceof Integer) return Integer.valueOf(Integer.parseInt(v));
            if (old instanceof Long) return Long.valueOf(Long.parseLong(v));
            if (old instanceof Short) return Short.valueOf(Short.parseShort(v));
            if (old instanceof Byte) return Byte.valueOf(Byte.parseByte(v));
            if (old instanceof Float) return Float.valueOf(Float.parseFloat(v));
            if (old instanceof Double) return Double.valueOf(Double.parseDouble(v));
            if (old instanceof byte[]) return v.getBytes("UTF-8");
            return v;
        } catch (Throwable t) { return v; }
    }

    private static String arr(Object[] a, int head, int tail) {
        if (a == null || a.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(", "); sb.append(brief(a[i], head, tail)); }
        return sb.toString();
    }

    // 大 buffer 只取 head + tail，中间省略，长度照实报告
    private static String brief(Object o, int head, int tail) {
        if (o == null) return "null";
        try {
            if (o instanceof byte[]) {
                byte[] b = (byte[]) o;
                if (b.length <= head + tail) return hexish(b, 0, b.length);
                return "b[" + b.length + "] " + hexish(b, 0, head) + " ...(+ " + (b.length - head - tail) + " B)... " + hexish(b, b.length - tail, tail);
            }
            if (o.getClass().isArray()) return "arr[" + Array.getLength(o) + "]";
            String v = String.valueOf(o);
            if (v.length() <= head + tail) return v;
            return v.substring(0, head) + " ...(+ " + (v.length() - head - tail) + " chars)... " + v.substring(v.length() - tail);
        } catch (Throwable t) { return "?"; }
    }

    private static String hexish(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len && i < b.length; i++) {
            int v = b[i] & 0xff;
            if (v >= 32 && v < 127) sb.append((char) v);
            else { sb.append('\\'); sb.append(HEX[v >> 4]); sb.append(HEX[v & 0xf]); }
        }
        return sb.toString();
    }

    private static String safe(String v) { return v == null ? "?" : v; }
    private static String s(Map<?, ?> m, String k) { Object v = m.get(k); return v == null ? "" : String.valueOf(v); }
    private static int n(Map<?, ?> m, String k, int def) { Object v = m.get(k); return v instanceof Number ? ((Number) v).intValue() : def; }
    private static List<?> list(Map<?, ?> m, String k) { Object v = m.get(k); return v instanceof List ? (List<?>) v : null; }

    // 读 APK 内置配置（assets/profiles/*.json）
    private static String readAsset(String path) {
        java.io.InputStream is = null;
        try {
            is = HookEntry.class.getClassLoader().getResourceAsStream(path);
            if (is == null) return null;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            String s = new String(bo.toByteArray(), "UTF-8");
            return s.length() == 0 ? null : s;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (is != null) is.close(); } catch (Throwable t) {}
        }
    }

    private static String readAll(File f) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = br.readLine()) != null) sb.append(ln);
            br.close();
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    // 总额度控制：日志不落盘，只走 XposedBridge.log，且条数封顶
    private static void emit(String pkg, String msg) {
        int left = LEFT.decrementAndGet();
        if (left < 0) return;
        if (left == 0) {
            try { XposedBridge.log(TAG + " budget exhausted"); } catch (Throwable t) {}
        }
        try { XposedBridge.log(TAG + " [" + pkg + "] " + msg); } catch (Throwable t) {}
    }
    static class MiniJson {
        private final String s;
        private int i;
        private MiniJson(String t) { s = t; }
        static Object parse(String t) {
            try { MiniJson p = new MiniJson(t); p.ws(); return p.val(); } catch (Throwable e) { return null; }
        }
        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private Object val() {
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            return lit();
        }
        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (i < s.length()) {
                ws();
                if (i < s.length() && s.charAt(i) == '}') { i++; break; }
                String k = str();
                ws();
                if (i < s.length() && s.charAt(i) == ':') i++;
                ws();
                m.put(k, val());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; break; }
                break;
            }
            return m;
        }
        private List<Object> arr() {
            List<Object> l = new ArrayList<Object>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (i < s.length()) {
                ws();
                if (i < s.length() && s.charAt(i) == ']') { i++; break; }
                l.add(val());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; break; }
                break;
            }
            return l;
        }
        private String str() {
            StringBuilder sb = new StringBuilder();
            if (i < s.length() && s.charAt(i) == '"') i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '\\') {
                    if (i >= s.length()) break;
                    char e = s.charAt(i++);
                    if (e == 'n') sb.append('\n');
                    else if (e == 't') sb.append('\t');
                    else if (e == 'r') sb.append('\r');
                    else if (e == 'u' && i + 4 <= s.length()) {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    } else sb.append(e);
                } else if (c == '"') break;
                else sb.append(c);
            }
            return sb.toString();
        }
        private Object lit() {
            int st = i;
            while (i < s.length() && ",}] ".indexOf(s.charAt(i)) < 0) i++;
            String t = s.substring(st, i).trim();
            if (t.equals("true")) return Boolean.TRUE;
            if (t.equals("false")) return Boolean.FALSE;
            if (t.equals("null")) return null;
            try { return Double.valueOf(t); } catch (Throwable e) { return t; }
        }
    }
}