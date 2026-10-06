# Herdr Mobile — 完全制作仕様書 v1

更新日: 2026-10-06

## 0. 文書の目的

本書は Herdr Mobile の UI/UX、Android 実装、Linux 側ブリッジ、端末エミュレーション、入力、通信、通知、状態同期、セキュリティ、テスト、開発環境までを一つにまとめた制作仕様書である。

設計の中心原則は以下。

> **Home for semantic overview; Terminal for deep interaction only when needed.**
>
> Home では意味のある状態だけを見る。Terminal は必要なときだけ深く入る。

Herdr Mobile は「Android 上で tmux/Herdr を再実装するアプリ」ではなく、Linux 側に存在する Herdr/tmux を唯一の実体として扱う **remote semantic client** とする。

---

# 1. プロダクト概要

## 1.1 目的

複数のコーディングエージェント、tmux セッション、Herdr ワークスペースを Android 端末から監視・操作する。

主な用途:

- 外出先から各 workspace / tab の状態を一目で確認する
- working / blocked / done / idle を即座に把握する
- 必要なときだけ対象 terminal に入る
- agent に短い追加入力を送る
- Ctrl-C 相当の interrupt を送る
- 日本語などの CJK 文字列を Android IME で普通に組み立ててから terminal に送る
- エージェント完了・失敗・入力待ちだけを通知する

## 1.2 非目標

以下は Android 側では行わない。

- tmux/session manager の再実装
- shell/runtime のローカル実行
- Termux 全体の埋め込み
- Linux 側 session state の複製を正とすること
- terminal 出力をバックグラウンドで常時ストリーミングすること
- agent ごとのプロトコルを Android 側へ直接大量実装すること

---

# 2. システム全体構成

```text
Codex / OMP / DSH / other agents
            │
      semantic hooks
            │
        Herdr / tmux       ← Source of Truth
            │
   herdr-mobile-daemon
            │
    HTTP/WS transport
            │
   SSH tunnel / Tailscale
            │
       Android client
```

## 2.1 Source of Truth

唯一の正は Linux 側 Herdr/tmux とする。

Android 側は表示・入力・通知を担うクライアントであり、workspace/tab/session の正規状態を独自保持しない。

## 2.2 herdr-mobile-daemon の責務

薄いブリッジとして以下だけを行う。

1. Herdr/tmux state の取得・集約
2. agent semantic event の受信
3. workspace/tab/pane/status の正規化
4. Android 向け API の提供
5. terminal byte stream の中継
6. Android からの input/interrupt/resize の転送
7. 再起動時に Herdr/tmux から state を再構築

daemon 自身を第二の Source of Truth にしない。

---

# 3. 推奨技術スタック

## 3.1 Android

- Kotlin
- Jetpack Compose
- Material 3 / Material 3 Expressive
- Navigation Compose
- Kotlin Coroutines / Flow
- OkHttp WebSocket などの安定した WebSocket 実装
- Android Keystore
- AndroidView で terminal rendering View を Compose に組み込む

## 3.2 Linux daemon

第一候補:

- Rust
- Tokio
- Axum
- Serde

理由:

- 常駐 daemon として軽量
- binary stream と WebSocket を扱いやすい
- async I/O が自然
- 型で protocol を固めやすい
- C/C++ 系 terminal/PTY API との接続もしやすい

Go は MVP 用代替として許容する。

---

# 4. 情報アーキテクチャ

トップレベル画面:

1. Home
2. Terminal
3. Settings

Terminal は恒常的な bottom navigation destination として扱う必要はない。
Home 上の workspace/tab、通知、deep link などから入る「作業コンテキスト画面」とする。

状態遷移例:

```text
Home
 ├─ tap workspace ──> Terminal(workspace, lastTab)
 ├─ tap tab ────────> Terminal(workspace, tab)
 ├─ bottom nav ─────> Settings
 └─ notification ───> Terminal(target)

Settings
 └─ bottom nav ─────> Home

Terminal
 ├─ Home/back ──────> Home
 ├─ workspace switch
 ├─ tab switch
 └─ + tab
```

---

# 5. Herdr 状態モデル

## 5.1 状態

