package com.superboyyy.mosinputglass;

import java.lang.reflect.Executable;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;

/**
 * before/after 风格的 hook。
 *
 * 荣耀工具箱合入适配（v1.8.0）：原版基于 libxposed 的
 * intercept(chain)，合入后改用传统 Xposed API 的
 * XC_MethodHook.before/afterHookedMethod 等价实现——
 * before 里 setResult 即 param.setResult（框架跳过原方法），
 * args 与 param.args 是同一个数组、原地改动直接生效；
 * after 里能看到/改返回值（param.result 回写）。原方法由
 * 框架直通调用，不经任何手写 proceed/反射原路返回，
 * 不存在反射再进分发的递归问题。before/after 自己抛异常
 * 只记日志，不影响原方法。
 *
 * 同一个 P 要在 before/after 之间传递（含 setObjectExtra
 * 暂存的数据）：用线程内栈配对——Xposed 的 before/after
 * 恒在同一线程成对回调且可嵌套（一个被 hook 的方法里再
 * 调另一个被 hook 的方法），栈式存取与嵌套顺序一致。
 */
abstract class Hook {
    protected void before(P p) throws Throwable {}

    protected void after(P p) throws Throwable {}

    static final class P {
        Object thisObject;
        Object[] args;
        Executable method;
        private Object result;
        private boolean returnEarly;
        private Map<String, Object> extras;

        void setResult(Object r) {
            result = r;
            returnEarly = true;
        }

        Object getResult() {
            return result;
        }

        void setObjectExtra(String k, Object v) {
            if (extras == null) extras = new HashMap<>(4);
            extras.put(k, v);
        }

        Object getObjectExtra(String k) {
            return extras == null ? null : extras.get(k);
        }
    }

    private static final ThreadLocal<ArrayDeque<P>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    final XC_MethodHook xposedHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                P p = new P();
                p.thisObject = param.thisObject;
                p.args = param.args;
                p.method = (Executable) param.method;
                STACK.get().push(p);
                try {
                    before(p);
                } catch (Throwable t) {
                    Main.log("hook before " + p.method.getName() + " failed: " + t);
                }
                if (p.returnEarly) param.setResult(p.result);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                P p = STACK.get().poll();
                if (p == null) {
                    p = new P();
                    p.thisObject = param.thisObject;
                    p.args = param.args;
                    p.method = (Executable) param.method;
                }
                // 原方法抛异常：原版 chain 里 proceed 抛错、
                // after 不跑、异常原样抛给调用方——这里同口径
                // 跳过 after 且不清异常。
                if (param.hasThrowable()) return;
                // 原方法（或 before 的 setResult）产生的结果。
                p.result = param.getResult();
                try {
                    after(p);
                } catch (Throwable t) {
                    Main.log("hook after " + p.method.getName() + " failed: " + t);
                }
                param.setResult(p.result);
            }
        };
    }
}
