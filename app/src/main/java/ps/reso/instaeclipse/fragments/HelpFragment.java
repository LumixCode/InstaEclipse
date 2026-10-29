package ps.reso.instaeclipse.fragments;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Html;
import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.OneShotPreDrawListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.transition.AutoTransition;
import androidx.transition.TransitionManager;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;

import java.util.List;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.Contributor;
import ps.reso.instaeclipse.utils.core.Credits;
import ps.reso.instaeclipse.utils.core.ExportBrand;
import ps.reso.instaeclipse.utils.ui.Gestures;
import ps.reso.instaeclipse.utils.ui.Links;

public class HelpFragment extends Fragment implements TabFragment {

    private static final String VECTOR_URL = "https://github.com/JingMatrix/Vector/releases/latest";
    private static final String LSPATCH_URL = "https://github.com/JingMatrix/LSPatch/releases/latest";
    private static final String LSPATCH_GUIDE_URL = "https://t.me/instaEclipse_discussion/158/5149";

    private static final int[] FAQ_STRINGS = {
            R.string.faq_1, R.string.help_faq_2, R.string.faq_3,
            R.string.help_faq_4, R.string.help_faq_5, R.string.faq_6,
            R.string.faq_7, R.string.faq_8, R.string.faq_9
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_help, container, false);

        buildFaq(view.findViewById(R.id.faq_container));