| State | 意味 | 表示色の役割 |
|---|---|---|
| blocked | ユーザー入力・権限・判断などを待っている | red |
| working | 実行中 | yellow |
| done | 完了 / 新しい結果あり | blue |
| idle | 待機・通常状態 | green |
| unknown | 判別不能 / 未接続 | gray |

色だけに依存してはならない。Accessibility 用 contentDescription / text status を持つ。

## 5.2 Roll-up

workspace status は子 tab/pane の中で最も対応優先度が高いものを代表状態として返す。

推奨 priority:

```text
blocked > working > done > idle > unknown
```

ただし failed が将来独立 state になる場合は blocked より上に追加できるよう enum を拡張可能にする。

---

# 6. Home 画面仕様

## 6.1 目的

Home は **semantic overview**。
terminal の選択 UI をそのまま再現しない。

## 6.2 構成

```text
Herdr
3 workspaces · N active tabs

[workspace card]
  ● cpp-fabricmc
    ~/school/app/cpp-fabricmc

    ● agents   ● logs   ● server

[workspace card]
  ● jev-trans
    ~/projects/jev-trans

    ● main     ● tests

...

[bottom navigation]
```

## 6.3 Workspace Card

表示:

- status dot
- workspace name
- path
- nested tab summaries
- optional chevron

タップ:

- workspace body: last active tab で Terminal を開く
- tab chip: その tab を直接 Terminal で開く

## 6.4 Home 内 tab の重要ルール

Home は overview なので **最初の tab を selected 表示しない**。

全 tab chip は同格。
差分は Herdr status dot のみ。

## 6.5 Surface

強い全面着色は避ける。

基本は Material tonal surface hierarchy を使用:

```text
background
  └─ surfaceContainerLow
       └─ surfaceContainer / surfaceContainerHigh
```

Herdr 状態色を card 背景に使う場合でも非常に薄い tonal tint に留める。
主な意味表現は status dot。

---

# 7. Bottom Navigation

## 7.1 現在の destination

- Home
- Settings

## 7.2 表示方針

横一列。

選択中 destination:

- icon
- label
- active indicator

非選択 destination:

- icon を主に表示
- label は隠してよい

Compose 実装では `NavigationBarItem(alwaysShowLabel = false)` を基本候補とする。

## 7.3 Material 3 上の注意

公式 `NavigationBar` は 3〜5 destination を推奨している。
Herdr Mobile は現状 Home / Settings の 2 destination のため、**厳密な canonical 構成からは外れる**。

対応方針:

- UI のためだけに意味のない第三 destination を追加しない
- 2 destination の情報設計が正しい限り、M3 の color/shape/motion/component grammar を守りつつ実用優先
- 将来 genuine な第三 destination（例: Activity / Notifications 等）が必要になった時だけ追加

`ShortNavigationBar` は小画面で 3〜5 items 推奨なので、現状 2 items のため主実装には固定しない。

---

# 8. Terminal 画面仕様

## 8.1 目的

必要な時だけ深く操作する画面。
画面中央は可能な限り terminal に与える。

## 8.2 上部 navigation

二階層:

1. Workspace
2. Tab

### Workspace row

- far left: Home/back affordance
- workspace chips
- status dot は各 item 左端
- current workspace は selected state

### Tab row

- tab chips
- status dot は各 item 左端
- far right: `+` new tab action

## 8.3 厚み

現時点で試した compact variant:

- outer rail: 40dp 相当
- item visual height: 32dp 相当
- status dot: 約 7dp
- text: 約 11.5sp 相当

これは visual geometry の目標値であり、実タップ領域は必要に応じて 40〜48dp 相当まで透明 hit area を広げる。

### さらに薄くする場合

候補:

- outer 36dp
- visual item 28〜30dp

ただし readability / touchability / M3 rhythm が崩れる場合は採用しない。

## 8.4 長さ

固定フル幅 rail だけに拘らない。

優先候補:

- content-width / variable-width floating bar
- 必要な長さだけ伸びる
- overflow 時 horizontal scroll

比較案として以下も保持:

- workspace + tab の二段を、一つの stacked floating container にまとめる

最終決定は実機 412px 相当前後の portrait 表示で比較して決める。

## 8.5 Selected state

selected item を強調するために **高さや太さを増やさない**。

変えるもの:

- tonal fill
- foreground color
- optional shape/motion

変えないもの:

