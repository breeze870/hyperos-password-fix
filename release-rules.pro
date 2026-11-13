# Shizuku loads this Binder service by class name in its separate process.
-keep,allowoptimization class app.hyperos.passwordfix.ShizukuAccess$SettingsService {
    public <init>(android.content.Context);
}
