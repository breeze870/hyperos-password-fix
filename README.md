# Repair Xiaomi HyperOS 4 Password Manager

## 功能

当澎湃 OS 自带设置无法切换凭据服务时，通过 Shizuku 直接修改系统配置，分别选择**账号密码自动填充**和**通行密钥首选**服务。切换后读回系统配置确认，未授权时只读。已有密码和通行密钥仍保存在原管理器中。

## 已验证版本

仅在 **小米 15（24129PN74C）**、**国行澎湃 OS 4.0.3.0（OS4.0.3.0.XOCCNXM）** 上做过真机验证。

| 软件 | 真机版本 |
| --- | --- |
| Bitwarden | `2026.10.1` |
| Google 密码管理工具 | `2026.08.09.965935303.1-release` |
| 小米密码管理器 | `1.1.26` |
| Google Play 服务（提供 Google 凭据服务） | `26.34.36 (260400-981326859)` |
| Shizuku（thedjchi 版） | `13.7.0-thedjchi` |

## 构建与安装

需要 JDK 17 或更高版本、Android SDK Platform 36.1 和 Build Tools 37.0.0。SDK 若装在自定义位置，先设置 `ANDROID_SDK_ROOT`。

```sh
./build.sh
adb install -t -r build/hyperos-password-fix.apk
```

应用名称为 **HyperOS Password Fix**，包名为 `app.hyperos.passwordfix`。它与旧版可同时安装，首次打开需重新授予 Shizuku 权限。同一包名的覆盖安装需使用相同签名密钥。

## 通过 GitHub Actions 发布

打开 **Actions → Build and publish Android release → Run workflow**，选择 `main`，
填写新的版本标签，例如 `v1.0.0`。构建成功后，工作流会把签名 APK 和
SHA-256 校验文件上传到对应的 **Releases**。

工作流使用仓库的 `ANDROID_RELEASE_KEYSTORE_BASE64`（PKCS#12 密钥库的 Base64）
和 `ANDROID_RELEASE_KEYSTORE_PASSWORD` 两个 Actions secrets。密钥别名为
`hyperos-password-fix`。首次配置后请保管好同一份密钥库及密码，后续版本继续使用。
APK 仍用上面的 `adb install -t -r` 安装。已安装且签名不同的 APK 无法覆盖更新。