- rail height
- item height

---

# 9. Terminal renderer

## 9.1 Termux 再利用方針

再利用候補:

- `terminal-emulator`
  - ANSI / VT parser
  - screen buffer
  - cursor
  - colors
  - alternate screen

- `terminal-view`
  - rendering
  - selection
  - scrolling
  - input interaction

再利用しない:

- Termux package manager
- local bash runtime
- proot
- local filesystem environment
- Termux service/plugin architecture
- local PTY process launch logic

## 9.2 Remote backend abstraction

Termux `TerminalSession` の local PTY/process coupling を直接使わず、remote backend adapter を作る。

概念:

```kotlin
interface TerminalBackend {
    fun send(data: ByteArray)
    fun resize(cols: Int, rows: Int)
    fun interrupt()
}
```

TerminalView は AndroidView 経由で Compose に埋め込む。

## 9.3 Stream

terminal output は raw PTY byte stream を binary WebSocket で送る。

- UTF-8 decode を transport 層で勝手に行わない
- ANSI sequence を保持
- binary frame の順序を保証
- semantic events と terminal bytes を同じ stream に混ぜない

---

# 10. Bottom Terminal Input Panel

## 10.1 基本構造

下部に floating panel。

この panel は horizontal pager とし、2 ページを持つ。

```text
Page A: Extra Keys
⇄ swipe
Page B: CJK pre-composition input
```

両者を常時同時表示しない。

## 10.2 Page A — Extra Keys

Termux default 二段レイアウトを基準とする。

```text
ESC   /    -    HOME   ↑    END   PGUP
TAB  CTRL ALT    ←     ↓     →    PGDN
```

見た目は Compose / Material 3 Expressive で再設計してよいが、操作意味は Termux に合わせる。

必要操作:

- ESC
- TAB
- CTRL
- ALT
- arrows
- HOME/END
- PGUP/PGDN
- `/`
- `-`

## 10.3 Page B — CJK / 事前入力

**これは仕様として保持し、初期静的モックに常時見せる必要はない。**

目的:

Terminal grid 内で IME composition を直接扱うと日本語入力が不安定になりやすいため、通常の Android TextField で未確定文字列を保持する。

フロー:

```text
Android IME
   ↓
Compose TextField
   ↓ composition / conversion
確定文字列
   ↓
UTF-8 bytes
   ↓
remote terminal
```

要件:

- composing text を terminal に逐次送らない
- IME 確定後に send
- multiline は設定で許可/禁止
- Enter で送信
- dedicated send button は option
- clipboard paste 対応
- clear button は option
- 日本語 / 中国語 / 韓国語で共通利用

## 10.4 Pager interaction

- horizontal swipe
- 現在 page を小さな indicator で示してよい
- 誤操作を防ぐため terminal scroll gesture との競合を panel 内に限定

---

# 11. Settings 画面

## 11.1 基本方針

巨大 pill を並べる独自 UI ではなく、Material 3 の標準 hierarchy を使う。

- Top App Bar
- section label
- grouped ListItem
- trailing value
- chevron
- Switch

## 11.2 Appearance

- App theme: System / Light / Dark
- Dynamic color on/off
- Terminal theme
- Terminal font
- Font size
- Line height
- Cursor style
- optional bold-is-bright

## 11.3 Connection

- Host profile list
- hostname / address
- username
- SSH port
- SSH private key reference
- daemon endpoint
- Tailscale direct mode on/off
- test connection

秘密鍵 raw text は UI に表示しない。
Android Keystore または OS が保護する secure storage を使用する。

## 11.4 Terminal

- Extra Keys on/off
- Extra Keys layout/customization
- CJK pre-input on/off
- swipe behavior
- haptic feedback
- bell/vibration
- scrollback limit

## 11.5 Notifications

個別 toggle:

- done
- blocked / permission required
- failed

原則通知しない:

- working の通常 progress
- terminal の一行出力ごと
- heartbeat

---

# 12. Material 3 / Material 3 Expressive 指針

## 12.1 Expressive の意味

「丸い」「派手な色」にすることではない。

以下を一体として扱う。

- component choice
- hierarchy
- shape
- typography
- motion
- color roles
- state transition
- adaptive layout

## 12.2 Color

