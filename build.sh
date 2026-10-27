#!/usr/bin/env bash
# Builds a debug APK without Gradle, using the Debian/Ubuntu Android SDK packages:
#   apt-get install android-sdk-platform-23 aapt dalvik-exchange apksigner zipalign
set -euo pipefail
cd "$(dirname "$0")"
SDK_JAR=${SDK_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
DX_JAR=${DX_JAR:-/usr/lib/android-sdk/build-tools/debian/lib/dx.jar}
SRC=app/src/main
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

aapt package -f -m -J "$OUT/gen" -M "$SRC/AndroidManifest.xml" -S "$SRC/res" -I "$SDK_JAR" \
  -F "$OUT/unsigned.apk"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath "$SDK_JAR" \
  -d "$OUT/classes" $(find "$SRC/java" "$OUT/gen" -name '*.java')
java -jar "$DX_JAR" --dex --min-sdk-version=23 --output="$OUT/dex/classes.dex" "$OUT/classes"
(cd "$OUT/dex" && aapt add ../unsigned.apk classes.dex >/dev/null)
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# Release builds pass KEYSTORE, KS_PASS, KEY_ALIAS and KEY_PASS; otherwise a local debug key is used.
KS=${KEYSTORE:-debug.keystore}
KS_PASS=${KS_PASS:-android}
KEY_ALIAS=${KEY_ALIAS:-androiddebugkey}
KEY_PASS=${KEY_PASS:-$KS_PASS}
export KS_PASS KEY_PASS
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KEY_PASS" -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Saling Jaga Debug" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-key-alias "$KEY_ALIAS" --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
  --out "$OUT/saling-jaga-debug.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/saling-jaga-debug.apk"
echo "APK: $OUT/saling-jaga-debug.apk"
