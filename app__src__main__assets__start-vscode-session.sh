#!/bin/bash
set -euo pipefail

MODE="${1:-x11}"
mkdir -p /tmp/.X11-unix /root/Workspace

if [ "$MODE" = "x11" ]; then
    echo "[start-vscode] Starting Embedded X11 Server (Xtigervnc :1) & Openbox Window Manager..."
    pkill -f Xtigervnc || true
    pkill -f websockify || true

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
    exec dbus-run-session -- /opt/vscode-arm64/bin/code \
        --no-sandbox \
        --disable-gpu \
        --disable-gpu-sandbox \
        --disable-dev-shm-usage \
        --password-store="basic" \
        --user-data-dir="/root/.config/Code" \
        --extensions-dir="/root/.vscode/extensions" \
        --wait \
        "/root/Workspace"
else
    echo "[start-vscode] Starting Official VS Code Native Workbench Host (code serve-web)..."
    pkill -f "code serve-web" || true
    (sleep 3 && echo "VSCODE_DISPLAY_READY") &
    exec /opt/vscode-arm64/bin/code serve-web \
        --host 127.0.0.1 \
        --port 8000 \
        --without-connection-token \
        --accept-server-license-terms \
        --user-data-dir="/root/.config/Code" \
        --extensions-dir="/root/.vscode/extensions"
fi
