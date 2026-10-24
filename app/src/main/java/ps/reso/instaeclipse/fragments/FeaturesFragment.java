package ps.reso.instaeclipse.fragments;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.devops.config.DevConfigReply;
import ps.reso.instaeclipse.mods.media.MediaStoreTarget;
import ps.reso.instaeclipse.utils.backup.CompanionRestorePlan;
import ps.reso.instaeclipse.utils.core.CompanionUiPrefs;
import ps.reso.instaeclipse.utils.core.PermissionAsk;
import ps.reso.instaeclipse.utils.core.SettingsSchema;
import ps.reso.instaeclipse.utils.feature.FeatureCatalog;
import ps.reso.instaeclipse.utils.ipc.ModuleReplies;
import ps.reso.instaeclipse.utils.media.StoryCacheRetention;
import ps.reso.instaeclipse.utils.text.SearchText;
import ps.reso.instaeclipse.utils.ui.CompanionRestartHint;
import ps.reso.instaeclipse.mods.location.LocationPickerActivity;
import ps.reso.instaeclipse.utils.core.CompanionSettings;
import ps.reso.instaeclipse.ui.theme.ThemeCustomizerActivity;

public class FeaturesFragment extends Fragment implements TabFragment {

    private static final String STATE_MENU = "currentMenu";
    private static final int MAX_BACKUP_BYTES = 4 * 1024 * 1024;
    private static final ExecutorService RESTORE_WORKER = restoreWorker();

    private SharedPreferences localCache;
    private View fragmentView;
    private RecyclerView recyclerFeatures;
    private FeatureAdapter adapter;
    private TextView tvHeaderTitle;
    private ImageButton btnBack;
    private TextInputLayout tilSearch;
    private TextInputEditText etSearch;
    /** True while the search text is being changed programmatically (skip re-filtering). */
    private boolean suppressSearchWatcher = false;
    private ActivityResultLauncher<Uri> dirPickerLauncher;
    private ActivityResultLauncher<String[]> restoreFileLauncher;
    private ActivityResultLauncher<Intent> locationPickerLauncher;
    private ActivityResultLauncher<Intent> themeCustomizerLauncher;
    private ActivityResultLauncher<PickVisualMediaRequest> profilePhotoLauncher;

    private String currentMenu = "main";
    private String searchShown;
    private boolean restoringMenu;
    private OnBackPressedCallback backCallback;
    /** True once Instagram answered the current request with today's reel count. */
    private boolean reelUsageSynced;
    private int reelsWatchedToday;
    private String reelUsageDate = "";

    /** Nonce of the last ACTION_REQUEST_PREFS; replies without it are ignored (IpcSecurity). */
    private String prefsNonce;
    private ps.reso.instaeclipse.utils.ipc.ModuleReplies.Pending prefsReply, configReply;
    private final android.os.Handler configTimer = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean configExport;
    private final Runnable configTimeout = () -> {
        if (configReply != null) { configReply.cancel(); configReply = null; }
        onDevConfigResult(DevConfigReply.Result.NO_REPLY, null);
    };

