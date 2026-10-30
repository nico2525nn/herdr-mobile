use std::borrow::Cow;

use anyhow::{Context, Result};
use axum::extract::ws::{Message, WebSocket};
use base64::Engine;
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};

use super::{CoreAttachment, CoreClient, FRAME_CLOSE, FRAME_DATA, FRAME_FACTS};

const INITIAL_CREDIT: u32 = 1024 * 1024;

pub async fn bridge(mut socket: WebSocket, core: CoreClient, address: String, cols: u32, rows: u32, history: u32) -> Result<()> {
    let mut attachment = match core.attach(&address, cols, rows, history, false).await {
        Ok(attachment) => attachment,
        Err(error) => {
            let _ = socket.send(Message::Text(json!({
                "type":"error",
                "code":"core_attach_failed",
                "message":format!("{error:#}"),
            }).to_string().into())).await;
            return Err(error);
        }
    };
    let ready = json!({
        "type":"ready",
        "paneId":address,
        "cols":cols,
        "rows":rows,
        "encoding":"ansi",
        "resumed":false,
        "historyRows":0,
        "historyTruncated":false,
        "historyError":null,
        "visibleOk":true,
    });
    socket.send(Message::Text(ready.to_string().into())).await.context("cannot send core terminal ready frame")?;
    attachment.grant_credit(INITIAL_CREDIT).await?;

    let (mut sink, mut source) = socket.split();
    loop {
        tokio::select! {
            frame = attachment.next_frame() => {
                let frame = match frame {
                    Ok(frame) => frame,
                    Err(error) => {
                        let _ = sink.send(Message::Text(json!({"type":"closed","reason":format!("{error:#}")}).to_string().into())).await;
                        return Err(error);
                    }
                };
                if frame.channel != attachment.channel {
                    continue;
                }
                match frame.kind {
                    FRAME_DATA => {
                        if !frame.payload.is_empty() {
                            let credit = u32::try_from(frame.payload.len()).unwrap_or(u32::MAX);
                            sink.send(Message::Binary(frame.payload.into())).await.context("core terminal WebSocket closed")?;
                            attachment.grant_credit(credit).await?;
                        }
                    }
                    FRAME_FACTS => {
                        if is_finished_facts(&frame.payload) {
                            let _ = sink.send(Message::Text(json!({"type":"closed","reason":"finished"}).to_string().into())).await;
                            break;
                        }
                    }
                    FRAME_CLOSE => {
                        let _ = sink.send(Message::Text(json!({"type":"closed","reason":"closed"}).to_string().into())).await;
                        break;
                    }
                    _ => {}
                }
            }
            message = source.next() => {
                match message {
                    Some(Ok(Message::Binary(bytes))) => attachment.send_data(&bytes).await?,
                    Some(Ok(Message::Text(text))) => {
                        if handle_control(&mut attachment, text.as_str()).await? {
                            break;
                        }
                    }
                    Some(Ok(Message::Close(_))) | None => {
                        let _ = attachment.close().await;
                        break;
                    }
                    Some(Ok(Message::Ping(_))) | Some(Ok(Message::Pong(_))) => {}
                    Some(Err(error)) => return Err(error.into()),
                }
            }
        }
    }
    Ok(())
}

fn is_finished_facts(bytes: &[u8]) -> bool {
    let Ok(value) = serde_json::from_slice::<Value>(bytes) else { return false };
    value.get("reason").and_then(Value::as_str).is_some()
        || value.get("exit_code").is_some_and(|value| !value.is_null())
}

fn input_payload(value: &Value) -> Result<Option<Cow<'_, [u8]>>> {
    match value.get("type").and_then(Value::as_str).unwrap_or("") {
        "input.text" => Ok(value
            .get("text")
            .and_then(Value::as_str)
            .map(|text| Cow::Borrowed(text.as_bytes()))),
        "input.bytes" => value
            .get("bytes")
            .and_then(Value::as_str)
            .map(|encoded| {
                base64::engine::general_purpose::STANDARD
                    .decode(encoded)
                    .map(Cow::Owned)
                    .context("invalid input.bytes base64")
            })
            .transpose(),
        _ => Ok(None),
    }
}

