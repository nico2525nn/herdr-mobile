use anyhow::{bail, Result};

/// Runtime configuration. Every field has a CLI flag; the token also reads the environment.
#[derive(Clone, Debug)]
pub struct Config {
    pub bind: String,
    pub allow_remote_bind: bool,
    pub token: Option<String>,
    pub herdr_socket: String,
    pub herdr_bin: String,
    pub print_token: bool,
}

impl Config {
    pub fn parse(args: impl IntoIterator<Item = String>) -> Result<Config> {
        let mut bind = "127.0.0.1:8765".to_string();
        let mut allow_remote_bind = false;
        let mut token: Option<String> = None;
        let mut herdr_socket: Option<String> = None;
        let mut herdr_bin: Option<String> = None;
        let mut print_token = false;

        let mut args = args.into_iter().peekable();
        while let Some(arg) = args.next() {
            match arg.as_str() {
                "--bind" => {
                    bind = args.next().ok_or_else(|| arg_err("--bind needs a value"))?;
                }
                "--allow-remote-bind" => allow_remote_bind = true,
                "--token" => {
                    token = Some(args.next().ok_or_else(|| arg_err("--token needs a value"))?);
                }
                "--herdr-socket" => {
                    herdr_socket = Some(args.next().ok_or_else(|| arg_err("--herdr-socket needs a value"))?);
                }
                "--herdr-bin" => {
                    herdr_bin = Some(args.next().ok_or_else(|| arg_err("--herdr-bin needs a value"))?);
                }
                "--print-token" => print_token = true,
                "-h" | "--help" => {
                    print!("{}", help_text());
                    std::process::exit(0);
                }
                other => bail!("unknown argument {other:?}\n\n{}", help_text()),
            }
        }

        if token.is_none() {
            if let Ok(env) = std::env::var("HERDR_MOBILE_TOKEN") {
                if !env.trim().is_empty() {
                    token = Some(env);
                }
            }
        }

        let herdr_socket = herdr_socket
            .or_else(|| std::env::var("HERDR_SOCKET_PATH").ok())
            .or_else(default_socket_path)
            .unwrap_or_else(|| "/tmp/herdr.sock".to_string());

        Ok(Config {
            bind,
            allow_remote_bind,
            token,
            herdr_socket,
            herdr_bin: herdr_bin.unwrap_or_else(|| "herdr".to_string()),
            print_token,
        })
    }
}

fn arg_err(msg: &str) -> anyhow::Error {
    anyhow::anyhow!("{msg}\n\n{}", help_text())
}

fn default_socket_path() -> Option<String> {
    std::env::var("HOME")
        .ok()
        .map(|home| format!("{home}/.config/herdr/herdr.sock"))
}

fn help_text() -> &'static str {
    "herdr-mobile-daemon — thin bridge between Herdr and the Herdr Mobile Android client\n\
     \n\
     Usage: herdr-mobile-daemon [OPTIONS]\n\
     \n\
       --bind <ADDR>         Bind address, default 127.0.0.1:8765\n\
       --allow-remote-bind   Permit a non-loopback bind address\n\
       --token <TOKEN>       Bearer token (falls back to HERDR_MOBILE_TOKEN)\n\
       --herdr-socket <PATH> Herdr Unix socket (falls back to $HERDR_SOCKET_PATH,\n\
                             then ~/.config/herdr/herdr.sock)\n\
       --herdr-bin <PATH>    Herdr CLI used for terminal attach (default: herdr on PATH)\n\
       --print-token         Print a fresh random token and exit\n\
       -h, --help            Show this text\n"
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_flags() {
        let cfg = Config::parse(
            [
                "--bind",
                "127.0.0.1:9999",
                "--allow-remote-bind",
                "--token",
                "secret",
                "--herdr-socket",
                "/x/herdr.sock",
                "--herdr-bin",
                "/bin/herdr",
            ]
            .iter()
            .map(|s| s.to_string()),
        )
        .unwrap();
        assert_eq!(cfg.bind, "127.0.0.1:9999");
        assert!(cfg.allow_remote_bind);
        assert_eq!(cfg.token.as_deref(), Some("secret"));
        assert_eq!(cfg.herdr_socket, "/x/herdr.sock");
        assert_eq!(cfg.herdr_bin, "/bin/herdr");
    }

    #[test]
    fn rejects_unknown_flags() {
        let err = Config::parse(["--nope".to_string()]).unwrap_err();
        assert!(err.to_string().contains("unknown argument"));
    }
}
