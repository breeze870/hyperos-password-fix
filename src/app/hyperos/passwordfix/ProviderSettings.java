package app.hyperos.passwordfix;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.provider.Settings;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProviderSettings {
    public static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    public static final String AUTOFILL_KEY = "autofill_service";
    public static final String PRIMARY_KEY = "credential_service_primary";
    public static final String ENABLED_KEY = "credential_service";

    public enum Role {
        AUTOFILL("密码自动填充", AUTOFILL_KEY, "android.service.autofill.AutofillService",
                "android.permission.BIND_AUTOFILL_SERVICE"),
        PASSKEY("通行密钥首选", PRIMARY_KEY, "android.service.credentials.CredentialProviderService",
                "android.permission.BIND_CREDENTIAL_PROVIDER_SERVICE");

        private final String label;
        private final String key;
        private final String action;
        private final String bindingPermission;

        Role(String label, String key, String action, String bindingPermission) {
            this.label = label;
            this.key = key;
            this.action = action;
            this.bindingPermission = bindingPermission;
        }

        public String label() {
            return label;
        }
    }

    public enum Provider {
        BITWARDEN("Bitwarden",
                List.of("com.x8bit.bitwarden/.Autofill.AutofillService"),
                List.of("com.x8bit.bitwarden/.Autofill.CredentialProviderService")),
        GOOGLE("Google 密码管理工具",
                List.of("com.google.android.gms/.autofill.service.AutofillService"),
                List.of("com.google.android.gms/.auth.api.credentials.credman.service.PasswordAndPasskeyService")),
        XIAOMI("小米密码管理器",
                List.of("com.miui.passwords/.newautofill.MiuiAutofillService"),
                List.of(
                        "com.miui.passwords/.credential.provider.XiaomiCredentialProviderService",
                        "com.miui.contentcatcher/com.miui.credential.provider.XiaomiCredentialProviderService"));

        private final String label;
        private final List<String> autofillComponents;
        private final List<String> passkeyComponents;

        Provider(String label, List<String> autofillComponents, List<String> passkeyComponents) {
            this.label = label;
            this.autofillComponents = autofillComponents;
            this.passkeyComponents = passkeyComponents;
        }

        public String label() {
            return label;
        }

        private List<String> components(Role role) {
            return role == Role.AUTOFILL ? autofillComponents : passkeyComponents;
        }
    }

    public record Snapshot(String autofill, String primary, String enabled) {
        public String current(Role role) {
            return role == Role.AUTOFILL ? autofill : primary;
        }
    }

    private final Context context;
    private final ContentResolver resolver;
    private final ShizukuAccess shizuku;

    public ProviderSettings(Context context, ShizukuAccess shizuku) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.shizuku = shizuku;
    }

    public Snapshot read() {
        boolean remote = hasWritePermission();
        return new Snapshot(readSetting(AUTOFILL_KEY, remote), readSetting(PRIMARY_KEY, remote),
                readSetting(ENABLED_KEY, remote));
    }

    public boolean hasWritePermission() {
        return shizuku.canWrite();
    }

    public Map<Provider, String> available(Role role, String current) {
        Map<Provider, String> available = new EnumMap<>(Provider.class);
        for (Provider provider : Provider.values()) {
            String component = availableComponent(role, provider, current);
            if (component != null) {
                available.put(provider, component);
            }
        }
        return available;
    }

    public boolean isInstalled(Provider provider) {
        String packageName = switch (provider) {
            case BITWARDEN -> "com.x8bit.bitwarden";
            case GOOGLE -> "com.google.android.gms";
            case XIAOMI -> "com.miui.passwords";
        };
        return isPackageInstalled(packageName) || (provider == Provider.GOOGLE
                && isPackageInstalled("com.google.android.apps.credentialmanager"));
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            context.getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    public synchronized void select(Role role, Provider provider) {
        if (!shizuku.canWrite()) {
            throw new IllegalStateException("请先通过 Shizuku 授权。");
        }
        String selected = availableComponent(role, provider, shizuku.get(role.key));
        if (selected == null) {
            throw new IllegalStateException(provider.label() + " 当前无法提供「" + role.label() + "」。");
        }

        EnabledSelection enabled = role == Role.PASSKEY
                ? ensurePasskeyEnabled(selected) : new EnabledSelection(selected, false);
        try {
            if (role == Role.PASSKEY
                    && findEnabledEntry(shizuku.get(ENABLED_KEY), enabled.component()) == null) {
                throw new IllegalStateException("系统未保留已启用的通行密钥服务，请刷新后重试。");
            }
            if (!sameComponent(shizuku.get(role.key), enabled.component())) {
                writeAndVerify(role.key, enabled.component(), role.label());
            }
            if (role == Role.PASSKEY
                    && findEnabledEntry(shizuku.get(ENABLED_KEY), enabled.component()) == null) {
                throw new IllegalStateException("系统未保留已启用的通行密钥服务，请刷新后重试。");
            }
        } catch (IllegalStateException exception) {
            if (enabled.added()) {
                throw new IllegalStateException("服务已加入启用列表，首选设置未能确认。请刷新后重试。",
                        exception);
            }
            throw exception;
        }
    }

    public static boolean sameComponent(String left, String right) {
        if (left == null || right == null || left.contains(":") || right.contains(":")) {
            return false;
        }
        ComponentName component = ComponentName.unflattenFromString(left);
        return component != null && component.equals(ComponentName.unflattenFromString(right));
    }

    public static String providerLabel(String component) {
        if (component == null || component.isBlank() || component.equals("null")) {
            return "未设置";
        }
        for (Provider provider : Provider.values()) {
            for (Role role : Role.values()) {
                for (String known : provider.components(role)) {
                    if (sameComponent(component, known)) {
                        return provider.label();
                    }
                }
            }
        }
        return component;
    }

    private String availableComponent(Role role, Provider provider, String current) {
        String preferred = null;
        for (String candidate : provider.components(role)) {
            ComponentName component = ComponentName.unflattenFromString(candidate);
            Intent intent = new Intent(role.action).setPackage(component.getPackageName());
            for (ResolveInfo match : context.getPackageManager().queryIntentServices(intent, 0)) {
                ServiceInfo service = match.serviceInfo;
                // The query applies the effective enabled state, including per-user overrides.
                if (service != null && service.exported
                        && role.bindingPermission.equals(service.permission)
                        && component.getPackageName().equals(service.packageName)
                        && component.getClassName().equals(service.name)) {
                    String resolved = component.flattenToString();
                    if (sameComponent(current, resolved)) {
                        return resolved;
                    }
                    if (preferred == null) {
                        preferred = resolved;
                    }
                }
            }
        }
        return preferred;
    }

    private record EnabledSelection(String component, boolean added) {}

    private EnabledSelection ensurePasskeyEnabled(String component) {
        String enabled = shizuku.get(ENABLED_KEY);
        String existingEntry = findEnabledEntry(enabled, component);
        if (existingEntry != null) {
            return new EnabledSelection(existingEntry, false);
        }

        String updated = enabledPrefix(enabled) + component;
        // The Shizuku service verifies that the existing entries still match before writing.
        writeAndVerify(ENABLED_KEY, updated, "已启用的通行密钥服务");
        return new EnabledSelection(component, true);
    }

    private static String enabledPrefix(String enabled) {
        return enabled == null || enabled.isBlank() || "null".equals(enabled) ? "" : enabled + ":";
    }

    static boolean isCredentialSetting(String key) {
        return AUTOFILL_KEY.equals(key) || PRIMARY_KEY.equals(key) || ENABLED_KEY.equals(key);
    }

    static int androidUserId(int uid) {
        // Android allocates a range of 100,000 UIDs to each user.
        return uid / 100_000;
    }

    static boolean isAllowedWrite(String key, String value, String enabled) {
        if (!isCredentialSetting(key) || value == null) {
            return false;
        }
        String component = value;
        if (ENABLED_KEY.equals(key)) {
            String prefix = enabledPrefix(enabled);
            if (!value.startsWith(prefix)) {
                return false;
            }
            component = value.substring(prefix.length());
        }
        Role role = AUTOFILL_KEY.equals(key) ? Role.AUTOFILL : Role.PASSKEY;
        for (Provider provider : Provider.values()) {
            for (String allowed : provider.components(role)) {
                if (sameComponent(allowed, component)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String findEnabledEntry(String enabled, String component) {
        if (enabled == null) {
            return null;
        }
        for (String entry : enabled.split(":")) {
            if (sameComponent(entry, component)) {
                return entry;
            }
        }
        return null;
    }

    private static Set<String> enabledComponents(String value) {
        Set<String> components = new HashSet<>();
        if (value == null || value.equals("null")) return components;
        for (String entry : value.split(":")) {
            if (entry.isBlank()) continue;
            ComponentName component = ComponentName.unflattenFromString(entry);
            components.add(component == null ? entry : component.flattenToString());
        }
        return components;
    }

    private String readSetting(String key, boolean remote) {
        try {
            return remote ? shizuku.get(key) : Settings.Secure.getString(resolver, key);
        } catch (SecurityException exception) {
            throw new IllegalStateException("当前系统限制了读取凭据配置。", exception);
        }
    }

    private void writeAndVerify(String key, String value, String label) {
        shizuku.put(key, value);
        String actual = shizuku.get(key);
        boolean matches = ENABLED_KEY.equals(key)
                ? enabledComponents(value).equals(enabledComponents(actual))
                : sameComponent(value, actual);
        if (!matches) {
            throw new IllegalStateException("系统未保留「" + label + "」的更改，请刷新后重试。");
        }
    }
}