fn allows_core_page_scroll(value: &Value) -> bool {
    value.get("allowPageKey").and_then(Value::as_bool) == Some(true)
}

async fn handle_control(attachment: &mut CoreAttachment, text: &str) -> Result<bool> {
    let Ok(value) = serde_json::from_str::<Value>(text) else { return Ok(false) };
    if let Some(bytes) = input_payload(&value)? {
        // Keep input byte-exact: bracketed-paste markers contain ESC and must
        // never pass through Termux's text/paste normalizer (bug class #1).
        attachment.send_data(&bytes).await?;
        return Ok(false);
    }
    match value.get("type").and_then(Value::as_str).unwrap_or("") {
        "resize" => {
            let cols = value.get("cols").and_then(Value::as_u64).unwrap_or(0).min(u16::MAX as u64) as u16;
            let rows = value.get("rows").and_then(Value::as_u64).unwrap_or(0).min(u16::MAX as u64) as u16;
            if cols > 0 && rows > 0 {
                attachment.resize(cols, rows).await?;
            }
        }
        "scroll" => {
            // Core exposes PTY DATA, not host scrollback. Only honor page-key
            // scrolling when the client observed an agent or alt-screen TUI;
            // otherwise literal PgUp bytes can land in shells such as `sleep`
            // (bug class #2).
            if !allows_core_page_scroll(&value) {
                return Ok(false);
            }
            let lines = value.get("lines").and_then(Value::as_i64).unwrap_or(0);
            if lines != 0 {
                // Core exposes PTY DATA, not Herdr's host-scroll RPC. Page-up/down
                // key sequences are the terminal-native equivalent for attached TUIs.
                let key: &[u8] = if value.get("direction").and_then(Value::as_str) == Some("down") {
                    b"\x1b[6~"
                } else {
                    b"\x1b[5~"
                };
                for _ in 0..lines.unsigned_abs().min(200) {
                    attachment.send_data(key).await?;
                }
            }
        }
        "mouse" => {
            let action = value.get("action").and_then(Value::as_str).unwrap_or("down");
            let button = match value.get("button").and_then(Value::as_str).unwrap_or("left") {
                "left" => 0,
                "middle" => 1,
                "right" => 2,
                _ => return Ok(false),
            };
            let column = value.get("column").and_then(Value::as_u64).unwrap_or(0).saturating_add(1);
            let row = value.get("row").and_then(Value::as_u64).unwrap_or(0).saturating_add(1);
            let suffix = if action == "up" { 'm' } else { 'M' };
            let bytes = format!("\u{1b}[<{button};{column};{row}{suffix}");
            attachment.send_data(bytes.as_bytes()).await?;
        }
        "release" => {
            attachment.close().await?;
            return Ok(true);
        }
        _ => {}
    }
    Ok(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn terminal_facts_only_close_for_finished_programs() {
        assert!(is_finished_facts(br#"{"reason":"exited","exit_code":0}"#));
        assert!(!is_finished_facts(br#"{"state":"live","status":{"state":"working"}}"#));
    }

    #[test]
    fn bracketed_paste_esc_markers_survive_text_and_bytes_controls() {
        // Regression for bug class #1: core attach input must preserve ESC-bearing
        // bracket markers rather than sending them through paste normalization.
        let expected: &[u8] = b"\x1b[200~pasted\x1b[201~";
        let text = json!({"type":"input.text","text":"\u{1b}[200~pasted\u{1b}[201~"});
        let bytes = json!({
            "type":"input.bytes",
            "bytes":base64::engine::general_purpose::STANDARD.encode(expected),
        });
        assert_eq!(input_payload(&text).unwrap().unwrap().as_ref(), expected);
        assert_eq!(input_payload(&bytes).unwrap().unwrap().as_ref(), expected);
    }

    #[test]
    fn core_scroll_requires_agent_or_alt_screen_evidence() {
        // Regression for bug class #2: old clients without a scroll capability
        // bit must not type PgUp into non-TUI foreground programs such as sleep.
        assert!(!allows_core_page_scroll(&json!({"type":"scroll","direction":"up","lines":5})));
        assert!(allows_core_page_scroll(&json!({"type":"scroll","allowPageKey":true})));
    }
}
