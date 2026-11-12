//! Host-side agent configuration that changes input transport.
//!
//! OpenCode's TUI reads `scroll.speed` from its global `cli.json`: the number
//! of transcript rows one SGR wheel tick advances. The phone cannot see that
//! file, but the daemon runs next to the agent, so it reads the same config and
//! stamps the effective stride onto the pane. The client then sends one wheel
//! notch per `speed` rows instead of the built-in OpenCode default (3).
//!
//! Only OpenCode is auto-read. A config it cannot resolve (missing file,
//! malformed JSONC, or `scroll.acceleration`) returns `None`, which leaves the
//! client on the audited matrix default.

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{LazyLock, Mutex};
use std::time::SystemTime;

#[derive(Clone, Copy)]
struct Cached {
    modified: Option<SystemTime>,
    len: u64,
    speed: Option<f64>,
}

static CACHE: LazyLock<Mutex<HashMap<PathBuf, Cached>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

/// Rows advanced by one wheel notch for `agent`, or `None` when the agent is
/// not OpenCode or its config does not specify a usable fixed speed.
pub fn wheel_rows_per_event(agent: Option<&str>) -> Option<f64> {
    let agent = agent?;
    if !agent.eq_ignore_ascii_case("opencode") {
        return None;
    }
    // The CLI supports an inline override; honour it when the daemon inherited
    // the same environment as the pane.
    if let Ok(content) = std::env::var("OPENCODE_CLI_CONFIG_CONTENT") {
        if !content.trim().is_empty() {
            return parse_scroll_speed(&content);
        }
    }
    let path = config_path()?;
    read_cached(&path)
}

fn env_path(name: &str) -> Option<PathBuf> {
    std::env::var_os(name)
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
}

/// OpenCode's TUI config is `cli.json` in its global config directory.
/// `OPENCODE_CONFIG_DIR` and XDG take precedence; the remaining candidates
/// cover the default Linux and macOS roots. A missing file is not an error:
/// the audited default already matches OpenCode's built-in speed.
fn config_path() -> Option<PathBuf> {
    if let Some(dir) = env_path("OPENCODE_CONFIG_DIR") {
        return Some(dir.join("cli.json"));
    }
    let mut candidates = Vec::new();
    if let Some(xdg) = env_path("XDG_CONFIG_HOME") {
        candidates.push(xdg.join("opencode").join("cli.json"));
    }
    if let Some(home) = env_path("HOME") {
        candidates.push(home.join(".config").join("opencode").join("cli.json"));
        candidates.push(
            home.join("Library")
                .join("Application Support")
                .join("opencode")
                .join("cli.json"),
        );
    }
    if let Some(appdata) = env_path("APPDATA") {
        candidates.push(appdata.join("opencode").join("cli.json"));
    }
    if let Some(found) = candidates.iter().find(|path| path.is_file()) {
        return Some(found.clone());
    }
    candidates.into_iter().next()
}

fn read_cached(path: &Path) -> Option<f64> {
    let metadata = fs::metadata(path).ok()?;
    let modified = metadata.modified().ok();
    let len = metadata.len();
    if let Ok(guard) = CACHE.lock() {
        if let Some(entry) = guard.get(path) {
            if entry.modified == modified && entry.len == len {
                return entry.speed;
            }
        }
    }
    let text = fs::read_to_string(path).ok()?;
    let speed = parse_scroll_speed(&text);
    if let Ok(mut guard) = CACHE.lock() {
        guard.insert(path.to_path_buf(), Cached { modified, len, speed });
    }
    speed
}

/// Read `scroll.speed` from OpenCode's JSONC config.
///
/// `scroll.acceleration` delegates to an accelerating curve that a fixed
/// notch count cannot model, so it deliberately falls back to the default.
pub fn parse_scroll_speed(text: &str) -> Option<f64> {
    let text = text.trim_start_matches('\u{feff}');
    let value: serde_json::Value = serde_json::from_str(&strip_jsonc(text)).ok()?;
    let scroll = value.get("scroll")?;
    if scroll.get("acceleration").and_then(serde_json::Value::as_bool) == Some(true) {
        return None;
    }
    let speed = scroll.get("speed")?.as_f64()?;
    if !speed.is_finite() || speed <= 0.0 {
        return None;
    }
    // Guard a hand-edited tiny value from turning every drag into thousands
    // of notches; the TUI schema itself only requires >= 0.001.
    Some(speed.clamp(0.01, 16.0))
}

/// JSONC (comments + trailing commas) is what OpenCode writes. `serde_json`
/// only accepts strict JSON, so remove the two extensions while preserving
/// string contents and byte positions enough for parsing.
fn strip_jsonc(input: &str) -> String {
    strip_trailing_commas(&strip_comments(input))
}