ハードコード色は prototype では許容するが、本実装は `MaterialTheme.colorScheme` へ寄せる。

Herdr state 色のみ semantic custom color として別 token 化する。

例:

```kotlin
HerdrColors.blocked
HerdrColors.working
HerdrColors.done
HerdrColors.idle
HerdrColors.unknown
```

## 12.3 Typography

App UI は Material typography。
Terminal content だけ monospace。

UI text に terminal font を流用しない。

## 12.4 Shape

- top/tab bars: compact rounded container
- chips: pill / rounded
- workspace cards: large rounded container
- settings: grouped list surface

すべてを同じ radius にしない。
役割ごとに shape hierarchy を持つ。

## 12.5 Motion

採用候補:

- selected navigation indicator transition
- workspace/tab chip selection
- bottom pager swipe
- workspace open → Terminal shared-context feeling
- status dot update の subtle animation

長い派手な animation は terminal 操作を阻害するため避ける。

---

# 13. 通信設計

## 13.1 API 草案

```text
GET  /sessions
GET  /panes
WS   /events
WS   /terminal/:pane
POST /pane/:id/input
POST /pane/:id/interrupt
POST /pane/:id/resize
```

正確な Herdr API との接続方法は実装前に再確認する。

## 13.2 Snapshot

Home 接続時は最初に snapshot を取得する。

例:

```json
{
  "revision": 1831,
  "workspaces": [
    {
      "id": "cpp-fabricmc",
      "name": "cpp-fabricmc",
      "path": "~/school/app/cpp-fabricmc",
      "status": "working",
      "tabs": []
    }
  ]
}
```

## 13.3 Semantic Event

```json
{
  "seq": 98213,
  "type": "tab.status_changed",
  "workspaceId": "cpp-fabricmc",
  "tabId": "agents",
  "status": "blocked",
  "at": "2026-10-06T18:00:00+09:00"
}
```

要件:

- monotonically increasing sequence
- reconnect 後の gap 検出
- gap があれば snapshot refetch

## 13.4 Input

通常文字入力:

```json
{
  "encoding": "utf-8",
  "text": "cargo test\n"
}
```

制御キーは別 action または raw bytes で送れるようにする。

## 13.5 Resize

Android 側 terminal view の cols/rows が変わったときに debounce して送る。

```json
{
  "cols": 92,
  "rows": 31
}
```

---

# 14. Connection / Transport

## 14.1 推奨 MVP

SSH tunnel 経由で daemon localhost に接続する構成を強く推奨。

```text
Android
  │ SSH key auth
  ▼
Linux SSH server
  │ local forward
  ▼
127.0.0.1:herdr-mobile-daemon
```

利点:

- daemon を LAN 全体へ expose しなくてよい
- 既存 SSH key を使える
- TLS/auth を daemon 自身へ大量実装しなくてよい

## 14.2 Direct Tailscale mode

将来:

```text
Android ── Tailscale ──> daemon WSS endpoint
```

条件:

- bind は tailnet interface / protected address
- TLS または tailnet 前提の明示的 auth
- bearer token を平文設定に保存しない

---

# 15. Lifecycle / 通信量 / バッテリー

## 15.1 Home foreground

接続:

- semantic event/control channel

接続しない:

- terminal raw byte stream

## 15.2 Terminal foreground

接続:

- semantic channel
- 選択 pane の terminal binary stream

## 15.3 Terminal を離れた瞬間

terminal stream を close。

Home へ戻っても terminal bytes を流し続けない。

## 15.4 Background

理想:

- persistent terminal socket なし
- persistent control socket も極力なし
- meaningful state transition は push notification

再 foreground 時:

1. reconnect
2. snapshot
3. revision/sequence reconcile
4. Terminal を開いた場合だけ stream attach

---

# 16. Notifications

通知対象:

- done
- failed
- blocked
- permission_required

通知 payload に terminal 出力全文を含めない。

推奨 payload:

```text
workspace
agent/tab
state
短い semantic summary
```

タップ時:

```text
notification
  ↓
app foreground
  ↓
refresh snapshot
  ↓
open workspace/tab
  ↓
attach terminal stream if needed
```

---

# 17. 状態同期 / 再接続

## 17.1 daemon restart

local DB の state を盲信しない。

起動時:

