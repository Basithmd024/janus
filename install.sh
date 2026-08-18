#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════════════
#  JANUS ECOSYSTEM BRIDGE — ONE-LINE COMMAND LINE INSTALLER & TOOLCHAIN
#  https://github.com/Basithmd123/Mgit
# ════════════════════════════════════════════════════════════════════════════

set -e

# ANSI Color Palette
CYAN='\033[0;36m'
PURPLE='\033[0;35m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BOLD='\033[1m'
NC='\033[0m' # No Color

clear

echo -e "${PURPLE}${BOLD}"
cat << "BANNER"
     ██╗ █████╗ ███╗   ██╗██╗   ██╗███████╗
     ██║██╔══██╗████╗  ██║██║   ██║██╔════╝
     ██║███████║██╔██╗ ██║██║   ██║███████╗
██   ██║██╔══██║██║╚██╗██║██║   ██║╚════██║
╚█████╔╝██║  ██║██║ ╚████║╚██████╔╝███████║
 ╚════╝ ╚═╝  ╚═╝╚═╝  ╚═══╝ ╚═════╝ ╚══════╝
  Seamless macOS ⟷ Android Ecosystem Bridge
BANNER
echo -e "${NC}"

echo -e "${CYAN}⚡ Initializing Janus Bridge installer environment...${NC}\n"

# Architecture Detection
OS="$(uname -s)"
ARCH="$(uname -m)"

echo -e "  ${BOLD}Platform:${NC} ${GREEN}${OS}${NC} (${ARCH})"
echo -e "  ${BOLD}Protocol:${NC} ${PURPLE}TLS 1.3 WebSocket P2P + mDNS Zero-Config${NC}\n"

# 1. Dependency Checks
echo -e "${CYAN}🔍 Checking System Toolchains:${NC}"

check_cmd() {
    if command -v "$1" >/dev/null 2>&1; then
        echo -e "  [${GREEN}✓${NC}] $1: $(which $1)"
        return 0
    else
        echo -e "  [${YELLOW}!${NC}] $1: ${RED}Not found${NC} ($2)"
        return 1
    fi
}

check_cmd "node" "Required for Svelte 5 frontend" || true
check_cmd "npm" "Required for package management" || true
check_cmd "cargo" "Required for Rust desktop engine" || true
check_cmd "rustc" "Required for Tauri compilation" || true
check_cmd "adb" "Required for wireless Android deployment" || true

echo ""

# 2. Parse arguments
RUN_DESKTOP=true
INSTALL_ANDROID=false

while [[ "$#" -gt 0 ]]; do
    case $1 in
        --android) INSTALL_ANDROID=true ;;
        --all) INSTALL_ANDROID=true; RUN_DESKTOP=true ;;
        --skip-desktop) RUN_DESKTOP=false ;;
        -h|--help)
            echo "Usage: ./install.sh [options]"
            echo "Options:"
            echo "  --all           Build desktop app and install APK to Android"
            echo "  --android       Build and install Android APK via ADB"
            echo "  --skip-desktop  Skip launching Tauri desktop dev server"
            exit 0
            ;;
        *) echo "Unknown parameter: $1"; exit 1 ;;
    esac
    shift
done

# 3. Install Node dependencies
echo -e "${CYAN}📦 Installing Node dependencies...${NC}"
npm install --silent

# 4. Android Build & Deployment
if [ "$INSTALL_ANDROID" = true ]; then
    echo -e "\n${CYAN}🤖 Building Android APK with CameraX & ZXing...${NC}"
    cd android-app
    ./gradlew assembleDebug --quiet
    cd ..
    
    if command -v adb >/dev/null 2>&1; then
        DEVICES=$(adb devices | grep -v "List" | grep "device" || true)
        if [ -n "$DEVICES" ]; then
            echo -e "${GREEN}📱 Found connected Android device. Installing APK...${NC}"
            adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk
            echo -e "${GREEN}✓ Launching Janus Bridge on phone...${NC}"
            adb shell am start -n com.janus.app/.MainActivity
        else
            echo -e "${YELLOW}ℹ️ No Android device found via ADB. APK is ready at: android-app/app/build/outputs/apk/debug/app-debug.apk${NC}"
        fi
    fi
fi

# 5. Launch Desktop App
if [ "$RUN_DESKTOP" = true ]; then
    echo -e "\n${GREEN}${BOLD}🚀 Launching Janus Desktop Command Center...${NC}"
    npm run tauri dev
fi
