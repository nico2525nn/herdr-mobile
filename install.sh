#!/bin/sh
# Install the Herdr Mobile bridge daemon from GitHub releases.
#
#   curl -fsSL https://raw.githubusercontent.com/nico2525nn/herdr-mobile/main/install.sh | sh
#
# Environment:
#   HERDR_MOBILE_VERSION    release tag (default: latest)
#   HERDR_MOBILE_PREFIX     install prefix (default: ~/.local)
#   HERDR_MOBILE_NO_SERVICE 1 = install binary only; do not touch systemd
#   HERDR_MOBILE_TOKEN      optional bearer token written into a new unit
#
# The daemon listens on loopback only. Use it behind the app's SSH tunnel (or a
# tailnet bind with --allow-remote-bind --token; see daemon/README.md).
set -eu

REPO="nico2525nn/herdr-mobile"
# Mirrors/testing can override; normal installs use GitHub releases.
BASE="${HERDR_MOBILE_BASE_URL:-https://github.com/$REPO}"
VERSION="${HERDR_MOBILE_VERSION:-}"
PREFIX="${HERDR_MOBILE_PREFIX:-$HOME/.local}"
NO_SERVICE="${HERDR_MOBILE_NO_SERVICE:-0}"
TOKEN="${HERDR_MOBILE_TOKEN:-}"

say()  { printf '== %s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }

case "${1:-}" in
    -h|--help)
        sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
        exit 0
        ;;
esac

command -v curl >/dev/null 2>&1 || die "curl is required"
command -v tar >/dev/null 2>&1 || die "tar is required"

if [ -z "$VERSION" ]; then
    say "resolving latest release"
    VERSION="$(curl -fsSL --max-time 20 "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null |
        sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1 || true)"
    [ -n "$VERSION" ] || die "could not resolve the latest release; set HERDR_MOBILE_VERSION=vX.Y.Z"
fi
case "$VERSION" in v*) ;; *) VERSION="v$VERSION" ;; esac

OS="$(uname -s 2>/dev/null || echo unknown)"
ARCH="$(uname -m 2>/dev/null || echo unknown)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/herdr-mobile-install.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

BIN="$PREFIX/bin/herdr-mobile-daemon"
ASSET=""
case "$OS:$ARCH" in
    Linux:x86_64|Linux:amd64) ASSET="herdr-mobile-daemon-linux-x86_64.tar.gz" ;;
    Linux:aarch64|Linux:arm64) ASSET="herdr-mobile-daemon-linux-aarch64.tar.gz" ;;
    Darwin:x86_64|Darwin:arm64) ASSET="" ;; # source build below
    *) die "unsupported platform $OS/$ARCH; build daemon/ with cargo instead" ;;
esac

install_from_archive() {
    url="$1"
    say "downloading $url"
    curl -fL --retry 2 --connect-timeout 10 --max-time 120 -o "$TMP/daemon.tar.gz" "$url" ||
        return 1
    tar -xzf "$TMP/daemon.tar.gz" -C "$TMP" herdr-mobile-daemon 2>/dev/null ||
        tar -xzf "$TMP/daemon.tar.gz" -C "$TMP"
    [ -x "$TMP/herdr-mobile-daemon" ] || die "archive did not contain herdr-mobile-daemon"
    install -d "$PREFIX/bin"
    install -m 0755 "$TMP/herdr-mobile-daemon" "$BIN"
}

installed=0
if [ -n "$ASSET" ]; then
    if install_from_archive "$BASE/releases/download/$VERSION/$ASSET"; then
        installed=1
    else
        warn "release asset $ASSET unavailable; trying a source build"
    fi
fi

if [ "$installed" -eq 0 ]; then
    command -v cargo >/dev/null 2>&1 ||
        die "no prebuilt daemon for $OS/$ARCH and cargo is not installed"
    command -v git >/dev/null 2>&1 || die "git is required for a source build"
    say "building daemon from source ($VERSION)"
    curl -fL --retry 2 -o "$TMP/source.tar.gz" \
        "$BASE/archive/refs/tags/$VERSION.tar.gz" ||
        die "cannot download source for $VERSION"
    tar -xzf "$TMP/source.tar.gz" -C "$TMP"
    src="$(find "$TMP" -maxdepth 1 -type d -name 'herdr-mobile-*' | head -n1)"
    [ -n "$src" ] || die "source archive layout is unexpected"
    ( cd "$src/daemon" && cargo build --release )
    install -d "$PREFIX/bin"
    install -m 0755 "$src/daemon/target/release/herdr-mobile-daemon" "$BIN"
fi

say "installed $BIN"
"$BIN" --help >/dev/null 2>&1 || die "installed binary does not run"

if ! command -v herdr >/dev/null 2>&1; then
    warn "herdr is not on PATH; install it from https://herdr.dev before starting the daemon"
fi

if [ "$NO_SERVICE" != "1" ] && command -v systemctl >/dev/null 2>&1; then
    UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
    UNIT="$UNIT_DIR/herdr-mobile-daemon.service"
    mkdir -p "$UNIT_DIR"
    if [ ! -f "$UNIT" ]; then
        say "writing $UNIT"
        {
            printf '%s\n' '[Unit]'
            printf '%s\n' 'Description=Herdr Mobile bridge daemon'
            printf '%s\n' 'After=network-online.target'
            printf '%s\n' 'Wants=network-online.target'
            printf '%s\n' ''
            printf '%s\n' '[Service]'
            printf 'ExecStart=%s --bind 127.0.0.1:8765\n' "$BIN"
            if [ -n "$TOKEN" ]; then
                printf 'Environment=HERDR_MOBILE_TOKEN=%s\n' "$TOKEN"
            fi
            printf '%s\n' 'Restart=always'
            printf '%s\n' 'RestartSec=2'
            printf '%s\n' ''
            printf '%s\n' '[Install]'
            printf '%s\n' 'WantedBy=default.target'
        } > "$UNIT"
    else
        say "preserving existing $UNIT"
    fi
    systemctl --user daemon-reload >/dev/null 2>&1 || true
    if systemctl --user enable --now herdr-mobile-daemon >/dev/null 2>&1; then
        say "herdr-mobile-daemon enabled (systemctl --user status herdr-mobile-daemon)"
    else
        warn "could not enable herdr-mobile-daemon; start it with: systemctl --user enable --now herdr-mobile-daemon"
    fi
else
    say "no systemd service installed; run: $BIN --bind 127.0.0.1:8765"
fi

say "done"
printf '%s\n' "  phone → daemon: SSH tunnel to 127.0.0.1:8765 (recommended)"
printf '%s\n' "  health:        curl -fsS http://127.0.0.1:8765/v1/health"
