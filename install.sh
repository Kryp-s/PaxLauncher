#!/bin/sh
# Встановлення без root: ~/.local/share/paxlauncher-app + ярлик у меню + команда pax-launcher
set -e
SRC="$(dirname "$(readlink -f "$0")")"
DEST="$HOME/.local/share/paxlauncher-app"
mkdir -p "$HOME/.local/bin" "$HOME/.local/share/applications"
rm -rf "$DEST"; cp -r "$SRC" "$DEST"
rm -f "$DEST/install.sh"
ln -sf "$DEST/bin/PaxLauncher" "$HOME/.local/bin/pax-launcher"
sed -e "s|@EXEC@|$DEST/bin/PaxLauncher|" -e "s|@ICON@|$DEST/lib/PaxLauncher.png|" \
    "$SRC/PaxLauncher.desktop" > "$HOME/.local/share/applications/paxlauncher.desktop"
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$HOME/.local/share/applications" || true
echo "PaxLauncher встановлено в $DEST. Запуск: pax-launcher або з меню програм."
