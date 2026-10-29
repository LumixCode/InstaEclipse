package ps.reso.instaeclipse.xposedcompat;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {

    public static final int PRIORITY_DEFAULT = 50;

    public final int priority;

    public XC_MethodHook() {
        this(PRIORITY_DEFAULT);
    }

    public XC_MethodHook(int priority) {
        this.priority = priority;
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public final void callBefore(MethodHookParam param) throws Throwable {
        beforeHookedMethod(param);
    }

    public final void callAfter(MethodHookParam param) throws Throwable {
        afterHookedMethod(param);
    }

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;

        private Object result;
        private Throwable throwable;
        public boolean returnEarly;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        public void setOutcome(Object result, Throwable throwable) {
            this.result = result;
            this.throwable = throwable;
        }
    }

    public interface Unhook {
        Member getHookedMethod();

        void unhook();
    }
}
