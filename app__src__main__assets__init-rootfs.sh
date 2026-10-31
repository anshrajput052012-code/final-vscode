#!/bin/bash
# Pocket VS Code — rootfs first-time setup (PRoot ke andar chalta hai).
# NOTE: apt-get ko internet chahiye (sirf pehli baar). Marker file ke baad dobara nahi chalta.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

MARKER=/root/.pocket-init-done
if [ -f "$MARKER" ]; then
    echo "[init-rootfs] Already configured."
    exit 0
fi

echo "[init-rootfs] STEP 1/3 apt ko PRoot ke liye configure kar raha hoon..."
mkdir -p /etc/apt/apt.conf.d /tmp /root/.vscode /root/.config/Code/User /root/Workspace
chmod 1777 /tmp || true
cat > /etc/apt/apt.conf.d/01-pocket-proot << 'APTEOF'
APT::Sandbox::User "root";
APT::Install-Recommends "false";
Dpkg::Options { "--force-confold"; "--force-confdef"; };
APTEOF

echo "[init-rootfs] STEP 2/3 Debian ARM64 packages (X11 + VS Code dependencies + dev tools) install ho rahe hain..."
apt-get update -y
apt-get install -y --no-install-recommends \
    ca-certificates curl wget gpg procps file \
    libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 \
    libgtk-3-0 libgbm1 libasound2 libxkbfile1 libsecret-1-0 \
    libx11-xcb1 libxcb-dri3-0 libxcomposite1 libxdamage1 libxfixes3 \
    libxrandr2 libxtst6 xdg-utils dbus-x11 x11-xserver-utils \
    tigervnc-standalone-server openbox novnc python3-websockify \
    fonts-noto-core fonts-firacode \
    build-essential gdb git python3 python3-pip python3-venv \
    nodejs npm

echo "[init-rootfs] STEP 3/3 VS Code config likh raha hoon..."
ln -sf /opt/vscode-arm64/bin/code /usr/local/bin/code

cat > /root/.vscode/argv.json << 'JSONEOF'
{
    "enable-crash-reporter": false,
    "password-store": "basic"
}
JSONEOF

# Mode 1 (Full Electron Desktop) ki settings. Auto-save yahan nahi lagaya (Mode 2 ke liye alag hai).
if [ ! -f /root/.config/Code/User/settings.json ]; then
cat > /root/.config/Code/User/settings.json << 'JSONEOF'
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
JSONEOF
fi

touch "$MARKER"
echo "[init-rootfs] Embedded Debian ARM64 & Genuine VS Code setup complete."
