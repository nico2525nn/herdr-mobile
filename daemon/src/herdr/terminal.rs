use anyhow::{Context, Result};
use base64::Engine;
use serde_json::{json, Value};
use std::process::Stdio;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, ChildStdout};
use tracing::{debug, warn};

use crate::herdr::HerdrClient;

/// One `herdr terminal session control` child: NDJSON on stdin/stdout.
///
/// Stdout records:
/// - `{"type":"terminal.frame","bytes":"<base64 ANSI>",...}`
/// - `{"type":"terminal.closed","reason":"..."}`
/// - `{"error":{...}}`
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ChildRecord {
    Frame(Vec<u8>),
    Closed(String),
}

pub struct TerminalChild {
    child: Child,
    stdin: ChildStdin,
    stdout_lines: tokio::io::Lines<BufReader<ChildStdout>>,
    pub pane_id: String,
    pub cols: u32,
    pub rows: u32,
}

impl TerminalChild {
    pub async fn spawn(
        herdr: &HerdrClient,
        pane_id: &str,
        takeover: bool,
        cols: u32,
        rows: u32,
    ) -> Result<TerminalChild> {
        let mut cmd = tokio::process::Command::new(&herdr.herdr_bin);
        cmd.arg("terminal")
            .arg("session")
            .arg("control")
            .arg(pane_id);
        if takeover {
            cmd.arg("--takeover");
        }
        cmd.arg("--cols")
            .arg(cols.to_string())
            .arg("--rows")
            .arg(rows.to_string())
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .kill_on_drop(true);
        let mut child = cmd.spawn().with_context(|| {
            format!("cannot spawn `{bin} terminal session control`", bin = herdr.herdr_bin)
        })?;
        let stdin = child.stdin.take().context("terminal child has no stdin")?;
        let stdout = child.stdout.take().context("terminal child has no stdout")?;
        Ok(TerminalChild {
            child,
            stdin,
            stdout_lines: BufReader::new(stdout).lines(),
            pane_id: pane_id.to_string(),
            cols,
            rows,
        })
    }

    async fn send_line(&mut self, value: &Value) -> Result<()> {
        let mut line = serde_json::to_string(value).context("cannot encode terminal command")?;
        line.push('\n');
        self.stdin
            .write_all(line.as_bytes())
            .await
            .context("terminal child stdin broke")?;
        self.stdin.flush().await?;
        Ok(())
    }

    pub async fn send_input_text(&mut self, text: &str) -> Result<()> {
        self.send_line(&json!({"type": "terminal.input", "text": text})).await
    }

    pub async fn send_input_bytes(&mut self, bytes: &[u8]) -> Result<()> {
        let encoded = base64::engine::general_purpose::STANDARD.encode(bytes);
        self.send_line(&json!({"type": "terminal.input", "bytes": encoded})).await
    }

    pub async fn send_resize(&mut self, cols: u32, rows: u32) -> Result<()> {
        self.cols = cols;
        self.rows = rows;
        self.send_line(&json!({"type": "terminal.resize", "cols": cols, "rows": rows})).await
    }

    pub async fn send_scroll(&mut self, direction: &str, lines: u32) -> Result<()> {
        self.send_line(&json!({"type": "terminal.scroll", "direction": direction, "lines": lines}))
            .await
    }

    pub async fn send_mouse(&mut self, action: &str, button: &str, column: u32, row: u32) -> Result<()> {
        self.send_line(
            &json!({"type": "terminal.mouse", "action": action, "button": button, "column": column, "row": row}),
        )
        .await
    }

    pub async fn send_release(&mut self) -> Result<()> {
        // Best effort: the child may already be gone.
        let _ = self.send_line(&json!({"type": "terminal.release"})).await;
        Ok(())
    }

