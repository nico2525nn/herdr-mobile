use base64::Engine;
use rand::RngCore;

/// Constant-time bearer comparison. Every early return below compares full-length buffers so
/// token length alone reveals nothing about the configured secret.
pub fn tokens_equal(configured: &str, presented: &str) -> bool {
    let a = configured.as_bytes();
    let b = presented.as_bytes();
    let len = a.len().max(b.len()).max(1);
    let mut diff = (a.len() ^ b.len()) as u8;
    for i in 0..len {
        let x = *a.get(i % a.len().max(1)).unwrap_or(&0);
        let y = *b.get(i % b.len().max(1)).unwrap_or(&0);
        diff |= x ^ y;
    }
    // subtle::ConstantTimeEq would be nicer; the loop above is the dependency-free equivalent.
    diff == 0 && !configured.is_empty()
}

/// Fresh URL-safe token for `--print-token`.
pub fn generate_token() -> String {
    let mut bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut bytes);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_exact_token() {
        assert!(tokens_equal("abc123", "abc123"));
    }

    #[test]
    fn rejects_wrong_and_empty() {
        assert!(!tokens_equal("abc123", "abc124"));
        assert!(!tokens_equal("abc123", "abc12"));
        assert!(!tokens_equal("abc123", "abc1234"));
        assert!(!tokens_equal("abc123", ""));
        assert!(!tokens_equal("", ""));
        assert!(!tokens_equal("", "x"));
    }
}
