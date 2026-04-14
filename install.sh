#!/usr/bin/env bash
#
# Argus Installer
#
# Downloads argus-agent and argus-cli from GitHub releases,
# installs them to ~/.argus/, and creates the 'argus' command.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/rlaope/argus/master/install.sh | bash
#
# Or with a specific version:
#   curl -fsSL https://raw.githubusercontent.com/rlaope/argus/master/install.sh | bash -s -- v0.3.0
#

set -euo pipefail

REPO="rlaope/argus"
INSTALL_DIR="$HOME/.argus"
BIN_DIR="$INSTALL_DIR/bin"
VERSION="${1:-latest}"

# --- OS/Arch detection ---

detect_platform() {
    local os arch
    os=$(uname -s | tr '[:upper:]' '[:lower:]')
    arch=$(uname -m)

    case "$os" in
        linux*)
            case "$arch" in
                x86_64|amd64) echo "linux-amd64" ;;
                aarch64|arm64) echo "linux-arm64" ;;
                *) echo "" ;;
            esac
            ;;
        darwin*)
            case "$arch" in
                arm64) echo "macos-aarch64" ;;
                x86_64) echo "macos-amd64" ;;
                *) echo "" ;;
            esac
            ;;
        *) echo "" ;;
    esac
}

PLATFORM=$(detect_platform)

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

info()  { echo -e "${CYAN}[INFO]${RESET}  $*"; }
ok()    { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
error() { echo -e "${RED}[ERROR]${RESET} $*" >&2; }

# --- Pre-flight checks ---

if ! command -v java &>/dev/null; then
    error "Java is not installed. Argus CLI requires Java 11+."
    exit 1
fi

JAVA_VER=$(java -version 2>&1 | head -1 | awk -F'"' '{print $2}' | cut -d. -f1)
if [ "$JAVA_VER" -lt 11 ] 2>/dev/null; then
    warn "Java $JAVA_VER detected. Argus CLI requires Java 11+."
fi

if ! command -v curl &>/dev/null; then
    error "curl is required to download Argus."
    exit 1
fi

# --- Resolve version ---

if [ "$VERSION" = "latest" ]; then
    info "Resolving latest release..."
    VERSION=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" \
        | grep '"tag_name"' | head -1 | sed 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')

    if [ -z "$VERSION" ]; then
        warn "Could not resolve latest release. Using 'v1.0.0' as fallback."
        VERSION="v1.0.0"
    fi
fi

# Strip 'v' prefix for JAR filenames
VER_NUM="${VERSION#v}"

echo ""
echo -e "${BOLD}  Argus Installer${RESET}"
echo -e "  Version: ${CYAN}${VERSION}${RESET}"
echo -e "  Install: ${CYAN}${INSTALL_DIR}${RESET}"
echo ""

# --- Download ---

mkdir -p "$INSTALL_DIR" "$BIN_DIR"

DOWNLOAD_BASE="https://github.com/$REPO/releases/download/$VERSION"

info "Downloading argus-agent-${VER_NUM}.jar ..."
curl -fSL "$DOWNLOAD_BASE/argus-agent-${VER_NUM}.jar" -o "$INSTALL_DIR/argus-agent.jar" \
    || { error "Failed to download argus-agent. Check version: $VERSION"; exit 1; }
ok "argus-agent.jar"

info "Downloading argus-cli-${VER_NUM}-all.jar ..."
curl -fSL "$DOWNLOAD_BASE/argus-cli-${VER_NUM}-all.jar" -o "$INSTALL_DIR/argus-cli.jar" \
    || { warn "argus-cli not found in release. CLI may not be available in $VERSION."; }
ok "argus-cli.jar"

# --- Attempt native binary download (faster startup, no JVM required for launch) ---

NATIVE_BIN=""
if [ -n "$PLATFORM" ]; then
    NATIVE_ARTIFACT="argus-${PLATFORM}"
    NATIVE_URL="$DOWNLOAD_BASE/${NATIVE_ARTIFACT}"
    info "Attempting native binary download for ${PLATFORM}..."
    if curl -fSL "$NATIVE_URL" -o "$INSTALL_DIR/argus-native" 2>/dev/null; then
        chmod +x "$INSTALL_DIR/argus-native"
        NATIVE_BIN="$INSTALL_DIR/argus-native"
        ok "Native binary (${PLATFORM})"
    else
        warn "No native binary for ${PLATFORM} — using fat JAR (requires Java 11+)"
    fi
else
    warn "Unknown platform — using fat JAR (requires Java 11+)"
fi

# --- Download async-profiler ---

ASPROF_VERSION="3.0"
ASPROF_DIR="$INSTALL_DIR/lib/async-profiler"

detect_asprof_platform() {
    local os=$(uname -s | tr '[:upper:]' '[:lower:]')
    local arch=$(uname -m)

    case "$os" in
        darwin*) echo "macos"; return ;;
        linux)
            local prefix="linux"
            # Detect musl (Alpine/Docker)
            if [ -f /lib/ld-musl-*.so.1 ] 2>/dev/null || (ldd --version 2>&1 | grep -qi musl); then
                prefix="linux-musl"
            fi
            case "$arch" in
                x86_64|amd64) echo "${prefix}-x64" ;;
                aarch64|arm64) echo "${prefix}-arm64" ;;
                *) echo "" ;;
            esac
            ;;
        *) echo "" ;;
    esac
}

