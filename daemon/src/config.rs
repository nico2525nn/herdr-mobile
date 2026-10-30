use anyhow::{bail, Result};

/// Runtime configuration. Every field has a CLI flag; the token also reads the environment.
#[derive(Clone, Debug)]
pub struct Config {
    pub bind: String,
    pub allow_remote_bind: bool,
    pub token: Option<String>,
    pub backend: Backend,
    pub herdr_socket: String,
    pub herdr_bin: String,
    pub core_socket: String,
    pub print_token: bool,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Backend {
    #[default]
    Tui,
    Core,
}

impl Backend {
    pub fn from_wire(value: &str) -> Option<Self> {
        match value {
            "tui" => Some(Self::Tui),
            "core" => Some(Self::Core),
            _ => None,
        }
    }

    pub fn as_wire(self) -> &'static str {
        match self {
            Self::Tui => "tui",
            Self::Core => "core",
        }
    }
}

impl Config {
    pub fn parse(args: impl IntoIterator<Item = String>) -> Result<Config> {
        let mut bind = "127.0.0.1:8765".to_string();
        let mut allow_remote_bind = false;
        let mut token: Option<String> = None;
        let mut backend: Option<Backend> = None;
        let mut herdr_socket: Option<String> = None;
        let mut herdr_bin: Option<String> = None;
        let mut core_socket: Option<String> = None;
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
                "--backend" => {
                    let value = args.next().ok_or_else(|| arg_err("--backend needs a value"))?;
                    backend = Some(Backend::from_wire(&value).ok_or_else(|| arg_err("--backend must be tui or core"))?);
                }
                "--core-socket" => {
                    core_socket = Some(args.next().ok_or_else(|| arg_err("--core-socket needs a value"))?);
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

        let backend = match backend {
            Some(backend) => backend,
            None => match std::env::var("HERDR_MOBILE_BACKEND") {
                Ok(value) => Backend::from_wire(&value)
                    .ok_or_else(|| arg_err("HERDR_MOBILE_BACKEND must be tui or core"))?,
                Err(_) => Backend::Tui,
            },
        };
        let herdr_socket = herdr_socket
            .or_else(|| std::env::var("HERDR_SOCKET_PATH").ok())
            .or_else(default_socket_path)
            .unwrap_or_else(|| "/tmp/herdr.sock".to_string());
        let core_socket = core_socket
            .or_else(|| std::env::var("HERDR_CORE_SOCKET").ok())
            .or_else(|| std::env::var("HC_TERMINAL_SOCKET").ok())
            .or_else(default_core_socket)
            .unwrap_or_else(|| "/run/user/1000/herdr-core/default.sock".to_string());

        Ok(Config {
            bind,
            allow_remote_bind,
            token,
            backend,
            herdr_socket,
            herdr_bin: herdr_bin.unwrap_or_else(|| "herdr".to_string()),
            core_socket,
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

fn default_core_socket() -> Option<String> {
    std::env::var("XDG_RUNTIME_DIR")
        .ok()
        .map(|runtime| format!("{runtime}/herdr-core/default.sock"))
        .or_else(|| std::env::var("UID").ok().map(|uid| format!("/run/user/{uid}/herdr-core/default.sock")))
}

fn help_text() -> &'static str {
    "herdr-mobile-daemon — thin bridge between Herdr and the Herdr Mobile Android client\n\
     \n\
     Usage: herdr-mobile-daemon [OPTIONS]\n\
     \n\
       --bind <ADDR>         Bind address, default 127.0.0.1:8765\n\
       --allow-remote-bind   Permit a non-loopback bind address\n\
       --token <TOKEN>       Bearer token (falls back to HERDR_MOBILE_TOKEN)\n\
       --backend <NAME>      Default backend: tui or core (env: HERDR_MOBILE_BACKEND; default: tui)\n\
       --core-socket <PATH>  herdr-core Unix socket (env: HERDR_CORE_SOCKET, then HC_TERMINAL_SOCKET)\n\
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
                "--backend",
                "core",
                "--core-socket",
                "/x/core.sock",
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
        assert_eq!(cfg.backend, Backend::Core);
        assert_eq!(cfg.core_socket, "/x/core.sock");
        assert_eq!(cfg.herdr_socket, "/x/herdr.sock");
        assert_eq!(cfg.herdr_bin, "/bin/herdr");
    }

    #[test]
    fn rejects_invalid_backend() {
        let err = Config::parse(["--backend".to_string(), "other".to_string()]).unwrap_err();
        assert!(err.to_string().contains("tui or core"));
    }

    #[test]
    fn rejects_unknown_flags() {
        let err = Config::parse(["--nope".to_string()]).unwrap_err();
        assert!(err.to_string().contains("unknown argument"));
    }
}