1. tmux/Herdr を scan
2. sessions/tabs/panes reconstruct
3. hooks の現在状態を merge
4. revision 発行

## 17.2 Android reconnect

- exponential backoff
- network regain で即時 retry
- stale UI は「古い snapshot」と分かる表示を可能にする
- reconnect 完了前に state color を断定しない

## 17.3 Terminal reconnect

terminal stream reconnect 時には画面内容の復元 strategy が必要。

候補優先順位:

1. tmux capture-pane snapshot + subsequent stream
2. daemon maintained minimal screen snapshot
3. raw stream only + clear/redraw

Source of Truth を daemon に移さないため、tmux capture を優先する。

---

# 18. Android モジュール構成案

```text
app/
core-model/
core-network/
core-designsystem/
feature-home/
feature-terminal/
feature-settings/
terminal-emulator/
terminal-view/
connection-ssh/
connection-direct/
notifications/
```

## core-model

```kotlin
enum class AgentStatus {
    BLOCKED,
    WORKING,
    DONE,
    IDLE,
    UNKNOWN
}

data class WorkspaceSummary(...)
data class TabSummary(...)
data class PaneRef(...)
```

## feature-terminal

- current workspace/tab
- tab switching
- stream lifecycle
- terminal View adapter
- extra-key pager
- CJK input

---

# 19. Security

必須:

- password の plain storage 禁止
- private key の plain export 禁止
- Android Keystore 利用
- host key verification を無効化しない
- SSH `StrictHostKeyChecking` 相当を初回確認付きで扱う
- daemon はデフォルトで public 0.0.0.0 bind しない
- WebSocket endpoint に認証無しで terminal input を許可しない

ログに含めない:

- SSH key
- token
- terminal 内 secret
- full environment dump

---

# 20. Accessibility

- state dot だけで状態を表現しない
- TalkBack 用 description
- minimum hit target を確保
- visual tab が 28〜32dp でも transparent hit area を拡大
- font scaling で破綻しない
- dynamic type 最大付近では tab names を ellipsize / horizontal scroll
- terminal zoom gesture または font size setting
- contrast を Material color role に従わせる

---

# 21. Internationalization

UI string は resource 化。

初期対象:

- Japanese
- English

Terminal content は翻訳しない。

CJK input は locale 依存処理を自前実装せず Android IME に任せる。

---

# 22. Performance 目標

Home:

- terminal stream 0 bytes
- state update は semantic event のみ
- card recomposition を workspace/tab 単位に限定

Terminal:

- raw stream decoding/rendering を UI main thread に集中させない
- burst output 時に backpressure を考慮
- rendering FPS より input latency を優先

目標感:

- key input → daemon send: perceptually immediate
- status event → Home update: < 500 ms on stable LAN/Tailscale
- workspace switch: terminal attach が 1 s を大きく超えない

---

# 23. Error UX

## Home

- Offline banner
- reconnecting indicator
- stale snapshot indication

## Terminal

- detached / reconnecting overlay
- terminal 内容自体は可能なら保持
- reconnect 失敗でも app 全体を modal で塞がない

## Settings

Test connection result:

- DNS failure
- auth failure
- host key mismatch
- daemon unavailable
- protocol mismatch

を分ける。

---

# 24. 実装フェーズ

## Phase 1 — UI shell

- Compose theme
- Home
- Settings
- Terminal frame
- compact top tabs
- Extra Keys pager
- dummy CJK TextField

## Phase 2 — terminal transport

- daemon prototype
- binary WS
- remote terminal backend
- resize/input/interrupt

## Phase 3 — semantic state

- sessions snapshot
- `/events`
- status mapping
- Home live update

## Phase 4 — SSH/Tailscale

- host profiles
- SSH key auth
- local tunnel
- optional direct tailnet mode

## Phase 5 — notifications / durability

- background push
- reconnect
- sequence gap recovery
- notification deep link

## Phase 6 — polish

- Material 3 Expressive motion
- accessibility
- tablet/adaptive layout
- performance tuning

---

# 25. MVP Acceptance Criteria

MVP 完了条件:

