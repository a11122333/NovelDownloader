#!/usr/bin/env bash
# 手动构建流水线：aapt2 link -> javac -> d8 -> 打包 -> zipalign -> apksigner
set -e

SDK=/opt/android-sdk
BT=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
PATH=$JAVA_HOME/bin:$PATH

PROJ=${PROJ:-/workspace/NovelDownloader}
OUT=$PROJ/build

MIN_SDK=24
TARGET_SDK=34
VER_CODE=20
VER_NAME=2.0

APK_NAME="NovelDownloader-$VER_NAME.apk"
ZIP_NAME="NovelDownloader-$VER_NAME.zip"

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "==> [1/7] aapt2 compile 资源"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/compiled/res.zip"

echo "==> [2/7] aapt2 link 生成基础 APK 与 R.java"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$PROJ/AndroidManifest.xml" \
  -R "$OUT/compiled/res.zip" \
  -A "$PROJ/assets" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  --min-sdk-version $MIN_SDK \
  --target-sdk-version $TARGET_SDK \
  --version-code $VER_CODE \
  --version-name $VER_NAME \
  --no-version-vectors

echo "==> [3/7] javac 编译 Java（源码 + 生成的 R.java）"
find "$PROJ/java" -name '*.java' > "$OUT/sources.txt"
find "$OUT/gen" -name '*.java' >> "$OUT/sources.txt"
javac -encoding UTF-8 --release 8 \
  -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

echo "==> [4/7] d8 生成 classes.dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --lib "$PLATFORM" --min-api $MIN_SDK \
  --output "$OUT/dex" @"$OUT/classes.txt"

echo "==> [5/7] 打包 classes.dex 进 APK"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/unsigned.apk" <<'PY'
import sys, shutil, zipfile
base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(base, out)
with zipfile.ZipFile(out, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('packed ->', out)
PY

echo "==> [6/7] zipalign"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> [7/7] apksigner 签名"
KS="$PROJ/keystore.jks"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias niganma \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass 123456 -keypass 123456 \
    -dname "CN=泥甘麻, O=qq2211927635, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign \
  --ks "$KS" --ks-key-alias niganma \
  --ks-pass pass:123456 --key-pass pass:123456 \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$PROJ/$APK_NAME" "$OUT/aligned.apk"

echo "==> 校验"
"$BT/apksigner" verify -v "$PROJ/$APK_NAME" | head -8
"$BT/aapt" dump badging "$PROJ/$APK_NAME" | head -4
echo "DONE -> $PROJ/$APK_NAME"
ls -la "$PROJ/$APK_NAME"
sha256sum "$PROJ/$APK_NAME"

# 额外打包 zip：避免中文名/传输导致 APK 损坏，zip 能保证二进制完整性
( cd "$PROJ" && rm -f "$ZIP_NAME" && zip -q -9 "$ZIP_NAME" "$APK_NAME" )
echo "ZIP -> $PROJ/$ZIP_NAME"
ls -la "$PROJ/$ZIP_NAME"