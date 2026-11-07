package ps.reso.instaeclipse.utils.dialog;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.IePalette;

import static ps.reso.instaeclipse.utils.dialog.IeRows.dp;

public final class IeSheet {

    static final int MAX_WIDTH_DP = 640;
    private static final float MAX_HEIGHT = 0.82f;
    private static final float DRAG_TO_CLOSE = 0.3f;
    private static final long SLIDE_MS = 220;
    private static final long FLASH_MS = 1400;
    private static final int REVEAL_TRIES = 6;

    private static IeSheet current;
    private static Application reopenApp;
    private static Application.ActivityLifecycleCallbacks reopenCallbacks;

    public static final class Page {
        final String title;
        final View content;
        View end;
        View footer;
        View pinned;
        Runnable onReturn;
        BooleanSupplier onBack;
        ScrollView scroll;

        public Page(String title, View content) {
            this.title = title == null ? "" : title;
            this.content = content;
        }

        public Page end(View view) {
            end = view;
            return this;
        }

        public Page footer(View view) {
            footer = view;
            return this;
        }

        public Page onReturn(Runnable action) {
            onReturn = action;
            return this;
        }

        public Page pinned(View view) {
            pinned = view;
            return this;
        }

        public Page onBack(BooleanSupplier handled) {
            onBack = handled;
            return this;
        }
    }

    private final Context context;
    private final Activity activity;
    private final Dialog dialog;
    private final LinearLayout sheet;
    private final ImageView back;
    private final TextView title;
    private final FrameLayout end;
    private final FrameLayout pinned;
    private final FrameLayout pages;
    private final FrameLayout pageFooter;
    private final FrameLayout slot;
    private final View bottom;
    private final ArrayDeque<Page> stack = new ArrayDeque<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refreshBar = this::refreshBar;
    private final Runnable onStatus = () -> {
        main.removeCallbacks(refreshBar);
        main.post(refreshBar);
    };
    private Runnable onExit;

    private IeSheet(Context context) {
        this.context = context;
        this.activity = activityOf(context);
        IePalette p = IePalette.of(context);

        FrameLayout root = new FrameLayout(context) {
            private boolean outsideDown;

            @Override
            protected void onConfigurationChanged(Configuration newConfig) {
                super.onConfigurationChanged(newConfig);
                fitWindow();
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        outsideDown = outsideSheet(event);
                        return outsideDown;
                    case MotionEvent.ACTION_UP:
                        if (outsideDown && outsideSheet(event)) dialog.cancel();
                        outsideDown = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        outsideDown = false;
                        return true;
                    default:
                        return outsideDown;
                }
            }
        };

        sheet = new SheetLayout(context, p.sheet);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(p.sheet);
        float corner = dp(context, 16);
        background.setCornerRadii(new float[]{corner, corner, corner, corner, 0, 0, 0, 0});
        sheet.setBackground(background);
        root.setClipChildren(false);
        root.addView(sheet, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        LinearLayout chrome = new Chrome(context);
        chrome.setOrientation(LinearLayout.VERTICAL);
        chrome.addView(handle(context, p));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(context, 48));
        header.setPaddingRelative(dp(context, 4), dp(context, 1), dp(context, 8), dp(context, 5));

