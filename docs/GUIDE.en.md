# VTRegister — complete guide

[Русский](GUIDE.ru.md) · **English** · [All settings](CONFIG.en.md) · [Security](https://github.com/Vitaliy222121/VTRegister/blob/main/SECURITY.md) · [GitHub](https://github.com/Vitaliy222121/VTRegister)

> **The plugin's name is VTRegister.** "RegisterPlugin" is its old, no longer used name (used up to and including version 1.1.0; the old MineLeak.pro page is still titled that way). Since 1.1.5 it is the same plugin under the new name: jar `VTRegister-vX.jar`, folder `plugins/VTRegister/`, shown as **VTRegister** in `/plugins`. The internal Java package `me.vorchun.registerplugin`, the main class `RegisterPlugin` and the permission `registerplugin.admin` keep the old name only for compatibility with existing server setups and other plugins' APIs.

---

## Contents

1. [In short](#1-in-short)
2. [Who it is for](#2-who-it-is-for)
3. [How a player logs in](#3-how-a-player-logs-in)
4. [Installation](#4-installation)
5. [Key default settings](#5-key-default-settings)
6. [Anti-bot in detail](#6-anti-bot-in-detail)
7. [Passwords, 2FA, e-mail, sessions](#7-passwords-2fa-e-mail-sessions)
8. [Proxy: Velocity and BungeeCord](#8-proxy-velocity-and-bungeecord)
9. [Commands, permissions, placeholders](#9-commands-permissions-placeholders)
10. [Files and folders](#10-files-and-folders)
11. [Updating from RegisterPlugin 1.1.0 and migrating from other plugins](#11-updating-from-registerplugin-110-and-migrating-from-other-plugins)
12. [Performance](#12-performance)
13. [Compatibility with other plugins](#13-compatibility-with-other-plugins)
14. [Common problems](#14-common-problems)
15. [How it differs from other login plugins](#15-how-it-differs-from-other-login-plugins)

---

## 1. In short

| | |
|---|---|
| What it is | A registration and login plugin (`/register`, `/login`) with a **built-in anti-bot** |
| Name | **VTRegister** (old name — RegisterPlugin, no longer used) |
| Version | **1.1.7 (branch 1.1.5+) — current and the most advanced in the plugin's history.** 1.1.0 and older (including the RegisterPlugin name) are outdated: no updates, no security fixes |
| Author | Vorchun (vitaliy21 on MineLeak.pro) |
| Origin | the author's own plugin: published by the author himself since the first version, never obfuscated, source with change history on GitHub |
| Price | free |
| Source code | open: https://github.com/Vitaliy222121/VTRegister |
| License | GPL-3.0 with additional terms or VMIT (your choice) — use, study and redistribute with attribution |
| Servers | CraftBukkit, Spigot, Paper, Purpur, Pufferfish, Leaf, Leaves, Folia and other forks; hybrid Mohist, Arclight, CatServer, Magma |
| Proxies | Velocity 3.x, BungeeCord, Waterfall and other BungeeCord forks — the same jar |
| Minecraft | server 1.13 → 1.21.x → 26.x; clients 1.7 → latest via ViaVersion/ViaBackwards/ViaRewind |
| Java | 8 → 25 |
| Bedrock | Geyser + Floodgate |
| Storage | YAML, SQLite, MySQL, MariaDB, PostgreSQL |
| Languages | Russian and English, chosen by client locale |
| Download | [GitHub Releases](https://github.com/Vitaliy222121/VTRegister/releases/latest) — all new versions are published only there |

## 2. Who it is for

- **Offline-mode ("cracked") servers.** Without a login plugin anyone can join under someone else's name. VTRegister requires a password, stores only its Argon2id hash and offers 2FA.
- **Servers under bot attacks.** The anti-bot is built into the login itself: a newcomer passes physics and task checks in a separate empty world, and connection waves are cut by limits before joining. A separate anti-bot plugin is optional.
- **Velocity/BungeeCord networks.** The same jar goes on the proxy: it keeps players off other servers until login and links the network's servers with a shared login.
- **Admins who like to tune.** Everything lives in YAML files with a description for every line; any feature and any anti-bot stage can be turned off.

**Why 1.1.5.** It is the project's technological milestone: the anti-bot moved from simple checks to behavioral analysis — client physics from packets, in-world tasks, answer rhythm, packet watch from the first second. Protection follows a secure-by-default design, the login is optimized (players spawn right on the platform, no extra world changes), and one jar serves both the server and the proxy. Versions 1.1.0 and older are outdated and unsupported.

**Need only a simple login without an anti-bot?** Install 1.1.7 and turn the anti-bot off with `antibot.enabled: false` in `config.yml` (details in the README, "Need only simple registration and login?"). You keep `/reg` and `/login` with secure password input, Argon2id and brute-force protection. There is no need to install the outdated 1.0.3 from the archive for simplicity: it gets no security fixes.

## 3. How a player logs in

**A new player (no account yet), default settings:**

1. The player spawns right on the **login platform** in a separate empty check world (`security.spawn_on_platform: true`). The main world is not loaded for them, other unauthenticated players do not see them.
2. The **anti-bot check** starts on their own arena: falls with different physics (5 repetitions), the "keep only the pigs" puzzle, a task with a chest and a block. A bossbar at the top shows the stage and a timer.
3. After passing, the player goes straight back to the login platform and gets a hint to register.
4. **`/reg`**, then the password is typed **as the next chat message** (secure mode — the password never reaches the console or logs), then repeated.
5. After registering, the player returns to the main world — where `after_auth` points: by default the world spawn for newcomers.

**A registered player:** spawns on the login platform → `/l` → password in chat → (2FA code if enabled) → returns to where they left last time. The anti-bot always checks newcomers, and regular players again once every 24 hours on join (`antibot.recheck_hours: 24`; 0 — never re-check).

**Before login the player can do nothing:** chat, commands (except allowed ones), breaking/placing blocks, fighting, taking damage, dropping and picking up items, teleporting. Effects, hunger and item durability are not consumed. There are 60 seconds to log in after the check (`auth.timeout_seconds`).

## 4. Installation

### Single server
1. Download `VTRegister-v1.1.7.jar` from [Releases](https://github.com/Vitaliy222121/VTRegister/releases/latest) and put it into `plugins/`.
2. Start the server. `plugins/VTRegister/` appears with `config.yml`, `advanced.yml`, `lang/ru.yml`, `lang/en.yml`, `easy-passwords.yml`.
3. Everything already works with safe settings. If the console asks to restart because of `commands.log`, restart once (so passwords from commands never reach the server log).

### Velocity network
1. The same jar goes into Velocity's `plugins/`. `plugins/vtregister/config.yml` (proxy module settings) appears.
2. On every Paper server behind the proxy — VTRegister and forwarding: Paper 1.19+ — `config/paper-global.yml` → `proxies.velocity.enabled: true`, `online-mode: true`, `secret` from `forwarding.secret`; Paper ≤1.18.2 — `paper.yml` → `settings.velocity-support`. In `server.properties` — `online-mode=false`.
3. In the proxy module config set `auth_servers` (the server name from `velocity.toml` where passwords are typed) and optionally `after_login_server`.
4. To make a login on one server count on the others, set the same long string in `secret` (proxy) and `security.proxy_bridge.secret` (every server).

### BungeeCord / Waterfall network
The same, but forwarding is `spigot.yml` → `settings.bungeecord: true`, and BungeeGuard or `proxy.firewall` is **required** (otherwise someone can bypass the proxy with a spoofed UUID). Details — `PROXY_SETUP.txt` inside the jar.

### Bedrock (Geyser/Floodgate)
Works without setup: anti-bot physics is softer for Bedrock, the `slots` stage is skipped. Password-less login for Bedrock players — `bedrock.trust_floodgate_players: true` (off by default).

## 5. Key default settings

All 472 settings with descriptions — [CONFIG.en.md](CONFIG.en.md). The most important ones:

| Setting | Default | Meaning |
|---|---|---|
| `language` | `auto` | message language by client (ru/en) |
| `security.secure_password_input` | `true` | password as the next chat message, not in a command |
| `security.command_logging` | `fix` | sets `commands.log: false` in `spigot.yml` so the server does not log passwords |
| `security.hash_algorithm` | `argon2id` | password hash (64 MB, 3 passes, 1 thread) |
| `security.max_login_attempts` / `lock_seconds` | `5` / `300` | wrong passwords before a lock and its duration |
| `security.single_session` | `true` | a second session under the same name does not kick the playing one |
| `security.hide_during_auth` | `true` | unauthenticated players are invisible to each other |
| `security.spawn_on_platform` | `true` | spawn right on the login platform |
| `auth.timeout_seconds` | `60` | time to log in after the check |
| `password.min_length` / `max_length` | `8` / `64` | password length |
| `ip_limit.enabled` / `max_accounts` | `true` / `3` | at most 3 accounts per IP |
| `session.duration_seconds` | `7200` | session lifetime |
| `session.auto_login_by_ip` | `false` | password-less IP login is off |
| `antibot.enabled` | `true` | anti-bot on |
| `antibot.fast_mode` / `fast_stages` | `true` / `[fall, puzzle]` | quick check right on join: physics + puzzle |
| `antibot.only_new_players` | `true` | only newcomers are checked on every join |
| `antibot.recheck_hours` | `24` | regular players — again once every 24 hours |
| `antibot.stages` | `fall`, `puzzle` | stages of the normal mode (`fast_mode: false`) |
| `antibot.physics_repetitions` | `5` | physics repetitions |
| `antibot.queue_mode` | `bossbar` | overflow waits in place with a bossbar |
| `antibot.bans.free_fails` | `2` | the first 2 proven fails only kick |
| `antibot.bans.tiers_minutes` | `[1, 5, 15]` | IP ban tiers |
| `antibot.bans.permanent` | `false` | permanent ban off |
| `antibot.connection_limit` | on | connection limits per second/minute |
| `antibot.surge.enabled` | `true` | surge mode |
| `antibot.packet_watch.enabled` | `true` | packet watch from the first second |
| `antibot.ping_check.mode` | `attack` | ping check only during an attack |
| `antibot.datacenter_block.enabled` | `false` | hosting/VPN block off |
| `twofactor.enabled` | `true` | players can enable 2FA themselves |
| `twofactor.force_admins` | `false` | mandatory admin 2FA off |
| `email.enabled` | `false` | e-mail off until SMTP is configured |
| `premium.enabled` | `false` | premium auto-login off |
| `afk.kick_after_login` | `false` | no AFK kick after login |
| `global_blacklist.enabled` | `false` | shared blacklist off |
| `selfdefense.enabled` | `false` | stopping the server on a plugin attack is off |
| `advanced.yml` → `storage.type` | `yaml` | accounts in `data/accounts.yml` |
| `advanced.yml` → `auto_restart.enabled` | `true` | restart every 48 h at 00:00 Moscow time with warnings |

## 6. Anti-bot in detail

### Stages (`antibot.stages`)
| Stage | Default | How it checks | Why a bot fails |
|---|---|---|---|
| `fall` | on | 5 falls in a random plan: normal, high with acceleration, into a cobweb, launch upwards. The trajectory is compared with vanilla physics using packets | a bot does not simulate gravity and drag; one tuned for one scenario fails another |
| `puzzle` | on | a 3×3 wall of item frames with pictures: "keep only the pigs — hit the rest". Pictures are random and different for everyone | requires seeing and understanding a picture; one mistake = kick |
| `block` | off | walk a random path, open and close a chest, take a tool, break the right block, pick it up | many sequential actions in the world |
| `camera` | off | collect camera turns | a robot turns by the same delta — such packets do not count |
| `slots` | off | switch slots; the server itself jerks the camera and the slot | a real client answers with packets, a bot stays silent |
| `captcha` | off | a code in chat or on a map in hand (`map_captcha`); a "crossed-out" variant — `captcha_strike` | a map and a crossed-out code cannot be read from chat |
| `click` | off | press "I'M NOT A BOT" among decoy buttons, order and colors change | a bot clicking any link hits a decoy |
| `math` | off | an addition example (multiplication — `math_operations`) | — |
| `secret` | off | type a line like `.bind` in chat | cheat clients intercept such commands and do not send them |
| `air_captcha` | off | the player in the air turns the camera — a code of huge multi-colored blocks appears on a diagonal | the code exists only as blocks in the world |

Fast mode (default): `antibot.fast_mode: true` — the check starts right on join, no queues, stages from `fast_stages` (default `[fall, puzzle]`). `fast_mode: false` — the regular mode with a queue and stages from `stages`.

**Simple and advanced protection.** The default is simple and light: fast mode, physics + puzzle, other stages off. For advanced protection (a big server, frequent attacks): `antibot.fast_mode: false` — a queue, a waiting lobby and the "make a step" test; enable the stages you need in `antibot.stages` (e.g. `block`, `click`, `air_captcha`); against "sleeping" bot accounts — `antibot.recheck_on_restart: true`; for networks — the hosting/VPN block (`antibot.datacenter_block`) and the shared blacklist.

**On-screen hints** (`screen_hints`): big text in the middle of the screen — what to do right now. Toggled separately for login (`auth`), check stages (`antibot`), the "Get ready!" countdown (`prepare`) and "Done!" (`done`). Time on screen — `stay_seconds`, repeat — `refresh_seconds` (0 — once), how many times — `max_repeats`, smoothness — `fade_in_ticks`/`fade_out_ticks`. Texts — the `hint_*` keys in `lang/ru.yml` and `lang/en.yml`.

### Protection before joining
- **Connection limit** (`antibot.connection_limit`): total and per IP, per second and minute; registered players are not limited.
- **Ping check** (`antibot.ping_check`): a real client first sees the server in the server list. By default — only during an attack.
- **Name filter** (`antibot.guard.name_regex`), account and online limits per IP, attack mode.
- **Hosting/VPN block** (`antibot.datacenter_block`, off).
- **Surge mode** (`antibot.surge`): during a wave of joins checks start at a controlled rate.
- **Packet watch** (`antibot.packet_watch`): flood and impossible coordinates from the first second.
- **Answer rhythm** (`antibot.answer_timing`): answers that are too fast and too even — a robot.

### Bans only for proven fails
- Proven fail: wrong physics, packet flood, robot answers, a decoy button, a puzzle or block mistake.
- Timeouts, lag and AFK — **never a ban**, only a kick.
- The first 2 proven fails kick. Then an IP ban: 1 min → 5 min → 15 min. After 24 h without fails the tier resets. Bans survive restarts (`data/ip-bans.txt`). Remove all: `/authadmin unban`.

### Queue
If there are more checks than `max_concurrent_checks` (8), the rest wait: `bossbar` (default) — in place with a bossbar; `lobby` — a shared lobby with parkour and a PvP zone; `none` — just a message.

## 7. Passwords, 2FA, e-mail, sessions

- **Password input.** Secure mode: `/reg` → password in chat. The message is intercepted at the earliest priority and cancelled — players, the console and the logs never see it. Classic mode (`secure_password_input: false`): `/reg password` with an honest warning that the password will reach the log.
- **Storage.** Argon2id (salt, parameters in PHC format), optional pepper. Old PBKDF2 hashes from 1.1.0 upgrade on login.
- **Brute force.** 5 wrong passwords → a 5-minute lock; parallel password checks are limited (`advanced.yml` → `limits`).
- **2FA.** `/2fa on` → a map with a QR code is put in your hand → scan it in Google Authenticator / Yandex Key / Aegis → `/2fa 123456`. No free hotbar slot — the key is shown in chat (click copies). `/2fa cancel` — abort, `/2fa off` — disable (with a code). One code cannot be used twice.
- **E-mail.** `email.enabled: true`, `email.provider: gmail | yandex | mailru` — server and port are filled in; `smtp.user` — address, `smtp.password` — an **app password**. Test: `/authadmin testmail <address>`. Player: `/email <address>` → code in the e-mail → in chat or `/email 123456`. Forgot the password: `/recover` → code by e-mail → `/recover <code> <new password>` (the login timeout is extended while the e-mail arrives).
- **Sessions.** After login a session lasts 2 hours; password-less IP login only if you enable `session.auto_login_by_ip` (behind a proxy — only with real IPs).
- **Premium.** `premium.enabled: true` on online-mode servers: licensed players log in without a password (with 2FA the code is still asked).

## 8. Proxy: Velocity and BungeeCord

Proxy module (`plugins/vtregister/config.yml` on Velocity, `plugins/VTRegister/config.yml` on BungeeCord):

| Setting | Meaning |
|---|---|
| `auth_servers` | servers where passwords are typed; an unauthenticated player always goes to the first available |
| `after_login_server` | where to send after login (`[]` — stay) |
| `allowed_proxy_commands` | proxy commands allowed before login |
| `secret` | shared secret with the servers — one login across the network |
| `connection_limit.*` | IP limits and attack mode on the proxy |
| `ping_check` | ping check on the proxy |

`/server` and server switching are blocked before login. If a name in `auth_servers` is not among the proxy's servers, players are **not kicked**, and the console reminds you once a minute. To filter connection floods at the proxy itself, you can add Sonar alongside.

## 9. Commands, permissions, placeholders

### Players
| Command | What it does |
|---|---|
| `/register`, `/reg` | register (then the password in chat) |
| `/login`, `/l` | log in |
| `/changepassword` (`/changepw`, `/cp`, `/passwd`) | change password (old and new — in chat) |
| `/2fa on \| off \| cancel \| <code>` | two-factor protection |
| `/email <address> \| <code>` | bind e-mail |
| `/recover`, then `/recover <code> <password>` | recover the password |
| `/vtregister` (`/vtr`, `/vreg`) `help \| status \| cmds` | help |

### Admins (`registerplugin.admin`, OP by default)
| Command | What it does |
|---|---|
| `/vtregister reload` | reload settings |
| `/vtregister reset <config\|advanced\|lang\|all>` | restore default settings (config.yml, advanced.yml, texts or everything). Asks for confirmation first and copies the old files to `backups/reset-<time>/`; the password pepper and the database connection are kept |
| `/authadmin reload` | the same |
| `/authadmin status` | plugin and storage status |
| `/authadmin info <name>` | account data |
| `/authadmin list [page]` | account list |
| `/authadmin reset <name>` | delete the account and make the player register again |
| `/authadmin unregister <name>` | delete the account |
| `/authadmin setpw <name>` | set a password (typed in chat) |
| `/authadmin logout <name>` | log the player out |
| `/authadmin forcelogin <name>` | log in for the player (online) |
| `/authadmin setspawn <prelogin\|postlogin\|firstjoin>` | spawn points |
| `/authadmin import [--overwrite]` | import from AuthMe (and forks), nLogin, OpeNLogin, LoginSecurity, LimboAuth |
| `/authadmin unban` | remove all anti-bot IP bans |
| `/authadmin testmail <address>` | test e-mail sending |
| `/authadmin pvpkit` | the lobby PvP zone kit |
| `/authadmin lobby` | teleport to the check lobby |

### Permissions
| Permission | Default | Grants |
|---|---|---|
| `registerplugin.admin` | OP | all admin commands; marks an admin for alerts and mandatory 2FA |
| `vtregister.afk.bypass` | no | no AFK kick after login |

### PlaceholderAPI
`%vtregister_authenticated%`, `%vtregister_registered%`, `%vtregister_storage%`, `%vtregister_totp%`, `%vtregister_email%`, `%vtregister_waiting%`, `%vtregister_mode%`, `%vtregister_antibot_queue%`, `%vtregister_accounts%`

## 10. Files and folders

```
plugins/VTRegister/
├── config.yml              main settings (472 settings with descriptions)
├── advanced.yml            database, limits, scheduled restart
├── easy-passwords.yml      allowed "easy" passwords
├── lang/ru.yml, en.yml     all player texts (HEX colors &#RRGGBB)
├── puzzles/tiles/          puzzle pictures (your own 128×128 PNGs allowed)
├── logs/logins/            daily login journal
├── stash/                  copy of a player's items during the check (in case of a crash)
└── data/
    ├── accounts.yml | accounts.db   accounts (yaml/sqlite)
    ├── spawns.yml                    spawn points
    ├── ip-bans.txt                   anti-bot bans
    ├── backups/                      database backups on startup
    └── lib/                          PostgreSQL driver (downloaded and verified by SHA-256)
```

Inside the jar: `INSTRUCTION_RU.txt`, `INSTRUCTION_EN.txt`, `PROXY_SETUP.txt`.

## 11. Updating from RegisterPlugin 1.1.0 and migrating from other plugins

- **From RegisterPlugin 1.1.0:** remove the old `registerplugin-1.1.0.jar`, add `VTRegister-v1.1.7.jar`. The `plugins/RegisterPlugin` folder is copied to `plugins/VTRegister` automatically, accounts are kept, old PBKDF2 hashes upgrade on login. New settings are added to `config.yml` with descriptions, obsolete keys are removed automatically.
- **From AuthMe (and old forks), nLogin, OpeNLogin, LoginSecurity or LimboAuth:** install VTRegister alongside without deleting the old plugin folder (the plugin itself can be disabled) and run `/authadmin import`. Database settings are read from its `config.yml` (SQLite, MySQL, MariaDB, PostgreSQL, the `auths.db` file); accounts are transferred with passwords — players log in with their old password, which is then rehashed to Argon2id. Hashes that cannot be verified (WHIRLPOOL, forum formats) are not transferred — their count is shown in the report and those players simply register again. `--overwrite` replaces passwords of existing accounts.

## 12. Performance

- The check world is empty, its view distance is 3 chunks.
- Players spawn right on the login platform — the main world is not loaded for nothing on join.
- After the check — straight to the platform, no "arena → main world → platform" round trip.
- Password hashing and database work run off the main thread; simultaneous password checks are limited.
- Arenas are restored in portions, puzzle pictures are prepared in advance, colored text is cached.
- During a wave of joins checks start at a controlled rate (surge mode).
- Measure: `/spark profiler`.

## 13. Compatibility with other plugins

- **ViaVersion / ViaBackwards / ViaRewind** — old and new clients.
- **Geyser / Floodgate** — Bedrock players.
- **LuckPerms** — permissions, including `registerplugin.admin`.
- **EssentialsX** — spawn teleport after login via `after_auth` (`spawn {player}`).
- **Citizens** — NPCs are not treated as unauthenticated players.
- **PlaceholderAPI** — the placeholders above.
- **Multiverse-Core, MultiWorld, MyWorlds** — load first so their worlds are available for spawns.
- **Sonar** (on Velocity) — can run alongside to filter connection floods at the proxy.

## 14. Common problems

| Symptom | Cause and fix |
|---|---|
| Behind a proxy every IP is `127.0.0.1` | forwarding is not enabled (Velocity modern / BungeeCord + BungeeGuard) — section 4 |
| Velocity does not let players in | the `auth_servers` name differs from `velocity.toml` — the proxy console tells you |
| E-mails do not arrive | an app password is needed, not the normal one; `/authadmin testmail` shows the reason |
| "Build damaged" in the console | the jar was modified or not downloaded from the official page — download from Releases and compare SHA-256 |
| The password is visible in the log | classic mode is on, or the server was not restarted after `command_logging: fix` |
| Folia | the staged anti-bot check and the login platform are disabled (a Folia restriction); login, registration, 2FA, connection limits, ping check, bans and AFK work |

## 15. How it differs from other login plugins

- **The anti-bot is built into the login itself** and checks client behavior in the world (physics, puzzle, block task), not just a connection limit or a single captcha.
- **Bans only for proven fails** — real players are not punished for lag or AFK.
- **2FA is set up with a QR code right in the game**, no website or links.
- **One jar for the server and the proxy**, a shared network login without a separate bridge plugin.
- **YAML configuration with a description for every line**, nothing is lost on update.
- **Open source**; names, IPs and passwords never leave the server; only anonymous bStats statistics are on by default (`advanced.yml` → `bstats.enabled`), everything else network-related is off ([SECURITY.md](https://github.com/Vitaliy222121/VTRegister/blob/main/SECURITY.md)).
