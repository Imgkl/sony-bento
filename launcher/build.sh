#!/bin/bash
# Builds build/sony-bento.apk with the Android SDK command-line tools (no Gradle).
# Needs the Satoshi font files in assets/fonts (see assets/fonts/README.md).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.1.0"
JAR="$SDK/platforms/android-36/android.jar"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"

for f in Satoshi-Regular Satoshi-Bold Satoshi-Black; do
  [ -f "assets/fonts/$f.ttf" ] || { echo "Missing assets/fonts/$f.ttf. See assets/fonts/README.md." >&2; exit 1; }
done

rm -rf build
mkdir -p build/classes build/dex build/gen

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml -A assets --java build/gen build/res.zip
"$JAVA_HOME/bin/javac" --release 17 -Xlint:-options -cp "$JAR" -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --release --min-api 31 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out build/sony-bento.apk build/aligned.apk
echo "built build/sony-bento.apk"
