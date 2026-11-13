#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
build_dir="$project_dir/build"
output_apk="${1:-$build_dir/hyperos-password-fix.apk}"
if [[ "$(uname -s)" == Darwin ]]; then
  default_sdk="$HOME/Library/Android/sdk"
else
  default_sdk="$HOME/Android/Sdk"
fi
sdk_dir="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$default_sdk}}"
build_tools="$sdk_dir/build-tools/37.0.0"
android_jar="$sdk_dir/platforms/android-36.1/android.jar"
studio_jdk="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

if [[ -z "${JAVA_HOME:-}" && -x "$studio_jdk/bin/javac" ]]; then
  export JAVA_HOME="$studio_jdk"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if [[ ! -r "$android_jar" ]]; then
  printf 'Android SDK Platform 36.1 is missing from %s\n' "$sdk_dir" >&2
  exit 1
fi
for tool in aapt2 zipalign apksigner; do
  if [[ ! -x "$build_tools/$tool" ]]; then
    printf 'Android SDK Build-Tools 37.0.0 is missing %s\n' "$tool" >&2
    exit 1
  fi
done
r8_jar="$build_tools/lib/d8.jar"
if [[ ! -r "$r8_jar" ]]; then
  printf 'Android SDK Build-Tools 37.0.0 is missing the R8 compiler.\n' >&2
  exit 1
fi
for tool in java javac jar keytool zip unzip curl shasum; do
  if ! command -v "$tool" >/dev/null; then
    printf 'Required executable missing: %s\n' "$tool" >&2
    exit 1
  fi
done

mkdir -p "$build_dir" "$(dirname "$output_apk")"
scratch_dir="$(mktemp -d "$build_dir/build.XXXXXXXX")"
trap 'rm -rf "$scratch_dir"' EXIT
mkdir -p "$scratch_dir"/{generated,classes,dex}

fetch_verified() {
  local uri="$1" checksum="$2" output="$3" download
  if [[ -f "$output" ]] && printf '%s  %s\n' "$checksum" "$output" | shasum -a 256 -c --status; then
    return
  fi
  download="$scratch_dir/$(basename "$output")"
  curl -fsSL --proto '=https' --proto-redir '=https' --retry 2 -o "$download" "$uri"
  if ! printf '%s  %s\n' "$checksum" "$download" | shasum -a 256 -c --status; then
    printf 'SHA-256 mismatch for %s\n' "$uri" >&2
    exit 1
  fi
  mv "$download" "$output"
}

shizuku_version=13.1.5
shizuku_maven=https://repo.maven.apache.org/maven2/dev/rikka/shizuku
shizuku_jars=()
keep_rules=()
java_classpath="$android_jar"
while read -r artifact checksum; do
  aar="$build_dir/shizuku-$artifact-$shizuku_version.aar"
  fetch_verified "$shizuku_maven/$artifact/$shizuku_version/$artifact-$shizuku_version.aar" "$checksum" "$aar"
  dependency="$scratch_dir/shizuku-$artifact.jar"
  unzip -p "$aar" classes.jar > "$dependency"
  shizuku_jars+=("$dependency")
  consumer_rules="$scratch_dir/shizuku-$artifact.pro"
  unzip -p "$aar" proguard.txt > "$consumer_rules"
  if [[ -s "$consumer_rules" ]]; then
    keep_rules+=(--pg-conf "$consumer_rules")
  fi
  java_classpath="$java_classpath:$dependency"
done <<'CHECKSUMS'
api 4def9bde498ef8626614c2fc5db9af4749c86f16f6c33e3f5658d35e70bab59b
provider b0f18cd9812464ec171c53cac93a819fe411718a3965c311f01eb4de265381b3
aidl 33fe7191cdd69fcb66d649264f3b0c47acb2f3d6343afc05b98dbbff6f221963
shared 4659642c9339be0a26e9c65bb8648f7ad6d8f4a465f557993ccbc78802381635
CHECKSUMS

annotations="$build_dir/androidx-annotation-1.3.0.jar"
fetch_verified \
  https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar \
  97dc45afefe3a1e421da42b8b6e9f90491477c45fc6178203e3a5e8a05ee8553 "$annotations"
java_classpath="$java_classpath:$annotations"

"$build_tools/aapt2" compile --dir "$project_dir/res" -o "$scratch_dir/resources.zip"
"$build_tools/aapt2" link -I "$android_jar" --manifest "$project_dir/AndroidManifest.xml" \
  --java "$scratch_dir/generated" --proguard "$scratch_dir/manifest-keep.pro" \
  -o "$scratch_dir/unsigned.apk" "$scratch_dir/resources.zip"