- [ ] Home で全 workspace/tab/status を確認できる
- [ ] Home の tab に fake selected state がない
- [ ] Workspace/tab タップで正しい Terminal が開く
- [ ] Terminal top bars が compact で terminal area を過剰に潰さない
- [ ] top bars の overflow を処理できる
- [ ] status dots が全画面で一貫する
- [ ] terminal ANSI rendering が正常
- [ ] resize が tmux pane に反映される
- [ ] direct ASCII input ができる
- [ ] Extra Keys が機能する
- [ ] CJK 事前入力 → 確定 → terminal send ができる
- [ ] Ctrl-C/interrupt ができる
- [ ] Home 時に terminal raw stream が閉じる
- [ ] reconnect 後に state が復元される
- [ ] done/blocked/failed 通知が動く
- [ ] Settings が M3 grouped list pattern に沿う
- [ ] SSH secret を安全に保存する

---

# 26. UI 設計上の未確定事項

以下は意図的に未確定とする。

## A. Terminal top bar の最終形

候補 1:

- compact 2-row
- variable width

候補 2:

- 2-row を 1 floating container に統合

候補 3:

- workspace selector をさらに圧縮し tab row を主役にする

評価軸:

- terminal usable area
- touchability
- switching speed
- overflow
- Material 3 visual grammar

## B. Bottom navigation 2 destination 問題

不要な destination は増やさない。
実用と M3 の推奨 item count を比較しながら最終判断。

## C. Direct Tailscale transport

MVP は SSH tunnel 優先。
必要になった段階で direct transport を実装。

---

# 付録A — Android Emulator を使った開発・テスト環境

Herdr Mobile は、開発中の大部分を **Android Emulator 上でテストする**ことを基本方針とする。実機ADBテストは、通知・IME・バックグラウンド動作・実際のネットワーク挙動など、エミュレータとの差分確認が必要になった段階で行う。

Android Studio、Android SDK、Android Emulator、platform-tools などの基本環境はインストール済みであることを前提とする。

## A.1 開発時の基本構成

```text
Ubuntu 26.04
├─ Android Studio
├─ Android SDK
├─ Android Emulator
│   └─ Herdr_API_36
├─ KVM
└─ herdr-mobile
    ├─ Android app
    └─ Linux上の開発用 herdr-mobile-daemon
```

通常の開発ループは次のようにする。

```text
コード変更
   ↓
Gradle build / Android Studio Run
   ↓
Android Emulator
   ↓
Herdr Mobile
   ↓
10.0.2.2
   ↓
Ubuntuホスト上の herdr-mobile-daemon
```

## A.2 SDKパス

Android Studioの標準構成ではSDKは通常以下にある。

```bash
~/Android/Sdk
```

現在のシェルでAndroid SDK関連コマンドを使えるようにする場合：

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"

export PATH="$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"
```

恒久化する場合は、使用しているshellに応じて `~/.bashrc` や `~/.zshrc` に追加する。

確認：

```bash
adb --version
emulator -version
```

## A.3 KVM確認

Android EmulatorはLinuxではKVMを使ってハードウェアアクセラレーションする。

確認：

```bash
emulator -accel-check
```

正常なら概ね以下のような内容になる。

```text
accel:
KVM (...) is installed and usable.
```

さらに：

```bash
ls -l /dev/kvm
```

ユーザーが `kvm` グループに入っているか確認：

```bash
groups
```

出力に

```text
kvm
```

が含まれていればよい。

### `kvm` に入っていない場合のみ

```bash
sudo usermod -aG kvm "$USER"
```

その後、一度ログアウトしてログインし直す。

一時的に現在のshellだけ反映させるなら：

```bash
newgrp kvm
```

再確認：

```bash
emulator -accel-check
```

## A.4 Emulator / AVDの作成

Android Studioから作成するのが最も簡単。

```text
Android Studio
→ Tools
→ Device Manager
→ Create Virtual Device
```

Herdr Mobileの標準テスト端末として、例えば以下を使用する。

```text
Device:
Pixel 8 相当

Android:
API 36

Architecture:
x86_64

Image:
Google APIs
```

AVD名は分かりやすく、

```text
Herdr_API_36
```

などとする。

作成済みAVDの一覧はCLIから確認できる。

```bash
emulator -list-avds
```

例：

```text
Herdr_API_36
```

## A.5 Emulator起動

基本：

```bash
emulator @Herdr_API_36
```

明示的にKVMとGPUアクセラレーションを使用：

```bash
emulator @Herdr_API_36 -accel on -gpu auto
```

普段はこれでよい。

### Snapshotを使わず完全起動

Snapshot周りで挙動がおかしい場合：

```bash
emulator @Herdr_API_36 \
  -accel on \
  -gpu auto \
  -no-snapshot-load
