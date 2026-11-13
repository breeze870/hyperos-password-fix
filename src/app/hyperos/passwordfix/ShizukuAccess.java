package app.hyperos.passwordfix;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

public final class ShizukuAccess implements AutoCloseable {
    public enum Status {
        NOT_RUNNING, UNSUPPORTED, NOT_GRANTED, REQUESTING, DENIED, CONNECTING, READY, UNAVAILABLE
    }

    private static final AtomicInteger REQUEST_CODES = new AtomicInteger(109);
    private static final int REQUEST_TIMEOUT_MS = 30_000;
    private static final int CONNECTION_TIMEOUT_MS = 8_000;
    private static final int COMMAND_TIMEOUT_SECONDS = 5;
    private static final String SYSTEM_CREDENTIAL_AUTOFILL = "credential-provider";
    private static final int READ = IBinder.FIRST_CALL_TRANSACTION;
    private static final int WRITE = READ + 1;
    private static final int DESTROY = 16777115; // Shizuku UserService lifecycle transaction.
    private static final String DESCRIPTOR = "app.hyperos.passwordfix.ISecureProviderSettings";
    private static final String TAG = "CredentialShizuku";
    private static final int MAX_SETTING_BYTES = 16 * 1024;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onChanged;
    private final Shizuku.UserServiceArgs serviceArgs;
    private final Shizuku.OnBinderReceivedListener received = this::refresh;
    private final Shizuku.OnBinderDeadListener dead = this::onBinderDead;
    private final Shizuku.OnRequestPermissionResultListener permission = this::onPermissionResult;
    private final Runnable timeout = this::onConnectionTimeout;
    private final Runnable permissionTimeout = this::onPermissionTimeout;
    private volatile Status status = Status.NOT_RUNNING;
    private volatile IBinder binder;
    private boolean started;
    private ServiceConnection connection;
    private volatile IBinder connectedServer;
    private IBinder requestServer;
    private int requestCode;