    private final BroadcastReceiver prefsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("ps.reso.instaeclipse.ACTION_SEND_PREFS".equals(intent.getAction())
                    && ps.reso.instaeclipse.utils.core.IpcSecurity.nonceMatches(prefsNonce, intent)) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    SharedPreferences.Editor editor = localCache.edit();
                    // Older builds stored the reply's nonce extra as if it were a setting.
                    editor.remove(ps.reso.instaeclipse.utils.core.IpcSecurity.EXTRA_NONCE);
                    editor.remove("dailyReelsWatchedToday").remove("dailyReelUsageDate");
                    // Instagram's values, minus the ones this app changed more recently (pending
                    // in Instagram) — see PrefsMerge. Also drops the nonce and stamp extras.
                    Map<String, Object> incoming = new HashMap<>();
                    for (String key : bundle.keySet()) incoming.put(key, bundle.get(key));
                    Object watched = incoming.remove("dailyReelsWatchedToday");
                    Object usageDate = incoming.remove("dailyReelUsageDate");
                    if (watched instanceof Integer) {
                        reelsWatchedToday = (Integer) watched;
                        reelUsageDate = usageDate instanceof String ? (String) usageDate : "";
                        reelUsageSynced = true;
                    }
                    Map<String, Object> changes =
                            ps.reso.instaeclipse.utils.core.PrefsMerge.select(incoming, localCache.getAll());
                    for (Map.Entry<String, Object> change : changes.entrySet()) {
                        String key = change.getKey();
                        Object value = change.getValue();
                        if (value instanceof Boolean) {
                            editor.putBoolean(key, (Boolean) value);
                        } else if (value instanceof String) {
                            editor.putString(key, (String) value);
                        } else if (value instanceof Integer) {
                            editor.putInt(key, (Integer) value);
                        }
                    }
                    editor.apply();
                    reloadCurrentMenu();
                }
            }
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoreFileLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::restoreFrom);

        locationPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result == null || result.getData() == null) return;
                    reloadCurrentMenu();
                });

        themeCustomizerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> reloadCurrentMenu());

        profilePhotoLauncher = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), this::onProfilePhotoPicked);

        dirPickerLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
            if (uri != null) {
                final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                requireContext().getContentResolver().takePersistableUriPermission(uri, takeFlags);

                String uriString = uri.toString();
                String path = uri.getPath();

                if (path != null) {
                    path = path.replace("/tree/primary:", "/storage/emulated/0/");
                    path = path.replace("/document/primary:", "/storage/emulated/0/");
                } else {
                    path = uriString;
                }

                LinkedHashMap<String, Object> folder = new LinkedHashMap<>();
                folder.put("downloaderCustomUri", uriString);
                folder.put("downloaderCustomPath", path);
                writeAll(folder);
                ps.reso.instaeclipse.utils.core.BackgroundAccess.askIfNeeded(requireContext(), true);
                askDownloadPermission(PermissionAsk.Trigger.FOLDER_PICKED);

                ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), getString(R.string.ig_toast_download_folder_updated), Toast.LENGTH_SHORT).show();

                reloadCurrentMenu();
            }
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        fragmentView = inflater.inflate(R.layout.fragment_features, container, false);
        localCache = ps.reso.instaeclipse.utils.core.CommonUtils.companionCache(requireContext());

        // Downloads to a chosen folder need this app to start from Instagram, and the folder's grant.
        String folder = localCache.getString("downloaderCustomUri", "");
        if (ps.reso.instaeclipse.utils.core.BackgroundAccess.folderGrantLost(requireContext(), folder))
            ps.reso.instaeclipse.utils.core.BackgroundAccess.askToRechooseFolder(requireContext(), folder,
                    () -> dirPickerLauncher.launch(null), this::resetDownloadFolder);
        else if (!folder.isEmpty())
            ps.reso.instaeclipse.utils.core.BackgroundAccess.askIfNeeded(requireContext(), false);
        askDownloadPermission(PermissionAsk.forOpening(this::getCurrentState));

        recyclerFeatures = fragmentView.findViewById(R.id.recycler_features);
        recyclerFeatures.setLayoutManager(new LinearLayoutManager(requireContext()));
        // Disable change animations so conditional enabled/alpha updates are instant, not crossfaded
        recyclerFeatures.setItemAnimator(null);
        adapter = new FeatureAdapter();
        recyclerFeatures.setAdapter(adapter);

        tvHeaderTitle = fragmentView.findViewById(R.id.tv_header_title);
        btnBack = fragmentView.findViewById(R.id.btn_back);

        tilSearch = fragmentView.findViewById(R.id.til_search);
        etSearch = fragmentView.findViewById(R.id.et_search);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (!suppressSearchWatcher) runSearch(s.toString());
                updateBackEnabled();
            }
        });
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                etSearch.clearFocus();
                return true;
            }
            return false;
        });
        // Dismiss the keyboard once the user starts scrolling through results
        recyclerFeatures.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && etSearch.hasFocus()) {
                    hideKeyboard();
                    etSearch.clearFocus();
                }
            }
        });

        btnBack.setOnClickListener(v -> loadParentMenu());

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (!"main".equals(currentMenu)) {
                    loadParentMenu();
                } else if (isSearching()) {
                    etSearch.setText("");
                    etSearch.clearFocus();
                    hideKeyboard();
                }
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backCallback);

        if (savedInstanceState == null) {
            loadMainMenu();
        } else {
            restoreMenu(savedInstanceState.getString(STATE_MENU, "main"));
            suppressSearchWatcher = true;
        }

        return fragmentView;
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        if (!suppressSearchWatcher) return;
        suppressSearchWatcher = false;
        if ("main".equals(currentMenu) && isSearching()) {
            restoringMenu = true;
            try {
                runSearch(etSearch.getText().toString());
            } finally {
                restoringMenu = false;
            }
        }
        updateBackEnabled();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_MENU, currentMenu);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        updateBackEnabled();
    }

    // =========================================================
    // MENU DATA MODEL & ADAPTER
    // =========================================================

    public static class FeatureItem {
        public static final int TYPE_HEADER = 0;
        public static final int TYPE_SWITCH = 1;
        public static final int TYPE_CLICKABLE = 2;
        public static final int TYPE_MASTER_SWITCH = 3;
        public static final int TYPE_SPACER = 4;
        public static final int TYPE_NOTICE = 5;
        public static final int TYPE_HINT = 6;
        public static final int TYPE_EMPTY = 7;

        public int type;
        public String title;
        public String disabledReason;
        public String requiresReason;
        public String overrideReason;
        public String prefKey;
        public Runnable onClick;
        public boolean destructive;
        public int textColor;
        public int iconRes;
        public int accentColor;
        public boolean isExtreme;
        public List<String> childKeys;
        /** When this switch is turned ON, also stage this key as true (parent dependency). */
        public String dependsOn;
        /** When this switch is turned OFF, also stage this key as false (child cascade). */
        public String cascadeOffKey;
        /** Switch is only enabled when at least one key in this list is currently true. */
        public List<String> requiresAnyOf;
        /** Switch is locked (disabled) when this key is currently true. */
        public String disabledWhenTrue;
        /** False when another setting overrides this row (fixed when the menu is built). */
        public boolean enabled = true;

        public int segmentPosition;
        public int segmentSize;

        /** Secondary line under the title (e.g. "3 on" on main-menu category rows). */
        public String subtitle;
        /** Subtitle drawn in the accent (primary) colour instead of the muted variant colour. */
        public boolean subtitleActive;
        /** Row navigates into a sub-menu: shows a trailing chevron. */
        public boolean isNav;
        /** Main-menu row: rendered as a standalone rounded card. */
        public boolean isCategory;
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView tvHeader;
        HeaderViewHolder(View v) {
            super(v);
            tvHeader = v.findViewById(R.id.tv_header);
        }
    }

    static class ItemViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle;
        TextView tvSubtitle;
        ImageView ivIcon;
        ImageView ivChevron;
        MaterialSwitch swToggle;
        FeatureRowCard cardView;
        ItemViewHolder(View v) {
            super(v);
            tvTitle = v.findViewById(R.id.tv_title);
            tvSubtitle = v.findViewById(R.id.tv_subtitle);
            ivChevron = v.findViewById(R.id.iv_chevron);
            ivIcon = v.findViewById(R.id.iv_icon);
            swToggle = v.findViewById(R.id.sw_toggle);
            cardView = (FeatureRowCard) v;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class NoticeViewHolder extends RecyclerView.ViewHolder {
        final TextView headline;
        final TextView text;
        final com.google.android.material.button.MaterialButton ok;

        NoticeViewHolder(ViewGroup parent) {
            super(new MaterialCardView(parent.getContext()));
            Context c = parent.getContext();
            MaterialCardView card = (MaterialCardView) itemView;
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            card.setLayoutParams(lp);
            card.setCardElevation(0f);
            card.setStrokeWidth(0);
            card.setRadius(dp(24));
            card.setCardBackgroundColor(MaterialColors.getColor(parent, com.google.android.material.R.attr.colorSecondaryContainer));
            android.widget.LinearLayout box = new android.widget.LinearLayout(c);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            box.setPadding(dp(16), dp(16), dp(16), dp(8));
            headline = new TextView(c);
            headline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            headline.setTypeface(null, android.graphics.Typeface.BOLD);
            headline.setTextColor(MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSecondaryContainer));
            android.widget.LinearLayout.LayoutParams headlineLp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            headlineLp.bottomMargin = dp(4);
            box.addView(headline, headlineLp);
            text = new TextView(c);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            text.setTextColor(MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSecondaryContainer));
            box.addView(text);
            ok = new com.google.android.material.button.MaterialButton(c);
            ok.setText(R.string.ig_dialog_ok);
            android.widget.LinearLayout.LayoutParams okLp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            okLp.gravity = android.view.Gravity.END;
            okLp.topMargin = dp(8);
            box.addView(ok, okLp);
            card.addView(box);
        }
    }

    private class FeatureAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        List<FeatureItem> items = new ArrayList<>();

        public void setItems(List<FeatureItem> items) {
            this.items = items;
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            int type = items.get(position).type;
            if (type == FeatureItem.TYPE_HEADER) return 0;
            if (type == FeatureItem.TYPE_SPACER)  return 2;
            if (type == FeatureItem.TYPE_NOTICE) return 3;
            if (type == FeatureItem.TYPE_HINT) return 4;
            if (type == FeatureItem.TYPE_EMPTY) return 5;
            return 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == 0) {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_feature_header, parent, false);
                return new HeaderViewHolder(v);
            } else if (viewType == 2) {
                // Spacer: a plain transparent view with fixed height
                View v = new View(parent.getContext());
                v.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, parent.getResources().getDisplayMetrics())
                ));
                return new RecyclerView.ViewHolder(v) {};
            } else if (viewType == 3) {
                return new NoticeViewHolder(parent);
            } else if (viewType == 4) {
                TextView hint = new TextView(parent.getContext());
                int side = dp(16);
                hint.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                hint.setPadding(side, dp(4), side, dp(8));
                hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                hint.setTextColor(MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurfaceVariant));
                return new RecyclerView.ViewHolder(hint) {};
            } else if (viewType == 5) {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_feature_empty, parent, false);
                return new RecyclerView.ViewHolder(v) {};
            } else {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_feature, parent, false);
                return new ItemViewHolder(v);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            FeatureItem item = items.get(position);

            if (item.type == FeatureItem.TYPE_SPACER || item.type == FeatureItem.TYPE_EMPTY) return;

            if (holder instanceof NoticeViewHolder) {
                NoticeViewHolder notice = (NoticeViewHolder) holder;
                notice.headline.setVisibility(item.subtitle != null ? View.VISIBLE : View.GONE);
                notice.headline.setText(item.subtitle != null ? item.title : null);
                notice.text.setText(item.subtitle != null ? item.subtitle : item.title);
                notice.ok.setOnClickListener(v -> {
                    if (item.onClick != null) item.onClick.run();
                });
            } else if (item.type == FeatureItem.TYPE_HINT) {
                ((TextView) holder.itemView).setText(item.title);
            } else if (holder instanceof HeaderViewHolder) {
                ((HeaderViewHolder) holder).tvHeader.setText(item.title);
            } else if (holder instanceof ItemViewHolder) {
                ItemViewHolder itemHolder = (ItemViewHolder) holder;

                bindIcon(itemHolder.ivIcon, item.iconRes, item.accentColor);

                // Master switches get a tinted card + bold text to stand out from regular items
                boolean isMaster = item.type == FeatureItem.TYPE_MASTER_SWITCH;
                if (isMaster) {
                    int bg = MaterialColors.getColor(itemHolder.itemView, com.google.android.material.R.attr.colorSecondaryContainer);
                    int fg = MaterialColors.getColor(itemHolder.itemView, com.google.android.material.R.attr.colorOnSecondaryContainer);
                    itemHolder.cardView.setCardBackgroundColor(bg);
                    itemHolder.tvTitle.setTextColor(fg);
                    itemHolder.tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                } else {
                    int bg = MaterialColors.getColor(itemHolder.itemView, item.isCategory
                            ? com.google.android.material.R.attr.colorSurfaceContainer
                            : com.google.android.material.R.attr.colorSurfaceContainerLow);
                    int defaultTextColor = MaterialColors.getColor(itemHolder.itemView, com.google.android.material.R.attr.colorOnSurface);
                    itemHolder.cardView.setCardBackgroundColor(bg);
                    itemHolder.tvTitle.setTextColor(item.destructive && item.textColor != 0 ? item.textColor : defaultTextColor);
                    itemHolder.tvTitle.setTypeface(null, android.graphics.Typeface.NORMAL);
                }

                float largeRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, getResources().getDisplayMetrics());
                float smallRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, getResources().getDisplayMetrics());

                ShapeAppearanceModel.Builder shapeBuilder = itemHolder.cardView.getShapeAppearanceModel().toBuilder();

                boolean switchEnabled = item.enabled && requirementMet(item);
                if (item.disabledWhenTrue != null && getCurrentState(item.disabledWhenTrue)) {
                    switchEnabled = false;
                }
                String reason = switchEnabled ? null : reasonFor(item);

                if (reason != null) {
                    itemHolder.tvSubtitle.setVisibility(View.VISIBLE);
                    itemHolder.tvSubtitle.setText(reason);
                    itemHolder.tvSubtitle.setTextColor(MaterialColors.getColor(itemHolder.itemView, com.google.android.material.R.attr.colorOnSurfaceVariant));
                } else if (item.subtitle != null) {
                    itemHolder.tvSubtitle.setVisibility(View.VISIBLE);
                    itemHolder.tvSubtitle.setText(item.subtitle);
                    itemHolder.tvSubtitle.setTextColor(MaterialColors.getColor(itemHolder.itemView, item.subtitleActive
                            ? androidx.appcompat.R.attr.colorPrimary
                            : com.google.android.material.R.attr.colorOnSurfaceVariant));
                } else {
                    itemHolder.tvSubtitle.setVisibility(View.GONE);
                }
                itemHolder.ivChevron.setVisibility(item.isNav ? View.VISIBLE : View.GONE);

                // Main-menu rows are standalone rounded cards with a small gap; sub-menu rows stay segmented
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) itemHolder.itemView.getLayoutParams();
                int wantBottom = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                        item.isCategory ? 6f : 2f, getResources().getDisplayMetrics());
                if (lp.bottomMargin != wantBottom) {
                    lp.bottomMargin = wantBottom;
                    itemHolder.itemView.setLayoutParams(lp);
                }

                if (item.isCategory || item.segmentSize == 1) {
                    shapeBuilder.setAllCornerSizes(largeRadius);
                } else if (item.segmentPosition == 0) {
                    shapeBuilder.setTopLeftCornerSize(largeRadius).setTopRightCornerSize(largeRadius)
                            .setBottomLeftCornerSize(smallRadius).setBottomRightCornerSize(smallRadius);
                } else if (item.segmentPosition == item.segmentSize - 1) {
                    shapeBuilder.setTopLeftCornerSize(smallRadius).setTopRightCornerSize(smallRadius)
                            .setBottomLeftCornerSize(largeRadius).setBottomRightCornerSize(largeRadius);
                } else {
                    shapeBuilder.setAllCornerSizes(smallRadius);
                }
                itemHolder.cardView.setShapeAppearanceModel(shapeBuilder.build());

                itemHolder.tvTitle.setText(item.title);
                itemHolder.itemView.setEnabled(switchEnabled);
                itemHolder.swToggle.setEnabled(switchEnabled);
                float faded = switchEnabled ? 1f : 0.38f;
                itemHolder.ivIcon.setAlpha(faded);
                itemHolder.tvTitle.setAlpha(faded);
                itemHolder.swToggle.setAlpha(faded);
                itemHolder.tvSubtitle.setAlpha(reason != null ? 1f : faded);
                itemHolder.cardView.setToggle(item.type != FeatureItem.TYPE_CLICKABLE ? itemHolder.swToggle : null);

                itemHolder.swToggle.setOnCheckedChangeListener(null);
                itemHolder.itemView.setOnClickListener(null);

                if (item.type == FeatureItem.TYPE_CLICKABLE) {
                    itemHolder.swToggle.setVisibility(View.GONE);
                    itemHolder.itemView.setOnClickListener(v -> {
                        if (item.onClick != null) item.onClick.run();
                    });
                } else {
                    itemHolder.swToggle.setVisibility(View.VISIBLE);
                    itemHolder.itemView.setOnClickListener(v -> itemHolder.swToggle.toggle());

                    boolean isChecked = false;
                    if (item.type == FeatureItem.TYPE_MASTER_SWITCH) {
                        isChecked = true;
                        for (String key : item.childKeys) {
                            if (!getCurrentState(key)) {
                                isChecked = false;
                                break;
                            }
                        }
                    } else if (item.prefKey != null) {
                        isChecked = getCurrentState(item.prefKey);
                    }
                    itemHolder.swToggle.setChecked(isChecked);

                    itemHolder.swToggle.setOnCheckedChangeListener((btn, checked) -> {
                        if (item.isExtreme && checked) {
                            new MaterialAlertDialogBuilder(itemHolder.itemView.getContext())
                                    .setTitle(getString(R.string.ig_dialog_distraction_extreme_title))
                                    .setMessage(getString(R.string.cat_extreme_message))
                                    .setPositiveButton(getString(R.string.ig_dialog_yes), (dialog, which) -> {
                                        stageChange(item.prefKey, true);
                                        updateMasterSwitches();
                                        refreshConditionalItems();
                                    })
                                    .setNegativeButton(getString(R.string.ig_dialog_cancel), (dialog, which) -> rebind(item))
                                    .setOnCancelListener(dialog -> rebind(item))
                                    .show();
                            return;
                        }

                        if (item.type == FeatureItem.TYPE_MASTER_SWITCH) {
                            LinkedHashMap<String, Boolean> children = new LinkedHashMap<>();
                            for (String key : item.childKeys) children.put(key, checked);
                            stageChanges(children);
                            for (int i = 0; i < items.size(); i++) {
                                if (items.get(i).type == FeatureItem.TYPE_SWITCH) {
                                    notifyItemChanged(i);
                                }
                            }
                            refreshConditionalItems();
                        } else if (item.prefKey != null) {
                            LinkedHashMap<String, Boolean> changes = new LinkedHashMap<>();
                            changes.put(item.prefKey, checked);
                            if (checked && item.dependsOn != null) changes.put(item.dependsOn, true);
                            if (!checked && item.cascadeOffKey != null) changes.put(item.cascadeOffKey, false);
                            stageChanges(changes);
                            if (checked && item.dependsOn != null) refreshRowByKey(item.dependsOn);
                            if (!checked && item.cascadeOffKey != null) refreshRowByKey(item.cascadeOffKey);
                            updateMasterSwitches();
                            refreshConditionalItems();
                        }
                    });
                }
            }
        }

        private void rebind(FeatureItem item) {
            int position = items.indexOf(item);
            if (position >= 0) notifyItemChanged(position);
        }

        private void refreshRowByKey(String prefKey) {
            for (int i = 0; i < items.size(); i++) {
                if (prefKey.equals(items.get(i).prefKey)) {
                    notifyItemChanged(i);
                    break;
                }
            }
        }

        /** Refreshes any item whose enabled state depends on runtime conditions. */
        void refreshConditionalItems() {
            if ("focus".equals(currentMenu)) {
                recyclerFeatures.post(() -> {
                    if (isAdded() && "focus".equals(currentMenu)) loadFocusMenu();
                });
                return;
            }
            for (int i = 0; i < items.size(); i++) {
                FeatureItem fi = items.get(i);
                if (fi.requiresAnyOf != null || fi.disabledWhenTrue != null) {
                    notifyItemChanged(i);
                }
            }
        }

        private void updateMasterSwitches() {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).type == FeatureItem.TYPE_MASTER_SWITCH) {
                    notifyItemChanged(i);
                }
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private void bindIcon(ImageView iconView, int iconRes, int accentColor) {
            if (iconRes == 0) {
                iconView.setVisibility(View.GONE);
                return;
            }
            iconView.setVisibility(View.VISIBLE);
            android.graphics.drawable.Drawable icon = ContextCompat.getDrawable(iconView.getContext(), iconRes);
            if (icon != null) {
                icon = icon.mutate();
                icon.setColorFilter(new android.graphics.PorterDuffColorFilter(accentColor, android.graphics.PorterDuff.Mode.SRC_IN));
                iconView.setImageDrawable(icon);
            }
            android.graphics.drawable.GradientDrawable chipBg = new android.graphics.drawable.GradientDrawable();
            chipBg.setColor((accentColor & 0x00FFFFFF) | 0x33000000);
            chipBg.setCornerRadius(iconView.getResources().getDisplayMetrics().density * 10);
            iconView.setBackground(chipBg);
        }
    }

    private void showMenu(String key, String title, List<Object> definitions) {
        boolean inPlace = key.equals(currentMenu) && searchShown == null && adapter.getItemCount() > 0;
        searchShown = null;
        showMenu(key, title, definitions, true, inPlace);
    }

    /** @param animate false for live search updates (no header transition / list slide-in per keystroke). */
    private void showMenu(String key, String title, List<Object> definitions, boolean animate, boolean inPlace) {
        currentMenu = key;
        boolean isMain = "main".equals(key);
        animate = animate && !inPlace && !restoringMenu;
        if (animate) {
            ViewGroup topBar = fragmentView.findViewById(R.id.top_bar);
            TransitionManager.beginDelayedTransition(topBar, new AutoTransition().setDuration(200));
        }

        tvHeaderTitle.setText(title);
        fragmentView.findViewById(R.id.header_layout).setVisibility(isMain ? View.GONE : View.VISIBLE);
        btnBack.setVisibility(isMain ? View.GONE : View.VISIBLE);
        tilSearch.setVisibility(isMain ? View.VISIBLE : View.GONE);
        if (!isMain) clearSearchSilently();

        List<FeatureItem> displayList = new ArrayList<>();
        for (int di = 0; di < definitions.size(); di++) {
            Object def = definitions.get(di);
            if (def instanceof String) {
                FeatureItem header = new FeatureItem();
                header.type = FeatureItem.TYPE_HEADER;
                header.title = (String) def;
                displayList.add(header);
            } else if (def instanceof List) {
                @SuppressWarnings("unchecked")
                List<FeatureItem> group = (List<FeatureItem>) def;
                int cards = 0;
                for (FeatureItem item : group) if (item.type != FeatureItem.TYPE_HINT) cards++;
                int position = 0;
                for (FeatureItem item : group) {
                    if (item.type != FeatureItem.TYPE_HINT) {
                        item.segmentPosition = position++;
                        item.segmentSize = cards;
                    }
                    displayList.add(item);
                }
                if (di < definitions.size() - 1) {
                    FeatureItem spacer = new FeatureItem();
                    spacer.type = FeatureItem.TYPE_SPACER;
                    displayList.add(spacer);
                }
            }
        }

        updateBackEnabled();
        if (inPlace) {
            RecyclerView.LayoutManager manager = recyclerFeatures.getLayoutManager();
            android.os.Parcelable scroll = manager != null ? manager.onSaveInstanceState() : null;
            adapter.setItems(displayList);
            if (scroll != null) manager.onRestoreInstanceState(scroll);
            return;
        }
        if (!animate) {
            recyclerFeatures.animate().cancel();
            recyclerFeatures.setAlpha(1f);
            recyclerFeatures.setTranslationY(0f);
            adapter.setItems(displayList);
            if (!restoringMenu) recyclerFeatures.scrollToPosition(0);
            return;
        }

        recyclerFeatures.setAlpha(0f);
        recyclerFeatures.setTranslationY(60f);

        adapter.setItems(displayList);
        recyclerFeatures.scrollToPosition(0);

        recyclerFeatures.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private FeatureItem createNav(int iconRes, int accent, String title, Runnable navAction) {
        FeatureItem item = createClickable(iconRes, accent, title, navAction);
        item.isNav = true;
        return item;
    }

    private FeatureItem createClickable(int iconRes, int accent, String title, Runnable onClick) {
        FeatureItem item = new FeatureItem();
        item.type = FeatureItem.TYPE_CLICKABLE;
        item.title = title;
        item.onClick = onClick;
        item.iconRes = iconRes;
        item.accentColor = accent;
        return item;
    }

    private FeatureItem createValueRow(int iconRes, int accent, String label, String value, Runnable onClick) {
        FeatureItem item = createClickable(iconRes, accent, label, onClick);
        item.subtitle = value;
        return item;
    }

    private static FeatureItem destructive(FeatureItem item) {
        item.destructive = true;
        item.textColor = item.accentColor;
        return item;
    }

    private FeatureItem createTool(int iconRes, int accent, String title, String subtitle, Runnable onClick) {
        FeatureItem item = createClickable(iconRes, accent, title, onClick);
        item.subtitle = subtitle;
        return item;
    }

    private FeatureItem createSwitch(int iconRes, int accent, String title, String prefKey) {
        FeatureItem item = new FeatureItem();
        item.type = FeatureItem.TYPE_SWITCH;
        item.title = title;
        item.prefKey = prefKey;
        item.iconRes = iconRes;
        item.accentColor = accent;
        return item;
    }

    private FeatureItem createSwitch(int iconRes, String accentHex, String title, String prefKey) {
        return createSwitch(iconRes, Color.parseColor(accentHex), title, prefKey);
    }

    private FeatureItem createMasterSwitch(String title, List<String> childKeys) {
        FeatureItem item = new FeatureItem();
        item.type = FeatureItem.TYPE_MASTER_SWITCH;
        item.title = title;
        item.childKeys = childKeys;
        return item;
    }

    private static FeatureItem hintRow(String text) {
        FeatureItem item = new FeatureItem();
        item.type = FeatureItem.TYPE_HINT;
        item.title = text;
        return item;
    }

    public static final String MENU_REORGANISED_SEEN = "menuReorganisedSeen";
    private static final int DESTRUCTIVE = 0xFFFF453A;
    private static final Map<String, String> RETIRED_MENUS = retiredMenus();
    private static final Map<String, FeatureCatalog.Category> CATEGORY_OF_ITEM = categoryOfItem();
    private static final Set<String> FOCUS_LOCKED = focusLocked();
    private static final List<String> LOCK_SWITCHES = Arrays.asList(
            "lockWholeApp", "lockDirectMessages", "lockSpecificMessages", "lockDirectAlways", "lockSavedCollections");
    private static final int[] CORNER_NAMES = {R.string.ig_dialog_extras_share_domain_off, R.string.feat_corner_small,
            R.string.feat_corner_medium, R.string.feat_corner_large, R.string.feat_corner_extra_large,
            R.string.feat_corner_round};

    private Map<String, Supplier<FeatureItem>> factories;

    private static Map<String, String> retiredMenus() {
        Map<String, String> out = new HashMap<>();
        out.put("distract", "focus");
        out.put("hidechats", "lock");
        out.put("postmenu", "downloads");
        out.put("downloader", "downloads");
        return out;
    }

    private static Map<String, FeatureCatalog.Category> categoryOfItem() {
        Map<String, FeatureCatalog.Category> out = new HashMap<>();
        for (FeatureCatalog.Category c : FeatureCatalog.CATEGORIES) {
            for (FeatureCatalog.Item i : c.items()) out.put(i.key, c);
        }
        return out;
    }

    private static Set<String> focusLocked() {
        Set<String> out = new HashSet<>();
        for (FeatureCatalog.Section s : FeatureCatalog.category("focus").sections) {
            if (s.header != R.string.cat_sec_turn_off && s.header != R.string.cat_sec_reel_limits) continue;
            for (FeatureCatalog.Item i : s.items) out.add(i.key);
        }
        return out;
    }

    private Map<String, Supplier<FeatureItem>> factories() {
        if (factories != null) return factories;
        Map<String, Supplier<FeatureItem>> f = new HashMap<>();
        f.put("quickToggle", () -> {
            FeatureItem row = actionRow("quickToggle", () -> loadCategory("qt"));
            row.isNav = true;
            return row;
        });
        f.put("customShareDomain", () -> valueRow("customShareDomain", shareDomainValue(), this::showShareDomainDialog));
        f.put("exactTimestampFormat", () -> valueRow("exactTimestampFormat",
                timestampFormatName(localCache.getString("exactTimestampFormat", "")), this::showTimestampFormatDialog));
        f.put("feedCornerRadius", () -> valueRow("feedCornerRadius",
                feedCornerName(localCache.getInt("feedCornerRadius", 0)), this::chooseFeedCorner));
        f.put("navTabOrder", () -> actionRow("navTabOrder", this::showTabOrderDialog));
        f.put("startupTab", () -> valueRow("startupTab", startupTabValue(), this::showStartupTabDialog));
        f.put("storyTrayScale", () -> valueRow("storyTrayScale",
                localCache.getInt("storyTrayScale", 100) + "%", this::chooseStoryTraySize));
        f.put("storyRingColor", () -> valueRow("storyRingColor", storyRingColorValue(), this::chooseStoryRingColor));
        f.put("forceReelQuality", () -> {
            int current = ps.reso.instaeclipse.utils.core.SettingsManager.readReelQuality(localCache);
            return valueRow("forceReelQuality", qualityLabel(current), () -> pickQuality(current));
        });
        f.put("spoofDevice", () -> valueRow("spoofDevice", spoofDeviceValue(), this::showSpoofDeviceDialog));
        f.put("downloaderUseOriginal", () -> valueRow("downloaderUseOriginal", downloadModeLabel(), this::chooseDownloadQuality));
        f.put("downloaderCompatibility", () -> bestOnly("downloaderCompatibility"));
        f.put("downloaderAllowFallback", () -> bestOnly("downloaderAllowFallback"));
        f.put("downloadFolder", () -> valueRow("downloadFolder", downloadFolderValue(), this::pickDownloadFolder));
        f.put("downloadFolderReset", () -> {
            if (!hasCustomFolder()) return null;
            FeatureCatalog.Item item = FeatureCatalog.item("downloadFolderReset");
            return destructive(createClickable(item.icon, DESTRUCTIVE, getString(item.label), this::confirmResetDownloadFolder));
        });
        f.put("storyCacheHours", this::storyCacheItem);
        f.put("locationPlace", this::locationPlaceItem);
        f.put("themeCustomize", () -> actionRow("themeCustomize", () ->
                themeCustomizerLauncher.launch(new Intent(requireContext(), ThemeCustomizerActivity.class))));
        f.put("themePresetId", () -> isNightMode() ? valueRow("themePresetId",
                darkModeLabel(localCache.getInt("themePresetId", 1)), this::pickDarkModeTheme) : null);
        f.put("themeLightPresetId", () -> isNightMode() ? null : valueRow("themeLightPresetId",
                lightModeLabel(localCache.getInt("themeLightPresetId", -1)), this::pickLightModeTheme));
        f.put("profileSpoofEdit", () -> actionRow("profileSpoofEdit", this::showProfileSpoofDialog));
        f.put("profilePhoto", () -> actionRow("profilePhoto", () ->
                startActivity(new Intent(requireContext(), ps.reso.instaeclipse.ui.ProfilePhotoPickerActivity.class))));
        f.put("autoClearCacheSizeMb", () -> {
            FeatureCatalog.Item item = FeatureCatalog.item("autoClearCacheSizeMb");
            FeatureItem row = createClickable(item.icon, accentOf(item), getString(item.label,
                    localCache.getInt("autoClearCacheSizeMb", 100)), this::pickAutoClearSize);
            applyRequires(row, item);
            return row;
        });
        f.put("settingsBackup", () -> actionRow("settingsBackup", this::backupSettings));
        f.put("settingsRestore", () -> actionRow("settingsRestore", this::restoreSettings));
        f.put("restartNow", () -> actionRow("restartNow", this::restartInstagram));
        f.put("devImport", () -> actionRow("devImport", this::importDevConfig));
        f.put("devExport", () -> actionRow("devExport", this::exportDevConfig));
        f.put("devReset", () -> actionRow("devReset", this::restoreDefaultConfig));
        f.put("qualityPointer", () -> {
            FeatureCatalog.Category downloads = FeatureCatalog.category("downloads");
            return createNav(downloads.icon, downloads.group.accent,
                    getString(FeatureCatalog.item("qualityPointer").label), () -> loadCategory("downloads"));
        });
        for (String lock : LOCK_SWITCHES) {
            f.put(lock, () -> {
                FeatureCatalog.Item item = FeatureCatalog.item(lock);
                FeatureItem row = createLockSwitch(item.icon, getString(item.label), lock);
                row.subtitle = describe(item);
                return row;
            });
        }
        factories = f;
        return f;
    }

    public Set<String> renderedKeysForTest() {
        Set<String> out = new HashSet<>();
        for (FeatureCatalog.Category cat : FeatureCatalog.CATEGORIES) {
            out.add(cat.key);
            for (FeatureCatalog.Item item : cat.items()) {
                boolean generic = item.kind == FeatureCatalog.Kind.SWITCH || item.kind == FeatureCatalog.Kind.MASTER
                        || item.kind == FeatureCatalog.Kind.INFO
                        || item.kind == FeatureCatalog.Kind.ACTION && item.side == FeatureCatalog.Side.IG_ONLY;
                if (generic || "focus".equals(cat.key) || "locationRecent".equals(item.key)
                        || factories().containsKey(item.key)) {
                    out.add(item.key);
                }
            }
        }
        return out;
    }

    private void loadMainMenu() {
        showMenu("main", getString(R.string.features), mainDefs());
    }

    private List<Object> mainDefs() {
        List<Object> defs = new ArrayList<>();
        if (ps.reso.instaeclipse.utils.feature.SpoofLastSeenNotice.shouldShow(localCache)) {
            FeatureItem notice = new FeatureItem();
            notice.type = FeatureItem.TYPE_NOTICE;
            notice.title = getString(R.string.ie_spoof_last_seen_removed);
            notice.onClick = this::dismissSpoofLastSeenNotice;
            defs.add(Collections.singletonList(notice));
        }
        if (!CompanionUiPrefs.get(requireContext()).getBoolean(MENU_REORGANISED_SEEN, false)) {
            FeatureItem card = new FeatureItem();
            card.type = FeatureItem.TYPE_NOTICE;
            card.title = getString(R.string.cat_menu_reorganised_title);
            card.subtitle = getString(R.string.cat_menu_reorganised_body);
            card.onClick = this::dismissMenuReorganised;
            defs.add(Collections.singletonList(card));
        }
        FeatureCatalog.Values values = cacheValues();
        for (FeatureCatalog.Group group : FeatureCatalog.Group.values()) {
            List<FeatureItem> rows = new ArrayList<>();
            for (FeatureCatalog.Category cat : FeatureCatalog.CATEGORIES) {
                if (cat.hidden || cat.group != group) continue;
                FeatureItem row = categoryRow(cat, values);
                row.isCategory = true;
                rows.add(row);
            }
            addGroup(defs, getString(group.title), rows);
        }
        return defs;
    }

    private FeatureItem categoryRow(FeatureCatalog.Category cat, FeatureCatalog.Values values) {
        String title = getString(cat.title);
        int accent = cat.group.accent;
        if ("restart".equals(cat.key)) {
            return createTool(cat.icon, accent, title, getString(R.string.tool_restart_sub), this::restartInstagram);
        }
        if ("about".equals(cat.key)) {
            return createTool(cat.icon, accent, title, getString(R.string.tool_about_sub), this::openHelpAbout);
        }
        FeatureItem row = createNav(cat.icon, accent, title, () -> openCategory(cat.key));
        if (countable(cat)) {
            int on = FeatureCatalog.countOn(cat, values);
            row.subtitle = on > 0 ? getString(R.string.feat_count_on, on) : getString(R.string.feat_count_off);
            row.subtitleActive = on > 0;
        }
        return row;
    }

    private static boolean countable(FeatureCatalog.Category cat) {
        for (FeatureCatalog.Item item : cat.items()) {
            if (item.kind == FeatureCatalog.Kind.SWITCH && item.counts) return true;
            if (item.kind == FeatureCatalog.Kind.PICKER && item.countWhen != null) return true;
        }
        return false;
    }

    private FeatureCatalog.Values cacheValues() {
        Map<String, ?> all = localCache.getAll();
        return new FeatureCatalog.Values() {
            @Override public boolean bool(String key, boolean def) {
                Object v = all.get(key);
                return v instanceof Boolean ? (Boolean) v : def;
            }

            @Override public int integer(String key, int def) {
                Object v = all.get(key);
                return v instanceof Integer ? (Integer) v : def;
            }

            @Override public String string(String key, String def) {
                Object v = all.get(key);
                return v instanceof String ? (String) v : def;
            }
        };
    }

    private static void addGroup(List<Object> defs, String header, List<FeatureItem> rows) {
        if (rows.isEmpty()) return;
        if (header != null) defs.add(header);
        defs.add(rows);
    }

    private void dismissSpoofLastSeenNotice() {
        ps.reso.instaeclipse.utils.feature.SpoofLastSeenNotice.dismiss(localCache);
        write(ps.reso.instaeclipse.utils.feature.SpoofLastSeenNotice.KEY, false);
        if ("main".equals(currentMenu) && !isSearching()) loadMainMenu();
    }

    private void dismissMenuReorganised() {
        CompanionUiPrefs.get(requireContext()).edit().putBoolean(MENU_REORGANISED_SEEN, true).apply();
        if ("main".equals(currentMenu) && !isSearching()) loadMainMenu();
    }

    private void openCategory(String key) {
        if ("focus".equals(key)) openFocusMenu();
        else if ("restart".equals(key)) restartInstagram();
        else if ("about".equals(key)) openHelpAbout();
        else loadCategory(key);
    }

    private void loadCategory(String key) {
        FeatureCatalog.Category cat = FeatureCatalog.category(key);
        if (cat == null || "restart".equals(key) || "about".equals(key)) {
            loadMainMenu();
        } else if ("focus".equals(key)) {
            loadFocusMenu();
        } else {
            showMenu(key, getString(cat.title), pageDefs(cat));
        }
    }

    private void loadParentMenu() {
        if ("qt".equals(currentMenu)) loadCategory("ghost");
        else loadMainMenu();
    }

    private void restoreMenu(String key) {
        restoringMenu = true;
        try {
            String menu = RETIRED_MENUS.containsKey(key) ? RETIRED_MENUS.get(key) : key;
            if ("main".equals(menu)) loadMainMenu();
            else loadCategory(menu);
        } finally {
            restoringMenu = false;
        }
    }

    private void updateBackEnabled() {
        if (backCallback != null) backCallback.setEnabled(isResumed() && !isHidden() && (!"main".equals(currentMenu) || isSearching()));
    }

    @Override
    public void onTabReselected() {
        if (recyclerFeatures == null) return;
        if ("main".equals(currentMenu)) recyclerFeatures.smoothScrollToPosition(0);
        else loadMainMenu();
    }

    private void reloadCurrentMenu() {
        if (adapter == null || localCache == null || !isAdded() || getView() == null) return;
        if (isSearching()) {
            runSearch(etSearch.getText().toString());
        } else if ("main".equals(currentMenu)) {
            loadMainMenu();
        } else {
            loadCategory(currentMenu);
        }
    }

    private List<Object> pageDefs(FeatureCatalog.Category cat) {
        List<Object> defs = new ArrayList<>();
        for (FeatureCatalog.Section section : cat.sections) {
            String header = section.header != 0 ? getString(section.header) : null;
            List<FeatureItem> rows = new ArrayList<>();
            for (FeatureCatalog.Item item : section.items) {
                if ("locationRecent".equals(item.key)) {
                    addGroup(defs, header, rows);
                    header = null;
                    rows = new ArrayList<>();
                    addGroup(defs, getString(R.string.ig_dialog_location_recent), recentPlaceRows());
                    continue;
                }
                if (!shownOnPage(item)) continue;
                FeatureItem row = rowFor(item);
                if (row != null) rows.add(row);
            }
            addGroup(defs, header, rows);
        }
        return defs;
    }

    private boolean shownOnPage(FeatureCatalog.Item item) {
        if ("downloaderCompatibility".equals(item.key) || "downloaderAllowFallback".equals(item.key)) {
            return !getCurrentState("downloaderUseOriginal");
        }
        return item.kind != FeatureCatalog.Kind.INFO || anyOn(item.requires);
    }

    private boolean anyOn(String[] keys) {
        if (keys.length == 0) return true;
        for (String key : keys) if (getCurrentState(key)) return true;
        return false;
    }

    private FeatureItem rowFor(FeatureCatalog.Item item) {
        FeatureCatalog.Category cat = CATEGORY_OF_ITEM.get(item.key);
        if (cat != null && "focus".equals(cat.key)) return focusRow(item);
        Supplier<FeatureItem> factory = factories().get(item.key);
        if (factory != null) return factory.get();
        int accent = accentOf(item);
        switch (item.kind) {
            case SWITCH:
                return catalogSwitch(item, accent);
            case MASTER:
                return createMasterSwitch(getString(R.string.cat_all), Arrays.asList(item.children));
            case ACTION:
                return item.side == FeatureCatalog.Side.IG_ONLY ? pointerRow(item, accent) : null;
            case INFO:
                return hintRow(getString(item.label));
            default:
                return null;
        }
    }

    private static int accentOf(FeatureCatalog.Item item) {
        FeatureCatalog.Category cat = CATEGORY_OF_ITEM.get(item.key);
        return cat != null ? cat.group.accent : FeatureCatalog.Group.TOOLS.accent;
    }

    private FeatureItem catalogSwitch(FeatureCatalog.Item item, int accent) {
        FeatureItem row = createSwitch(item.icon, accent, getString(item.label), item.key);
        row.subtitle = describe(item);
        applyRequires(row, item);
        if (item.overriddenBy != null && getCurrentState(item.overriddenBy)) {
            row.subtitle = lines(row.subtitle, getString(R.string.cat_also_on_with, labelOf(item.overriddenBy)));
        }
        return row;
    }

    private FeatureItem pointerRow(FeatureCatalog.Item item, int accent) {
        FeatureItem row = createTool(item.icon, accent, getString(item.label), getString(R.string.cat_in_instagram),
                this::openInstagram);
        applyRequires(row, item);
        return row;
    }

    private FeatureItem valueRow(String key, String value, Runnable onClick) {
        FeatureCatalog.Item item = FeatureCatalog.item(key);
        FeatureItem row = createValueRow(item.icon, accentOf(item), getString(item.label), value, onClick);
        applyRequires(row, item);
        return row;
    }

    private FeatureItem actionRow(String key, Runnable onClick) {
        FeatureCatalog.Item item = FeatureCatalog.item(key);
        FeatureItem row = createClickable(item.icon, accentOf(item), getString(item.label), onClick);
        row.subtitle = describe(item);
        applyRequires(row, item);
        return row;
    }

    private FeatureItem bestOnly(String key) {
        FeatureItem row = catalogSwitch(FeatureCatalog.item(key), accentOf(FeatureCatalog.item(key)));
        row.enabled = !getCurrentState("downloaderUseOriginal");
        row.overrideReason = getString(R.string.feat_reason_best_only);
        return row;
    }

    private String describe(FeatureCatalog.Item item) {
        String text = item.desc != 0 ? getString(item.desc) : null;
        if (item.restartOn != FeatureCatalog.Restart.NONE) text = lines(text, getString(R.string.ig_restart_to_apply_hint));
        return text;
    }

    private static String lines(String first, String second) {
        if (first == null) return second;
        return second == null ? first : first + "\n" + second;
    }

    private void applyRequires(FeatureItem row, FeatureCatalog.Item item) {
        if (item.requires.length == 0) return;
        row.requiresAnyOf = Arrays.asList(item.requires);
        if (item.requires.length == 1) {
            row.requiresReason = getString(R.string.cat_requires, labelOf(item.requires[0]));
        } else {
            row.requiresReason = getString(LOCK_SWITCHES.containsAll(Arrays.asList(item.requires))
                    ? R.string.feat_requires_lock : R.string.feat_requires_above);
        }
    }

    private String labelOf(String key) {
        FeatureCatalog.Item item = FeatureCatalog.item(key);
        return item != null ? getString(item.label) : key;
    }

    private String reasonFor(FeatureItem item) {
        if (item.disabledWhenTrue != null && getCurrentState(item.disabledWhenTrue)) return item.disabledReason;
        if (!item.enabled && item.overrideReason != null) return item.overrideReason;
        if (!requirementMet(item) && item.requiresReason != null) return item.requiresReason;
        return item.disabledReason;
    }

    /** True when the row has no {@link FeatureItem#requiresAnyOf} or one of those keys is on. */
    private boolean requirementMet(FeatureItem item) {
        if (item.requiresAnyOf == null) return true;
        for (String key : item.requiresAnyOf) {
            if (getCurrentState(key)) return true;
        }
        return false;
    }

    private boolean isSearching() {
        return etSearch != null && etSearch.getText() != null && etSearch.getText().toString().trim().length() > 0;
    }

    /** Clears the search field without re-rendering (used when navigating away from the main menu). */
    private void clearSearchSilently() {
        if (etSearch == null) return;
        if (etSearch.getText() != null && etSearch.getText().length() > 0) {
            suppressSearchWatcher = true;
            etSearch.setText("");
            suppressSearchWatcher = false;
        }
        if (etSearch.hasFocus()) {
            etSearch.clearFocus();
            hideKeyboard();
        }
    }

    private void hideKeyboard() {
        if (etSearch == null) return;
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
    }

    private void runSearch(String raw) {
        String query = raw == null ? "" : raw.trim();
        String folded = SearchText.fold(query);
        if (folded.isEmpty()) {
            loadMainMenu();
            return;
        }
        List<Object> defs = new ArrayList<>();
        for (FeatureCatalog.Category cat : FeatureCatalog.CATEGORIES) {
            List<FeatureItem> hits = new ArrayList<>();
            for (FeatureCatalog.Item item : cat.items()) {
                if (item.kind == FeatureCatalog.Kind.MASTER || !FeatureCatalog.matches(item, cat, query, this::getString)) {
                    continue;
                }
                if ("locationRecent".equals(item.key)) {
                    hits.addAll(recentPlaceRows());
                    continue;
                }
                FeatureItem row = rowFor(item);
                if (row != null) hits.add(row);
            }
            addGroup(defs, getString(cat.title), hits);
        }
        if (defs.isEmpty()) {
            FeatureItem empty = new FeatureItem();
            empty.type = FeatureItem.TYPE_EMPTY;
            defs.add(Collections.singletonList(empty));
        }
        boolean inPlace = folded.equals(searchShown) && "main".equals(currentMenu);
        searchShown = folded;
        showMenu("main", getString(R.string.features), defs, false, inPlace);
    }

    private void openFocusMenu() {
        reelUsageSynced = false;
        loadFocusMenu();
        requestPrefs();
    }

    private void loadFocusMenu() {
        FeatureCatalog.Category cat = FeatureCatalog.category("focus");
        List<Object> defs = new ArrayList<>();
        for (FeatureCatalog.Section section : cat.sections) {
            List<FeatureItem> rows = new ArrayList<>();
            for (FeatureCatalog.Item item : section.items) {
                if (!focusShown(item.key)) continue;
                FeatureItem row = focusRow(item);
                if (row != null) rows.add(row);
            }
            addGroup(defs, section.header != 0 ? getString(section.header) : null, rows);
        }
        showMenu("focus", getString(cat.title), defs);
    }

    private boolean focusShown(String key) {
        switch (key) {
            case "disableReelsExceptDM":
                return getCurrentState("disableReels");
            case "dailyReelLimit":
            case "dailyReelLimitAllowDM":
                return getCurrentState("dailyReelLimitEnabled");
            case "nightReelBlockStartMinutes":
            case "nightReelBlockEndMinutes":
            case "nightReelStatus":
            case "nightReelBlockAllowDM":
                return getCurrentState("nightReelBlockEnabled");
            default:
                return true;
        }
    }

    private FeatureItem focusRow(FeatureCatalog.Item item) {
        int accent = FeatureCatalog.Group.BROWSE.accent;
        FeatureItem row;
        switch (item.key) {
            case "reelUsage":
                return reelUsageRow(accent);
            case "nightReelStatus":
                return hintRow(nightReelStatus());
            case "isExtremeMode":
                return extremeRow(item);
            case "focusAll":
                row = createMasterSwitch(getString(R.string.cat_all), Arrays.asList(item.children));
                break;
            case "dailyReelLimit":
                row = reelLimitRow(item, accent);
                break;
            case "nightReelBlockStartMinutes":
            case "nightReelBlockEndMinutes":
                row = nightTimeRow(item, accent);
                break;
            default:
                row = catalogSwitch(item, accent);
        }
        if ("disableReels".equals(item.key)) row.cascadeOffKey = "disableReelsExceptDM";
        if (item.overriddenBy != null) {
            row.enabled = !getCurrentState(item.overriddenBy);
            row.overrideReason = getString(R.string.feat_reason_overridden, labelOf(item.overriddenBy));
        }
        if (FOCUS_LOCKED.contains(item.key)) lockedByExtreme(row);
        return row;
    }

    private FeatureItem extremeRow(FeatureCatalog.Item item) {
        FeatureItem row = destructive(createSwitch(item.icon, DESTRUCTIVE, getString(item.label), item.key));
        row.subtitle = describe(item);
        row.isExtreme = true;
        applyRequires(row, item);
        row.disabledWhenTrue = "isExtremeMode";
        row.disabledReason = getString(R.string.ig_extreme_active);
        return row;
    }

    private FeatureItem reelLimitRow(FeatureCatalog.Item item, int accent) {
        int limit = ps.reso.instaeclipse.mods.reels.DailyReelLimit.validLimit(localCache.getInt("dailyReelLimit", 100));
        FeatureItem row = createValueRow(item.icon, accent, getString(item.label), String.valueOf(limit),
                () -> ps.reso.instaeclipse.utils.dialog.DialogUtils.showDailyReelLimitPicker(requireContext(), limit, value -> {
                    if (!reelLimitsEditable() || !getCurrentState("dailyReelLimitEnabled")) return;
                    write("dailyReelLimit", value);
                    reloadCurrentMenu();
                }));
        applyRequires(row, item);
        return row;
    }

    private int nightMinutes(String key) {
        int fallback = "nightReelBlockStartMinutes".equals(key) ? 22 * 60 : 7 * 60;
        return ps.reso.instaeclipse.mods.reels.NightReelSchedule.validMinutes(localCache.getInt(key, fallback), fallback);
    }

    private FeatureItem nightTimeRow(FeatureCatalog.Item item, int accent) {
        String other = "nightReelBlockStartMinutes".equals(item.key) ? "nightReelBlockEndMinutes" : "nightReelBlockStartMinutes";
        int minutes = nightMinutes(item.key);
        int otherMinutes = nightMinutes(other);
        FeatureItem row = createValueRow(item.icon, accent, getString(item.label),
                ps.reso.instaeclipse.utils.dialog.DialogUtils.formatNightReelTime(requireContext(), minutes),
                () -> ps.reso.instaeclipse.utils.dialog.DialogUtils.showNightReelTimePicker(
                        requireContext(), minutes, selected -> {
                            if (selected == otherMinutes) {
                                ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), getString(R.string.ig_night_reels_same_time), Toast.LENGTH_SHORT).show();
                                return;
                            }
                            updateNightReelTime(item.key, selected);
                        }));
        applyRequires(row, item);
        return row;
    }

    private String nightReelStatus() {
        int startMinutes = nightMinutes("nightReelBlockStartMinutes");
        int endMinutes = nightMinutes("nightReelBlockEndMinutes");
        String start = ps.reso.instaeclipse.utils.dialog.DialogUtils.formatNightReelTime(requireContext(), startMinutes);
        String end = ps.reso.instaeclipse.utils.dialog.DialogUtils.formatNightReelTime(requireContext(), endMinutes);
        boolean active = ps.reso.instaeclipse.mods.reels.NightReelSchedule.isActiveAt(
                ps.reso.instaeclipse.mods.reels.NightReelSchedule.currentMinutes(), startMinutes, endMinutes);
        return getString(active ? R.string.ig_night_reels_status_active
                : R.string.ig_night_reels_status_inactive, active ? end : start);
    }

    private FeatureItem reelUsageRow(int accent) {
        int limit = ps.reso.instaeclipse.mods.reels.DailyReelLimit.validLimit(localCache.getInt("dailyReelLimit", 100));
        boolean today = java.time.LocalDate.now().toString().equals(reelUsageDate);
        String count = getString(R.string.ig_reel_debug_waiting);
        if (reelUsageSynced && today) {
            count = getCurrentState("dailyReelLimitEnabled")
                    ? getString(R.string.ig_reel_limit_usage, reelsWatchedToday, limit)
                    : getString(R.string.ig_reel_usage_count, reelsWatchedToday);
        }
        return createClickable(R.drawable.ic_timer, accent, count, this::openFocusMenu);
    }

    private FeatureItem lockedByExtreme(FeatureItem item) {
        item.disabledWhenTrue = "isExtremeMode";
        item.disabledReason = getString(R.string.home_shortcuts_locked_extreme);
        return item;
    }

    private boolean reelLimitsEditable() {
        return !getCurrentState("isExtremeMode") && !getCurrentState("disableReels");
    }

    private void updateNightReelTime(String key, int value) {
        if (!reelLimitsEditable() || !getCurrentState("nightReelBlockEnabled")) return;
        write(key, value);
        reloadCurrentMenu();
    }

    private String feedCornerName(int dp) {
        int[] radii = ps.reso.instaeclipse.utils.dialog.DialogUtils.FEED_CORNER_RADII;
        for (int i = 0; i < radii.length && i < CORNER_NAMES.length; i++) {
            if (radii[i] == dp) return getString(CORNER_NAMES[i]);
        }
        return dp <= 0 ? getString(R.string.ig_dialog_extras_share_domain_off) : dp + " dp";
    }

    private void chooseFeedCorner() {
        int[] radii = ps.reso.instaeclipse.utils.dialog.DialogUtils.FEED_CORNER_RADII;
        String[] labels = new String[radii.length];
        int current = localCache.getInt("feedCornerRadius", 0), selected = 0;
        for (int i = 0; i < radii.length; i++) {
            labels[i] = feedCornerName(radii[i]);
            if (radii[i] == current) selected = i;
        }
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.ig_feed_corner_radius)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    write("feedCornerRadius", radii[which]);
                    dialog.dismiss(); reloadCurrentMenu();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    /** #238: reorder the bottom-nav tabs (saved as the "navTabOrder" string pref). */
    private void showTabOrderDialog() {
        final String[] ids = ps.reso.instaeclipse.mods.ui.DistractionFreeUIHook.ORDERABLE_TABS;
        final java.util.Map<String, String> labels = new java.util.HashMap<>();
        labels.put("feed_tab", getString(R.string.ig_nav_name_home));
        labels.put("clips_tab", getString(R.string.ig_nav_name_reels));
        labels.put("search_tab", getString(R.string.ig_nav_name_search));
        labels.put("creation_tab", getString(R.string.ig_nav_name_create));
        labels.put("direct_tab", getString(R.string.ig_nav_name_messages));
        labels.put("news_tab", getString(R.string.ig_nav_name_activity));
        labels.put("profile_tab", getString(R.string.ig_nav_name_profile));
        final java.util.Map<String, String> hideKeys = new java.util.HashMap<>();
        hideKeys.put("feed_tab", "hideTabHome");
        hideKeys.put("clips_tab", "hideTabReels");
        hideKeys.put("creation_tab", "hideTabCreate");
        hideKeys.put("direct_tab", "hideTabMessages");
        hideKeys.put("news_tab", "hideTabActivity");
        hideKeys.put("profile_tab", "hideTabProfile");
        final java.util.List<String> order = new java.util.ArrayList<>();
        for (String n : localCache.getString("navTabOrder", "").split(","))
            if (java.util.Arrays.asList(ids).contains(n.trim()) && !order.contains(n.trim())) order.add(n.trim());
        for (String n : ids) if (!order.contains(n)) order.add(n);

        final android.widget.LinearLayout list = new android.widget.LinearLayout(requireContext());
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        list.setPadding(pad, pad / 2, pad, 0);
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            list.removeAllViews();
            for (int i = 0; i < order.size(); i++) {
                final int idx = i;
                android.widget.LinearLayout row = new android.widget.LinearLayout(requireContext());
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                android.widget.TextView name = new android.widget.TextView(requireContext());
                String label = labels.get(order.get(i));
                String hideKey = hideKeys.get(order.get(i));
                name.setText((i + 1) + ".  " + (hideKey != null && getCurrentState(hideKey)
                        ? getString(R.string.feat_tab_hidden, label) : label));
                name.setTextSize(16);
                row.addView(name, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                for (final int dir : new int[]{-1, 1}) {
                    android.widget.Button b = new android.widget.Button(requireContext(), null, android.R.attr.borderlessButtonStyle);
                    b.setText(dir < 0 ? "↑" : "↓");
                    b.setContentDescription(getString(dir < 0 ? R.string.feat_tab_move_up : R.string.feat_tab_move_down, label));
                    b.setEnabled(dir < 0 ? idx > 0 : idx < order.size() - 1);
                    b.setOnClickListener(v -> {
                        java.util.Collections.swap(order, idx, idx + dir);
                        render[0].run();
                    });
                    row.addView(b, new android.widget.LinearLayout.LayoutParams(
                            (int) (48 * getResources().getDisplayMetrics().density), ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                list.addView(row);
            }
        };
        render[0].run();
        android.widget.TextView hint = new android.widget.TextView(requireContext());
        hint.setText(R.string.ig_nav_tab_order_hint);
        hint.setPadding(0, pad / 2, 0, 0);
        android.widget.LinearLayout box = new android.widget.LinearLayout(requireContext());
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.addView(list);
        box.addView(hint);
        box.setPadding(pad, 0, pad, 0);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ig_nav_tab_order)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        saveTabOrder(order.equals(Arrays.asList(ids)) ? "" : String.join(",", order)))
                .setNeutralButton(R.string.ig_nav_tab_order_reset, (d, w) -> saveTabOrder(""))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String shareDomainValue() {
        String d = localCache.getString("customShareDomain", "");
        return d.isEmpty() ? getString(R.string.ig_dialog_extras_share_domain_off) : d;
    }

    /** Custom share domain (saved as the "customShareDomain" string pref). */
    private void showShareDomainDialog() {
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("ddinstagram.com");
        input.setText(localCache.getString("customShareDomain", ""));
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(requireContext());
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ig_dialog_extras_share_domain)
                .setMessage(R.string.ig_dialog_extras_share_domain_hint)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String domain = ps.reso.instaeclipse.mods.ads.ShareDomain.normalize(input.getText().toString());
                    if (domain == null) {
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), R.string.ig_dialog_extras_share_domain_invalid,
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    write("customShareDomain", domain);
                    reloadCurrentMenu();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String[] startupTabNames() {
        return new String[]{
                getString(R.string.ig_story_ring_default),
                getString(R.string.ig_nav_name_home),
                getString(R.string.ig_nav_name_reels),
                getString(R.string.ig_nav_name_messages),
                getString(R.string.ig_nav_name_search),
                getString(R.string.ig_nav_name_profile),
                getString(R.string.ig_nav_name_activity)};
    }

    private String timestampFormatName(String pattern) {
        String sample = ps.reso.instaeclipse.utils.text.TimestampFormat.format(pattern, new java.util.Date(), java.util.Locale.getDefault());
        return pattern.isEmpty() ? getString(R.string.ig_timestamp_format_default) + "  ·  " + sample : sample;
    }

    /** Presets shown as today's date in that format, plus a custom pattern with a live preview. */
    private void showTimestampFormatDialog() {
        String[] presets = ps.reso.instaeclipse.utils.text.TimestampFormat.PRESETS;
        String current = localCache.getString("exactTimestampFormat", "");
        String[] rows = new String[presets.length + 1];
        int checked = presets.length;
        for (int i = 0; i < presets.length; i++) {
            rows[i] = timestampFormatName(presets[i]);
            if (presets[i].equals(current)) checked = i;
        }
        rows[presets.length] = checked == presets.length
                ? getString(R.string.ig_timestamp_format_custom) + "  ·  " + timestampFormatName(current)
                : getString(R.string.ig_timestamp_format_custom);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ig_timestamp_format)
                .setSingleChoiceItems(rows, checked, (d, which) -> {
                    d.dismiss();
                    if (which == presets.length) showCustomTimestampFormat();
                    else saveTimestampFormat(presets[which]);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCustomTimestampFormat() {
        android.content.Context c = requireContext();
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout box = new android.widget.LinearLayout(c);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        final android.widget.EditText input = new android.widget.EditText(c);
        input.setSingleLine(true);
        String current = localCache.getString("exactTimestampFormat", "");
        input.setText(current.isEmpty() ? "yyyy-MM-dd HH:mm:ss" : current);
        final android.widget.TextView preview = new android.widget.TextView(c);
        preview.setPadding(0, pad / 2, 0, 0);
        preview.setTextSize(16);
        box.addView(input);
        box.addView(preview);
        Runnable update = () -> {
            String p = input.getText().toString();
            preview.setText(ps.reso.instaeclipse.utils.text.TimestampFormat.isValid(p) && !p.isEmpty()
                    ? ps.reso.instaeclipse.utils.text.TimestampFormat.format(p, new java.util.Date(), java.util.Locale.getDefault())
                    : getString(R.string.ig_timestamp_format_invalid));
        };
        input.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int n) {}
            public void onTextChanged(CharSequence s, int a, int b, int n) {}
            public void afterTextChanged(android.text.Editable s) { update.run(); }
        });
        update.run();
        new MaterialAlertDialogBuilder(c)
                .setTitle(R.string.ig_timestamp_format_custom)
                .setMessage(R.string.ig_timestamp_format_custom_hint)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String p = input.getText().toString().trim();
                    if (!ps.reso.instaeclipse.utils.text.TimestampFormat.isValid(p)) {
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(c, R.string.ig_timestamp_format_invalid,
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    saveTimestampFormat(p);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveTimestampFormat(String pattern) {
        write("exactTimestampFormat", pattern);
        reloadCurrentMenu();
    }

    private String[] spoofDeviceNames() {
        String[] ids = ps.reso.instaeclipse.mods.misc.DeviceSpoofHook.DEVICES;
        String[] names = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            String n = ps.reso.instaeclipse.mods.misc.DeviceSpoofHook.deviceName(ids[i]);
            names[i] = n != null ? n : getString(R.string.cat_value_this_phone);
        }
        return names;
    }

    private String spoofDeviceValue() {
        int i = Arrays.asList(ps.reso.instaeclipse.mods.misc.DeviceSpoofHook.DEVICES).indexOf(localCache.getString("spoofDevice", ""));
        return spoofDeviceNames()[Math.max(i, 0)];
    }

    /** Which device Instagram sees (saved as the "spoofDevice" string pref). */
    private void showSpoofDeviceDialog() {
        String[] ids = ps.reso.instaeclipse.mods.misc.DeviceSpoofHook.DEVICES;
        int current = Math.max(Arrays.asList(ids).indexOf(localCache.getString("spoofDevice", "")), 0);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.cat_spoofDevice)
                .setSingleChoiceItems(spoofDeviceNames(), current, (d, which) -> {
                    write("spoofDevice", ids[which]);
                    d.dismiss();
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), R.string.ig_spoof_device_note, Toast.LENGTH_LONG).show();
                    reloadCurrentMenu();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String startupTabValue() {
        int i = Arrays.asList(ps.reso.instaeclipse.mods.ui.StartupTabHook.TABS).indexOf(localCache.getString("startupTab", ""));
        return startupTabNames()[Math.max(i, 0)];
    }

    /** Which tab Instagram opens on (saved as the "startupTab" string pref). */
    private void showStartupTabDialog() {
        String[] tabs = ps.reso.instaeclipse.mods.ui.StartupTabHook.TABS;
        int current = Math.max(Arrays.asList(tabs).indexOf(localCache.getString("startupTab", "")), 0);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ig_dialog_extras_startup_tab)
                .setSingleChoiceItems(startupTabNames(), current, (d, which) -> {
                    write("startupTab", tabs[which]);
                    d.dismiss();
                    reloadCurrentMenu();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveTabOrder(String value) {
        write("navTabOrder", value);
        reloadCurrentMenu();
    }

    /** The open spoofer editor, so a picked photo lands in it. */
    private ps.reso.instaeclipse.utils.dialog.ProfileSpoofEditor profileSpoofEditor;

    /** Same editor as inside Instagram (preview + grouped fields); the photo is picked here. */
    private void showProfileSpoofDialog() {
        Context c = requireContext();
        ps.reso.instaeclipse.utils.dialog.ProfileSpoofEditor.Values v = new ps.reso.instaeclipse.utils.dialog.ProfileSpoofEditor.Values();
        v.username = localCache.getString("spoofUsername", "");
        v.name = localCache.getString("spoofName", "");
        v.bio = localCache.getString("spoofBio", "");
        v.avatar = localCache.getString("spoofAvatar", "");
        v.posts = localCache.getInt("spoofPosts", -1);
        v.followers = localCache.getInt("spoofFollowers", -1);
        v.following = localCache.getInt("spoofFollowing", -1);
        v.verified = localCache.getBoolean("spoofVerified", false);
        int text = com.google.android.material.color.MaterialColors.getColor(c, com.google.android.material.R.attr.colorOnSurface, Color.WHITE);
        int sub = com.google.android.material.color.MaterialColors.getColor(c, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY);
        int field = com.google.android.material.color.MaterialColors.getColor(c, com.google.android.material.R.attr.colorSurfaceContainerHighest, Color.DKGRAY);
        int accent = com.google.android.material.color.MaterialColors.getColor(c, androidx.appcompat.R.attr.colorPrimary, 0xFF0095F6);
        int border = com.google.android.material.color.MaterialColors.getColor(c, com.google.android.material.R.attr.colorOutlineVariant, 0x33808080);
        ps.reso.instaeclipse.utils.dialog.ProfileSpoofEditor editor = new ps.reso.instaeclipse.utils.dialog.ProfileSpoofEditor(c, v,
                new ps.reso.instaeclipse.utils.dialog.ProfileSpoofEditor.Palette(text, sub, field, accent, border),
                () -> profilePhotoLauncher.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()), null, null);
        profileSpoofEditor = editor;
        android.widget.ScrollView scroll = new android.widget.ScrollView(c);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        editor.view().setPadding(pad, pad / 2, pad, 0);
        scroll.addView(editor.view());
        new MaterialAlertDialogBuilder(c)
                .setTitle(R.string.ig_profile_spoofer_editor_title)
                .setView(scroll)
                .setPositiveButton(R.string.ig_profile_spoofer_save, (d, w) -> saveProfileSpoof(v, editor.values()))
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> { if (profileSpoofEditor == editor) profileSpoofEditor = null; })
                .show();
    }

    /** Sends only what changed, so untouched settings keep Instagram's newer values. */
    private void saveProfileSpoof(ps.reso.instaeclipse.utils.dialog.ProfileSpoofEditor.Values old,
                                  ps.reso.instaeclipse.utils.dialog.ProfileSpoofEditor.Values now) {
        if (!now.username.equals(old.username)) write("spoofUsername", now.username);
        if (!now.name.equals(old.name)) write("spoofName", now.name);
        if (!now.bio.equals(old.bio)) write("spoofBio", now.bio);
        if (!now.avatar.equals(old.avatar)) write("spoofAvatar", now.avatar);
        if (now.posts != old.posts) write("spoofPosts", now.posts);
        if (now.followers != old.followers) write("spoofFollowers", now.followers);
        if (now.following != old.following) write("spoofFollowing", now.following);
        if (now.verified != old.verified) write("spoofVerified", now.verified);
    }

    /** Picked photo: downscaled, JPEG, Base64 (SpoofAvatar), into the open editor; saved with it. */
    private void onProfilePhotoPicked(Uri uri) {
        if (uri == null || !isAdded()) return;
        Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            String b64 = ps.reso.instaeclipse.utils.profile.SpoofAvatar.encode(app.getContentResolver(), uri);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (b64 == null) {
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(app,
                            app.getString(R.string.ig_profile_spoofer_photo_failed), Toast.LENGTH_SHORT).show();
                } else if (profileSpoofEditor != null) {
                    profileSpoofEditor.setAvatar(b64);
                }
            });
        }, "IE-ProfilePhoto").start();
    }

    private void openInstagram() {
        Intent launch = requireContext().getPackageManager()
                .getLaunchIntentForPackage(ps.reso.instaeclipse.utils.core.CommonUtils.IG_PACKAGE_NAME);
        if (launch != null) startActivity(launch);
        else ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(),
                getString(R.string.not_installed_instagram), Toast.LENGTH_SHORT).show();
    }

    private void pickAutoClearSize() {
        final int[] values = {50, 100, 200, 500};
        int current = localCache.getInt("autoClearCacheSizeMb", 100);
        String[] labels = new String[values.length];
        int sel = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] + " MB";
            if (values[i] == current) sel = i;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.ig_dialog_section_auto_clear_cache))
                .setSingleChoiceItems(labels, sel, (dialog, which) -> {
                    write("autoClearCacheSizeMb", values[which]);
                    dialog.dismiss();
                    reloadCurrentMenu();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * A lock this app can only turn on: Instagram refuses turning it off from here while a passcode
     * is set (SettingsManager.isLockedByPasscode), so once on the row locks itself and points to
     * Instagram's Lock menu, which asks for the passcode.
     */
    private FeatureItem createLockSwitch(int iconRes, String title, String prefKey) {
        FeatureItem item = createSwitch(iconRes, "#5E5CE6", title, prefKey);
        item.disabledWhenTrue = prefKey;
        item.disabledReason = getString(R.string.ig_lock_turn_off_in_ig);
        return item;
    }

    private FeatureItem locationPlaceItem() {
        double lat = 0.0, lng = 0.0;
        try { lat = Double.parseDouble(localCache.getString("spoofLat", "0")); } catch (Throwable ignored) {}
        try { lng = Double.parseDouble(localCache.getString("spoofLng", "0")); } catch (Throwable ignored) {}
        String label = localCache.getString("spoofLabel", "");
        String place = !ps.reso.instaeclipse.mods.location.LocationPresets.valid(lat, lng)
                ? getString(R.string.ig_dialog_location_unset)
                : !label.isEmpty() ? label : ps.reso.instaeclipse.mods.location.LocationPresets.coords(lat, lng);
        final double curLat = lat, curLng = lng;
        FeatureCatalog.Item item = FeatureCatalog.item("locationPlace");
        FeatureItem row = createNav(item.icon, accentOf(item), getString(item.label), () -> {
            Intent i = new Intent(requireContext(), LocationPickerActivity.class);
            i.putExtra(LocationPickerActivity.EXTRA_LAT, curLat);
            i.putExtra(LocationPickerActivity.EXTRA_LNG, curLng);
            locationPickerLauncher.launch(i);
        });
        row.subtitle = place;
        return row;
    }

    private List<FeatureItem> recentPlaceRows() {
        List<FeatureItem> rows = new ArrayList<>();
        int accent = accentOf(FeatureCatalog.item("locationRecent"));
        for (ps.reso.instaeclipse.mods.location.LocationPresets.Preset p
                : ps.reso.instaeclipse.mods.location.LocationPresets.parse(localCache.getString("spoofRecent", ""))) {
            rows.add(createNav(R.drawable.ic_timer, accent, p.title(), () -> {
                ps.reso.instaeclipse.mods.location.LocationPresets.applyFromCompanion(requireContext(), p);
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), R.string.ig_dialog_location_applied, Toast.LENGTH_SHORT).show();
                reloadCurrentMenu();
            }));
        }
        return rows;
    }

    private String lightModeLabel(int value) {
        if (value == 0) return getString(R.string.theme_light_same);
        if (value == -1) return getString(R.string.theme_light_stock);
        return ps.reso.instaeclipse.mods.ui.theme.ThemePresets.getDisplayName(requireContext(), value);
    }

    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private String darkModeLabel(int value) {
        if (value == 0) return getString(R.string.theme_section_custom);
        return ps.reso.instaeclipse.mods.ui.theme.ThemePresets.getDisplayName(requireContext(), value);
    }

    /** Dark-mode theme: one of the dark presets, or the custom palette when that is in use. */
    private void pickDarkModeTheme() {
        int current = localCache.getInt("themePresetId", 1);
        int[] presets = ps.reso.instaeclipse.mods.ui.theme.ThemePresets.darkModeChoices();
        final int[] values;
        if (current == 0) {
            values = new int[presets.length + 1];
            System.arraycopy(presets, 0, values, 1, presets.length);
        } else {
            values = presets;
        }
        String[] labels = new String[values.length];
        int sel = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = darkModeLabel(values[i]);
            if (values[i] == current) sel = i;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.theme_dark_mode))
                .setSingleChoiceItems(labels, sel, (dialog, which) -> {
                    write("themePresetId", values[which]);
                    dialog.dismiss();
                    reloadCurrentMenu();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Light-mode behaviour of the custom theme (#251). */
    private void pickLightModeTheme() {
        final int[] values = ps.reso.instaeclipse.mods.ui.theme.ThemePresets.lightModeChoices();
        int current = localCache.getInt("themeLightPresetId", -1);
        String[] labels = new String[values.length];
        int sel = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = lightModeLabel(values[i]);
            if (values[i] == current) sel = i;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.theme_light_mode))
                .setSingleChoiceItems(labels, sel, (dialog, which) -> {
                    write("themeLightPresetId", values[which]);
                    dialog.dismiss();
                    reloadCurrentMenu();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String qualityLabel(int h) {
        if (h == 360) return getString(R.string.ig_dialog_quality_360);
        if (h == 480) return getString(R.string.ig_dialog_quality_480);
        if (h == 720) return getString(R.string.ig_dialog_quality_720);
        if (h == 1080) return getString(R.string.ig_dialog_quality_1080);
        if (h == Integer.MAX_VALUE) return getString(R.string.ig_dialog_quality_max);
        return getString(R.string.ig_dialog_quality_auto);
    }

    private void chooseStoryTraySize() {
        int[] sizes = ps.reso.instaeclipse.utils.dialog.DialogUtils.STORY_TRAY_SIZES;
        String[] labels = new String[sizes.length];
        int current = localCache.getInt("storyTrayScale", 100), selected = 0;
        for (int i = 0; i < sizes.length; i++) {
            labels[i] = sizes[i] + "%";
            if (sizes[i] == current) selected = i;
        }
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.ig_story_tray_size)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    write("storyTrayScale", sizes[which]);
                    dialog.dismiss(); reloadCurrentMenu();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private String storyRingColorValue() {
        String v = localCache.getString("storyRingColor", "");
        return v.isEmpty() ? getString(R.string.ig_story_ring_default) : v.replace(",", " → ");
    }

    /** Same picker as inside Instagram: live preview, one color or a gradient of up to 4. */
    private void chooseStoryRingColor() {
        Context ctx = requireContext();
        String[] picked = {localCache.getString("storyRingColor", "")};
        int text = com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.WHITE);
        int sub = com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY);
        int field = com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurfaceContainerHighest, Color.DKGRAY);
        int accent = com.google.android.material.color.MaterialColors.getColor(ctx, androidx.appcompat.R.attr.colorPrimary, 0xFF0095F6);
        View picker = ps.reso.instaeclipse.utils.dialog.RingColorPicker.build(ctx, picked[0],
                new ps.reso.instaeclipse.utils.dialog.RingColorPicker.Palette(text, sub, field, accent), v -> picked[0] = v);
        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        picker.setPadding(pad, pad / 2, pad, 0);
        scroll.addView(picker);
        new MaterialAlertDialogBuilder(ctx).setTitle(R.string.ig_story_ring_color)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    write("storyRingColor", picked[0]);
                    reloadCurrentMenu();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void pickQuality(int current) {
        String[] labels = {
                getString(R.string.ig_dialog_quality_auto), getString(R.string.ig_dialog_quality_360),
                getString(R.string.ig_dialog_quality_480), getString(R.string.ig_dialog_quality_720),
                getString(R.string.ig_dialog_quality_1080), getString(R.string.ig_dialog_quality_max)
        };
        int[] values = {0, 360, 480, 720, 1080, Integer.MAX_VALUE};

        int sel = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) { sel = i; break; }
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.ig_dialog_quality_force_reels))
                .setSingleChoiceItems(labels, sel, (dialog, which) -> {
                    write("forceReelQuality", values[which]);
                    dialog.dismiss();
                    reloadCurrentMenu();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private FeatureItem storyCacheItem() {
        int cacheHours = StoryCacheRetention.fromStored(localCache.getAll().get("storyCacheHours"));
        return valueRow("storyCacheHours", getString(R.string.ig_story_cache_hours, cacheHours),
                () -> ps.reso.instaeclipse.utils.dialog.DialogUtils.showStoryCacheDurationPicker(requireContext(), cacheHours, value -> {
                    if (!getCurrentState("cacheStories")) return;
                    write("storyCacheHours", StoryCacheRetention.normalize(value));
                    reloadCurrentMenu();
                }));
    }

    private boolean hasCustomFolder() {
        return !localCache.getString("downloaderCustomPath", "").isEmpty()
                || !localCache.getString("downloaderCustomUri", "").isEmpty();
    }

    private String downloadFolderValue() {
        String customPath = localCache.getString("downloaderCustomPath", "");
        String customUri = localCache.getString("downloaderCustomUri", "");
        if (!customPath.isEmpty()) return customPath;
        if (!customUri.isEmpty()) return customUri;
        return getString(R.string.feat_download_folder_default, MediaStoreTarget.DEFAULT_FOLDER);
    }

    /** Standard (the original downloader) or Best, with its resolution cap. */
    private String downloadModeLabel() {
        if (getCurrentState("downloaderUseOriginal")) return getString(R.string.ie_downloader_original);
        int ceiling = localCache.getInt("downloaderQualityMaxShortSide", 0);
        return ceiling <= 0 ? getString(R.string.ie_download_best) : getString(R.string.ie_download_ceiling, ceiling);
    }

    /** One choice for both: Standard, or Best with no cap / up to a resolution. */
    private void chooseDownloadQuality() {
        int[] values = {-1, 0, 2160, 1440, 1080, 720, 480};
        String[] labels = new String[values.length];
        boolean standard = getCurrentState("downloaderUseOriginal");
        int current = localCache.getInt("downloaderQualityMaxShortSide", 0), selected = 0;
        for (int i=0; i<values.length; i++) {
            labels[i] = values[i] < 0 ? getString(R.string.ie_downloader_original)
                    : values[i] == 0 ? getString(R.string.ie_download_best) : getString(R.string.ie_download_ceiling, values[i]);
            if (!standard && values[i] == current) selected = i;
        }
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.ie_download_quality)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    write("downloaderUseOriginal", values[which] < 0);
                    if (values[which] >= 0) write("downloaderQualityMaxShortSide", values[which]);
                    dialog.dismiss(); reloadCurrentMenu();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    // =========================================================
    // TOOLS ACTIONS & HANDLERS
    // =========================================================

    private void pickDownloadFolder() {
        dirPickerLauncher.launch(null);
    }

    private void confirmResetDownloadFolder() {
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.feat_reset_folder_confirm, MediaStoreTarget.DEFAULT_FOLDER))
                .setPositiveButton(R.string.feat_use_default_folder, (dialog, which) -> resetDownloadFolder())
                .setNegativeButton(R.string.ig_dialog_cancel, null)
                .show();
    }

    private void askDownloadPermission(PermissionAsk.Trigger trigger) {
        if (trigger != PermissionAsk.Trigger.NONE && getActivity() instanceof ps.reso.instaeclipse.MainActivity) {
            ((ps.reso.instaeclipse.MainActivity) getActivity()).requestDownloadPermissionsIfNeeded(trigger);
        }
    }

    private void resetDownloadFolder() {
        LinkedHashMap<String, Object> folder = new LinkedHashMap<>();
        folder.put("downloaderCustomUri", "");
        folder.put("downloaderCustomPath", "");
        writeAll(folder);

        ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), getString(R.string.ig_toast_download_folder_reset), Toast.LENGTH_SHORT).show();

        reloadCurrentMenu();
    }

    private void backupSettings() {
        try {
            JSONObject settings = new JSONObject();
            Map<String, ?> all = localCache.getAll();
            for (String key : SettingsSchema.backupKeys(true)) {
                Object value = all.get(key);
                if (!ModuleReplies.isPreferenceValue(key, value)) continue;
                settings.put(key, "storyCacheHours".equals(key) ? StoryCacheRetention.fromStored(value) : value);
            }
            String pins = CompanionUiPrefs.get(requireContext()).getString(CompanionUiPrefs.HOME_PINNED_SHORTCUTS, null);
            if (pins != null) settings.put(CompanionUiPrefs.HOME_PINNED_SHORTCUTS, pins);
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("settings", settings);
            Intent exportIntent = new Intent();
            exportIntent.setComponent(new ComponentName(requireContext(), "ps.reso.instaeclipse.mods.devops.config.JsonExportActivity"));
            exportIntent.putExtra("json_content", root.toString(2));
            exportIntent.putExtra("file_name", "instaeclipse_settings_" + java.time.LocalDate.now() + ".json");
            startActivity(exportIntent);
        } catch (JSONException e) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), getString(R.string.ig_dialog_backup_failed,
                    getString(R.string.feat_backup_unreadable)), Toast.LENGTH_SHORT).show();
        }
    }

    private void restoreSettings() {
        restoreFileLauncher.launch(new String[]{"application/json", "text/plain", "application/octet-stream"});
    }

    public void restoreFrom(Uri uri) {
        if (uri == null || !isAdded()) return;
        Context app = requireContext().getApplicationContext();
        RESTORE_WORKER.execute(() -> {
            CompanionRestorePlan plan;
            try {
                plan = CompanionRestorePlan.build(readBackup(app, uri),
                        ps.reso.instaeclipse.utils.core.CommonUtils.companionCache(app).getAll());
            } catch (Exception e) {
                plan = null;
            }
            CompanionRestorePlan result = plan;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (result == null || result.count() == 0) {
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(app, app.getString(R.string.ig_toast_restore_failed,
                            app.getString(R.string.feat_restore_invalid_file)), Toast.LENGTH_SHORT).show();
                } else if (isAdded()) {
                    confirmRestore(app, result);
                }
            });
        });
    }

    private static Map<String, Object> readBackup(Context app, Uri uri) throws IOException, JSONException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                bytes.write(buffer, 0, n);
                if (bytes.size() > MAX_BACKUP_BYTES) throw new IOException();
            }
        }
        JSONObject root = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        JSONObject s = root.has("settings") ? root.getJSONObject("settings") : root;
        Map<String, Object> settings = new LinkedHashMap<>();
        for (Iterator<String> it = s.keys(); it.hasNext(); ) {
            String key = it.next();
            Object value = s.get(key);
            settings.put(key, value == JSONObject.NULL ? null : value);
        }
        return settings;
    }

    private void confirmRestore(Context app, CompanionRestorePlan plan) {
        int count = plan.count();
        StringBuilder message = new StringBuilder(getResources().getQuantityString(R.plurals.feat_restore_confirm, count, count));
        if (plan.skipped > 0) {
            message.append("\n\n").append(getResources().getQuantityString(R.plurals.feat_restore_skipped,
                    plan.skipped, plan.skipped));
        }
        if (plan.turnsLocksOn) message.append("\n\n").append(getString(R.string.feat_restore_needs_passcode));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ig_dialog_restore_settings)
                .setMessage(message)
                .setPositiveButton(R.string.feat_restore_action, (dialog, which) -> applyRestore(app, plan))
                .setNegativeButton(R.string.ig_dialog_cancel, null)
                .show();
    }

    private void applyRestore(Context app, CompanionRestorePlan plan) {
        RESTORE_WORKER.execute(() -> {
            Set<String> restart = CompanionSettings.putAll(app, plan.writes);
            if (!plan.uiWrites.isEmpty()) {
                SharedPreferences.Editor ui = CompanionUiPrefs.get(app).edit();
                for (Map.Entry<String, Object> entry : plan.uiWrites.entrySet()) {
                    ui.putString(entry.getKey(), (String) entry.getValue());
                }
                ui.apply();
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (isAdded()) {
                    reloadCurrentMenu();
                    CompanionRestartHint.show(getActivity(), restart);
                }
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(app, plan.skipped == 0
                        ? app.getString(R.string.ig_toast_settings_restored)
                        : app.getString(R.string.ig_toast_settings_restored_skipped, plan.skipped), Toast.LENGTH_SHORT).show();
            });
        });
    }

    private static ExecutorService restoreWorker() {
        ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                runnable -> new Thread(runnable, "ie-restore"));
        worker.allowCoreThreadTimeOut(true);
        return worker;
    }

    private void restartInstagram() {
        String pkg = ps.reso.instaeclipse.utils.core.InstagramRestarter.activePackage(requireContext(),
                ps.reso.instaeclipse.utils.core.InstagramRestarter.installedPackages(requireContext()));
        ps.reso.instaeclipse.utils.core.InstagramRestarter.restart(requireActivity(),
                pkg != null ? pkg : ps.reso.instaeclipse.utils.core.CommonUtils.IG_PACKAGE_NAME, outcome -> { });
    }

    private void openHelpAbout() {
        if (getActivity() instanceof ps.reso.instaeclipse.MainActivity) ((ps.reso.instaeclipse.MainActivity) getActivity()).showHelpAbout();
    }

    private void importDevConfig() {
        Intent importIntent = new Intent();
        importIntent.setComponent(new ComponentName(requireContext(), "ps.reso.instaeclipse.mods.devops.config.JsonImportActivity"));
        importIntent.putExtra("target_package", "com.instagram.android");
        startActivity(importIntent);
    }

    private void exportDevConfig() {
        Intent request = new Intent("ps.reso.instaeclipse.ACTION_EXPORT_CONFIG");
        request.setPackage("com.instagram.android");
        requestDevConfig(request, "ps.reso.instaeclipse.ACTION_SEND_CONFIG", true);
    }

    private void requestDevConfig(Intent request, String replyAction, boolean export) {
        final String nonce = ps.reso.instaeclipse.utils.core.IpcSecurity.newNonce();
        BroadcastReceiver configReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ps.reso.instaeclipse.utils.core.IpcSecurity.nonceMatches(nonce, intent)) return;
                configTimer.removeCallbacks(configTimeout);
                if (configReply != null) { configReply.cancel(); configReply = null; }
                onDevConfigResult(DevConfigReply.of(true, intent.getStringExtra("error")), intent.getStringExtra("json_content"));
            }
        };
        configTimer.removeCallbacks(configTimeout);
        if (configReply != null) configReply.cancel();
        configExport = export;
        request.putExtra(ps.reso.instaeclipse.utils.core.IpcSecurity.EXTRA_NONCE, nonce);
        configReply = ps.reso.instaeclipse.utils.core.IpcSecurity.requestReply(requireContext(), request, replyAction, configReceiver);
        if (configReply == null) onDevConfigResult(DevConfigReply.Result.NO_REPLY, null);
        else configTimer.postDelayed(configTimeout, DevConfigReply.TIMEOUT_MS);
    }

    private void onDevConfigResult(DevConfigReply.Result result, String json) {
        DevConfigReply.report(result);
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        if (configExport && result == DevConfigReply.Result.OK) {
            if (json == null || json.isEmpty()) {
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(activity, getString(R.string.export_no_config_data), Toast.LENGTH_SHORT).show();
                return;
            }
            Intent exportIntent = new Intent();
            exportIntent.setComponent(new ComponentName(activity, "ps.reso.instaeclipse.mods.devops.config.JsonExportActivity"));
            exportIntent.putExtra("json_content", json);
            startActivity(exportIntent);
            return;
        }
        int message = DevConfigReply.messageFor(result, configExport);
        if (message != 0) ps.reso.instaeclipse.utils.toast.IeToast.makeText(activity, getString(message), Toast.LENGTH_SHORT).show();
    }

    private void restoreDefaultConfig() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.ig_dialog_dev_restore_default_config))
                .setMessage(getString(R.string.ig_dialog_dev_restore_default_config_confirm))
                .setPositiveButton(getString(R.string.ig_dialog_yes), (dialog, which) -> {
                    try (InputStream is = requireContext().getAssets().open("default_mc_overrides.json");
                         Scanner scanner = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
                        String json = scanner.hasNext() ? scanner.next() : "";
                        if (json.isEmpty()) {
                            ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), getString(R.string.ig_toast_config_import_failed), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Intent broadcast = new Intent("ps.reso.instaeclipse.ACTION_IMPORT_CONFIG");
                        broadcast.setPackage("com.instagram.android");
                        broadcast.putExtra("json_content", json);
                        requestDevConfig(broadcast, "ps.reso.instaeclipse.ACTION_IMPORT_CONFIG_RESULT", false);
                    } catch (Exception e) {
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(getString(R.string.ig_dialog_cancel), null)
                .show();
    }

    // =========================================================
    // STAGING LOGIC
    // =========================================================

    /** A switch applies at once, like in Instagram's menu: saved, stamped and sent to Instagram
     *  live (or picked up by Instagram's sync on its next start / resume). No Save step. */
    private void stageChange(String prefKey, boolean isChecked) {
        if (localCache.getBoolean(prefKey, defaultState(prefKey)) == isChecked) return;
        write(prefKey, isChecked);
        askDownloadPermission(PermissionAsk.forChanges(java.util.Collections.singletonMap(prefKey, isChecked)));
    }

    private void stageChanges(LinkedHashMap<String, Boolean> wanted) {
        LinkedHashMap<String, Object> changed = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : wanted.entrySet()) {
            if (localCache.getBoolean(entry.getKey(), defaultState(entry.getKey())) != entry.getValue()) {
                changed.put(entry.getKey(), entry.getValue());
            }
        }
        if (changed.size() == 1) {
            Map.Entry<String, Object> only = changed.entrySet().iterator().next();
            write(only.getKey(), only.getValue());
        } else if (!changed.isEmpty()) {
            writeAll(changed);
        }
        askDownloadPermission(PermissionAsk.forChanges(changed));
    }

    private boolean getCurrentState(String prefKey) {
        if (prefKey == null) return false;
        return localCache.getBoolean(prefKey, defaultState(prefKey));
    }

    private static boolean defaultState(String prefKey) {
        FeatureCatalog.Item item = FeatureCatalog.item(prefKey);
        return item != null && item.defaultOn;
    }

    private void write(String key, Object value) {
        CompanionRestartHint.show(getActivity(), CompanionSettings.put(requireContext(), key, value));
    }

    private void writeAll(LinkedHashMap<String, ?> values) {
        CompanionRestartHint.show(getActivity(), CompanionSettings.putAll(requireContext(), values));
    }

    // =========================================================
    // LIFECYCLE BROADCAST REGISTRATION
    // =========================================================

    /** Settings changed elsewhere (inside Instagram, pushed by its sync, or a restore) redraw the
     *  page on screen, so both menus always show the same values. Coalesced and posted, so a burst
     *  of changes or a change made from a switch callback never rebinds mid-toggle. */
    private final Runnable syncRefresh = this::reloadCurrentMenu;
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (prefs, key) -> {
        View v = getView();
        if (v == null) return;
        v.removeCallbacks(syncRefresh);
        v.postDelayed(syncRefresh, 250);
    };

    /** Asks Instagram for its current settings; only the reply carrying this nonce is accepted. */
    private void requestPrefs() {
        if (prefsReply != null) prefsReply.cancel();
        prefsNonce = ps.reso.instaeclipse.utils.core.IpcSecurity.newNonce();
        prefsReply = ps.reso.instaeclipse.utils.core.IpcSecurity.requestReply(requireContext(),
                new Intent("ps.reso.instaeclipse.ACTION_REQUEST_PREFS")
                        .putExtra(ps.reso.instaeclipse.utils.core.IpcSecurity.EXTRA_NONCE, prefsNonce),
                "ps.reso.instaeclipse.ACTION_SEND_PREFS", prefsReceiver);
    }

    @Override
    public void onResume() {
        super.onResume();
        reelUsageSynced = false;
        if (localCache != null) localCache.registerOnSharedPreferenceChangeListener(prefsListener);
        requestPrefs();
        reloadCurrentMenu();
        updateBackEnabled();
    }

    @Override
    public void onPause() {
        super.onPause();
        updateBackEnabled();
        if (localCache != null) localCache.unregisterOnSharedPreferenceChangeListener(prefsListener);
        if (prefsReply != null) { prefsReply.cancel(); prefsReply = null; }
        configTimer.removeCallbacks(configTimeout);
        if (configReply != null) { configReply.cancel(); configReply = null; }
    }
}