```

Android StudioではDevice Managerから **Cold Boot** を選んでもよい。

## A.6 Emulatorの確認

起動後：

```bash
adb devices
```

例えば：

```text
List of devices attached
emulator-5554	device
```

となれば正常。

複数AVDを起動している場合は、以後 `-s` で指定できる。

```bash
adb -s emulator-5554 shell
```

## A.7 Herdr MobileをEmulatorへ入れる

プロジェクトルート：

```text
/run/media/nico/d/学校/app/herdr-mobile
```

から、

```bash
./gradlew installDebug
```

これでDebug APKをビルドして、起動中のエミュレータへインストールできる。

ただし普段はAndroid Studioの

```text
Run ▶
```

を使う方が楽。

ターゲットとして

```text
Herdr_API_36
```

を選択する。

日常開発では基本的に、

```text
コード変更
→ Run
→ Emulatorで確認
```

でよい。

## A.8 ホストPCのdaemonへ接続する

Herdr Mobileではここが重要。

Android Emulator内で、

```text
127.0.0.1
localhost
```

は **UbuntuホストではなくAndroid Emulator自身** を指す。

Android EmulatorからホストPCへアクセスする場合、特殊アドレス

```text
10.0.2.2
```

を使用する。

例えばUbuntu側で開発用daemonを：

```text
127.0.0.1:8765
```

で起動している場合、Android側は：

```text
http://10.0.2.2:8765
```

へ接続する。

WebSocketなら：

```text
ws://10.0.2.2:8765/events
```

Terminal streamなら例えば：

```text
ws://10.0.2.2:8765/terminal/<pane-id>
```

となる。

したがって開発用設定として、

```text
Development host:
10.0.2.2
```

を用意しておくと便利。

## A.9 開発用cleartext通信

ローカル開発時に、

```text
http://
ws://
```

を使用する場合、Android側のcleartext制限に引っかかる可能性がある。

開発ビルドでは必要に応じて、

```xml
<application
    android:usesCleartextTraffic="true"
    ...>
```

を使用する。

ただしこれは **debug環境用** とし、本番通信の標準にはしない。

本番では、

```text
SSH tunnel
WSS
Tailscale
```

などの安全な通信経路を使用する。

理想的には `debug` manifest overlayだけでcleartextを許可する。

例：

```text
app/
├─ src/
│  ├─ main/
│  │  └─ AndroidManifest.xml
│  │
│  └─ debug/
│     └─ AndroidManifest.xml
```

## A.10 開発時の推奨ネットワーク構成

ローカル開発：

```text
Android Emulator
      │
      │ 10.0.2.2
      ▼
Ubuntu Host
      │
      ▼
herdr-mobile-daemon
      │
      ▼
Herdr / tmux
```

この構成なら、開発中はSSHやTailscaleを毎回経由しなくてもよい。

本番相当テスト時だけ：

```text
Android
   │
Tailscale / SSH
   │
Remote Linux
   │
herdr-mobile-daemon
   │
