#!/usr/bin/env bash
# PaxLauncher — збірка Linux-пакетів (.deb і .tar.gz).
# Використання: packaging/linux/build.sh <версія>   (наприклад: 1.0.0)
# Результат лягає в dist/ (звідти його забирає крок релізу в GitHub Actions).
#
# Потрібно: JDK 21+ (javac, jar, jpackage), fakeroot, dpkg-deb, python3-pil.
set -euo pipefail

VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  echo "Використання: $0 <версія>" >&2
  exit 2
fi
# jpackage (deb) вимагає версію, що починається з цифри: прибираємо префікс "v" і зайві символи
VERSION="${VERSION#v}"
DEB_VERSION="$(printf '%s' "$VERSION" | sed -E 's/[^0-9A-Za-z.+~-]/./g')"
if [[ ! "$DEB_VERSION" =~ ^[0-9] ]]; then
  echo "Версія '$VERSION' має починатися з цифри (очікується щось на кшталт 1.0.0)" >&2
  exit 2
fi

# Працюємо з кореня репозиторію, звідки б не запустили скрипт
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

APP_NAME="PaxLauncher"
MAIN_CLASS="PaxLauncher"
BUILD="$ROOT/build/linux"
DIST="$ROOT/dist"

for tool in javac jar jpackage python3; do
  command -v "$tool" >/dev/null || { echo "Не знайдено '$tool' у PATH" >&2; exit 1; }
done
[[ -f icon.ico ]] || { echo "Не знайдено icon.ico у корені репозиторію" >&2; exit 1; }
compgen -G "*.java" >/dev/null || { echo "Не знайдено *.java у корені репозиторію" >&2; exit 1; }

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/input" "$DIST"

echo "==> Компіляція"
javac --release 21 -nowarn -encoding UTF-8 -d "$BUILD/classes" ./*.java

echo "==> Пакування JAR"
jar --create --file "$BUILD/input/$APP_NAME.jar" --main-class "$MAIN_CLASS" -C "$BUILD/classes" .

echo "==> Іконка (icon.ico -> png)"
python3 - "$ROOT/icon.ico" "$BUILD/$APP_NAME.png" <<'PY'
import sys
from PIL import Image

src, dst = sys.argv[1], sys.argv[2]
ico = Image.open(src)
sizes = sorted(ico.info.get("sizes") or {ico.size}, key=lambda s: s[0] * s[1])
ico.size = sizes[-1]          # беремо найбільший розмір із .ico
img = ico.convert("RGBA")
if img.size != (256, 256):
    img = img.resize((256, 256), Image.LANCZOS)
img.save(dst, "PNG")
PY

# Модулі, потрібні лаунчеру (Swing, HTTP-клієнт, TLS, керування процесами).
# Вказані явно, бо jdeps не бачить модулі, що підтягуються через рефлексію.
MODULES="java.base,java.desktop,java.net.http,java.management,java.logging,java.naming,java.prefs,java.xml,jdk.crypto.ec,jdk.unsupported,jdk.compiler"

COMMON_ARGS=(
  --name "$APP_NAME"
  --app-version "$DEB_VERSION"
  --vendor "Kryp-s"
  --description "Український лаунчер Minecraft"
  --input "$BUILD/input"
  --main-jar "$APP_NAME.jar"
  --main-class "$MAIN_CLASS"
  --icon "$BUILD/$APP_NAME.png"
  --license-file "$ROOT/LICENSE"
  --add-modules "$MODULES"
  --java-options "-Dfile.encoding=UTF-8"
)

echo "==> app-image -> .tar.gz"
jpackage --type app-image "${COMMON_ARGS[@]}" --dest "$BUILD/app-image"
tar -C "$BUILD/app-image" -czf "$DIST/$APP_NAME-$VERSION-linux-x64.tar.gz" "$APP_NAME"

echo "==> .deb"
if command -v fakeroot >/dev/null && command -v dpkg-deb >/dev/null; then
  jpackage --type deb "${COMMON_ARGS[@]}" \
    --linux-package-name paxlauncher \
    --linux-menu-group "Game" \
    --linux-app-category games \
    --linux-shortcut \
    --dest "$BUILD/deb"
  cp "$BUILD"/deb/*.deb "$DIST/"
else
  echo "fakeroot або dpkg-deb не знайдено — .deb пропущено" >&2
fi

echo "==> Готово:"
ls -lh "$DIST"