ASPROF_PLATFORM=$(detect_asprof_platform)

if [ -n "$ASPROF_PLATFORM" ]; then
    if [ "$ASPROF_PLATFORM" = "macos" ]; then
        ASPROF_FILE="async-profiler-${ASPROF_VERSION}-${ASPROF_PLATFORM}.zip"
    else
        ASPROF_FILE="async-profiler-${ASPROF_VERSION}-${ASPROF_PLATFORM}.tar.gz"
    fi
    ASPROF_URL="https://github.com/async-profiler/async-profiler/releases/download/v${ASPROF_VERSION}/${ASPROF_FILE}"

    info "Downloading async-profiler ${ASPROF_VERSION} for ${ASPROF_PLATFORM}..."
    ASPROF_TMP="$INSTALL_DIR/lib/${ASPROF_FILE}"
    mkdir -p "$INSTALL_DIR/lib"
    curl -fSL "$ASPROF_URL" -o "$ASPROF_TMP" \
        || { warn "async-profiler download failed. 'argus profile' will download on first use."; ASPROF_TMP=""; }

    if [ -n "$ASPROF_TMP" ] && [ -f "$ASPROF_TMP" ]; then
        rm -rf "$ASPROF_DIR" "$INSTALL_DIR/lib/extract-tmp"
        mkdir -p "$INSTALL_DIR/lib/extract-tmp"
        if [ "$ASPROF_PLATFORM" = "macos" ]; then
            unzip -o -q "$ASPROF_TMP" -d "$INSTALL_DIR/lib/extract-tmp"
        else
            tar xzf "$ASPROF_TMP" -C "$INSTALL_DIR/lib/extract-tmp"
        fi
        # Move extracted dir (e.g., async-profiler-3.0-macos/) to async-profiler/
        mv "$INSTALL_DIR/lib/extract-tmp"/async-profiler-* "$ASPROF_DIR" 2>/dev/null
        rm -rf "$INSTALL_DIR/lib/extract-tmp" "$ASPROF_TMP"
        chmod +x "$ASPROF_DIR/bin/asprof" 2>/dev/null
        ok "async-profiler ${ASPROF_VERSION} (${ASPROF_PLATFORM})"
    fi
else
    warn "async-profiler not available for this platform. 'argus profile' will not work."
fi

# --- Create wrapper scripts ---

# argus - CLI diagnostic tool
# Use native binary if available, otherwise fall back to fat JAR via JVM
if [ -x "$INSTALL_DIR/argus-native" ]; then
    cat > "$BIN_DIR/argus" << 'WRAPPER'
#!/usr/bin/env bash
exec "$HOME/.argus/argus-native" "$@"
WRAPPER
else
    cat > "$BIN_DIR/argus" << 'WRAPPER'