    private final class SettingsConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // The SDK dispatches while iterating its callback set. Defer changes to bindings.
            main.post(() -> handleConnected(service));
        }

        private void handleConnected(IBinder service) {
            if (!started || connection != this || status != Status.CONNECTING) {
                return;
            }
            main.removeCallbacks(timeout);
            if (!isAlive(service) || !isConnectedServer(connectedServer)) {
                disconnect();
                setStatus(Status.UNAVAILABLE);
                return;
            }
            binder = service;
            setStatus(Status.READY);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            main.post(this::handleDisconnected);
        }

        private void handleDisconnected() {
            if (!started || connection != this) return;
            disconnect();
            setStatus(isServerAlive() ? Status.UNAVAILABLE : Status.NOT_RUNNING);
        }
    }

    public ShizukuAccess(Context context, Runnable onChanged) {
        this.onChanged = onChanged;
        serviceArgs = new Shizuku.UserServiceArgs(new ComponentName(context, SettingsService.class))
                .tag("secure-settings-" + context.getApplicationInfo().uid)
                .processNameSuffix("secure_settings")
                .version(2)
                .daemon(false);
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;
        Shizuku.addBinderDeadListener(dead, main);
        Shizuku.addRequestPermissionResultListener(permission, main);
        Shizuku.addBinderReceivedListenerSticky(received, main);
        refresh();
    }

    @Override
    public void close() {
        if (!started) {
            return;
        }
        started = false;
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(dead);
        Shizuku.removeRequestPermissionResultListener(permission);
        clearPermissionRequest();
        disconnect();
    }

    public Status status() {
        return status;
    }

    public boolean canWrite() {
        return status == Status.READY && isAlive(binder) && isConnectedServer(connectedServer);
    }

    public void refresh() {
        if (!started) return;
        IBinder server = Shizuku.getBinder();
        if (!isAlive(server)) {
            onBinderDead();
            return;
        }
        if (status == Status.REQUESTING && requestServer == server) return;
        if (requestServer != null && requestServer != server) clearPermissionRequest();
        if (connection != null && connectedServer != server) disconnect();
        try {
            // SDK helpers cache grants and rationale. Ask this app's current server session.
            IShizukuService service = IShizukuService.Stub.asInterface(server);
            if (Shizuku.getVersion() < 13) {
                disconnect();
                setStatus(Status.UNSUPPORTED);
            } else if (!service.checkSelfPermission()) {
                disconnect();
                setStatus(service.shouldShowRequestPermissionRationale()
                        ? Status.DENIED : Status.NOT_GRANTED);
            } else if (Shizuku.checkRemotePermission(ProviderSettings.PERMISSION)
                    != PackageManager.PERMISSION_GRANTED) {
                disconnect();
                setStatus(Status.UNAVAILABLE);
            } else if (connection == null || (status == Status.READY && !isAlive(binder))) {
                disconnect();
                connection = new SettingsConnection();
                connectedServer = server;
                setStatus(Status.CONNECTING);
                main.postDelayed(timeout, CONNECTION_TIMEOUT_MS);
                Shizuku.bindUserService(serviceArgs, connection);
            }
        } catch (RemoteException | RuntimeException exception) {
            Log.w(TAG, "Unable to connect to the Shizuku service", exception);
            disconnect();
            setStatus(isServerAlive() ? Status.UNAVAILABLE : Status.NOT_RUNNING);
        }
    }

    public void requestPermission() {
        IBinder server = Shizuku.getBinder();
        if (!isAlive(server)) {
            throw new IllegalStateException("请先打开并启动 Shizuku。");
        }
        if (status == Status.REQUESTING || status == Status.CONNECTING || canWrite()) {
            return;
        }
        try {
            if (Shizuku.getVersion() < 13) {
                throw new IllegalStateException("需要 Shizuku 13 或更新版本。");
            }
            IShizukuService service = IShizukuService.Stub.asInterface(server);
            if (service.checkSelfPermission()) {
                refresh();
                if (status == Status.UNAVAILABLE) {
                    throw new IllegalStateException(
                            "Shizuku 已授权，但服务暂时不可用。请检查 Shizuku 的运行状态后重试。");
                }
                return;
            }
            if (service.shouldShowRequestPermissionRationale()) {
                setStatus(Status.DENIED);
                throw new IllegalStateException("请在 Shizuku 的「已授权的应用」里允许本工具。");
            }
            requestCode = REQUEST_CODES.getAndIncrement();
            requestServer = server;
            setStatus(Status.REQUESTING);
            main.postDelayed(permissionTimeout, REQUEST_TIMEOUT_MS);
            Shizuku.requestPermission(requestCode);
        } catch (IllegalStateException exception) {
            if (status == Status.REQUESTING) {
                clearPermissionRequest();
                setStatus(isServerAlive() ? Status.UNAVAILABLE : Status.NOT_RUNNING);
            }
            throw exception;
        } catch (RemoteException | RuntimeException exception) {
            Log.w(TAG, "Unable to request Shizuku authorization", exception);
            clearPermissionRequest();
            setStatus(isServerAlive() ? Status.UNAVAILABLE : Status.NOT_RUNNING);
            throw new IllegalStateException("无法发起 Shizuku 授权。请检查 Shizuku 是否正在运行。", exception);
        }
    }

    String get(String key) {
        return call(READ, key, null);
    }

    void put(String key, String value) {
        IBinder server = connectedServer;
        boolean authorized;
        try {
            authorized = canWrite() && hasLiveGrant(server) && isConnectedServer(server);
        } catch (RemoteException | RuntimeException exception) {
            main.post(this::refresh);
            throw new IllegalStateException("无法验证 Shizuku 授权，请重新检查连接后重试。", exception);
        }
        if (!authorized) {
            main.post(this::refresh);
            throw new IllegalStateException("Shizuku 授权已失效，请重新授权后重试。");
        }
        call(WRITE, key, value);
    }

    private static boolean hasLiveGrant(IBinder server) throws RemoteException {
        // The SDK helper caches positive results. Query the same app-scoped RPC on this server.
        return isAlive(server) && IShizukuService.Stub.asInterface(server).checkSelfPermission();
    }

    private String call(int operation, String key, String value) {
        IBinder current = binder;
        if (current == null || !canWrite()) {
            throw new IllegalStateException("Shizuku 连接已断开。请启动 Shizuku 后重试。");
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(key);
            if (operation == WRITE) {
                data.writeString(value);
            }
            if (!current.transact(operation, data, reply, 0)) {
                throw new IllegalStateException("Shizuku 服务无法处理本次请求。");
            }
            reply.readException();
            String error = reply.readString();
            if (error != null) {
                throw new IllegalStateException(error);
            }
            return operation == READ ? reply.readString() : null;
        } catch (RemoteException | SecurityException exception) {
            throw new IllegalStateException("Shizuku 无法访问系统设置。请检查授权和运行状态。", exception);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void onPermissionResult(int requestCode, int result) {
        if (!started || requestCode != this.requestCode || requestServer != Shizuku.getBinder()) {
            return;
        }
        clearPermissionRequest();
        setStatus(result == PackageManager.PERMISSION_GRANTED ? Status.NOT_GRANTED : Status.DENIED);
        if (result == PackageManager.PERMISSION_GRANTED) {
            refresh();
        }
    }

    private void onBinderDead() {
        if (!started || isServerAlive()) return;
        clearPermissionRequest();
        disconnect();
        setStatus(Status.NOT_RUNNING);
    }

    private void onPermissionTimeout() {
        if (!started || status != Status.REQUESTING) return;
        // Let the user retry if the manager never returns a result. A late result for this
        // request still applies until a new request is sent.
        setStatus(Status.NOT_GRANTED);
        refresh();
    }

    private void clearPermissionRequest() {
        main.removeCallbacks(permissionTimeout);
        requestCode = 0;
        requestServer = null;
    }

    private void onConnectionTimeout() {
        if (status == Status.CONNECTING) {
            Log.w(TAG, "Timed out connecting to the Shizuku UserService");
            disconnect();
            setStatus(Status.UNAVAILABLE);
        }
    }

    private void disconnect() {
        main.removeCallbacks(timeout);
        binder = null;
        ServiceConnection previous = connection;
        connection = null;
        connectedServer = null;
        if (previous != null) {
            try {
                Shizuku.unbindUserService(serviceArgs, previous, false);
            } catch (RuntimeException ignored) {
                // Shizuku may be stopping while the activity is being closed.
            }
        }
    }

    private static boolean isAlive(IBinder binder) {
        return binder != null && binder.isBinderAlive();
    }

    private static boolean isServerAlive() {
        return isAlive(Shizuku.getBinder());
    }

    private static boolean isConnectedServer(IBinder server) {
        return isAlive(server) && server == Shizuku.getBinder();
    }

    private void setStatus(Status next) {
        if (status != next) {
            status = next;
            onChanged.run();
        }
    }

    public static final class SettingsService extends Binder {
        private final int ownerUid;
        private final String user;

        public SettingsService(Context context) {
            attachInterface(null, DESCRIPTOR);
            ownerUid = context.getApplicationInfo().uid;
            user = Integer.toString(ProviderSettings.androidUserId(ownerUid));
        }

        @Override
        protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == DESTROY) {
                int caller = Binder.getCallingUid();
                if (caller != 0 && caller != 2000) {
                    throw new SecurityException("Only the Shizuku server may stop this service");
                }
                System.exit(0);
                return true;
            }
            if (code != READ && code != WRITE) {
                return super.onTransact(code, data, reply, flags);
            }
            if (Binder.getCallingUid() != ownerUid) {
                throw new SecurityException("Caller does not own this service");
            }
            data.enforceInterface(DESCRIPTOR);
            String key = data.readString();
            String value = code == WRITE ? data.readString() : null;
            data.enforceNoDataAvail();
            if (!ProviderSettings.isCredentialSetting(key)) {
                throw new SecurityException("Unknown credential setting");
            }

            String error = null;
            String result = null;
            try {
                if (code == WRITE) {
                    String enabled = ProviderSettings.ENABLED_KEY.equals(key) ? getSetting(key) : null;
                    if (!ProviderSettings.isAllowedWrite(key, value, enabled)) {
                        throw new IllegalStateException("系统设置已变化，或所选服务不可用。请刷新后重试。");
                    }
                    runSettings("put", key, value);
                } else {
                    result = getSetting(key);
                }
            } catch (IllegalStateException exception) {
                error = exception.getMessage();
            }
            reply.writeNoException();
            reply.writeString(error);
            if (code == READ) {
                reply.writeString(result);
            }
            return true;
        }

        private String getSetting(String key) {
            String result = runSettings("get", key, null);
            if (!isSettingValue(key, result)) {
                throw new IllegalStateException("系统返回了无法识别的凭据设置。");
            }
            return result.equals("null") ? null : result;
        }

        private static boolean isSettingValue(String key, String value) {
            if (value.isEmpty() || value.equals("null")) return true;
            if (ProviderSettings.AUTOFILL_KEY.equals(key)) {
                return SYSTEM_CREDENTIAL_AUTOFILL.equals(value) || isFlattenedComponent(value);
            }
            for (String entry : value.split(":", -1)) {
                if (!entry.isEmpty() && !isFlattenedComponent(entry)) return false;
            }
            return true;
        }

        private static boolean isFlattenedComponent(String value) {
            // Validate the serialized ComponentName rather than treating a CLI diagnostic as data.
            return value.matches("[\\p{L}\\p{N}_.]+/\\.?[\\p{L}\\p{N}_$]+(?:\\.[\\p{L}\\p{N}_$]+)*");
        }

        private String runSettings(String verb, String key, String value) {
            Process process = null;
            FutureTask<byte[]> reader = null;
            try {
                // Each argument is passed directly to cmd. No shell evaluates the setting value.
                ProcessBuilder command = new ProcessBuilder(
                        "/system/bin/cmd", "settings", "--user", user, verb, "secure", key);
                if (value != null) {
                    command.command().add(value);
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMMAND_TIMEOUT_SECONDS);
                process = command.redirectErrorStream(true).start();
                InputStream stream = process.getInputStream();
                reader = new FutureTask<>(() -> {
                    try (stream) {
                        return stream.readNBytes(MAX_SETTING_BYTES + 1);
                    }
                });
                Thread outputReader = new Thread(reader, "credential-settings-output");
                outputReader.setDaemon(true);
                outputReader.start();
                byte[] output = reader.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (output.length > MAX_SETTING_BYTES) {
                    throw new IllegalStateException("凭据设置超出可读取的长度。");
                }
                if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    throw new IllegalStateException("读取或保存系统设置超时，请重试。");
                }
                if (process.exitValue() != 0) {
                    throw new IllegalStateException("系统拒绝通过 Shizuku 访问凭据设置。");
                }
                String result = new String(output, StandardCharsets.UTF_8).replaceFirst("\\r?\\n$", "");
                if ("put".equals(verb) && !result.isBlank()) {
                    throw new IllegalStateException("系统拒绝通过 Shizuku 保存凭据设置。");
                }
                if ("get".equals(verb) && (result.contains("\n") || result.contains("\r"))) {
                    throw new IllegalStateException("无法读取此项系统设置。");
                }
                return result;
            } catch (TimeoutException exception) {
                throw new IllegalStateException("读取或保存系统设置超时，请重试。", exception);
            } catch (IOException | ExecutionException exception) {
                throw new IllegalStateException("Shizuku 无法访问系统设置。", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("操作已中断。", exception);
            } finally {
                if (process != null) {
                    process.destroyForcibly();
                }
                if (reader != null) reader.cancel(true);
            }
        }
    }
}
