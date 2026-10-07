# SSH Ed25519 on Android: BouncyCastle signer

## Problem
JSch 0.2.23 defaults `ssh-ed25519` to `jce.SignatureEd25519`, which needs Java 15+
JCE EdDSA. Android only ships that from API 35, so on the emulator (API 37 in this
repo, but minSdk 26 generally) `Session.connect()` fails key auth with:

- client log: `ssh-ed25519 preauth failure`
- `JSchException: Auth fail for methods 'publickey,password'`
- sshd sees no offered key at all (client-side signature unavailability)

Verified by decompiling `UserAuthPublicKey`/`Session.checkSignatures` and by a
throwaway on-device probe: `jce.SignatureEd25519.init()` throws
`NoClassDefFoundError: Lorg/bouncycastle/crypto/signers/Ed25519Signer`.

## Fix
- `org.bouncycastle:bcprov-jdk15to18:1.81` added to `:connection-ssh`.
- `SshTunnelProvider.open()` registers the bundled BC-backed signers before dialing:

```kotlin
JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
```

## Verification
- `:connection-ssh:connectedDebugAndroidTest` against 100.64.34.116 over tailnet:
  `keyAuthTunnelReachesDaemon` PASSED (tunnel + `/v1/health` through it).
- `passwordAuthTunnelReachesDaemon` SKIPPED unless a real account password is passed
  via `-Pandroid.testInstrumentationRunnerArguments.password=...` (sshd here has no
  password to test against; a dummy value must not fail the suite).

## Release signing

`app/build.gradle.kts` wires a `herdrRelease` signing config into the `release`
build type. Keystore: `~/.config/herdr-mobile/release.keystore` (outside the repo,
`600`). Password: `HERDR_RELEASE_KEYSTORE_PASSWORD` env var (optional
`HERDR_RELEASE_KEY_ALIAS`, default `herdr`).

- Password set + keystore present → `app-release.apk` (signed, `apksigner verify` clean).
- Otherwise → `app-release-unsigned.apk` (CI/verification builds keep working).