#!/usr/bin/env bash
find_java() {
    local candidates=()
    [ -n "$ARGUS_JAVA_HOME" ] && [ -x "$ARGUS_JAVA_HOME/bin/java" ] && candidates+=("$ARGUS_JAVA_HOME/bin/java")
    [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ] && candidates+=("$JAVA_HOME/bin/java")
    if command -v /usr/libexec/java_home &>/dev/null; then
        for v in 21 17 11; do
            local jh; jh=$(/usr/libexec/java_home -v $v 2>/dev/null)
            [ -n "$jh" ] && [ -x "$jh/bin/java" ] && candidates+=("$jh/bin/java")
        done
    fi
    if [ -d "$HOME/.jenv/versions" ]; then
        for d in "$HOME/.jenv/versions"/*/bin/java; do [ -x "$d" ] && candidates+=("$d"); done
    fi
    for p in /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java; do [ -x "$p" ] && candidates+=("$p"); done
    for p in /usr/lib/jvm/java-*/bin/java; do [ -x "$p" ] && candidates+=("$p"); done
    command -v java &>/dev/null && candidates+=("$(command -v java)")
    local best="" best_ver=0
    for c in "${candidates[@]}"; do
        local ver; ver=$("$c" -version 2>&1 | head -1 | awk -F'"' '{print $2}' | cut -d. -f1)
        if [ "$ver" -ge 21 ] 2>/dev/null && [ "$ver" -gt "$best_ver" ] 2>/dev/null; then
            best="$c"; best_ver="$ver"
        fi
    done
    [ -n "$best" ] && echo "$best" && return 0
    return 1
}
ARGUS_JAVA=$(find_java)
if [ $? -ne 0 ] || [ -z "$ARGUS_JAVA" ]; then
    echo "Error: Java 11+ is required but not found." >&2
    echo "Set ARGUS_JAVA_HOME or JAVA_HOME." >&2
    exit 1
fi
JAVA_VER=$("$ARGUS_JAVA" -version 2>&1 | head -1 | awk -F'"' '{print $2}' | cut -d. -f1)
PREVIEW=""
[ "$JAVA_VER" -ge 21 ] 2>/dev/null && PREVIEW="--enable-preview"
exec "$ARGUS_JAVA" $PREVIEW -jar "$HOME/.argus/argus-cli.jar" "$@"
WRAPPER
fi
chmod +x "$BIN_DIR/argus"

# argus-agent - prints the agent JAR path (for -javaagent)
cat > "$BIN_DIR/argus-agent" << 'WRAPPER'
#!/usr/bin/env bash
if [ "$1" = "--path" ] || [ "$1" = "-p" ]; then
    echo "$HOME/.argus/argus-agent.jar"
else
    echo "Argus Agent JAR: $HOME/.argus/argus-agent.jar"
    echo ""
    echo "Usage:"
    echo "  java -javaagent:\$(argus-agent --path) -jar your-app.jar"
    echo ""
    echo "  Or directly:"
    echo "  java -javaagent:$HOME/.argus/argus-agent.jar -jar your-app.jar"
fi
WRAPPER
chmod +x "$BIN_DIR/argus-agent"

# --- Add to PATH ---

SHELL_NAME=$(basename "$SHELL" 2>/dev/null || echo "bash")
PROFILE=""

case "$SHELL_NAME" in
    zsh)  PROFILE="$HOME/.zshrc" ;;
    bash)
        if [ -f "$HOME/.bash_profile" ]; then
            PROFILE="$HOME/.bash_profile"
        else
            PROFILE="$HOME/.bashrc"
        fi
        ;;
    fish) PROFILE="$HOME/.config/fish/config.fish" ;;
    *)    PROFILE="$HOME/.profile" ;;
esac

PATH_LINE="export PATH=\"\$HOME/.argus/bin:\$PATH\""

if [ "$SHELL_NAME" = "fish" ]; then
    PATH_LINE="set -gx PATH \$HOME/.argus/bin \$PATH"
fi

