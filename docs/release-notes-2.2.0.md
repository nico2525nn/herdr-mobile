## v2.2.0

M3 Expressive UI refresh for Home, navigation, Settings, and terminal keys.

### Home
- The header uses the expressive large-title type (`displaySmall` 36/44 and
  `titleMedium` 16/24) and wraps instead of clipping at large font scales.
- Workspace cards use the 28dp expressive shape with 20dp padding, 12dp status
  dots, `bodyMedium` paths, and filled tonal chips (12dp unselected / full
  selected).
- The phone list keeps its explicit reorder handle; the tablet grid no longer
  renders a dead handle or a dead long-press strip. The FAB leaves room for
  the last card.

### Navigation
- Official full-width `NavigationBar` with `alwaysShowLabel = false`: only the
  selected destination shows its label below the indicator.
- Active icons are filled, inactive icons outlined.
- Status/navigation bar icon appearance follows the resolved app theme, so
  Dark/Midnight does not hide the status bar icons on a light system.

### Terminal and Settings
- Extra keys are 40dp with 12dp square corners. PGUP keeps the downward-flick
  PGDN action (`PGUP↓`) and exposes a "Page down" TalkBack/switch-access
  action. The flick no longer races the panel pager.
- Panel swipes only switch pages when Swipe behavior is PAGE; NONE and
  SCROLLBACK no longer trigger the pager.
- Settings groups use `surfaceContainer`; terminal rails use the expressive
  chip shape.

### Midnight
- AMOLED hairlines for cards, chips, and keys; a visible nav indicator and
  nav-bar divider; a primary FAB container.

### Emulator/CI
- HerdrTest now boots at 1080x2400@420 (411x914dp) instead of HVGA.
- `tools/ci.sh` logs the device screen and fails emulators below
  1080x1920px / 360x640dp; physical devices are log-only.
- The debug override waits past its DataStore timeout and writes both state
  files, removing the cold-boot flake.

### Installer
```bash
curl -fsSL https://raw.githubusercontent.com/nico2525nn/herdr-mobile/main/install.sh | sh
```
Resolves the latest release, installs `herdr-mobile-daemon` under `~/.local`,
and enables a user systemd unit on `127.0.0.1:8765`. Overrides:
`HERDR_MOBILE_VERSION`, `HERDR_MOBILE_PREFIX`, `HERDR_MOBILE_NO_SERVICE=1`,
`HERDR_MOBILE_TOKEN`.

### Artifacts
- `herdr-mobile-2.2.0-arm64-v8a.apk` (default)
- `herdr-mobile-2.2.0-armeabi-v7a.apk`
- `herdr-mobile-daemon-linux-x86_64.tar.gz` (+ SHA-256)
- `install.sh`

Tests: `tools/check.sh` (JVM/JNI/Rust 66), `:feature-home` 5,
`:feature-terminal` 15, `:feature-settings` 3, `:terminal-ghostty` 5
connected tests, and the isolated `tools/ci.sh` smoke (14 checks, live Herdr
session unchanged, 1080x2400@420).
