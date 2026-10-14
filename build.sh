#!/bin/bash
# Linux-збірка, дзеркало Windows-воркфлоу: jpackage з вбудованим runtime (Java в системі не потрібна).
# Результат: dist/PaxLauncher-<ver>-linux.tar.gz (портативний/без root) і dist/paxlauncher_<ver>*.deb
# Потрібні: JDK 21 (javac, jar, jpackage), fakeroot, dpkg-deb. Запуск з кореня репо: packaging/linux/build.sh 1.0.16
set -euo pipefail
VER="${1:?usage: build.sh <version>}"
cd "$(dirname "$0")/../.."
PK=packaging/linux
OUT="$PWD/dist"; W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT" "$W/src" "$W/classes" "$W/input" "$W/img"

# 1. компіляція (версія — у копії, як і у Windows)
cp ./*.java "$W/src/"
sed -i "s/static final String VERSION = \".*\";/static final String VERSION = \"$VER\";/" "$W/src/Updater.java"
javac -encoding UTF-8 -d "$W/classes" "$W"/src/*.java
jar cfe "$W/input/PaxLauncher.jar" PaxLauncher -C "$W/classes" .

# 2. іконка PNG
ICON="$PK/icon.png"
if [ ! -f "$ICON" ]; then
  python3 - "$W/icon.png" <<'PY'
import sys
from PIL import Image
im = Image.open("icon.ico")
im.size = max(im.info.get("sizes", {im.size}))
im.convert("RGBA").resize((256, 256)).save(sys.argv[1])
PY
  ICON="$W/icon.png"
fi

# 3. app-image з вбудованим runtime (ті самі модулі, що й у Windows)
MODS=java.desktop,java.net.http,java.management,java.logging,jdk.crypto.ec
jpackage --type app-image --name PaxLauncher --app-version "$VER" --vendor "Kryp-s" \
  --description "Ukrainian Minecraft launcher" --copyright "Kryp-s" \
  --input "$W/input" --main-jar PaxLauncher.jar --main-class PaxLauncher \
  --icon "$ICON" --add-modules "$MODS" --dest "$W/img"

# 4. .deb з того самого app-image (ставиться в /opt/paxlauncher, ярлик у меню)
jpackage --type deb --app-image "$W/img/PaxLauncher" --name PaxLauncher --linux-package-name paxlauncher \
  --app-version "$VER" --vendor "Kryp-s" --linux-shortcut --linux-menu-group Game \
  --linux-deb-maintainer "noreply@github.com" --linux-app-category games --dest "$OUT"

# 5. tar.gz: той самий app-image + install.sh (встановлення в ~/.local без root)
cp "$PK/install.sh" "$PK/PaxLauncher.desktop" "$W/img/PaxLauncher/"
chmod +x "$W/img/PaxLauncher/install.sh"
tar -C "$W/img" -czf "$OUT/PaxLauncher-$VER-linux.tar.gz" PaxLauncher

ls -lh "$OUT"
