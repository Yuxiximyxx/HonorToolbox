package io.github.yuxiximyxx.honortoolbox.lockpanel;
/*
 * 移植自 LockPanel（作者 chaosz）v1.20 的 Log：异步日志队列
 * 与警告去重逻辑零改，标签仍为 LockPanel（与独立版日志可
 * 对照）。
 */

import de.robv.android.xposed.XposedBridge;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/* JADX INFO: loaded from: classes.dex */
public final class Log {
    private static final int MAX_SEEN_WARNINGS = 64;
    private static final BlockingQueue<String> QUEUE = new ArrayBlockingQueue(512);
    private static final Set<String> SEEN_WARNINGS = new HashSet();
    public static final String TAG = "LockPanel";
    private static volatile boolean sThreadStarted;

    private Log() {
    }

    public static void i(String str) {
        enqueue("I", str);
    }

    public static void w(String str) {
        synchronized (SEEN_WARNINGS) {
            if (SEEN_WARNINGS.contains(str)) {
                return;
            }
            if (SEEN_WARNINGS.size() < MAX_SEEN_WARNINGS) {
                SEEN_WARNINGS.add(str);
            }
            enqueue("W", str);
        }
    }

    private static void enqueue(String str, String str2) {
        ensureThread();
        QUEUE.offer(str + " " + str2);
    }

    private static synchronized void ensureThread() {
        if (sThreadStarted) {
            return;
        }
        sThreadStarted = true;
        Thread thread = new Thread(new Runnable() { // from class: com.chaosz.lockpanel.Log.1
            @Override // java.lang.Runnable
            public void run() {
                while (true) {
                    try {
                        XposedBridge.log("[LockPanel] " + ((String) Log.QUEUE.take()));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "LockPanelLog");
        thread.setDaemon(true);
        thread.start();
    }
}
