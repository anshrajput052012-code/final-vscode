#!/bin/bash
# Pocket VS Code — session launcher.
#   $1 = x11     -> Mode 1: Full Electron Desktop (Xtigervnc + openbox + noVNC). Koi battery limiter nahi.
#   $1 = direct  -> Mode 2: High-FPS Native Web Host (code serve-web, port 8080) + battery saver.
set -uo pipefail

MODE="${1:-x11}"
MODE2_PORT=8080
VSCODE_DIR=/opt/vscode-arm64
SERVER_DATA=/root/.vscode-server-data
HEARTBEAT=/tmp/mode2.heartbeat
IDLE_LIMIT=600   # seconds (10 minute)

mkdir -p /tmp /dev/shm /tmp/.X11-unix /root/Workspace /usr/local/bin \
         /root/.config/Code/User "$SERVER_DATA/data/User" "$SERVER_DATA/data/Machine"
chmod 1777 /tmp /dev/shm 2>/dev/null || true

# --- Common fixes -------------------------------------------------------------------------

# sudo shim (kuch extensions/scripts sudo expect karte hain)
if [ ! -f /usr/local/bin/sudo ]; then
    printf '#!/bin/sh\nexec "$@"\n' > /usr/local/bin/sudo
    chmod 755 /usr/local/bin/sudo
fi

# WSL-detection bypass (PRoot mein /proc/version Android kernel ka hota hai)
if [ -f "$VSCODE_DIR/bin/code" ]; then
    sed -i 's|/proc/version|/dev/null|g' "$VSCODE_DIR/bin/code" 2>/dev/null || true
fi

# [Superuser] tag / root warning hatane ke liye UI patch (uid=1000 dikhana). Sirf ek baar.
if [ ! -f "$VSCODE_DIR/.noroot_patched" ] && [ -d "$VSCODE_DIR/resources/app/out" ]; then
    find "$VSCODE_DIR/resources/app/out" -name "preload*.js" \
        -exec sed -i 's/process\.getuid()/1000/g' {} + 2>/dev/null || true
    WB_JS="$VSCODE_DIR/resources/app/out/vs/workbench/workbench.desktop.main.js"
    if [ -f "$WB_JS" ]; then
        sed -i 's/\.getuid?\.()===0/false/g; s/\.getuid()===0/false/g; s/ \[Superuser\]//g; s/\[Superuser\]//g' \
            "$WB_JS" 2>/dev/null || true
    fi
    touch "$VSCODE_DIR/.noroot_patched"
fi

# =============================================================================================
# MODE 1 — Full Electron Desktop (unthrottled)
# =============================================================================================
if [ "$MODE" != "direct" ]; then
    echo "[start-vscode] Mode 1: Xtigervnc :1 + Openbox + Electron VS Code..."
    pkill -9 -f Xtigervnc 2>/dev/null || true
    pkill -9 -f websockify 2>/dev/null || true
    pkill -9 -f openbox 2>/dev/null || true
    rm -rf /tmp/.X1-lock /tmp/.X11-unix/X1
    sleep 1

    Xtigervnc :1 \
        -geometry 1920x1080 \
        -depth 24 \
        -localhost \
        -SecurityTypes None \
        -AlwaysShared &
    sleep 2

    export DISPLAY=:1
    openbox &
    websockify --web=/usr/share/novnc/ 127.0.0.1:6080 127.0.0.1:5901 &
    sleep 1

    echo "VSCODE_DISPLAY_READY"
    exec dbus-run-session -- "$VSCODE_DIR/code" \
        --no-sandbox \
        --disable-gpu \
        --disable-gpu-sandbox \
        --disable-dev-shm-usage \
        --disable-software-rasterizer \
        --password-store=basic \
        --user-data-dir=/root/.config/Code \
        --extensions-dir=/root/.vscode/extensions \
        /root/Workspace
fi