Herdr / tmux
```

へ切り替える。

## A.11 Logcat

アプリがクラッシュしたり、WebSocket接続などがおかしい場合：

```bash
adb logcat
```

大量に出るため、Herdr関連を簡易検索するなら：

```bash
adb logcat | grep -i herdr
```

アプリのprocess IDが分かる場合：

```bash
adb shell pidof <package-name>
```

そのPIDだけを見ることもできる。

```bash
adb logcat --pid=$(adb shell pidof -s <package-name>)
```

Android Studioの **Logcat** ウィンドウを使ってもよい。

## A.12 Emulator内のshell

```bash
adb shell
```

これでAndroid Emulator内部のshellへ入れる。

例：

```bash
getprop ro.build.version.release
```

```bash
getprop ro.build.version.sdk
```

ネットワーク確認：

```bash
ping 10.0.2.2
```

ただしAndroid側の環境によっては `ping` 等が制限される場合もある。

## A.13 APKの直接インストール

APKが既にあるなら：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`-r` は既存アプリを保持したまま更新する。

完全に削除して入れ直す場合：

```bash
adb uninstall <package-name>
```

その後：

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## A.14 Emulatorの終了

通常はウィンドウを閉じればよい。

CLIから終了：

```bash
adb -s emulator-5554 emu kill
```

## A.15 Emulatorがおかしくなった場合

まずCold Boot。

```bash
emulator @Herdr_API_36 -no-snapshot-load
```

ADBだけがおかしい場合：

```bash
adb kill-server
adb start-server
adb devices
```

それでも直らない場合はAVDのデータを初期化できる。

```bash
emulator @Herdr_API_36 -wipe-data
```

ただし `-wipe-data` は **そのAVD内部のアプリ・設定・データを全削除する**ため、最終手段とする。

## A.16 日常開発用の最短手順

基本的には以下だけ覚えておけばよい。

### Emulator起動

```bash
emulator @Herdr_API_36 -accel on -gpu auto
```

### 接続確認

```bash
adb devices
```

### アプリ更新

```bash
cd /run/media/nico/d/学校/app/herdr-mobile

./gradlew installDebug
```

またはAndroid Studioの：

```text
Run ▶
```

を使用する。

### daemon接続先

```text
10.0.2.2:<port>
```

これを基本開発ループとする。

## A.17 実機テストは後工程

基本UI、Terminal rendering、Workspace/Tab navigation、Settings、daemon protocolなどはAndroid Emulatorで開発する。

実機確認が特に重要になるのは以下。

- 日本語IME / CJK composition
- ソフトウェアキーボード表示時のTerminal resize
- Extra KeysとIMEの切り替え
- swipe gesture
- haptic feedback
- Notification
- アプリのbackground / foreground transition
- Doze
- ネットワーク切断・復帰
- Tailscale
- SSH
- 実際のモバイル回線
- Bluetooth / physical keyboard
- 高refresh rate
- メーカー固有Android挙動

この段階まではUSB debugging等の実機ADB環境を後回しにしてよい。

## A.18 sudoの扱い

通常のAndroid開発では以下を **sudoで実行しない**。

```bash
android-studio
```

```bash
adb
```

```bash
emulator
```

```bash
./gradlew
```

```bash
sdkmanager
```

SDKディレクトリも基本的にユーザー所有にする。

```text
/home/nico/Android/Sdk
```

sudoが必要になる可能性があるのは、主にOSレベルの設定変更のみ。

例：

```bash
sudo usermod -aG kvm "$USER"
```

KVMが正常に設定された後は、日常のEmulator利用にsudoは不要。

**`sudo emulator` や `sudo adb` は使用しない。**

---

# 付録 B — 公式仕様上の確認ポイント

2026-10 時点の Android 公式資料で確認した点:

- Compose Material 3 は Material component / color / typography / navigation を提供する
- `NavigationBarItem(alwaysShowLabel = false)` では非選択 item の label を隠し、selected 時は label を表示できる
- `NavigationBar` は 3〜5 destination 推奨
- `ShortNavigationBar` は small screen で 3〜5 item + EqualWeight 推奨
- Material 3 Expressive の `FloatingToolbar` API は 2026 年の Compose Material3 で non-experimental 化が進んでいる
- Linux Android Emulator の VM acceleration は KVM を使用する
- Ubuntu の実機 adb は `plugdev` と udev rules が必要になる場合がある

本仕様では Material 3 を「見た目の模倣」ではなく Compose の標準 component semantics を優先して使う。

---

# 27. 最終要約

Herdr Mobile の役割は明確に限定する。

```text
Home     = semantic overview
Terminal = on-demand deep interaction
Settings = connection / appearance / terminal behavior
```

Linux 側 Herdr/tmux が常に正。
Android は state を見せ、必要な時だけ terminal stream を開き、入力を返す。

UI は Material 3 Expressive を基準にしつつ、terminal という高密度用途に合わせて compact にする。
特に top workspace/tab navigation は厚みを抑え、variable-width または stacked container を比較する。

日本語入力は terminal grid に無理に IME composition を埋め込まず、Bottom panel の CJK pre-composition page で Android IME を正常に使い、確定後に terminal へ送る。

この構成を MVP の基準仕様とする。