if [ -n "$PROFILE" ] && ! grep -q '.argus/bin' "$PROFILE" 2>/dev/null; then
    echo "" >> "$PROFILE"
    echo "# Argus JVM Monitor" >> "$PROFILE"
    echo "$PATH_LINE" >> "$PROFILE"
    ok "Added to PATH in $PROFILE"
else
    ok "PATH already configured in $PROFILE"
fi

# --- Install shell completions ---

COMPLETIONS_DIR="$INSTALL_DIR/completions"
mkdir -p "$COMPLETIONS_DIR"

# Download completion scripts from repo
COMP_BASE="https://raw.githubusercontent.com/rlaope/argus/master/completions"
curl -fsSL "$COMP_BASE/argus.bash" -o "$COMPLETIONS_DIR/argus.bash" 2>/dev/null
curl -fsSL "$COMP_BASE/argus.zsh" -o "$COMPLETIONS_DIR/argus.zsh" 2>/dev/null
curl -fsSL "$COMP_BASE/argus.fish" -o "$COMPLETIONS_DIR/argus.fish" 2>/dev/null

# Source completion in shell profile
case "$SHELL_NAME" in
    zsh)
        if ! grep -q 'argus.zsh' "$PROFILE" 2>/dev/null; then
            echo "source \"\$HOME/.argus/completions/argus.zsh\"" >> "$PROFILE"
        fi
        ;;
    bash)
        if ! grep -q 'argus.bash' "$PROFILE" 2>/dev/null; then
            echo "source \"\$HOME/.argus/completions/argus.bash\"" >> "$PROFILE"
        fi
        ;;
    fish)
        FISH_COMP_DIR="$HOME/.config/fish/completions"
        mkdir -p "$FISH_COMP_DIR"
        cp "$COMPLETIONS_DIR/argus.fish" "$FISH_COMP_DIR/argus.fish" 2>/dev/null
        ;;
esac
ok "Shell completions installed"

# --- Done ---

echo ""
echo -e "${GREEN}${BOLD}  Argus installed successfully!${RESET}"
echo ""
echo -e "  ${BOLD}Quick Start:${RESET}"
echo ""
echo -e "  1. Restart your terminal or run:"
echo -e "     ${CYAN}source $PROFILE${RESET}"
echo ""
echo -e "  2. Diagnose any running JVM (no agent required):"
echo -e "     ${CYAN}argus ps${RESET}                    # List JVM processes"
echo -e "     ${CYAN}argus histo <pid>${RESET}            # Heap object histogram"
echo -e "     ${CYAN}argus threads <pid>${RESET}          # Thread dump summary"
echo -e "     ${CYAN}argus gc <pid>${RESET}               # GC statistics"
echo -e "     ${CYAN}argus gcutil <pid>${RESET}           # GC generation utilization"
echo -e "     ${CYAN}argus heap <pid>${RESET}             # Heap memory usage"
echo -e "     ${CYAN}argus sysprops <pid>${RESET}         # JVM system properties"
echo -e "     ${CYAN}argus vmflag <pid>${RESET}           # VM flags (view/set)"
echo -e "     ${CYAN}argus nmt <pid>${RESET}              # Native memory tracking"
echo -e "     ${CYAN}argus classloader <pid>${RESET}      # Class loader hierarchy"
echo -e "     ${CYAN}argus jfr <pid> start${RESET}        # Flight Recorder control"
echo -e "     ${CYAN}argus info <pid>${RESET}             # JVM information"
echo ""
echo -e "  3. For real-time monitoring, attach the agent:"
echo -e "     ${CYAN}java -javaagent:\$(argus-agent --path) -jar your-app.jar${RESET}"
echo -e "     ${CYAN}argus top${RESET}                    # Real-time dashboard"
echo -e "     ${CYAN}http://localhost:9202/${RESET}       # Web dashboard"
echo ""
echo -e "  4. First-time setup (language, defaults):"
echo -e "     ${CYAN}argus init${RESET}"
echo ""
echo -e "  ${BOLD}Uninstall:${RESET}"
echo -e "     ${CYAN}rm -rf ~/.argus${RESET}  and remove the PATH line from $PROFILE"
echo ""
