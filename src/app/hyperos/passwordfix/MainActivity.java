package app.hyperos.passwordfix;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.hyperos.passwordfix.ProviderSettings.Provider;
import app.hyperos.passwordfix.ProviderSettings.Role;
import app.hyperos.passwordfix.ProviderSettings.Snapshot;

public final class MainActivity extends Activity {
    private static final int CARD_GAP = 10;
    private static final int CARD_RADIUS = 12;
    private static final int CARD_PADDING = 12;
    private static final int CONTROL_RADIUS = 8;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private final Map<Role, Map<Provider, RadioButton>> options = new EnumMap<>(Role.class);
    private final Map<Role, TextView> currentLabels = new EnumMap<>(Role.class);
    private final Map<Provider, View> providerChoices = new EnumMap<>(Provider.class);
    private final Map<Provider, TextView> missingLabels = new EnumMap<>(Provider.class);
    private ProviderSettings settings;
    private ShizukuAccess shizuku;
    private Button shizukuButton;
    private TextView status;
    private TextView feedback;
    private final Runnable hideFeedback = () -> feedback.setVisibility(View.GONE);
    private Button refresh;
    private ProgressBar switchProgress;
    private boolean busy;
    private boolean refreshPending;

    private int surface;
    private int text;
    private int muted;
    private int inactive;
    private int accent;
    private int selectedSurface;
    private int rowSurface;
    private int readOnlySurface;
    private int readOnlySelectedSurface;
    private int divider;
    private int error;
    private int ripple;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        shizuku = new ShizukuAccess(this, this::onPermissionChanged);
        settings = new ProviderSettings(this, shizuku);
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        surface = dark ? 0xff201711 : 0xfffff6eb;
        text = dark ? 0xfff6e6da : 0xff392a20;
        muted = dark ? 0xffd8bbab : 0xff685041;
        inactive = dark ? 0xffba9e91 : 0xff755e50;
        accent = dark ? 0xffffba96 : 0xff82421f;
        selectedSurface = dark ? 0xff4b2b20 : 0xfff6e3d3;
        rowSurface = dark ? 0xff2b2019 : 0xfffffbf5;
        readOnlySurface = dark ? 0xff2b2826 : 0xfff3efeb;
        readOnlySelectedSurface = dark ? 0xff3b3530 : 0xffe8dfd6;
        divider = dark ? 0xff654a3d : 0xffe8d6c6;
        error = dark ? 0xffffb4ab : 0xffa32620;
        ripple = dark ? 0x33ffba96 : 0x2282421f;
        createScreen(dark);
        shizuku.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        shizuku.refresh();
        renderPermissions();
        update(null, null);
    }

    @Override
    protected void onDestroy() {
        feedback.removeCallbacks(hideFeedback);
        shizuku.close();
        super.onDestroy();
    }

    private void createScreen(boolean dark) {
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarContrastEnforced(false);
        getWindow().setNavigationBarContrastEnforced(false);

        FrameLayout root = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(surface);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        int width = getWindowManager().getCurrentWindowMetrics().getBounds().width();
        int horizontal = dp(22) + Math.max(0, (width - dp(620)) / 2);
        screen.setPadding(horizontal, dp(12), horizontal, dp(22));
        scroll.addView(screen, new FrameLayout.LayoutParams(-1, -2));
        addFeedbackOverlay(root);
        setContentView(root);
        int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(dark ? 0 : appearance, appearance);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(getString(R.string.app_name), 22, text, true);
        title.setAccessibilityHeading(true);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        shizukuButton = actionButton("Shizuku授权");
        shizukuButton.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        shizukuButton.setOnClickListener(view -> requestShizuku());
        toolbar.addView(shizukuButton, new LinearLayout.LayoutParams(-2, -2));
        refresh = actionButton("刷新");
        refresh.setOnClickListener(view -> {
            shizuku.refresh();
            renderPermissions();
            update(null, null);
        });
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(-2, -2);
        refreshParams.setMarginStart(dp(8));
        toolbar.addView(refresh, refreshParams);
        add(screen, toolbar, 0);

        status = label("", 13, muted, false);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setTextIsSelectable(true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addProviderCards(screen);
        add(screen, status, CARD_GAP);
        renderPermissions();
    }

    private Button actionButton(String text) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(text);
        styleActionButton(button);
        return button;
    }

    private void styleActionButton(Button button) {
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTextColor(accent);
        RippleDrawable fill = new RippleDrawable(ColorStateList.valueOf(ripple),
                outlined(rowSurface, CONTROL_RADIUS), null);
        button.setBackground(new InsetDrawable(fill, 0, dp(9), 0, dp(9)));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
    }

    private void requestShizuku() {
        if (shizuku.status() == ShizukuAccess.Status.NOT_RUNNING) {
            Intent manager = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (manager == null) {
                showFeedback("请先安装并启动 Shizuku。");
                return;
            }
            try {
                startActivity(manager);
            } catch (ActivityNotFoundException | SecurityException exception) {
                showFeedback("请在桌面打开并启动 Shizuku。");
            }
            return;
        }
        try {
            shizuku.requestPermission();
        } catch (IllegalStateException exception) {
            showFeedback(exception.getMessage());
        }
    }

    private void onPermissionChanged() {
        if (isDestroyed()) return;
        renderPermissions();
        update(null, null);
    }

    private void renderPermissions() {
        var state = shizuku.status();
        boolean ready = settings.hasWritePermission();
        shizukuButton.setText(ready ? "已授权" : "Shizuku授权");
        shizukuButton.setContentDescription(ready ? "Shizuku 已授权" : "Shizuku授权");
        shizukuButton.setEnabled(!ready && state != ShizukuAccess.Status.REQUESTING
                && state != ShizukuAccess.Status.CONNECTING);
    }

    private void finishPermissionRefresh() {
        renderPermissions();
        if (refreshPending) {
            refreshPending = false;
            update(null, null);
        }
    }

    private void addProviderCards(LinearLayout screen) {
        for (Role role : Role.values()) {
            options.put(role, new EnumMap<>(Provider.class));
            TextView current = label("", 12, muted, false);
            current.setVisibility(View.GONE);
            currentLabels.put(role, current);
            add(screen, current, 4);
        }
        for (Provider provider : Provider.values()) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(CARD_PADDING), dp(8), dp(CARD_PADDING), dp(8));
            card.setBackground(outlined(rowSurface, CARD_RADIUS));
            LinearLayout header = new LinearLayout(this);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setMinimumHeight(dp(28));
            TextView title = label(provider.label(), 14, text, true);
            title.setAccessibilityHeading(true);
            header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            TextView missing = label("未安装", 12, muted, false);
            missing.setPadding(dp(8), 0, 0, 0);
            missing.setVisibility(View.GONE);
            header.addView(missing, new LinearLayout.LayoutParams(-2, -2));
            missingLabels.put(provider, missing);
            add(card, header, 0);
            LinearLayout cells = new LinearLayout(this);
            cells.setBaselineAligned(false);
            providerChoices.put(provider, cells);
            for (Role role : Role.values()) {
                RadioButton choice = new RadioButton(this) {
                    @Override
                    public void toggle() {
                        // Only a verified system value changes the checked state.
                    }
                };
                choice.setEnabled(false);
                choice.setPadding(dp(7), dp(5), dp(6), dp(5));
                choice.setGravity(Gravity.CENTER_VERTICAL);
                choice.setTextSize(12);
                choice.setText(roleTitle(role));
                choice.setOnClickListener(view -> update(role, provider));
                options.get(role).put(provider, choice);
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1);
                if (role == Role.PASSKEY) cell.setMarginStart(dp(8));
                FrameLayout choiceFrame = new FrameLayout(this);
                choiceFrame.addView(choice, new FrameLayout.LayoutParams(-1, -2));
                cells.addView(choiceFrame, cell);
            }
            add(card, cells, 4);
            add(screen, card, CARD_GAP);
        }
    }

    private static String roleTitle(Role role) {
        return role == Role.AUTOFILL ? "自动填充" : "通行密钥";
    }

    private void update(Role role, Provider provider) {
        if (busy) {
            if (role == null) refreshPending = true;
            return;
        }
        busy = true;
        setStatus(role == null ? "正在读取系统设置…" : "正在保存…", false);
        refresh.setEnabled(false);
        for (Map<Provider, RadioButton> rows : options.values()) {
            for (RadioButton button : rows.values()) {
                button.setEnabled(false);
            }
        }
        if (role != null) showSwitchProgress(options.get(role).get(provider));
        WORKER.execute(() -> {
            String message = null;
            boolean failed = false;
            if (role != null) {
                try {
                    settings.select(role, provider);
                } catch (RuntimeException exception) {
                    failed = true;
                    message = "保存失败。 " + exception.getMessage();
                }
            }
            final String result = message;
            final boolean saveFailed = failed;
            try {
                Snapshot state = settings.read();
                Map<Provider, String> autofill = settings.available(Role.AUTOFILL, state.autofill());
                Map<Provider, String> passkeys = settings.available(Role.PASSKEY, state.primary());
                Map<Provider, Boolean> installed = new EnumMap<>(Provider.class);
                for (Provider manager : Provider.values()) {
                    installed.put(manager, autofill.containsKey(manager)
                            || passkeys.containsKey(manager) || settings.isInstalled(manager));
                }
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    clearSwitchProgress();
                    busy = false;
                    refresh.setEnabled(true);
                    boolean canWrite = settings.hasWritePermission();
                    renderRole(Role.AUTOFILL, state, autofill, installed, canWrite);
                    renderRole(Role.PASSKEY, state, passkeys, installed, canWrite);
                    renderProviderAvailability(installed);
                    setStatus(canWrite ? "点选即保存" : "通过 Shizuku 授权后可切换服务", false);
                    if (role != null) {
                        String selected = (role == Role.AUTOFILL ? autofill : passkeys).get(provider);
                        if (saveFailed) {
                            showFeedback(result);
                        } else if (isActive(role, state, selected)) {
                            showFeedback(role.label() + "已保存为 " + provider.label());
                        } else {
                            showFeedback("系统未保留这次切换，请重试。");
                        }
                    }
                    finishPermissionRefresh();
                });
            } catch (RuntimeException exception) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    clearSwitchProgress();
                    busy = false;
                    refresh.setEnabled(true);
                    for (Role item : Role.values()) {
                        renderRole(item, null, Map.of(), Map.of(), false);
                    }
                    setStatus("无法读取系统设置。 " + exception.getMessage(), true);
                    if (role != null) {
                        showFeedback(saveFailed ? result : "本次切换结果未能确认，请刷新后查看。");
                    }
                    finishPermissionRefresh();
                });
            }
        });
    }

    private void renderProviderAvailability(Map<Provider, Boolean> installed) {
        for (Provider provider : Provider.values()) {
            boolean missing = Boolean.FALSE.equals(installed.get(provider));
            missingLabels.get(provider).setVisibility(missing ? View.VISIBLE : View.GONE);
            providerChoices.get(provider).setVisibility(missing ? View.GONE : View.VISIBLE);
        }
    }

    private void renderRole(Role role, Snapshot state, Map<Provider, String> available,
                            Map<Provider, Boolean> installed, boolean canWrite) {
        String current = state == null ? null : state.current(role);
        boolean anyChecked = false;
        boolean needsEnable = false;
        for (var entry : options.get(role).entrySet()) {
            Provider provider = entry.getKey();
            RadioButton button = entry.getValue();
            String component = available.get(provider);
            boolean checked = ProviderSettings.sameComponent(current, component);
            boolean ready = checked && isActive(role, state, component);
            anyChecked |= ready;
            needsEnable |= checked && !ready;
            boolean enabled = state != null && component != null && canWrite;
            String note = component == null && Boolean.TRUE.equals(installed.get(provider))
                    ? "服务不可用" : "";
            button.setText(roleTitle(role) + (note.isEmpty() ? "" : "\n" + note));
            button.setContentDescription(provider.label() + "，" + role.label()
                    + (note.isEmpty() ? "" : "，" + note));
            button.setChecked(ready);
            button.setEnabled(enabled);
            styleChoice(button, enabled, false);
        }
        TextView extra = currentLabels.get(role);
        extra.setVisibility(state != null && !anyChecked ? View.VISIBLE : View.GONE);
        if (state != null && !anyChecked) {
            extra.setText(needsEnable ? "通行密钥首选尚未启用"
                    : role.label() + "：" + friendlyCurrent(current));
        }
    }

    private void styleChoice(RadioButton button, boolean enabled, boolean pending) {
        boolean checked = button.isChecked();
        int fill = pending || (checked && enabled) ? selectedSurface
                : enabled ? rowSurface : checked ? readOnlySelectedSurface : readOnlySurface;
        int color = pending || (checked && enabled) ? accent : enabled ? text : inactive;
        button.setTextColor(color);
        int icon = checked ? R.drawable.radio_selected : R.drawable.radio_unselected;
        button.setButtonDrawable(new InsetDrawable(getDrawable(icon), dp(6), 0, dp(5), 0));
        button.setButtonTintList(ColorStateList.valueOf(enabled && !checked ? muted : color));
        GradientDrawable shape = outlined(fill, CONTROL_RADIUS);
        button.setBackground(enabled ? new RippleDrawable(ColorStateList.valueOf(ripple), shape, null) : shape);
        button.setPadding(dp(7), dp(5), dp(pending ? 30 : 6), dp(5));
        button.setMinHeight(dp(48));
        button.setStateDescription(pending ? "切换中" : null);
    }

    private void showSwitchProgress(RadioButton button) {
        feedback.removeCallbacks(hideFeedback);
        feedback.setVisibility(View.GONE);
        button.setText("切换中…");
        styleChoice(button, false, true);
        switchProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        switchProgress.setIndeterminateTintList(ColorStateList.valueOf(accent));
        switchProgress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(dp(16), dp(16),
                Gravity.END | Gravity.CENTER_VERTICAL);
        position.setMarginEnd(dp(10));
        ((FrameLayout) button.getParent()).addView(switchProgress, position);
    }

    private void clearSwitchProgress() {
        if (switchProgress == null) return;
        ((FrameLayout) switchProgress.getParent()).removeView(switchProgress);
        switchProgress = null;
    }

    private static boolean isActive(Role role, Snapshot state, String component) {
        if (!ProviderSettings.sameComponent(state.current(role), component)) return false;
        if (role == Role.AUTOFILL) return true;
        if (state.enabled() == null) return false;
        for (String entry : state.enabled().split(":")) {
            if (ProviderSettings.sameComponent(entry, component)) return true;
        }
        return false;
    }

    private static String friendlyCurrent(String value) {
        return value != null && value.contains(":") ? "系统设置了多个首选服务"
                : ProviderSettings.providerLabel(value);
    }

    private void setStatus(String message, boolean isError) {
        status.setTextColor(isError ? error : muted);
        status.setText(message);
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void addFeedbackOverlay(FrameLayout root) {
        feedback = label("", 13, 0xfffff6eb, true);
        feedback.setGravity(Gravity.CENTER);
        feedback.setPadding(dp(16), dp(10), dp(16), dp(10));
        feedback.setBackground(background(0xff392a20, 20));
        int windowWidth = getWindowManager().getCurrentWindowMetrics().getBounds().width();
        feedback.setMaxWidth(Math.min(dp(440), Math.max(dp(120), windowWidth - dp(44))));
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(View.GONE);
        FrameLayout.LayoutParams placement = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.addView(feedback, placement);
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top == oldBottom - oldTop) return;
            placement.bottomMargin = Math.max(dp(64), Math.round((bottom - top) * 0.21f));
            feedback.setLayoutParams(placement);
        });
    }

    private void showFeedback(String message) {
        if (message == null || message.isBlank()) message = "操作未完成，请重试。";
        feedback.removeCallbacks(hideFeedback);
        feedback.setText(message);
        feedback.setVisibility(View.VISIBLE);
        AccessibilityManager accessibility = getSystemService(AccessibilityManager.class);
        int duration = accessibility.getRecommendedTimeoutMillis(2400, AccessibilityManager.FLAG_CONTENT_TEXT);
        feedback.postDelayed(hideFeedback, duration);
    }

    private TextView label(String value, int size, int color, boolean emphasis) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        if (emphasis) {
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return label;
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private GradientDrawable background(int color, int corner) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(corner));
        return shape;
    }

    private GradientDrawable outlined(int color, int corner) {
        GradientDrawable shape = background(color, corner);
        shape.setStroke(dp(1), divider);
        return shape;
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }
}
