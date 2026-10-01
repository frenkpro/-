#!/usr/bin/env bash
# Запасная сборка APK без Android SDK — когда GitHub Actions недоступен.
# Нужны: JDK 17+, curl, unzip и пакеты Ubuntu: aapt zipalign apksigner dalvik-exchange.
# Использование: tools/build-without-sdk.sh [versionCode] [versionName]
set -euo pipefail

VERSION_CODE=${1:-1}
VERSION_NAME=${2:-0.1.$VERSION_CODE-local}
HERE=$(cd "$(dirname "$0")/.." && pwd)
APP=$HERE/app
WORK=$HERE/build/nosdk
C=${CACHE:-$HOME/.cache/metro-recorder-nosdk}
KOTLIN=1.9.24
ANDROID_ALL=14-robolectric-10818077   # классы и ресурсы Android 14 (API 34) из Maven Central
MAVEN=https://repo1.maven.org/maven2
mkdir -p "$C"

fetch() {
  [ -s "$C/$2" ] && return 0
  for i in 1 2 3 4 5 6 7; do
    code=$(curl -sS -m 600 -o "$C/$2.part" -w "%{http_code}" "$MAVEN/$1") || code=000
    if [ "$code" = 200 ]; then mv "$C/$2.part" "$C/$2"; return 0; fi
    sleep $((i * 5))
  done
  echo "Не удалось скачать $1" >&2
  exit 1
}
fetch org/robolectric/android-all/$ANDROID_ALL/android-all-$ANDROID_ALL.jar android-all.jar
fetch org/jetbrains/kotlin/kotlin-compiler-embeddable/$KOTLIN/kotlin-compiler-embeddable-$KOTLIN.jar kotlin-compiler.jar
fetch org/jetbrains/kotlin/kotlin-stdlib/$KOTLIN/kotlin-stdlib-$KOTLIN.jar kotlin-stdlib.jar
fetch org/jetbrains/kotlin/kotlin-script-runtime/$KOTLIN/kotlin-script-runtime-$KOTLIN.jar kotlin-script-runtime.jar
fetch org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar kotlin-reflect.jar
fetch org/jetbrains/kotlin/kotlin-daemon-embeddable/$KOTLIN/kotlin-daemon-embeddable-$KOTLIN.jar kotlin-daemon.jar
fetch org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar trove4j.jar
fetch org/jetbrains/annotations/13.0/annotations-13.0.jar annotations.jar

rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/stdlib" "$WORK/dex" "$WORK/res" "$WORK/apk"

# 1. Kotlin → .class. Лямбды — обычными классами: dx не умеет invokedynamic.
java -cp "$C/kotlin-compiler.jar:$C/kotlin-stdlib.jar:$C/kotlin-script-runtime.jar:$C/kotlin-reflect.jar:$C/kotlin-daemon.jar:$C/trove4j.jar:$C/annotations.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
  -classpath "$C/android-all.jar:$C/kotlin-stdlib.jar:$C/annotations.jar" \
  -jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class \
  -d "$WORK/classes" $(find "$APP/src/main/java" -name '*.kt')

# Части stdlib с invokedynamic (kotlin.comparisons, kotlin.streams) на Android после dx не работают.
if grep -rlE "LambdaMetafactory|StringConcatFactory|kotlin/comparisons|kotlin/streams" "$WORK/classes"; then
  echo "Код ссылается на конструкции, которые не переживут dx (см. файлы выше)." >&2
  exit 1
fi

# 2. .class → classes.dex
(cd "$WORK/stdlib" && unzip -q "$C/kotlin-stdlib.jar" && rm -rf META-INF/versions)
dalvik-exchange --dex --min-sdk-version=26 --output="$WORK/dex/classes.dex" "$WORK/classes" "$WORK/stdlib"

# 3. Манифест и ресурсы (старому aapt нужен package в манифесте)
sed 's#<manifest xmlns:android="http://schemas.android.com/apk/res/android">#<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.metro.recorder">#' \
  "$APP/src/main/AndroidManifest.xml" > "$WORK/res/AndroidManifest.xml"
aapt package -f -M "$WORK/res/AndroidManifest.xml" -S "$APP/src/main/res" -I "$C/android-all.jar" \
  --min-sdk-version 29 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -F "$WORK/apk/unsigned.apk"
cp "$WORK/dex/classes.dex" "$WORK/apk/"
(cd "$WORK/apk" && aapt add -f unsigned.apk classes.dex > /dev/null)

# 4. Выравнивание и подпись тем же ключом, что и в сборке через Gradle
zipalign -f -p 4 "$WORK/apk/unsigned.apk" "$WORK/apk/aligned.apk"
OUT="$HERE/build/MetroRecorder-$VERSION_NAME.apk"
apksigner sign --ks "$APP/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --min-sdk-version 29 --out "$OUT" "$WORK/apk/aligned.apk"
apksigner verify "$OUT"
echo "Готово: $OUT"