sources=()
while IFS= read -r -d '' source; do
  sources+=("$source")
done < <(find "$project_dir/src" "$scratch_dir/generated" -type f -name '*.java' -print0)
if [[ ${#sources[@]} -eq 0 ]]; then
  printf 'No Java source files found.\n' >&2
  exit 1
fi
javac --release 17 -g:none -encoding UTF-8 -classpath "$java_classpath" \
  -d "$scratch_dir/classes" "${sources[@]}"
jar cf "$scratch_dir/classes.jar" -C "$scratch_dir/classes" .
java -cp "$r8_jar" com.android.tools.r8.R8 --release --min-api 34 \
  --lib "$android_jar" --classpath "$annotations" \
  --pg-conf "$scratch_dir/manifest-keep.pro" --pg-conf "$project_dir/release-rules.pro" \
  "${keep_rules[@]}" --output "$scratch_dir/dex" \
  "$scratch_dir/classes.jar" "${shizuku_jars[@]}"
(
  cd "$scratch_dir/dex"
  mkdir assets
  cp "$project_dir/THIRD_PARTY_NOTICES.txt" assets/
  export TZ=UTC
  touch -t 198001010000 classes*.dex assets/THIRD_PARTY_NOTICES.txt
  zip -9 -X -q "$scratch_dir/unsigned.apk" classes*.dex assets/THIRD_PARTY_NOTICES.txt
)
"$build_tools/zipalign" -f 4 "$scratch_dir/unsigned.apk" "$scratch_dir/aligned.apk"

signing_alias=bitwarden-settings
signing_key="${PENGPAI_SIGNING_KEY:-}"
if [[ -n "$signing_key" ]]; then
  signing_alias="${PENGPAI_SIGNING_ALIAS:?Set the alias for PENGPAI_SIGNING_KEY}"
  : "${PENGPAI_KEYSTORE_PASSWORD:?Set the password for PENGPAI_SIGNING_KEY}"
elif [[ -f "$build_dir/local-signing.p12" ]]; then
  # Reuse the existing signing key.
  signing_key="$build_dir/local-signing.p12"
  chmod 600 "$signing_key"
  PENGPAI_KEYSTORE_PASSWORD=android
  PENGPAI_KEY_PASSWORD="$PENGPAI_KEYSTORE_PASSWORD"
else
  local_signing="$build_dir/signing"
  if [[ ! -f "$local_signing/key.p12" ]]; then
    if ! (umask 077 && mkdir "$local_signing"); then
      printf 'Local signing setup is incomplete or another build is initializing it.\n' >&2
      exit 1
    fi
    trap 'rm -rf "$scratch_dir"; if [[ ! -f "$local_signing/key.p12" ]]; then rm -f "$local_signing/password"; rmdir "$local_signing"; fi' EXIT
    (
      umask 077
      export PENGPAI_KEYSTORE_PASSWORD
      PENGPAI_KEYSTORE_PASSWORD="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
      printf '%s\n' "$PENGPAI_KEYSTORE_PASSWORD" > "$local_signing/password"
      keytool -genkeypair -noprompt -storetype PKCS12 -keystore "$scratch_dir/key.p12" \
        -storepass:env PENGPAI_KEYSTORE_PASSWORD -alias "$signing_alias" -keyalg RSA -keysize 2048 \
        -validity 10000 -dname 'CN=HyperOS Password Fix Local Build'
      mv "$scratch_dir/key.p12" "$local_signing/key.p12"
    )
  fi
  signing_key="$local_signing/key.p12"
  PENGPAI_KEYSTORE_PASSWORD="$(cat "$local_signing/password")"
  PENGPAI_KEY_PASSWORD="$PENGPAI_KEYSTORE_PASSWORD"
fi
export PENGPAI_KEYSTORE_PASSWORD
export PENGPAI_KEY_PASSWORD="${PENGPAI_KEY_PASSWORD:-$PENGPAI_KEYSTORE_PASSWORD}"

"$build_tools/apksigner" sign --ks "$signing_key" --ks-key-alias "$signing_alias" \
  --ks-pass env:PENGPAI_KEYSTORE_PASSWORD --key-pass env:PENGPAI_KEY_PASSWORD \
  --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true \
  --v4-signing-enabled false --out "$scratch_dir/signed.apk" "$scratch_dir/aligned.apk"
"$build_tools/apksigner" verify "$scratch_dir/signed.apk"
mv "$scratch_dir/signed.apk" "$output_apk"
printf 'APK ready: %s\n' "$output_apk"