# =============================================================================================
# MODE 2 — code serve-web (battery optimised)
# =============================================================================================
echo "[start-vscode] Mode 2: Official VS Code Native Workbench Host (serve-web :$MODE2_PORT)..."

# 1. Auto-save 0.5s + hot-exit + window restore (User aur Machine dono settings.json mein merge)
python3 - "$SERVER_DATA" << 'PYEOF'
import json, os, sys
base = sys.argv[1]
wanted = {
    "files.autoSave": "afterDelay",
    "files.autoSaveDelay": 500,
    "files.hotExit": "onExitAndWindowClose",
    "window.restoreWindows": "all",
    "workbench.startupEditor": "none",
    "telemetry.telemetryLevel": "off",
    "extensions.autoCheckUpdates": False,
    "extensions.autoUpdate": False,
    "update.mode": "none",
    "files.watcherExclude": {
        "**/.git/objects/**": True,
        "**/.git/subtree-cache/**": True,
        "**/node_modules/*/**": True,
        "**/build/**": True,
        "**/.gradle/**": True,
    },
}
for sub in ("User", "Machine"):
    path = os.path.join(base, "data", sub, "settings.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    data = {}
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                txt = f.read().strip()
            data = json.loads(txt) if txt else {}
        except Exception:
            # User ki file comments (JSONC) wali hai — usko bigaadna nahi hai.
            print("[start-vscode] %s parse nahi hui (JSONC?); touch nahi kiya." % path)
            continue
    changed = False
    for k, v in wanted.items():
        if k == "files.watcherExclude":
            cur = data.get(k) if isinstance(data.get(k), dict) else {}
            merged = dict(v); merged.update(cur)
            if merged != data.get(k):
                data[k] = merged; changed = True
        elif data.get(k) != v:
            data[k] = v; changed = True
    if changed or not os.path.exists(path):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=4)
PYEOF

# 2. Server start (background) + idle hibernation watcher
touch "$HEARTBEAT"
nice -n 10 "$VSCODE_DIR/bin/code" serve-web \
    --host 127.0.0.1 \
    --port "$MODE2_PORT" \
    --without-connection-token \
    --accept-server-license-terms \
    --server-data-dir "$SERVER_DATA" &
SERVER_PID=$!

hibernate() {
    echo "[battery-saver] Server ko gracefully band (SIGTERM) kar raha hoon..."
    kill -TERM "$SERVER_PID" 2>/dev/null || true
    pkill -TERM -f -- "--server-data-dir $SERVER_DATA" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        kill -0 "$SERVER_PID" 2>/dev/null || break
        sleep 1
    done
    exit 0
}
trap hibernate TERM INT

# Port 8080 par koi ESTABLISHED connection? (Android 10+ par /proc/net kabhi blocked hota hai —
# isliye app ka heartbeat file bhi check hota hai.)
has_connection() {
    local f
    for f in /proc/net/tcp /proc/net/tcp6; do
        [ -r "$f" ] || continue
        if awk -v p="$(printf '%04X' "$MODE2_PORT")" \
            'NR>1 && $4=="01" { n=split($2,a,":"); if (toupper(a[n])==p) found=1 } END { exit found ? 0 : 1 }' \
            "$f" 2>/dev/null; then
            return 0
        fi
    done
    return 1
}

heartbeat_fresh() {
    [ -f "$HEARTBEAT" ] || return 1
    local now mt
    now=$(date +%s)
    mt=$(stat -c %Y "$HEARTBEAT" 2>/dev/null || echo 0)
    [ $((now - mt)) -lt 120 ]
}

IDLE=0
while kill -0 "$SERVER_PID" 2>/dev/null; do
    sleep 30 &
    wait $! 2>/dev/null || true
    if has_connection || heartbeat_fresh; then
        IDLE=0
    else
        IDLE=$((IDLE + 30))
    fi
    if [ "$IDLE" -ge "$IDLE_LIMIT" ]; then
        echo "[battery-saver] Mode 2 10 minute se inactive hai. Hibernating to save battery..."
        hibernate
    fi
done
exit 0