    /// Next record from the child, or `None` when it exited.
    pub async fn next_record(&mut self) -> Result<Option<ChildRecord>> {
        loop {
            let line = match self.stdout_lines.next_line().await {
                Ok(Some(line)) => line,
                Ok(None) => return Ok(None),
                Err(e) => return Err(e).context("terminal child stdout broke"),
            };
            if line.trim().is_empty() {
                continue;
            }
            match parse_record(&line) {
                Ok(Some(record)) => return Ok(Some(record)),
                Ok(None) => continue,
                Err(e) => {
                    debug!("skipping invalid terminal child line: {e}");
                    continue;
                }
            }
        }
    }

    pub async fn kill(&mut self) {
        let _ = self.send_release().await;
        // Give release a moment; then force.
        tokio::select! {
            _ = self.child.wait() => {},
            _ = tokio::time::sleep(std::time::Duration::from_millis(400)) => {
                if let Err(e) = self.child.kill().await {
                    warn!("cannot kill terminal child for {}: {e:#}", self.pane_id);
                }
            }
        }
    }
}

/// Parse one stdout line. Returns `Ok(None)` for lines that carry no payload.
pub fn parse_record(line: &str) -> Result<Option<ChildRecord>> {
    let value: Value = serde_json::from_str(line)?;
    if value.get("error").is_some() {
        let code = value
            .pointer("/error/code")
            .and_then(|c| c.as_str())
            .unwrap_or("terminal_error");
        let message = value
            .pointer("/error/message")
            .and_then(|m| m.as_str())
            .unwrap_or("terminal child error");
        anyhow::bail!("terminal child error {code}: {message}");
    }
    match value.get("type").and_then(|t| t.as_str()) {
        Some("terminal.frame") => {
            let encoded = value
                .get("bytes")
                .and_then(|b| b.as_str())
                .context("terminal.frame has no bytes")?;
            let bytes = base64::engine::general_purpose::STANDARD
                .decode(encoded)
                .context("terminal.frame bytes are not base64")?;
            Ok(Some(ChildRecord::Frame(bytes)))
        }
        Some("terminal.closed") => {
            let reason = value
                .get("reason")
                .and_then(|r| r.as_str())
                .unwrap_or("closed")
                .to_string();
            Ok(Some(ChildRecord::Closed(reason)))
        }
        _ => Ok(None),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_frame_and_closed() {
        let frame = parse_record(
            r#"{"type":"terminal.frame","bytes":"G1sxOzEwSA==","encoding":"ansi","full":true,"width":90,"height":30,"seq":1}"#,
        )
        .unwrap()
        .unwrap();
        match frame {
            ChildRecord::Frame(bytes) => assert_eq!(bytes, b"\x1b[1;10H"),
            other => panic!("unexpected {other:?}"),
        }
        let closed = parse_record(r#"{"type":"terminal.closed","reason":"detached"}"#)
            .unwrap()
            .unwrap();
        assert_eq!(closed, ChildRecord::Closed("detached".to_string()));
    }

    #[test]
    fn rejects_error_lines() {
        let err = parse_record(r#"{"error":{"code":"terminal_busy","message":"taken"}}"#);
        assert!(err.is_err());
    }

    #[test]
    fn command_shapes() {
        // Encoder contract: exactly these JSON lines go to the child's stdin.
        assert_eq!(
            serde_json::to_string(&json!({"type": "terminal.input", "text": "x"})).unwrap(),
            r#"{"text":"x","type":"terminal.input"}"#,
        );
        assert_eq!(
            serde_json::to_string(&json!({"type": "terminal.resize", "cols": 90, "rows": 30})).unwrap(),
            r#"{"cols":90,"rows":30,"type":"terminal.resize"}"#,
        );
        assert_eq!(
            serde_json::to_string(&json!({"type": "terminal.scroll", "direction": "up", "lines": 5})).unwrap(),
            r#"{"direction":"up","lines":5,"type":"terminal.scroll"}"#,
        );
        assert_eq!(
            serde_json::to_string(&json!({"type": "terminal.release"})).unwrap(),
            r#"{"type":"terminal.release"}"#,
        );
    }
}
