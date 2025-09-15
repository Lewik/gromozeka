#!/bin/bash
# Gromozeka AppImage Build Script
# Automates the full process from Compose Desktop build to final AppImage

set -e  # Exit on any error

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
APPIMAGE_RESOURCES="$PROJECT_ROOT/appimage-resources"
BUILD_DIR="$PROJECT_ROOT/build/appimage"
COMPOSE_BUILD_DIR="$PROJECT_ROOT/bot/build/compose/binaries/main"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log() {
    echo -e "${GREEN}[$(date '+%H:%M:%S')]${NC} $1" >&2
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1" >&2
}

error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

info() {
    echo -e "${BLUE}[INFO]${NC} $1" >&2
}

check_linux() {
    if [[ "$OSTYPE" != "linux-gnu"* ]]; then
        error "AppImage can only be built on Linux systems."
        error "Current OS: $OSTYPE"
        echo
        info "To build AppImage:"
        info "1. Use a Linux VM or container"
        info "2. Use GitHub Actions with ubuntu-latest"
        info "3. Use a Linux development server"
        exit 1
    fi
}

check_dependencies() {
    log "Checking dependencies..."
    
    local missing_deps=()
    
    # Check for required tools
    if ! command -v java >/dev/null 2>&1; then
        missing_deps+=("java")
    fi
    
    if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
        missing_deps+=("curl or wget")
    fi
    
    if [ ${#missing_deps[@]} -ne 0 ]; then
        error "Missing required dependencies: ${missing_deps[*]}"
        echo
        info "Install missing dependencies:"
        info "Ubuntu/Debian: sudo apt update && sudo apt install default-jdk curl"
        info "Fedora/RHEL:   sudo dnf install java-latest-openjdk curl"
        info "Arch:          sudo pacman -S jdk-openjdk curl"
        exit 1
    fi
    
    log "All dependencies satisfied"
}

download_appimagetool() {
    log "Setting up appimagetool..."
    
    local appimagetool_path="$BUILD_DIR/appimagetool-x86_64.AppImage"
    
    if [ -f "$appimagetool_path" ] && [ -x "$appimagetool_path" ]; then
        log "appimagetool already available"
        echo "$appimagetool_path"
        return 0
    fi
    
    log "Downloading appimagetool..."
    mkdir -p "$BUILD_DIR"
    
    local download_url="https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"
    
    if command -v curl >/dev/null 2>&1; then
        curl -L -o "$appimagetool_path" "$download_url"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$appimagetool_path" "$download_url"
    else
        error "Neither curl nor wget available for downloading appimagetool"
        exit 1
    fi
    
    chmod +x "$appimagetool_path"
    log "appimagetool downloaded and ready"
    echo "$appimagetool_path"
}

enable_appimage_target() {
    log "Enabling AppImage target in build.gradle.kts..."
    
    local gradle_file="$PROJECT_ROOT/bot/build.gradle.kts"
    
    # Check if AppImage is already enabled
    if grep -q "^[[:space:]]*TargetFormat\.AppImage" "$gradle_file"; then
        warn "AppImage target already enabled"
        return 0
    fi
    
    # Enable AppImage target by uncommenting it
    if grep -q "^[[:space:]]*//[[:space:]]*TargetFormat\.AppImage" "$gradle_file"; then
        log "Uncommenting AppImage target format..."
        sed -i 's|^[[:space:]]*//[[:space:]]*TargetFormat\.AppImage.*|                TargetFormat.AppImage  // Linux AppImage (requires appimagetool)|' "$gradle_file"
    else
        # Add AppImage target if not present
        log "Adding AppImage target format..."
        sed -i '/TargetFormat\.Dmg,/a\                TargetFormat.AppImage,  // Linux AppImage (requires appimagetool)' "$gradle_file"
    fi
    
    log "AppImage target enabled in Gradle build"
}

build_compose_app() {
    log "Building Compose Desktop application with AppImage target..."
    
    cd "$PROJECT_ROOT"
    
    # Clean previous builds to ensure fresh start
    log "Cleaning previous builds..."
    ./gradlew clean -q
    
    # Build with AppImage target
    log "Running Gradle build with AppImage support..."
    if ! ./gradlew :bot:packageDistributionForCurrentOS -q; then
        error "Gradle build failed"
        exit 1
    fi
    
    log "Compose Desktop build completed successfully"
}

find_app_directory() {
    log "Locating app directory from Compose build..."
    
    # Look for app directory in compose build output
    local app_dir_candidates=(
        "$COMPOSE_BUILD_DIR/app"
        "$COMPOSE_BUILD_DIR/Gromozeka/app"
        "$PROJECT_ROOT/bot/build/compose/binaries/main-release/app"
    )
    
    for candidate in "${app_dir_candidates[@]}"; do
        if [ -d "$candidate" ]; then
            log "Found app directory: $candidate"
            echo "$candidate"
            return 0
        fi
    done
    
    error "App directory not found. Compose build may have failed."
    error "Searched locations:"
    for candidate in "${app_dir_candidates[@]}"; do
        error "  - $candidate"
    done
    exit 1
}

prepare_appdir() {
    local compose_app_dir="$1"
    local appdir="$BUILD_DIR/Gromozeka.AppDir"
    
    log "Preparing AppDir structure..."
    
    # Remove existing AppDir if present
    if [ -d "$appdir" ]; then
        rm -rf "$appdir"
    fi
    
    # Copy compose app directory as base
    log "Copying Compose app structure..."
    cp -r "$compose_app_dir" "$appdir"
    
    # Copy AppRun launcher
    log "Installing AppRun launcher..."
    cp "$APPIMAGE_RESOURCES/AppRun" "$appdir/"
    chmod +x "$appdir/AppRun"
    
    # Copy desktop file
    log "Installing desktop integration..."
    cp "$APPIMAGE_RESOURCES/gromozeka.desktop" "$appdir/"
    
    # Copy icon (use the 256x256 PNG as main icon)
    log "Installing application icon..."
    local icon_source="$PROJECT_ROOT/bot/src/jvmMain/resources/logos/logo-256x256.png"
    if [ -f "$icon_source" ]; then
        cp "$icon_source" "$appdir/gromozeka.png"
    else
        warn "Icon not found at $icon_source"
    fi
    
    # Verify AppDir structure
    log "Verifying AppDir structure..."
    local required_files=("AppRun" "gromozeka.desktop")
    
    for file in "${required_files[@]}"; do
        if [ ! -f "$appdir/$file" ]; then
            error "Required file missing: $file"
            exit 1
        fi
    done
    
    # List AppDir contents for verification
    info "AppDir structure:"
    ls -la "$appdir" | head -10 >&2
    
    echo "$appdir"
}

build_appimage() {
    local appdir="$1"
    local appimagetool="$2"
    
    log "Building AppImage from AppDir..."
    
    local version=$(grep 'val projectVersion = ' "$PROJECT_ROOT/build.gradle.kts" | head -1 | sed 's/.*"\(.*\)".*/\1/' || echo "unknown")
    local output_name="Gromozeka-$version-x86_64.AppImage"
    local output_path="$BUILD_DIR/$output_name"
    
    # Remove existing AppImage if present
    if [ -f "$output_path" ]; then
        rm -f "$output_path"
    fi
    
    # Build AppImage
    cd "$BUILD_DIR"
    log "Running appimagetool..."
    
    # Set VERSION environment variable for appimagetool
    export VERSION="$version"
    
    if ! "$appimagetool" "$appdir" "$output_path"; then
        error "appimagetool failed to create AppImage"
        exit 1
    fi
    
    if [ ! -f "$output_path" ]; then
        error "AppImage was not created at expected location: $output_path"
        exit 1
    fi
    
    # Make AppImage executable
    chmod +x "$output_path"
    
    # Get file size for reporting
    local file_size=$(du -h "$output_path" | cut -f1)
    
    log "✅ AppImage build completed successfully!"
    echo
    info "📦 AppImage: $output_path"
    info "📏 Size: $file_size"
    info "🔍 Version: $version"
    echo
    info "To test the AppImage:"
    info "  $output_path"
    echo
    info "To distribute the AppImage:"
    info "  1. Test on different Linux distributions"
    info "  2. Upload to releases or file hosting"
    info "  3. Consider submitting to AppImageHub"
    
    return 0
}

cleanup() {
    # Disable AppImage target to restore original state
    if grep -q "^[[:space:]]*TargetFormat\.AppImage" "$PROJECT_ROOT/bot/build.gradle.kts"; then
        log "Disabling AppImage target to restore original state..."
        sed -i 's|^[[:space:]]*TargetFormat\.AppImage.*|//                TargetFormat.AppImage  // Linux AppImage (requires appimagetool)|' "$PROJECT_ROOT/bot/build.gradle.kts"
    fi
}

# Main build process
main() {
    log "🚀 Starting Gromozeka AppImage build process..."
    echo
    
    # Environment checks
    check_linux
    check_dependencies
    
    # Setup trap for cleanup on exit
    trap cleanup EXIT
    
    # Download tools
    local appimagetool
    appimagetool=$(download_appimagetool)
    
    # Prepare Gradle build
    enable_appimage_target
    
    # Build application
    build_compose_app
    
    # Find the built app directory
    local compose_app_dir
    compose_app_dir=$(find_app_directory)
    
    # Prepare AppDir
    local appdir
    appdir=$(prepare_appdir "$compose_app_dir")
    
    # Build final AppImage
    build_appimage "$appdir" "$appimagetool"
    
    log "🎉 AppImage build process completed successfully!"
}

# Handle script arguments
case "${1:-}" in
    --help|-h)
        echo "Gromozeka AppImage Build Script"
        echo
        echo "Usage: $0 [options]"
        echo
        echo "Options:"
        echo "  --help, -h    Show this help message"
        echo "  --clean       Clean build artifacts and exit"
        echo
        echo "This script must be run on a Linux system."
        echo "It will:"
        echo "  1. Enable AppImage target in Gradle"
        echo "  2. Build the Compose Desktop application"
        echo "  3. Download appimagetool if needed"
        echo "  4. Create AppDir structure"
        echo "  5. Build final AppImage using appimagetool"
        echo
        exit 0
        ;;
    --clean)
        log "Cleaning build artifacts..."
        rm -rf "$BUILD_DIR"
        log "Build artifacts cleaned"
        exit 0
        ;;
    "")
        # No arguments - run main build process
        main
        ;;
    *)
        error "Unknown option: $1"
        echo "Use --help for usage information"
        exit 1
        ;;
esac