        back = new ImageView(context);
        Drawable arrow = IeRows.icon(context, R.drawable.ic_arrow_back, p.text);
        if (arrow != null) {
            arrow.setAutoMirrored(true);
            back.setImageDrawable(arrow);
        }
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription(I18n.t(context, R.string.back));
        back.setClickable(true);
        back.setFocusable(true);
        back.setBackground(IeRows.ripple(context, 24));
        back.setOnClickListener(v -> back());
        header.addView(back, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));

        title = new TextView(context);
        title.setTextColor(p.text);
        title.setTextSize(21);
        title.setTypeface(null, Typeface.BOLD);
        title.setLetterSpacing(0.01f);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        end = new FrameLayout(context);
        end.setVisibility(View.GONE);
        header.addView(end, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        chrome.addView(header);
        sheet.addView(chrome, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pinned = new FrameLayout(context);
        pinned.setVisibility(View.GONE);
        sheet.addView(pinned, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(IeRows.divider(context));

        pages = new Pages(context);
        sheet.addView(pages, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        pageFooter = new FrameLayout(context);
        pageFooter.setVisibility(View.GONE);
        sheet.addView(pageFooter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        slot = new FrameLayout(context);
        slot.setVisibility(View.GONE);
        slot.addView(restartBar(context, p));
        sheet.addView(slot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bottom = new View(context);
        sheet.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 16)));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            ViewGroup.LayoutParams lp = bottom.getLayoutParams();
            int height = Math.max(dp(context, 16), bottomInset(insets));
            if (lp.height != height) {
                lp.height = height;
                bottom.setLayoutParams(lp);
            }
            return insets;
        });

        dialog = new Dialog(context) {
            @Override
            @SuppressWarnings("deprecation")
            public void onBackPressed() {
                back();
            }
        };
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnShowListener(d -> {
            ps.reso.instaeclipse.utils.feature.FeatureStatusTracker.addListener(onStatus);
            refreshBar();
        });
        dialog.setOnDismissListener(d -> {
            ps.reso.instaeclipse.utils.feature.FeatureStatusTracker.removeListener(onStatus);
            main.removeCallbacks(refreshBar);
            if (current == this) current = null;
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            window.getAttributes().windowAnimations = android.R.style.Animation_InputMethod;
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
        fitWindow();
    }

    public static IeSheet open(Context context, Page root, Runnable onExit) {
        close();
        IeSheet sheet = new IeSheet(context);
        sheet.onExit = onExit;
        sheet.attach(root);
        sheet.stack.push(root);
        sheet.showHeader(root);
        current = sheet;
        if (sheet.activity != null && (sheet.activity.isFinishing() || sheet.activity.isDestroyed())) {
            current = null;
            return sheet;
        }
        try {
            sheet.dialog.show();
        } catch (WindowManager.BadTokenException e) {
            current = null;
            ModuleLog.warn("(IE|Menu) The window to show the menu on is gone", e);
        }
        return sheet;
    }

    public static IeSheet showing() {
        IeSheet sheet = current;
        return sheet != null && sheet.dialog.isShowing() ? sheet : null;
    }

    static IeSheet showing(Context context) {
        IeSheet sheet = showing();
        return sheet != null && sheet.activity == activityOf(context) ? sheet : null;
    }

    public static void close() {
        IeSheet sheet = current;
        current = null;
        if (sheet != null) sheet.dismiss();
    }

    public void dismiss() {
        if (current == this) current = null;
        try {
            if (dialog.isShowing()) dialog.dismiss();
        } catch (Throwable ignored) {
        }
    }

    public boolean isShowing() {
        return current == this && dialog.isShowing();
    }

    public String title() {
        Page top = stack.peek();
        return top == null ? null : top.title;
    }

    public int depth() {
        return stack.size();
    }

    public Page top() {
        return stack.peek();
    }

    ViewGroup footer() {
        return slot;
    }

    void refreshBar() {
        boolean pending;
        try {
            pending = !DialogUtils.restartPending().isEmpty();
        } catch (Throwable t) {
            pending = false;
        }
        slot.setVisibility(pending ? View.VISIBLE : View.GONE);
    }

    private View restartBar(Context context, IePalette p) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(dp(context, 52));
        bar.setPaddingRelative(dp(context, 16), dp(context, 6), dp(context, 8), dp(context, 6));
        GradientDrawable background = new GradientDrawable();
        background.setColor(p.card);
        background.setCornerRadius(dp(context, 14));
        background.setStroke(Math.max(1, dp(context, 1)), p.line);
        bar.setBackground(background);

        TextView message = new TextView(context);
        message.setText(I18n.t(context, R.string.ig_restart_bar));
        message.setTextColor(p.text);
        message.setTextSize(14);
        bar.addView(message, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView action = new TextView(context);
        action.setText(I18n.t(context, R.string.ig_restart_bar_action));
        action.setTextColor(p.onAccent);
        action.setTextSize(14);
        action.setTypeface(Typeface.DEFAULT_BOLD);
        action.setGravity(Gravity.CENTER);
        action.setMinHeight(dp(context, 40));
        action.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(p.accent);
        pill.setCornerRadius(dp(context, 20));
        action.setBackground(pill);
        action.setOnClickListener(v -> DialogUtils.restart(context, false));
        bar.addView(action, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(context, 8), dp(context, 6), dp(context, 8), 0);
        bar.setLayoutParams(lp);
        return bar;
    }

    public Page push(Page page) {
        Page below = stack.peek();
        keepTop();
        attach(page);
        stack.push(page);
        showHeader(page);
        if (below != null) slide(below.scroll, page.scroll, true, false);
        return page;
    }

    public Page replaceTop(Page page) {
        keepTop();
        Page old = stack.poll();
        attach(page);
        stack.push(page);
        showHeader(page);
        if (old != null) pages.removeView(old.scroll);
        return page;
    }

    public void pop() {
        if (stack.size() < 2) {
            back();
            return;
        }
        keepTop();
        Page top = stack.pop();
        Page below = stack.peek();
        showHeader(below);
        slide(top.scroll, below.scroll, false, true);
        if (below.onReturn != null) below.onReturn.run();
    }

    void back() {
        Page top = stack.peek();
        if (top != null && top.onBack != null && top.onBack.getAsBoolean()) return;
        if (stack.size() > 1) {
            pop();
            return;
        }
        Runnable exit = onExit;
        dismiss();
        if (exit != null) exit.run();
    }

    public static void reopenAfterRecreate(Activity recreated, Consumer<Activity> reopen, long windowMs) {
        cancelReopen();
        if (recreated == null || reopen == null) return;
        Application app = recreated.getApplication();
        if (app == null) return;
        Handler main = new Handler(Looper.getMainLooper());
        Class<?> type = recreated.getClass();
        Application.ActivityLifecycleCallbacks[] self = new Application.ActivityLifecycleCallbacks[1];
        Runnable expire = () -> {
            if (reopenCallbacks == self[0]) cancelReopen();
        };
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity a) {
                if (a == recreated || a.getClass() != type) return;
                main.removeCallbacks(expire);
                if (reopenCallbacks == this) cancelReopen();
                main.post(() -> {
                    if (!a.isFinishing() && !a.isDestroyed()) reopen.accept(a);
                });
            }

            @Override public void onActivityCreated(Activity a, Bundle state) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle state) {}
            @Override public void onActivityDestroyed(Activity a) {}
        };
        self[0] = callbacks;
        reopenApp = app;
        reopenCallbacks = callbacks;
        app.registerActivityLifecycleCallbacks(callbacks);
        main.postDelayed(expire, windowMs);
    }

    private static void cancelReopen() {
        if (reopenApp != null && reopenCallbacks != null) {
            reopenApp.unregisterActivityLifecycleCallbacks(reopenCallbacks);
        }
        reopenApp = null;
        reopenCallbacks = null;
    }

    private void attach(Page page) {
        if (page.scroll == null) {
            ScrollView scroll = new PageScroll(context);
            scroll.setVerticalScrollBarEnabled(true);
            LinearLayout wrapper = new LinearLayout(context);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.setPadding(dp(context, 8), 0, dp(context, 8), dp(context, 8));
            if (page.content != null) wrapper.addView(page.content);
            scroll.addView(wrapper);
            page.scroll = scroll;
        }
        if (page.scroll.getParent() instanceof ViewGroup parent) parent.removeView(page.scroll);
        page.scroll.setVisibility(View.VISIBLE);
        page.scroll.setTranslationX(0);
        page.scroll.setAlpha(1f);
        pages.addView(page.scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void showHeader(Page page) {
        title.setText(page.title);
        boolean hasBack = stack.size() > 1 || onExit != null;
        back.setVisibility(hasBack ? View.VISIBLE : View.GONE);
        title.setPaddingRelative(hasBack ? dp(context, 4) : dp(context, 9), 0, dp(context, 4), 0);
        swap(end, page.end);
        swap(pinned, page.pinned);
        swap(pageFooter, page.footer);
    }

    public boolean reveal(Object tag) {
        Page page = stack.peek();
        if (page == null || page.content == null || page.scroll == null || tag == null) return false;
        View target = page.content.findViewWithTag(tag);
        if (target == null) return false;
        reveal(page.scroll, target, 0);
        return true;
    }

    private void reveal(ScrollView scroll, View target, int tries) {
        main.post(() -> {
            if (!target.isShown() || target.getHeight() == 0) {
                if (tries < REVEAL_TRIES) main.postDelayed(() -> reveal(scroll, target, tries + 1), 50);
                return;
            }
            int y = 0;
            for (View v = target; v != null && v != scroll; v = v.getParent() instanceof View parent ? parent : null) {
                y += v.getTop();
            }
            scroll.scrollTo(0, Math.max(0, y - dp(context, 24)));
            flash(target);
        });
    }

    private void flash(View target) {
        IePalette p = IePalette.of(context);
        GradientDrawable glow = new GradientDrawable();
        glow.setColor((p.accent & 0x00FFFFFF) | 0x38000000);
        glow.setCornerRadius(dp(context, 5));
        glow.setBounds(0, 0, target.getWidth(), target.getHeight());
        glow.setAlpha(0);
        target.getOverlay().add(glow);
        ValueAnimator fade = ValueAnimator.ofInt(0, 255, 255, 0);
        fade.setDuration(FLASH_MS);
        fade.addUpdateListener(a -> {
            glow.setAlpha((Integer) a.getAnimatedValue());
            target.invalidate();
        });
        fade.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                target.getOverlay().remove(glow);
            }
        });
        fade.start();
    }

    private static void swap(FrameLayout holder, View view) {
        holder.removeAllViews();
        if (view != null) {
            if (view.getParent() instanceof ViewGroup parent) parent.removeView(view);
            holder.addView(view);
        }
        holder.setVisibility(view == null ? View.GONE : View.VISIBLE);
    }

    private void slide(View out, View in, boolean forward, boolean removeOut) {
        int width = pages.getWidth();
        out.animate().cancel();
        in.animate().cancel();
        in.setVisibility(View.VISIBLE);
        in.setAlpha(1f);
        Runnable finish = () -> {
            out.setTranslationX(0);
            out.setAlpha(1f);
            if (removeOut) {
                pages.removeView(out);
            } else if (stack.peek() == null || stack.peek().scroll != out) {
                out.setVisibility(View.GONE);
            }
        };
        if (width <= 0) {
            in.setTranslationX(0);
            finish.run();
            return;
        }
        int towardEnd = sheet.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1;
        int direction = forward ? towardEnd : -towardEnd;
        in.setTranslationX(direction * width);
        in.animate().translationX(0).setDuration(SLIDE_MS).start();
        out.animate().translationX(forward ? -direction * width * 0.3f : -direction * width)
                .alpha(forward ? 0f : 1f).setDuration(SLIDE_MS).withEndAction(finish).start();
    }

    private void fitWindow() {
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        int screen = context.getResources().getDisplayMetrics().widthPixels;
        window.setLayout(Math.min(screen, dp(context, MAX_WIDTH_DP)), ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private boolean outsideSheet(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        return x < sheet.getX() || x > sheet.getX() + sheet.getWidth()
                || y < sheet.getY() || y > sheet.getY() + sheet.getHeight();
    }

    private void keepTop() {
        if (!sheet.isLaidOut()) return;
        float from = sheet.getY();
        sheet.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                ViewTreeObserver observer = sheet.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                float offset = from - sheet.getTop();
                if (Math.abs(offset) >= 1f) glide(offset);
                return true;
            }
        });
    }

    private void glide(float offset) {
        sheet.animate().cancel();
        sheet.setTranslationY(offset);
        sheet.animate().translationY(0f)
                .setDuration(context.getResources().getInteger(android.R.integer.config_mediumAnimTime))
                .setInterpolator(new DecelerateInterpolator(2.5f))
                .start();
    }

    private void slideAway() {
        int height = sheet.getHeight();
        sheet.animate().cancel();
        sheet.animate().translationY(height).setDuration(SLIDE_MS).withEndAction(this::dismiss).start();
    }

    @SuppressWarnings("deprecation")
    private static int bottomInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) return insets.getInsets(WindowInsets.Type.systemBars()).bottom;
        return insets.getSystemWindowInsetBottom();
    }

    private static View handle(Context context, IePalette p) {
        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(Gravity.CENTER_HORIZONTAL);
        wrapper.setPadding(0, dp(context, 8), 0, dp(context, 6));
        View bar = new View(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(context, 40), dp(context, 4));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        bar.setLayoutParams(lp);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(p.sub);
        shape.setCornerRadius(dp(context, 2));
        bar.setBackground(shape);
        wrapper.addView(bar);
        return wrapper;
    }

    static Activity activityOf(Context context) {
        Context c = context;
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity a) return a;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private final class Chrome extends LinearLayout {
        private final int slop;
        private float downY;
        private boolean dragging;

        Chrome(Context context) {
            super(context);
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = event.getRawY();
                    dragging = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (event.getRawY() - downY > slop) dragging = true;
                    break;
                default:
                    break;
            }
            return dragging;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float dy = Math.max(0f, event.getRawY() - downY);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = event.getRawY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && dy > slop) dragging = true;
                    if (dragging) {
                        sheet.animate().cancel();
                        sheet.setTranslationY(dy);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging && dy > sheet.getHeight() * DRAG_TO_CLOSE) slideAway();
                    else sheet.animate().translationY(0f).setDuration(SLIDE_MS).start();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    sheet.animate().translationY(0f).setDuration(SLIDE_MS).start();
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        }
    }

    private static final class SheetLayout extends LinearLayout {
        private final Paint below = new Paint();

        SheetLayout(Context context, int color) {
            super(context);
            below.setColor(color);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int height = getHeight();
            canvas.drawRect(0, height, getWidth(), height + getResources().getDisplayMetrics().heightPixels, below);
        }
    }

    private final class Pages extends FrameLayout {
        Pages(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            Page top = stack.peek();
            View shown = top == null ? null : top.scroll;
            if (shown == null || shown.getParent() != this || shown.getVisibility() == GONE) return;
            int height = shown.getMeasuredHeight() + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(getMeasuredWidthAndState(), resolveSizeAndState(height, heightMeasureSpec, 0));
        }
    }

    private static final class PageScroll extends ScrollView {
        PageScroll(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int cap = Math.round(getResources().getDisplayMetrics().heightPixels * MAX_HEIGHT);
            if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                cap = Math.min(cap, MeasureSpec.getSize(heightMeasureSpec));
            }
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
        }
    }
}