fn strip_comments(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut index = 0;
    let mut in_string = false;
    let mut escaped = false;
    while index < bytes.len() {
        let byte = bytes[index];
        if in_string {
            out.push(byte);
            if escaped {
                escaped = false;
            } else if byte == b'\\' {
                escaped = true;
            } else if byte == b'"' {
                in_string = false;
            }
            index += 1;
            continue;
        }
        if byte == b'"' {
            in_string = true;
            out.push(byte);
            index += 1;
            continue;
        }
        if byte == b'/' && bytes.get(index + 1) == Some(&b'/') {
            index += 2;
            while index < bytes.len() && bytes[index] != b'\n' {
                index += 1;
            }
            continue;
        }
        if byte == b'/' && bytes.get(index + 1) == Some(&b'*') {
            index += 2;
            while index + 1 < bytes.len() && !(bytes[index] == b'*' && bytes[index + 1] == b'/') {
                index += 1;
            }
            index = (index + 2).min(bytes.len());
            continue;
        }
        out.push(byte);
        index += 1;
    }
    String::from_utf8(out).unwrap_or_default()
}

fn strip_trailing_commas(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut index = 0;
    let mut in_string = false;
    let mut escaped = false;
    while index < bytes.len() {
        let byte = bytes[index];
        if in_string {
            out.push(byte);
            if escaped {
                escaped = false;
            } else if byte == b'\\' {
                escaped = true;
            } else if byte == b'"' {
                in_string = false;
            }
            index += 1;
            continue;
        }
        if byte == b'"' {
            in_string = true;
            out.push(byte);
            index += 1;
            continue;
        }
        if byte == b',' {
            let mut next = index + 1;
            while next < bytes.len() && bytes[next].is_ascii_whitespace() {
                next += 1;
            }
            if matches!(bytes.get(next), Some(b'}' | b']')) {
                index += 1;
                continue;
            }
        }
        out.push(byte);
        index += 1;
    }
    String::from_utf8(out).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_speed_from_plain_json() {
        assert_eq!(parse_scroll_speed(r#"{"scroll":{"speed":2}}"#), Some(2.0));
    }

    #[test]
    fn accepts_fractional_speed() {
        assert_eq!(parse_scroll_speed(r#"{"scroll":{"speed":0.5}}"#), Some(0.5));
    }

    #[test]
    fn reads_jsonc_comments_and_trailing_commas() {
        let text = r#"{
            // TUI scrolling
            "scroll": { "speed": 4, /* rows per notch */ },
        }"#;
        assert_eq!(parse_scroll_speed(text), Some(4.0));
    }

    #[test]
    fn ignores_comment_markers_and_commas_inside_strings() {
        let text = r#"{"theme":{"name":"a//b,}"},"scroll":{"speed":2,}}"#;
        assert_eq!(parse_scroll_speed(text), Some(2.0));
    }

    #[test]
    fn reads_speed_from_a_file_and_refreshes_after_a_change() {
        let path = std::env::temp_dir().join(format!(
            "herdr-opencode-cli-{}-{:?}.json",
            std::process::id(),
            std::thread::current().id()
        ));
        fs::write(&path, r#"{"scroll":{"speed":2}}"#).unwrap();
        assert_eq!(read_cached(&path), Some(2.0));
        fs::write(&path, r#"{"scroll":{"speed":5}}"#).unwrap();
        assert_eq!(read_cached(&path), Some(5.0));
        let _ = fs::remove_file(path);
    }

    #[test]
    fn accepts_a_utf8_bom() {
        assert_eq!(
            parse_scroll_speed("\u{feff}{\"scroll\":{\"speed\":2}}"),
            Some(2.0)
        );
    }

    #[test]
    fn acceleration_or_missing_speed_falls_back() {
        assert_eq!(
            parse_scroll_speed(r#"{"scroll":{"speed":2,"acceleration":true}}"#),
            None
        );
        assert_eq!(parse_scroll_speed(r#"{"scroll":{}}"#), None);
        assert_eq!(parse_scroll_speed(r#"{"theme":{}}"#), None);
        assert_eq!(parse_scroll_speed("not json"), None);
    }

    #[test]
    fn rejects_non_positive_and_clamps_extremes() {
        assert_eq!(parse_scroll_speed(r#"{"scroll":{"speed":0}}"#), None);
        assert_eq!(parse_scroll_speed(r#"{"scroll":{"speed":-1}}"#), None);
        assert_eq!(parse_scroll_speed(r#"{"scroll":{"speed":0.001}}"#), Some(0.01));
        assert_eq!(parse_scroll_speed(r#"{"scroll":{"speed":99}}"#), Some(16.0));
    }
}