        view.findViewById(R.id.vector_row).setOnClickListener(v -> openUrl(VECTOR_URL));
        view.findViewById(R.id.lspatch_row).setOnClickListener(v -> openUrl(LSPATCH_URL));
        view.findViewById(R.id.lspatch_guide_button).setOnClickListener(v -> openUrl(LSPATCH_GUIDE_URL));
        for (int id : new int[]{R.id.vector_logo, R.id.lspatch_logo}) {
            View logo = view.findViewById(id);
            logo.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
                }
            });
            logo.setClipToOutline(true);
        }

        view.findViewById(R.id.github_button).setOnClickListener(v -> openUrl(ExportBrand.GITHUB + "/issues"));
        view.findViewById(R.id.telegram_button).setOnClickListener(v -> openUrl(ExportBrand.TELEGRAM));
        view.findViewById(R.id.source_button).setOnClickListener(v -> openUrl(ExportBrand.GITHUB));
        TextView basedOn = view.findViewById(R.id.based_on_button);
        basedOn.setText(getString(R.string.about_based_on, ExportBrand.UPSTREAM_OWNER));
        basedOn.setOnClickListener(v -> openUrl(ExportBrand.UPSTREAM));

        TextView version = view.findViewById(R.id.about_version);
        version.setText(getString(R.string.help_version, readVersionName(requireContext())));
        TextView tested = view.findViewById(R.id.about_tested_with);
        tested.setText(getString(R.string.help_tested_with, ExportBrand.TESTED_INSTAGRAM));

        buildGestures(view.findViewById(R.id.credits_contributors_title));
        buildCredits(view.findViewById(R.id.credits_contributors), Credits.CONTRIBUTORS);
        buildCredits(view.findViewById(R.id.credits_special_thanks), Credits.SPECIAL_THANKS);

        return view;
    }

    private void buildGestures(View before) {
        ViewGroup about = (ViewGroup) before.getParent();
        Context ctx = about.getContext();
        int at = about.indexOfChild(before);
        TextView title = new TextView(ctx);
        title.setTextAppearance(resolveResId(ctx, com.google.android.material.R.attr.textAppearanceTitleSmall));
        title.setTextColor(MaterialColors.getColor(about, com.google.android.material.R.attr.colorOnSurface));
        title.setText(R.string.ig_gestures);
        ViewCompat.setAccessibilityHeading(title, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(20);
        titleLp.bottomMargin = dp(4);
        about.addView(title, at++, titleLp);
        for (Gestures.Gesture g : Gestures.ALL) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            row.setTag(g.key());
            TextView name = new TextView(ctx);
            name.setTextAppearance(resolveResId(ctx, com.google.android.material.R.attr.textAppearanceBodyLarge));
            name.setTextColor(MaterialColors.getColor(about, com.google.android.material.R.attr.colorOnSurface));
            name.setText(g.title());
            TextView what = new TextView(ctx);
            what.setTextAppearance(resolveResId(ctx, com.google.android.material.R.attr.textAppearanceBodyMedium));
            what.setTextColor(MaterialColors.getColor(about, com.google.android.material.R.attr.colorOnSurfaceVariant));
            what.setText(g.description());
            row.addView(name);
            row.addView(what);
            about.addView(row, at++, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private static final int[] MONOGRAM_COLORS = {
            0xFF5E5CE6, 0xFFFF375F, 0xFFFF9F0A, 0xFF30D158, 0xFF64D2FF,
            0xFFBF5AF2, 0xFFFF9500, 0xFF32D74B, 0xFF0A84FF, 0xFFFFD60A
    };

    private void buildCredits(ChipGroup group, List<Contributor> people) {
        Context ctx = group.getContext();
        for (Contributor person : people) {
            Chip chip = new Chip(ctx);
            chip.setText(person.name());
            chip.setEnsureMinTouchTargetSize(true);
            chip.setChipIcon(new MonogramDrawable(Credits.initials(person.name()),
                    MONOGRAM_COLORS[Math.floorMod(person.name().hashCode(), MONOGRAM_COLORS.length)], dp(24)));
            chip.setChipIconTint(null);
            chip.setChipIconVisible(true);
            String link = Credits.mainLink(person);
            if (link != null) {
                chip.setContentDescription(getString(siteLabel(Credits.site(person)), person.name()));
                chip.setOnClickListener(v -> openUrl(link));
            } else {
                chip.setClickable(false);
                chip.setFocusable(false);
            }
            group.addView(chip);
        }
    }

    private static int siteLabel(Credits.Site site) {
        if (site == Credits.Site.TELEGRAM) return R.string.credits_on_telegram;
        if (site == Credits.Site.LINKEDIN) return R.string.credits_on_linkedin;
        return R.string.credits_on_github;
    }

    private static final class MonogramDrawable extends Drawable {
        private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String initials;
        private final int size;

        MonogramDrawable(String initials, int color, int size) {
            this.initials = initials;
            this.size = size;
            circle.setColor(color);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextSize(size * (initials.length() > 1 ? 0.4f : 0.5f));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float r = Math.min(b.width(), b.height()) / 2f;
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, circle);
            float baseline = b.exactCenterY() - (text.descent() + text.ascent()) / 2f;
            canvas.drawText(initials, b.exactCenterX(), baseline, text);
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void setAlpha(int alpha) {
            circle.setAlpha(alpha);
            text.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            circle.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    @Override
    public void onTabReselected() {
        if (getView() instanceof NestedScrollView) ((NestedScrollView) getView()).smoothScrollTo(0, 0);
    }

    public void scrollToAbout() {
        if (!(getView() instanceof NestedScrollView)) return;
        NestedScrollView scroll = (NestedScrollView) getView();
        View about = scroll.findViewById(R.id.about_title);
        OneShotPreDrawListener.add(scroll, () -> scroll.smoothScrollTo(0, about.getTop()));
    }

    private void buildFaq(LinearLayout container) {
        Context ctx = container.getContext();
        for (int i = 0; i < FAQ_STRINGS.length; i++) {
            if (i > 0) container.addView(hairline(ctx));
            CharSequence raw = FAQ_STRINGS[i] == R.string.help_faq_5
                    ? getString(R.string.help_faq_5, ExportBrand.TESTED_INSTAGRAM) : getText(FAQ_STRINGS[i]);
            CharSequence[] qa = splitFaq(raw);
            container.addView(faqRow(container, qa[0], qa[1]));
        }
    }

    private View faqRow(LinearLayout parentCard, CharSequence question, CharSequence answer) {
        Context ctx = parentCard.getContext();
        int pad = dp(16);
        int gutter = dp(20);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(56));
        header.setPadding(gutter, pad, dp(12), pad);
        header.setBackgroundResource(resolveResId(ctx, android.R.attr.selectableItemBackground));
        header.setClickable(true);
        header.setFocusable(true);

        TextView q = new TextView(ctx);
        q.setTextAppearance(resolveResId(ctx, com.google.android.material.R.attr.textAppearanceTitleMedium));
        q.setTextColor(MaterialColors.getColor(parentCard, com.google.android.material.R.attr.colorOnSurface));
        q.setText(question.toString());
        header.addView(q, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chevron = new ImageView(ctx);
        chevron.setImageResource(R.drawable.ic_expand_more);
        chevron.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(parentCard,
                com.google.android.material.R.attr.colorOnSurfaceVariant)));
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams chevronLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        chevronLp.setMarginStart(dp(12));
        header.addView(chevron, chevronLp);

        TextView a = new TextView(ctx);
        a.setTextAppearance(resolveResId(ctx, com.google.android.material.R.attr.textAppearanceBodyMedium));
        a.setTextColor(MaterialColors.getColor(parentCard, com.google.android.material.R.attr.colorOnSurfaceVariant));
        a.setLineSpacing(0f, 1.15f);
        a.setText(answer);
        Linkify.addLinks(a, Linkify.WEB_URLS);
        if (a.getText() instanceof Spannable) {
            Spannable spannable = (Spannable) a.getText();
            for (URLSpan span : spannable.getSpans(0, spannable.length(), URLSpan.class)) {
                int start = spannable.getSpanStart(span);
                int end = spannable.getSpanEnd(span);
                int flags = spannable.getSpanFlags(span);
                spannable.removeSpan(span);
                spannable.setSpan(new LinkSpan(span.getURL()), start, end, flags);
            }
        }
        a.setMovementMethod(LinkMovementMethod.getInstance());
        a.setPadding(gutter, 0, gutter, pad);
        a.setVisibility(View.GONE);

        row.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(a, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        boolean hasAnswer = answer.length() > 0;
        if (!hasAnswer) chevron.setVisibility(View.INVISIBLE);
        header.setContentDescription(question.toString());

        Runnable toggle = () -> {
            boolean expand = a.getVisibility() != View.VISIBLE;
            ViewGroup root = (ViewGroup) parentCard.getParent().getParent();
            TransitionManager.beginDelayedTransition(root != null ? root : parentCard,
                    new AutoTransition().setDuration(220));
            a.setVisibility(expand ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(expand ? 180f : 0f).setDuration(220).start();
            bindFaqState(header, expand);
        };
        header.setOnClickListener(v -> {
            if (hasAnswer) toggle.run();
        });
        if (hasAnswer) {
            header.setTag(toggle);
            bindFaqState(header, false);
        }
        return row;
    }

    private void bindFaqState(View header, boolean expanded) {
        Runnable toggle = (Runnable) header.getTag();
        ViewCompat.setStateDescription(header, getString(expanded ? R.string.help_faq_expanded : R.string.help_faq_collapsed));
        ViewCompat.removeAccessibilityAction(header, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_EXPAND.getId());
        ViewCompat.removeAccessibilityAction(header, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_COLLAPSE.getId());
        ViewCompat.replaceAccessibilityAction(header, expanded
                        ? AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_COLLAPSE
                        : AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_EXPAND,
                null, (v, arguments) -> {
                    toggle.run();
                    return true;
                });
    }

    static CharSequence[] splitFaq(CharSequence raw) {
        String text = raw.toString();
        if (text.contains("<")) {
            text = Html.fromHtml(text.replace("\n", "<br/>"), Html.FROM_HTML_MODE_LEGACY).toString();
        }
        text = text.trim();
        int cut = text.indexOf('\n');
        int answerStart = cut + 1;
        if (cut < 0) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '?' || c == '؟' || c == '？') {
                    cut = i + 1;
                    answerStart = cut;
                    break;
                }
            }
        }
        if (cut <= 0 || cut >= text.length()) return new CharSequence[]{stripNumber(text), ""};
        String q = stripNumber(text.substring(0, cut).trim());
        String a = text.substring(answerStart).trim();
        if (a.startsWith("-")) a = a.substring(1).trim();
        return new CharSequence[]{q, a};
    }

    private static String stripNumber(String question) {
        int i = 0;
        while (i < question.length() && Character.isDigit(question.charAt(i))) i++;
        if (i == 0 || i >= question.length()) return question;
        char mark = question.charAt(i);
        if (mark != '.' && mark != ')') return question;
        if (i + 1 < question.length() && Character.isDigit(question.charAt(i + 1))) return question;
        return question.substring(i + 1).trim();
    }

    private View hairline(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorOutlineVariant, 0x22FFFFFF));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMarginStart(dp(20));
        lp.setMarginEnd(dp(20));
        v.setLayoutParams(lp);
        return v;
    }

    private void openUrl(String url) {
        Links.open(requireContext(), url);
    }

    private static final class LinkSpan extends URLSpan {
        LinkSpan(String url) {
            super(url);
        }

        @Override
        public void onClick(@NonNull View widget) {
            Links.open(widget.getContext(), getURL());
        }
    }

    private static String readVersionName(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info.versionName != null ? info.versionName : "?";
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private static int resolveResId(Context ctx, @AttrRes int attr) {
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
