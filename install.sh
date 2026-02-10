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
    error "Java is not installed. Argus requires Java 21+."
    exit 1
fi

JAVA_VER=$(java -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\).*/\1/')
if [ "$JAVA_VER" -lt 21 ] 2>/dev/null; then
    warn "Java $JAVA_VER detected. Argus requires Java 21+."
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
        warn "Could not resolve latest release. Using 'v0.3.0' as fallback."
        VERSION="v0.3.0"
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

# --- Create wrapper scripts ---

# argus - CLI monitor (argus top)
cat > "$BIN_DIR/argus" << 'WRAPPER'
#!/usr/bin/env bash
java -jar "$HOME/.argus/argus-cli.jar" "$@"
WRAPPER
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

# --- Done ---

echo ""
echo -e "${GREEN}${BOLD}  Argus installed successfully!${RESET}"
echo ""
echo -e "  ${BOLD}Quick Start:${RESET}"
echo ""
echo -e "  1. Restart your terminal or run:"
echo -e "     ${CYAN}source $PROFILE${RESET}"
echo ""
echo -e "  2. Attach to your Java app:"
echo -e "     ${CYAN}java -javaagent:\$(argus-agent --path) -jar your-app.jar${RESET}"
echo ""
echo -e "  3. Open the dashboard:"
echo -e "     ${CYAN}http://localhost:9202/${RESET}"
echo ""
echo -e "  4. Or use the CLI monitor:"
echo -e "     ${CYAN}argus${RESET}"
echo -e "     ${CYAN}argus --port 9202 --interval 2${RESET}"
echo ""
echo -e "  ${BOLD}Uninstall:${RESET}"
echo -e "     ${CYAN}rm -rf ~/.argus${RESET}  and remove the PATH line from $PROFILE"
echo ""
