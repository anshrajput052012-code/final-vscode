#!/bin/bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

echo "[init-rootfs] Updating Debian ARM64 packages and installing X11 + VS Code dependencies..."
apt-get update -y
apt-get install -y --no-install-recommends \
    ca-certificates curl wget gpg \
    libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 \
    libgtk-3-0 libgbm1 libasound2 libxkbfile1 libsecret-1-0 \
    libx11-xcb1 libxcb-dri3-0 libxcomposite1 libxdamage1 libxfixes3 \
    libxrandr2 libxtst6 xdg-utils dbus-x11 x11-xserver-utils \
    tigervnc-standalone-server openbox novnc python3-websockify \
    fonts-noto-core fonts-firacode \
    build-essential gdb git python3 python3-pip python3-venv \
    nodejs npm

ln -sf /opt/vscode-arm64/bin/code /usr/local/bin/code

mkdir -p /root/.vscode /root/.config/Code/User

cat > /root/.vscode/argv.json << 'EOF'
{
    "enable-crash-reporter": false,
    "password-store": "basic"
}
EOF

cat > /root/.config/Code/User/settings.json << 'EOF'
{
    "window.titleBarStyle": "custom",
    "terminal.integrated.defaultProfile.linux": "bash",
    "terminal.integrated.gpuAcceleration": "off",
    "security.workspace.trust.enabled": false,
    "files.watcherExclude": {
        "**/.git/objects/**": true,
        "**/node_modules/*/**": true
    }
}
EOF

echo "[init-rootfs] Embedded Debian ARM64 & Genuine VS Code setup complete."
