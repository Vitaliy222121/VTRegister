# VTRegister — all settings and their defaults

[Русский](CONFIG.ru.md) · **English**

Generated automatically from the configuration files inside the jar, so the default values always match the release. Every file is created in `plugins/VTRegister/` on first start; after an update, missing settings are added with their descriptions and your values are kept.

[Back to the full guide](GUIDE.en.md)

## config.yml — main settings

| Setting | Default | What it does |
|---|---|---|
| `language` | `auto` | Message language: auto (by client locale), ru, en |

### `session`

Session (auto-login without a password)

| Setting | Default | What it does |
|---|---|---|
| `session.duration_seconds` | `7200` | How long a trusted session lasts after login, in seconds (7200 = 2 hours) |
| `session.auto_login_by_ip` | `false` | Auto-login when the player's IP matches a trusted one. ⚠️ Dangerous behind a proxy without IP forwarding — see proxy.known_proxy_ips |
| `session.ip_prefix_octets` | `0` | IP match for auto-login by prefix: 0 = exact, 3 = /24 (dynamic IPs), 4 = exact. 1–2 are raised to 3. IPv6 with a prefix matches /64. Compared only with the account's last IP. ⚠️ Weakens binding: any client in the same subnet gets the session |
| `session.invalidate_on_restart` | `true` | A server restart ends IP sessions: everyone logs in again after a reboot (stops bot reconnect spam). false — sessions survive restarts within duration_seconds |

### `auth`

Authentication

| Setting | Default | What it does |
|---|---|---|
| `auth.timeout_seconds` | `60` | Seconds a player has to log in / register AFTER the anti-bot check |
| `auth.kick_on_timeout` | `true` | Kick the player if they did not log in in time |
| **`auth.reminder`** | — | "Type /login" reminders while the player is not logged in |
| `auth.reminder.enabled` | `true` | reminders (ActionBar) until the player logs in |
| `auth.reminder.interval_seconds` | `2` | how often |
| `auth.reminder.send_chat` | `true` | also send them to chat |
| `auth.reminder.chat_interval_seconds` | `10` | How often to repeat the reminder in chat (seconds); 0 = same as interval_seconds |

### `screen_hints`

Hints in the middle of the screen: what the player should do right now — register, log in, type the password, pass a check stage. For those who do not read chat. Texts are in lang/ru.yml and lang/en.yml (hint_* keys)

| Setting | Default | What it does |
|---|---|---|
| `screen_hints.enabled` | `true` | master switch for all on-screen hints |
| `screen_hints.auth` | `true` | registration / login / "type your password" |
| `screen_hints.antibot` | `true` | anti-bot check stages: "Check 1/2 — what to do" |
| `screen_hints.prepare` | `true` | the "Get ready!" countdown before the check |
| `screen_hints.done` | `true` | "Done!" after logging in |
| `screen_hints.stay_seconds` | `3` | how many seconds the text stays on screen (1–30) |
| `screen_hints.refresh_seconds` | `3` | how often to show it again until the step is done (0 — once) |
| `screen_hints.max_repeats` | `0` | how many times to show one hint (0 — until the step is done) |
| `screen_hints.fade_in_ticks` | `8` | fade-in in ticks (20 = 1 s); repeats appear without blinking |
| `screen_hints.fade_out_ticks` | `10` | fade-out in ticks |

### `password`

Passwords

| Setting | Default | What it does |
|---|---|---|
| `password.min_length` | `8` | minimum length |
| `password.max_length` | `64` | maximum length |
| `password.enforce_strength` | `true` | check strength (different character types) |
| `password.allow_easy_passwords` | `false` | Allow passwords from easy-passwords.yml (even simple/short ones) |
| `password.require_confirm` | `false` | Ask to repeat the password on registration/change (secure mode) |

### `ip_limit`

Account limit per IP. Protection against multi-accounts and bots: no more than max_accounts accounts per IP (counts accounts created from this IP or last joined from it). A new nickname over the limit is refused on join and on /register; existing accounts can always log in. Does not work behind a proxy without real-IP forwarding (everyone has the same address). Mobile internet often gives one IP to many people — if newcomers complain about being refused, raise max_accounts

| Setting | Default | What it does |
|---|---|---|
| `ip_limit.enabled` | `true` | turn the limit on |
| `ip_limit.max_accounts` | `3` | how many accounts are allowed per IP |

### `security`

Security

| Setting | Default | What it does |
|---|---|---|
| **`security.login_log`** | — | Login journal: plugins/VTRegister/logs/logins/YYYY-MM-DD.log (successful logins and failed attempts: name, UUID, IP) |
| `security.login_log.enabled` | `true` | turn the login journal on/off |
| `security.login_log.keep_days` | `30` | how many days to keep journals |
| **`security.admin_alerts`** | — | Alert when an admin logs in from a NEW IP (Discord and/or Telegram) |
| `security.admin_alerts.enabled` | `false` | turn admin alerts on/off |
| `security.admin_alerts.permission` | `"registerplugin.admin"` | who counts as an admin (plus all OPs) |
| `security.admin_alerts.server_name` | `"myserver"` | server name shown in the alert |
| `security.admin_alerts.on_first_login` | `false` | also alert on the very first login |
| `security.admin_alerts.discord_webhook` | `""` | Discord webhook URL (Channel settings → Integrations) |
| `security.admin_alerts.telegram_bot_token` | `""` | bot token from @BotFather |
| `security.admin_alerts.telegram_chat_id` | `""` | chat/channel ID to send to |
| `security.secure_password_input` | `true` | ★ SECURE password input: true — the password is typed as the NEXT CHAT MESSAGE (never reaches logs); false — classic /reg <password> (the password reaches the console and logs!). The mode is ALWAYS shown to the player and cannot be hidden |
| `security.command_logging` | `"fix"` | What to do with server command logging (spigot.yml → commands.log). The server logs "name issued server command: /reg password" BEFORE plugins see the event, so the only fix is: fix — the plugin sets commands.log: false (server restart needed); warn — only warn in the console; off — do nothing |
| `security.log4j_filter` | `true` | Extra log4j filter: removes lines with our commands from the logs |
| `security.confirm_password_in_command` | `true` | Confirm risky input: the first command with a password is blocked and the player is asked to repeat it if they accept the risk |
| `security.confirm_timeout_seconds` | `30` | seconds to wait for the repeated password on registration |
| `security.password_input_timeout_seconds` | `25` | Seconds to wait for a password/code typed in chat (5–120) |
| `security.max_login_attempts` | `5` | Maximum wrong login attempts before a lock |
| `security.lock_seconds` | `300` | How long to lock after all attempts are used (seconds) |
| `security.max_trusted_ips` | `3` | How many trusted IPs to keep per account |
| `security.pbkdf2_iterations` | `200000` | PBKDF2 iterations (50000–2000000). More = stronger but slightly slower login |
| `security.hash_algorithm` | `"argon2id"` | Password hashing algorithm: argon2id (recommended) \| pbkdf2 |
| `security.argon2_memory_kib` | `19456` | Argon2id parameters: memory in KiB, iterations, threads. Default is the OWASP recommendation (19456 KiB = 19 MB, 2 passes): ~45 ms of CPU per login and little garbage for the GC — the server does not stutter when many players join at once. Stricter: 65536 and 3 (about 5x heavier). Old hashes with other parameters keep working. |
| `security.argon2_iterations` | `2` | Argon2id passes (more = stronger and slower) |
| `security.argon2_parallelism` | `1` | Argon2id threads per hash |
| `security.password_pepper` | `""` | Secret additive (pepper). ⚠️ Changing/removing it breaks existing passwords! |
| `security.admin_setpw_timeout_seconds` | `30` | Timeout for the new password in /authadmin setpw (seconds) |
| `security.hide_during_auth` | `true` | Hide unauthenticated players: they see nobody and nobody sees them |
| `security.auth_darkness` | `auth_only` | Darkness/blindness before login: always = always (old behavior), auth_only = only during reg/login (the queue lobby stays bright — recommended), never = no effect at all |
| `security.auth_platform` | `true` | Login platform in the check world (only with the anti-bot on and no prelogin spawn): unauthenticated players stand on a separate platform (hidden from each other, frozen). After login the player returns to where they joined. World unavailable — the player stays in place |
| `security.spawn_on_platform` | `true` | Spawn right on the login platform (no teleport after joining): the server does not load the main world for a player who is moved away at once. Less load on every join. false — the old way (teleport after join) |
| `security.protect_inventory_on_death` | `true` | Inventory protection: items and XP are kept if the player dies before login |
| `security.single_session` | `true` | One session per name (offline-mode): if a player with this name is already online and logged in, the new connection is refused and the playing one is NOT kicked |
| **`security.proxy_bridge`** | — | Bridge with the VTRegister proxy module (the same jar on Velocity/BungeeCord), see PROXY_SETUP.txt section 6 |
| `security.proxy_bridge.enabled` | `true` | send AUTH/LOGOUT to the proxy (only works behind a proxy) |
| `security.proxy_bridge.secret` | `""` | shared secret = secret in the proxy module config.yml; empty — login is not carried between servers |
| `security.proxy_bridge.trust_network_login` | `true` | login without a password on a signed TRUST from the proxy (the player already logged in on another network server) |
| **`security.integrity`** | — | Jar integrity check (broken archives, repacks). strict: true — a damaged build does not start. Do not disable |
| `security.integrity.strict` | `true` | refuse to start a damaged build |
| `security.integrity.interval_seconds` | `300` | how often to re-check integrity (seconds) |

### `antibot`

Multi-stage anti-bot check. The player goes to a separate EMPTY world (zero load) and passes stages. Reminders and the login timeout are paused during the check

| Setting | Default | What it does |
|---|---|---|
| `antibot.enabled` | `true` | main anti-bot switch |
| `antibot.fast_mode` | `true` | SIMPLE SETUP — these lines are usually enough. Fast anti-bot mode: true (default) — the check starts RIGHT on join: no queues, no afk_first, no lobby; stages from fast_stages. Minimal waiting for the player and minimal server load; false — regular mode: queue (queue_mode), the "take a step" test (afk_first), stages from stages below |
| `antibot.fast_stages` | `[fall, puzzle]` | Fast-mode stages in order. Choose from: fall (fall physics), camera (camera turn), slots (slots + camera jolts), captcha (code), click (click a message), puzzle (remove extra pictures), math (example), secret (cheat-command test), block (path + chest + break a block), air_captcha (block code in the air). Default physics + puzzle ≈ 30–40 s for a human. Empty [] — use stages below |
| `antibot.fast_max_concurrent` | `50` | How many players are checked at once (each has their own arena in the empty world). Others wait in place with a bossbar timer — no lobby |
| `antibot.recheck_hours` | `24` | Re-check of regular players: an already registered player passes the check again on join if this many hours passed since the last one (24–48 is reasonable). Protects against "sleeping" bot accounts. Players online longer than that are not kicked — the check happens on their next join. 0 — never re-check regular players |
| `antibot.only_new_players` | `true` | Check only new players (no account yet) — regular players are re-checked by the recheck_hours period. false — everyone on every join |
| `antibot.world_name` | `"auth_verify"` | Check world (created automatically; the staged check does not run on Folia) |
| `antibot.empty_inventory` | `true` | During the check AND in the queue lobby real items go to a stash (with a copy in plugins/<plugin>/stash/ in case of a crash) and come back on any exit. false — the player keeps their items in the lobby, a PvP death drops nothing |
| `antibot.restore_player_state` | `true` | Restore the player's game mode/flight after the check |
| `antibot.max_concurrent_checks` | `8` | Limit of simultaneous checks — the rest wait in the queue |
| `antibot.arena_spacing` | `32` | Distance between arenas (each player has their own area) |
| `antibot.queue_mode` | `"bossbar"` | Check queue mode when checks exceed max_concurrent_checks: none — the player stands still with a position message; bossbar — as none + a bossbar with position and progress; lobby — a shared waiting platform with parkour (easy/medium/hard), bossbar and hologram. In the lobby: no commands, no fighting, no dying, no falling into the void |
| `antibot.lobby_first_seconds` | `8` | Everyone first waits N seconds in the lobby (parkour, PvP arena) and then goes to the check — even with free slots. 0 = old behavior |
| `antibot.queue_max_size` | `0` | Queue size limit (0 = unlimited). Overflow — a polite kick |
| `antibot.queue_chat_allowed` | `false` | Allow chat in the queue (seen only by other waiting players). false — chat closed |
| **`antibot.queue_chat_filter`** | — | Queue lobby chat filter: anti-advertising, cooldown, word limit |
| `antibot.queue_chat_filter.enabled` | `true` | turn the whole filter on/off |
| `antibot.queue_chat_filter.cooldown_ms` | `1500` | minimum pause between a player's messages |
| `antibot.queue_chat_filter.max_words` | `12` | maximum words per message |
| `antibot.queue_chat_filter.block_links` | `true` | block links/domains/IPs (anti-advertising) |
| `antibot.queue_chat_filter.block_repeat` | `true` | block repeating the same message |
| `antibot.queue_spam_seconds` | `5` | How often to remind waiting players to watch the bossbar (seconds) |
| `antibot.queue_hologram` | `true` | Hologram above the platform with the queue counter |
| `antibot.queue_parkour` | `true` | Build parkour in the lobby (easy/medium/hard) — safe, no deaths |
| `antibot.queue_hide_players` | `false` | Hide queue players from each other (no seeing, no pushing) |
| `antibot.afk_first` | `true` | AFK first: when the queue lobby is OFF (queue_mode: none/bossbar), a new player first gets an AFK check (take a step), then the anti-bot chain. A bot that never moves is kicked by afk.initial_timeout before reaching the arenas |
| `antibot.queue_flight` | `false` | Allow flight in the queue lobby (waiting players may fly) |
| `antibot.queue_kick_flyers` | `true` | Kick waiting players who fly WITHOUT server permission — a cheat (3 ticks in a row) |
| `antibot.queue_build_lobby` | `true` | Build the standard lobby platform. false — place your own schematic in the check world at the platform center (see INSTRUCTION_EN.txt) |
| **`antibot.queue_pvp`** | — | PvP zone in the queue lobby: a loot chest refills on a timer, fights only inside the zone (set by corner coordinates) |
| `antibot.queue_pvp.enabled` | `true` | 43x43 arena next to the south side of the lobby (gate in the wall) |
| `antibot.queue_pvp.corner1_x` | `21` | first zone corner (coordinates in the check world) |
| `antibot.queue_pvp.corner1_z` | `-484` | Z of the first PvP zone corner |
| `antibot.queue_pvp.corner2_x` | `-21` | opposite corner |
| `antibot.queue_pvp.corner2_z` | `-442` | Z of the second PvP zone corner |
| `antibot.queue_pvp.chest_x` | `0` | where the loot chest stands |
| `antibot.queue_pvp.chest_z` | `-463` | Z of the PvP zone loot chest |
| `antibot.queue_pvp.chest_seconds` | `15` | how often the chest refills |
| `antibot.queue_pvp.kit_enabled` | `true` | Double-chest kit: a virtual inventory per player, every N seconds. The admin edits its contents in game: /authadmin pvpkit |
| `antibot.queue_pvp.kit_cooldown_seconds` | `30` | pause between kit handouts (seconds) |
| `antibot.queue_pvp.admin_modify` | `true` | the admin may change the PvP kit (/authadmin pvpkit) |
| `antibot.queue_pvp.admin_lobby_tp` | `true` | the admin may teleport into the lobby |
| `antibot.queue_pvp.protect_functional` | `true` | protect lobby buttons and chests from breaking |
| `antibot.queue_pvp.items` | `["NETHERITE_CHESTPLATE:PROTECTION:4", "DIAMOND_SWORD:SHARPNESS:1", "GOLDEN_APPLE:25"]` | Loot items: "MATERIAL[:ENCHANTMENT:LEVEL][:AMOUNT]" |
| `antibot.queue_pvp.queue_steal` | `true` | A kill in the PvP zone moves the queue: the killer goes FORWARD by gain_positions, the victim goes BACK by lose_positions. false = just a fight |
| `antibot.queue_pvp.gain_positions` | `1` | queue places gained per kill |
| `antibot.queue_pvp.lose_positions` | `1` | queue places lost per death |
| `antibot.queue_pvp.hologram` | `true` | Floating text at the PvP line warning about the stakes |
| `antibot.queue_pvp.hologram_text` | `"&c⚠ PvP-зона: смерть = -{lose} в очереди, убийство = +{gain}"` | hologram text above the PvP zone |
| **`antibot.queue_pvp.speed_button`** | — | A button 4 blocks from the chest: press it to get a speed effect |
| `antibot.queue_pvp.speed_button.enabled` | `true` | turn the speed button on/off |
| `antibot.queue_pvp.speed_button.seconds` | `30` | effect duration |
| `antibot.queue_pvp.speed_button.level` | `2` | effect level (2 = Speed II) |
| **`antibot.influx`** | — | Player influx (emergency protection): admit at most burst places in total (checks + queue). The rest are kicked with a polite message — a bot wave cannot block real players |
| `antibot.influx.enabled` | `true` | turn influx protection on/off |
| `antibot.influx.burst` | `64` | maximum places in total (checks + queue) |
| `antibot.batch_size` | `5` | Batches: release players from the queue into checks in WAVES of batch_size with a pause — arenas are built in portions, not all at once (smooth load) |
| `antibot.batch_delay_seconds` | `4` | pause between release waves (seconds) |
| `antibot.prepare_seconds` | `5` | Countdown before the first stage: title + chat "get ready" (0 = immediately) |
| **`antibot.entry_queue`** | — | Server ENTRY queue (after passing the check): if the server is full, players who PASSED the check wait on the lobby platform and are released in waves as slots free up |
| `antibot.entry_queue.enabled` | `false` | turn the entry queue on/off |
| `antibot.entry_queue.max_online` | `0` | Maximum players admitted to the server. 0 = server limit (max-players from server.properties minus reserve_slots) |
| `antibot.entry_queue.reserve_slots` | `0` | Slots kept in reserve (for admins/donors) |
| `antibot.entry_queue.release_batch` | `3` | How many to release per wave |
| `antibot.entry_queue.release_seconds` | `3` | how often to release the next ones from the entry queue (seconds) |
| `antibot.entry_queue.spam_seconds` | `8` | Chat reminder about the position (seconds) |
| `antibot.entry_queue.bossbar` | `true` | Bossbar "Joining the server: N/M" |
| `antibot.entry_queue.target_server` | `""` | PROXY: lobby server name to send players to after release via Velocity/BungeeCord Connect. Empty = the player stays on this server |
| `antibot.puzzle_map` | `true` | Puzzle: pictures on the wall. PNG images from plugins/VTRegister/puzzles/ are shown on the arena wall in item frames with maps (128×128). Empty folder — they are generated automatically |
| `antibot.fallback_main_world` | `true` | If the check world could NOT be created — check on a backup arena high in the sky of the main world (instead of letting bots through). true = safer |
| **`antibot.emergency`** | — | Emergency mode: the plugin always tries to keep working (backup arena, backup checks) and reports problems to the console |
| `antibot.emergency.alert_times` | `10` | how many times to repeat each warning |
| `antibot.emergency.alert_interval_seconds` | `300` | interval between repeats (seconds) |
| **`antibot.stages`** | — | Stages (each can be turned on/off) |
| `antibot.stages.fall` | `true` | physics: several falls onto a platform |
| `antibot.stages.camera` | `false` | camera turning (collect N turns) — off by default |
| `antibot.stages.slots` | `false` | slot switching + camera jolts (client check) |
| `antibot.stages.captcha` | `false` | code typed in chat |
| `antibot.stages.click` | `false` | click a chat message |
| `antibot.stages.puzzle` | `true` | "remove the extra ones" puzzle |
| `antibot.stages.math` | `false` | an example on screen/in chat (off by default) |
| `antibot.stages.secret` | `false` | cheat-command test (.bind/#help/...) — off by default |
| `antibot.stages.air_captcha` | `false` | sky captcha: turn the camera → a code of blocks appears → type it in chat |
| `antibot.stages.block` | `false` | path + chest + break a block and pick it up — off by default |
| `antibot.lobby_item_clean_seconds` | `60` | Clear dropped items in the queue lobby every N seconds (0 = off) |
| **`antibot.timeouts`** | — | Stage timeouts (seconds) |
| `antibot.timeouts.fall` | `45` | for the whole fall stage (with 5 repetitions — at least 45) |
| `antibot.timeouts.camera` | `30` | camera turning |
| `antibot.timeouts.slots` | `35` | slots + camera jolts |
| `antibot.timeouts.captcha` | `60` | time to type the captcha |
| `antibot.timeouts.click` | `25` | click a message |
| `antibot.timeouts.puzzle` | `60` | puzzle |
| `antibot.timeouts.math` | `40` | example |
| `antibot.timeouts.secret` | `40` | cheat-command test |
| `antibot.timeouts.block` | `60` | path + chest + block |
| `antibot.timeouts.air_captcha` | `60` | sky captcha |
| `antibot.bossbar` | `true` | Stage options: bossbar on top with progress and countdown |
| `antibot.bossbar_color` | `"auto"` | Bossbar color: auto — by stage, or fixed: BLUE/GREEN/PINK/PURPLE/RED/WHITE/YELLOW |
| `antibot.bossbar_title` | `""` | Custom bossbar title (empty = from lang). Placeholders: {stage_num} {stage_total} {seconds} |
| `antibot.bossbar2_enabled` | `false` | A second (advertising) bossbar above the main one — own text and color |
| `antibot.bossbar2_title` | `"&dДобро пожаловать на сервер!"` | text of the second (advertising) bossbar |
| `antibot.bossbar2_color` | `"PURPLE"` | color of the second bossbar (PINK/BLUE/RED/GREEN/YELLOW/PURPLE/WHITE) |
| `antibot.physics_repetitions` | `5` | how many physics repetitions (1–8), 5 by default |
| **`antibot.packet_watch`** | — | Background packet check from the very first second. Until login it watches the player's packets: packet flood and impossible coordinates = bot (kick + ban tier). If packet interception does not work on this server core, it silently stays off and breaks nothing |
| `antibot.packet_watch.enabled` | `true` | turn the background packet check on/off |
| `antibot.packet_watch.max_packets_per_second` | `300` | more than this (vanilla sends 20–80) ... |
| `antibot.packet_watch.flood_seconds` | `3` | ... for this many seconds in a row = flood |
| **`antibot.answer_timing`** | — | Answer timing (captcha, example, secret, button, puzzle word). A bot answers instantly and with the same delay; a human types slower and differently every time. Violation = check failed |
| `antibot.answer_timing.enabled` | `true` | turn answer timing on/off |
| `antibot.answer_timing.min_ms_per_char` | `120` | faster than N ms per answer character = bot (0 = off) |
| `antibot.answer_timing.min_samples` | `3` | how many answers are needed to judge "evenness" |
| `antibot.answer_timing.max_stddev_ms` | `40` | delay spread below this = robot (0 = off) |
| **`antibot.air_captcha`** | — | Sky captcha (the air_captcha stage, enable it in stages). The player hangs in the air and turns the camera — a code of multi-colored blocks appears ahead (letters jump/tilt, noise around) |
| `antibot.air_captcha.turn_degrees` | `150` | how many degrees to turn the camera before the code appears |
| `antibot.air_captcha.code_length` | `4` | code length (3–6) |
| `antibot.air_captcha.noise_blocks` | `6` | "junk" blocks around the letters (0 = no noise) |
| `antibot.air_captcha.tilt` | `true` | jumping letters and uneven gaps (a "crooked" captcha); false — straight |
| `antibot.air_captcha.scale` | `2` | letter size: 1 — small, 2 — big, full-screen, 3 — huge (shrinks automatically if it does not fit between arenas) |
| `antibot.air_captcha.diagonal` | `true` | the code goes along a random diagonal (up/down); false — always horizontal |
| `antibot.arena_restore_per_tick` | `2` | How many arenas to restore per tick after checks (spreads the load during a flood). 0 = restore immediately |
| **`antibot.surge`** | — | Surge mode (a wave of joins): when many players enter checks at once, checks start at a controlled rate — at most max_starts_per_second per second, others wait in place with a bossbar. The load is spread over time, no lag |
| `antibot.surge.enabled` | `true` | turn surge mode on/off |
| `antibot.surge.trigger_joins` | `15` | this many check joins within 10 seconds = surge |
| `antibot.surge.max_starts_per_second` | `3` | how many checks to start per second during a surge |
| `antibot.surge.calm_seconds` | `30` | seconds without a wave before the mode turns off |
| `antibot.world_view_distance` | `3` | View distance in the check world (Paper). The world is empty, so there is nothing to see further, and a teleport sends 9× fewer chunks. 0 = server default |
| `antibot.tick_ticks` | `10` | Fine tuning (advanced): how often the anti-bot checks players (in ticks, 5–40); lower = more precise but costlier |
| `antibot.jolt_interval_ticks` | `8` | pause between camera jolts on the slots stage (ticks, 2–40) |
| `antibot.packet_window_ticks` | `3` | packet fall-check window (ticks, 1–10) |
| `antibot.fall_smart_plan` | `true` | true = smart repetition plan: 1st — normal fall, the rest in random order — high fall (acceleration), cobweb (slow sinking), launch upwards (the client must fly up and land), normal falls. A bot tuned for one scenario fails on another. Softer for Bedrock |
| `antibot.fall_launch` | `true` | enable the "launch upwards" repetition |
| `antibot.fall_min_height` | `5` | minimum launch height (blocks) |
| `antibot.fall_max_height` | `9` | maximum launch height (blocks) — random each time |
| `antibot.min_fall_millis` | `250` | minimum fall time (ms) — cuts off teleport cheats |
| `antibot.physics_blocks` | `true` | Platforms with SPECIAL physics: SLIME — the client must really bounce up, COBWEB — slow sinking, HONEY — soft landing. Packet bots cannot reproduce this. false — ordinary blocks only |
| `antibot.camera_turns` | `2.0` | Camera: how many FULL turns to collect (yaw+pitch together, pauses allowed, fast or slow). 2.0 = two turns |
| `antibot.camera_linearity` | `true` | Camera "linearity" anti-bot: a robot turns by the exact same delta every packet — a human never does. On CAMERA an even streak simply does not count; on BLOCK a long streak = kick. Bedrock is not checked |
| `antibot.linearity_streak` | `60` | Aim linearity on the BLOCK stage: how many identical non-trivial (>=1°) turn deltas in a row (and >=360° total) count as a spin-bot. 30..500. A packet without rotation resets the streak |
| `antibot.camera_seconds` | `3` | (obsolete, kept for compatibility — see camera_turns) |
| `antibot.slots_required` | `2` | how many times to switch the slot |
| `antibot.slots_anti_cheat` | `true` | check for the NoSlotChange cheat (the server changes the slot itself) |
| `antibot.slot_grace_millis` | `300` | Grace period after a forced slot change (ms): earlier packets are "old" (the client has not seen the change yet) and are NOT punished. Prevents false kicks |
| `antibot.slot_max_attempts` | `3` | How many times to repeat the forced slot change before failing (1–6) |
| `antibot.slot_camera_test` | `true` | Camera jolts on the SLOTS stage: the server sharply rotates the camera N times, a real client answers with Look packets. A bot without answers → kick |
| `antibot.slot_camera_jolts` | `5` | how many jolts (2–10) |
| `antibot.slot_camera_min_acks` | `2` | minimum client answers, otherwise fail |
| `antibot.packet_check` | `true` | Background packet check: bots send abnormally many move packets |
| `antibot.packet_max_per_tick` | `60` | limit of move events per tick (~0.5 s) |
| `antibot.kick_flyers` | `true` | A player flies WITHOUT server permission (allowFlight) for 3 ticks in a row → kick. Flight granted by other plugins or permissions is not punished |
| `antibot.block_distance` | `5` | BLOCK: random path, falls, tool chest (obsolete — see block_path_length) |
| `antibot.block_path_length` | `14` | length of the RANDOM zigzag path (6–40) |
| `antibot.block_max_falls` | `5` | falls from the path before a kick (1–20) |
| `antibot.block_max_mistakes` | `1` | Mistakes on the block stage before a kick: broke the wrong block or hit with the wrong tool. 1 = the first mistake kicks; 0 = do not count mistakes |
| **`antibot.click_buttons`** | — | Buttons of the click stage ("I'M NOT A BOT" + decoy buttons). The buttons are reshuffled into a NEW random order on every send |
| `antibot.click_buttons.color_mode` | `lang` | lang — colors from the lang file (each button its own); custom — button_color for "I'M NOT A BOT", decoy_color for the rest; single — all buttons in single_color (harder to guess by eye); off — no color (white) |
| `antibot.click_buttons.button_color` | `"&a"` | color of "I'M NOT A BOT" (&-code or HEX: "&#55FF55") |
| `antibot.click_buttons.decoy_color` | `"&c"` | color of decoy buttons |
| `antibot.click_buttons.single_color` | `"&f"` | color of all buttons in single mode |
| `antibot.click_buttons.bold` | `true` | bold button text (for custom/single/off) |
| `antibot.block_tool_chest` | `true` | A chest with a special tool at the path start — opens only during the stage. Without the tool the target block cannot be broken |
| `antibot.block_chest_required` | `true` | true = the target block can be broken only after the player OPENS and CLOSES the tool chest at the path start (an extra step for a bot) |
| `antibot.block_tool_material` | `"GOLDEN_PICKAXE"` | default tool item (Material) |
| `antibot.block_tool_name` | `"&eКлюч арены"` | tool name on the BLOCK stage |
| `antibot.puzzle_auto_pass` | `true` | PUZZLE "remove the extra ones": auto-pass — the stage counts as soon as all extra pictures are removed. false — the player must also type the start word in chat (old scheme) |
| `antibot.puzzle_unclosable` | `true` | The puzzle GUI cannot be closed until the stage is passed (E/Esc reopen it) |
| `antibot.puzzle_confirm_word` | `"vse"` | Word the player types in chat to start the puzzle check (only with auto_pass: false) |
| `antibot.puzzle_remove` | `["OCELOT_SPAWN_EGG", "CAT_SPAWN_EGG"]` | Which animals must be removed (any materials/spawn eggs) |
| `antibot.puzzle_keep` | `["WOLF_SPAWN_EGG", "LLAMA_SPAWN_EGG", "RABBIT_SPAWN_EGG", "PARROT_SPAWN_EGG", "FOX_SPAWN_EGG"]` | Which ones must NOT be touched — they stay |
| `antibot.puzzle_max_wrong` | `1` | Puzzle mistakes before a kick (hitting a "right" picture, a wrong GUI click). 1 = the first mistake kicks (counts as a proven fail) |
| `antibot.puzzle_mode` | `"both"` | PUZZLE mode: blocks — a 3x3 item-frame wall, each cell = one animal picture, extra ones must be PUNCHED; gui — inventory GUI (take marked eggs); both — frames first, GUI as a fallback if they fail to spawn |
| `antibot.puzzle_remove_count` | `3` | How many extra pictures on the wall (1–8) |
| `antibot.puzzle_tiles` | `[cat, dog, pig, cow, chicken, sheep, rabbit, fox, panda, man_black]` | All wall tiles. Pictures come from plugins/<plugin>/puzzles/tiles/<name>.png — put your OWN 128x128 PNG there; a missing tile is auto-drawn and saved to that folder |
| `antibot.puzzle_random_targets` | `true` | true (recommended) = the "extra" pictures are chosen RANDOMLY from all puzzle_tiles with equal chance, different for every player — bots cannot memorize them. false = taken from puzzle_remove_names with the weights below. Format: "name" or "name:weight" |
| `antibot.puzzle_style` | `keep` | How the puzzle task sounds: keep — "keep only the pigs, remove the rest" (clearer, default): a random animal fills all "right" cells, extra ones are other pictures; remove — "remove the extra: cat ×1, fox ×2" (old variant) |
| `antibot.puzzle_task_keep` | `""` | Custom task text for keep (empty = from lang: antibot_stage_puzzle_keep). Placeholders: {keep} {count} {word} {stage_num} {stage_total} |
| `antibot.puzzle_remove_names` | `["man_black", "cat", "fox"]` | Which pictures are "extra" (used with puzzle_random_targets: false) |
| `antibot.puzzle_same_target` | `false` | true = all "extra" pictures are one type (one weighted roll); false = a mix on the wall |
| `antibot.puzzle_task` | `""` | Custom task text (empty = auto "Remove extra: <names>x<count>"). Placeholders: {targets} {count} {word} {stage_num} {stage_total} |
| `antibot.min_answer_ms` | `400` | An answer faster than this (ms) after the question appeared = bot. Applies to captcha/math/secret/click/puzzle. 0 = off |
| `antibot.min_click_ms` | `100` | A click on a clickable message faster than N ms after it appeared = bot (0 = off) |
| `antibot.slots_response_ms` | `8000` | Silence after a forced slot change longer than N ms = a cheat ignores the server slot (NoSlotChange). Retry, then kick |
| `antibot.slot_relock_max` | `4` | Returns to the pre-force slot (re-lock cheat) before a kick |
| `antibot.camera_snap_degrees` | `60` | Camera: sharp turns > N degrees per packet IN A ROW = spin-bot |
| `antibot.camera_snap_max` | `8` | How many identical (±1°) jolts >= camera_snap_degrees IN A ROW count as a bot (they simply do not count toward turns, no kick). Minimum in code is 8: a fast mouse swipe gives 4–6 |
| **`antibot.slot_lock`** | — | Forbid moving items during the check |
| `antibot.slot_lock.enabled` | `true` | turn the restriction on |
| `antibot.slot_lock.kick_after` | `5` | violations before a kick |
| `antibot.recheck_on_restart` | `false` | true — after a server RESTART every player (even old accounts) passes the anti-bot check again: protection against "sleeping" bot accounts. false (default) — regular players are re-checked only by the recheck_hours period (above), not after every restart |
| `antibot.slime_extra_seconds` | `8` | SLIME: extra seconds for the bounce (fly up + land + stop). Margin for ping and client lag |
| `antibot.puzzle_sign_lines` | `["&6&lПАЗЛ", "прямо перед тобой", "убери &cлишние&r предметы", "&aостальное не трогай"]` | Sign text in front of the player on the puzzle stage (4 lines, &-colors) |
| `antibot.fall_void_max` | `3` | VOID on the FALL stage: message + forced retry. Falls allowed before a kick |
| `antibot.fall_packets` | `true` | Packet fall check (Netty, limbo style): teleport into the air + AcceptTeleportation, then dy is measured with the vanilla formula v=(v-0.08)*0.98. false — old platforms. Interception unavailable / no packets seen — retry on Bukkit events (no kick) |
| `antibot.fall_confirm_timeout_ms` | `2500` | no AcceptTeleportation → retry the fall on Bukkit events (NOT a kick) |
| `antibot.fall_move_timeout_ms` | `3000` | no move packets → retry on Bukkit events (NOT a kick) |
| `antibot.fall_max_duration_ms` | `10000` | overall limit of one packet fall check, then legacy (NOT a kick) |
| `antibot.fall_ticks` | `4` | lower bound of fall samples; the real minimum is computed from fall_height by vanilla physics |
| `antibot.fall_tolerance` | `0.01` | allowed dy deviation |
| `antibot.fall_tolerance_bedrock` | `0.05` | tolerance for Bedrock/Geyser |
| `antibot.fall_packets_bedrock` | `false` | packet check for Bedrock (Geyser); tolerance not confirmed by measurement — Bedrock uses legacy by default |
| `antibot.fall_height` | `12` | teleport height above the arena |
| `antibot.fall_max_violations` | `3` | formula violations in a row before a kick |
| `antibot.puzzle_front` | `true` | Puzzle in front of the player (right in front of the crosshair). false — the wall is behind: the player must turn 180° |
| `antibot.math_max` | `20` | MATH: numbers 1..N in the example (on screen/in chat, off by default) |
| `antibot.math_operations` | `[add]` | Which examples to give: add — addition (default); mul — multiplication of the simplest numbers 2–5. Both: [add, mul] |
| `antibot.math_where` | `"chat"` | chat \| bossbar \| both — where to show the example |
| `antibot.secret_mode` | `"default"` | SECRET: cheat-command test. Cheat clients intercept .help/#bind commands and do not send them to chat — a real client sends the line as a message. default — pool [.bind .help .bro #bro #bind #help]; custom — your own list below; random — random strings like .fjdw / #jkkg |
| **`antibot.secret_custom`** | — | Your own cheat commands for the secret stage (with secret_mode: custom) |
| `antibot.secret_count` | `2` | how many tests in a row |
| `antibot.code_length` | `4` | captcha code length (3–8) |
| `antibot.captcha_prefixes` | `".#!$"` | captcha: prefix + token (e.g. #7bK9) |
| `antibot.captcha_strike` | `false` | "Crossed-out" map captcha: thin lines through the middle and crosswise. Humans read it (letters are thicker than the lines), OCR programs struggle. Works with map_captcha: true |
| `antibot.max_attempts` | `5` | attempts to type the captcha/math |
| `antibot.captcha_attempts` | `3` | captcha (and sky captcha) attempts before a kick |
| `antibot.map_captcha` | `false` | Draw the captcha code on a map in hand (large, distorted). On → the code is NOT sent to chat or title, only the map (Bedrock/Floodgate also gets it as text). Recommended against bots that read chat |
| `antibot.click_decoys` | `2` | CLICK: decoy buttons next to the real "I'M NOT A BOT" (0–3). A bot clicking any chat link hits a decoy: the 1st miss is forgiven, the 2nd = fail. 0 — old mode (one clickable message) |
| `antibot.bedrock_skip_slots` | `true` | Bedrock (Geyser): skip the SLOTS stage — server-side slot changes are unreliable on Bedrock and would kick honest players. Other stages remain |
| **`antibot.datacenter_block`** | — | Protection BEFORE joining (cuts bot waves at connection time): block hosting / data-center / VPN IPs. Bot farms almost always run on VPS and proxies. The subnet list is downloaded from url every refresh_hours (cache — data/datacenter-ipv4.txt). OFF by default: honest players on a VPN cannot join either |
| `antibot.datacenter_block.enabled` | `false` | turn the hosting/VPN block on/off |
| `antibot.datacenter_block.mode` | `new_players` | new_players — only unregistered; all — everyone |
| `antibot.datacenter_block.url` | `"https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/datacenter/ipv4.txt"` | URL of the hosting/VPN subnet list |
| `antibot.datacenter_block.refresh_hours` | `24` | how often to refresh the list |
| **`antibot.ping_check`** | — | Ping check before joining: a real client first sees the server in the server list (ping), a bot usually connects directly. Registered and Bedrock players are not checked. Behind a proxy it works on the proxy (its own ping_check) and turns itself off here |
| `antibot.ping_check.mode` | `attack` | off — disabled; attack — only during an attack; always — always |
| `antibot.ping_check.window_seconds` | `600` | how many seconds a ping stays valid |
| **`antibot.connection_limit`** | — | Connection limit (protection against bot floods) |
| `antibot.connection_limit.enabled` | `true` | turn the limit on |
| `antibot.connection_limit.per_second` | `10` | total server connections per second (0 = no limit) |
| `antibot.connection_limit.per_minute` | `150` | total connections per minute (0 = no limit) |
| `antibot.connection_limit.per_ip_per_second` | `2` | from one IP per second (0 = no limit) |
| `antibot.connection_limit.per_ip_per_minute` | `10` | from one IP per minute (0 = no limit) |
| `antibot.connection_limit.registered_bypass` | `true` | already registered players are not limited |
| **`antibot.bans`** | — | Bans for failing the anti-bot check — only for a PROVEN fail (wrong physics, flood, robot answers, decoy, puzzle/block mistake). Timeouts, lag and AFK never ban. mode: auto — the first free_fails fails (2 by default) only kick; after that every fail from the same IP gives the next tier: tiers_minutes[0] (1 min), then [1] (5 min), [2] (15 min); after the last — permanent if permanent: true, otherwise the last tier again. mode: off — no bans. Bans are saved in data/ip-bans.txt (survive restarts). Remove all bans: /authadmin unban |
| `antibot.bans.mode` | `auto` | auto / off |
| `antibot.bans.tiers_minutes` | `[1, 5, 15]` | tier durations in minutes (more tiers allowed) |
| `antibot.bans.permanent` | `false` | a PERMANENT ban after the last tier (off by default) |
| `antibot.bans.free_fails` | `2` | How many first PROVEN fails only kick (a human might make a mistake). The IP ban starts at fail (free_fails+1): with 2 — the third fail = 1 min, then 5, 15… Timeouts (lag) and AFK NEVER ban |
| `antibot.bans.forget_after_hours` | `24` | after this many hours without new fails the tier resets |
| **`antibot.guard`** | — | Pre-join guard: join limits per IP and globally, attack mode, name filter, account and online limits per IP |
| `antibot.guard.enabled` | `true` | join guard (per-IP and global join limits, attack mode, name filter) |
| `antibot.guard.window_seconds` | `60` | connection counting window |
| `antibot.guard.max_joins_per_ip` | `5` | maximum joins from one IP per window |
| `antibot.guard.max_joins_global` | `60` | maximum joins to the whole server per window |
| `antibot.guard.attack_mode_threshold` | `30` | this many joins turns on attack mode |
| `antibot.guard.reconnect_seconds` | `0` | >0 — in attack mode ask players to reconnect |
| `antibot.guard.fail_ban_minutes` | `0` | (only with bans.mode: off) IP ban after a failed check, minutes |
| `antibot.guard.max_online_per_ip` | `4` | Online at the same time from one IP — only for NEW accounts (0 = no limit). Proxy IPs are not limited |
| `antibot.guard.repeat_fail_ban_minutes` | `5` | (only with bans.mode: off) 3 failed checks from an IP within an hour → IP ban for N minutes, unregistered only (0 = off). Leaving mid-stage after mistakes = fail |
| `antibot.guard.name_regex` | `"^[A-Za-z0-9_]{3,16}$"` | Allowed names (regex). Bots like "Player12345678" are cut off. Floodgate Bedrock names (".Steve") pass: the prefix is stripped automatically |

### `afk`

AFK protection and bot detection (2 levels + anti-macro)

| Setting | Default | What it does |
|---|---|---|
| `afk.enabled` | `true` | turn AFK protection on/off |
| `afk.initial_grace_seconds` | `5` | Level 1: a new player did not move for the first N seconds → countdown in chat/ActionBar, kick after initial_timeout |
| `afk.initial_timeout_seconds` | `15` | seconds before a kick if the player did nothing at all after joining |
| `afk.queue_idle_seconds` | `300` | Level 2: a player in the queue without activity for N seconds → kick |
| `afk.bossbar` | `true` | Bossbar with the countdown to the kick (AFK warning) |
| `afk.move_block_delta` | `0.2` | Anti-macro: jumping in place does NOT reset the timer — minimum movement (blocks) |
| `afk.camera_window` | `20` | Linear aim: tick window and the standard-deviation threshold of yaw/pitch deltas |
| `afk.camera_stddev_max` | `0.01` | camera turn spread below this = "robot" |
| `afk.camera_min_mean_delta` | `1.0` | minimum average turn to evaluate at all |
| `afk.camera_strikes` | `2` | how many "robotic" windows before a kick |
| `afk.bedrock_multiplier` | `3.0` | Bedrock (Floodgate): softer camera thresholds (touch input) |
| `afk.ip_ban_minutes` | `15` | IP ban for bots/AFK for N minutes (0 = off) |
| `afk.ip_ban_on_kick` | `false` | IP ban for an AFK kick of an unauthenticated player (off: idling is not proof of a bot) |
| `afk.ip_ban_strikes` | `2` | IP ban: a confirmed bot — at once; AFK — from the 2nd violation |
| **`afk.spectator_grace`** | — | AFK in the queue (any mode): instead of an instant kick — spectator mode with a bossbar; activity (camera/flight) returns the player to the queue |
| `afk.spectator_grace.enabled` | `true` | turn spectator grace on/off |
| `afk.spectator_grace.timeout_seconds` | `60` | how many seconds a player may fly as a spectator |
| `afk.kick_after_login` | `false` | Kick players who ALREADY logged in for AFK (after authed_idle_seconds without activity). OFF by default: after login the plugin does not touch AFK players. Activity when on: movement, vehicles, camera, chat, commands, clicks, blocks. (The old key track_authed no longer works) |
| `afk.authed_idle_seconds` | `900` | Seconds without activity before an AFK kick of a logged-in player (min 60). Permission vtregister.afk.bypass is exempt |
| **`afk.siege`** | — | Siege mode: many AFK kicks in a row → limit joins of unauthenticated players |
| `afk.siege.enabled` | `true` | turn siege mode on/off |
| `afk.siege.kicks_threshold` | `8` | this many AFK kicks per window → siege |
| `afk.siege.window_seconds` | `60` | AFK kick counting window for siege mode (seconds) |
| `afk.siege.duration_seconds` | `180` | limit duration |
| `afk.siege.max_pending` | `8` | maximum unauthenticated players online during a siege |

### `info_bar`

Info bossbar #3 (ads/donations/message rotation)

| Setting | Default | What it does |
|---|---|---|
| `info_bar.enabled` | `true` | turn the info bossbar on/off |
| `info_bar.interval_seconds` | `8` | message rotation |
| `info_bar.color` | `"PURPLE"` | info bossbar color |
| `info_bar.messages` | `["&dДобро пожаловать! Пройди проверку и войди.", "&bРегистрация: &f/reg&b, вход: &f/login", "&6Пароль вводится в чат и не попадает в логи сервера", "&eНикому не сообщай свой пароль — даже администрации"]` | Info bossbar messages in turn |

### `twofactor`

Two-factor authentication (TOTP)

| Setting | Default | What it does |
|---|---|---|
| `twofactor.enabled` | `true` | players enable it themselves: /2fa on |
| `twofactor.timeout_seconds` | `60` | time to enter the code on login |
| `twofactor.max_attempts` | `4` | wrong 2FA codes before a kick |
| `twofactor.trust_minutes` | `0` | 0 = ask for the code on every login |
| `twofactor.issuer` | `"Minecraft"` | name shown in the authenticator app |
| `twofactor.force_admins` | `false` | Mandatory 2FA for admins (OFF by default). When on: an admin without 2FA can only enable it after login (/2fa on) — other commands and chat are blocked until then |
| `twofactor.admin_permission` | `"registerplugin.admin"` | who counts as an admin (plus all OPs) |
| `twofactor.admin_setup_seconds` | `300` | kick after this many seconds if 2FA was not enabled (0 = no kick) |

### `email`

E-mail (SMTP) — for /email and /recover

| Setting | Default | What it does |
|---|---|---|
| `email.enabled` | `false` | turn e-mail on/off |
| `email.provider` | `"custom"` | Ready-made mail settings: gmail, yandex, mailru — host/port/ssl are filled in automatically, just set smtp.user (your address) and smtp.password. custom — your own SMTP server, everything manual. IMPORTANT: password is an "app password", NOT your normal one: Gmail — enable 2-step verification → myaccount.google.com/apppasswords; Yandex — id.yandex.ru → Security → App passwords → "Mail", and allow mail clients in Yandex Mail settings; Mail.ru — Settings → Security → Passwords for external apps. Test: /authadmin testmail <your address> |
| `email.code_minutes` | `10` | how many minutes the e-mailed code is valid |
| **`email.smtp`** | — | Mail server for recovery e-mails |
| `email.smtp.host` | `"smtp.example.com"` | SMTP server address |
| `email.smtp.port` | `465` | port (465 = SSL, 587 = STARTTLS) |
| `email.smtp.user` | `"noreply@example.com"` | login |
| `email.smtp.password` | `""` | password (keep this file private) |
| `email.smtp.from` | `"noreply@example.com"` | sender address |
| `email.smtp.ssl` | `true` | port 465 — yes; for 587 set ssl: false, starttls: true |
| `email.smtp.starttls` | `false` | true for port 587 |

### `premium`

Premium auto-login (online-mode servers only). If the server is licensed (behind a proxy — only with secure forwarding: Velocity modern, BungeeGuard or proxy.firewall; otherwise it turns off automatically), licensed players log in without a password. With 2FA on, the code is still asked. Do NOT enable on offline-mode — UUIDs will not match

| Setting | Default | What it does |
|---|---|---|
| `premium.enabled` | `false` | turn premium auto-login on/off |
| `premium.only_first_join` | `false` | check premium only on the first join |
| `premium.cache_minutes` | `60` | how many minutes to remember Mojang's answer |
| `premium.min_interval_ms` | `250` | pause between Mojang requests (ms) |

### `spawns`

Spawns. Points are set with /authadmin setspawn <prelogin\|postlogin\|firstjoin>

### `after_auth`

Where to send the player after login: target — for registered players, new_target — for new ones after /reg. auto — the setspawn point if set; otherwise returning players stay where they joined, newcomers go to the world spawn; spawn — /authadmin setspawn postlogin (newcomers: firstjoin); zero — main world spawn; coords — exact world/x/y/z below; command — run a console command: {player} = name, {uuid} = UUID, e.g. "spawn {player}", "warp lobby {player}" (EssentialsSpawn, SetSpawn, warp plugins); plugin — the same, the command comes from plugin_command; last — stay where they joined; none — do nothing

| Setting | Default | What it does |
|---|---|---|
| `after_auth.target` | `"auto"` | destination for registered players |
| `after_auth.new_target` | `"auto"` | the same for players who just registered |
| `after_auth.command` | `"spawn {player}"` | command for target: command |
| `after_auth.command_new` | `""` | for newcomers; empty = the same command |
| `after_auth.plugin_command` | `"spawn {player}"` | command for target: plugin |
| `after_auth.world` | `"world"` | world for target: coords |
| `after_auth.x` | `0.5` | X coordinate (target: coords) |
| `after_auth.y` | `100.0` | Y coordinate |
| `after_auth.z` | `0.5` | Z coordinate |
| `after_auth.yaw` | `0` | horizontal head rotation |
| `after_auth.pitch` | `0` | head pitch |

### `bedrock`

Bedrock (Geyser/Floodgate)

| Setting | Default | What it does |
|---|---|---|
| `bedrock.enabled` | `false` | turn Bedrock support on/off |
| `bedrock.trust_floodgate_players` | `false` | Bedrock players log in without a password |
| `bedrock.log_api_errors` | `true` | log Floodgate API errors |

### `global_blacklist`

Shared blacklist between servers: partner servers exchange ban lists, so a bot or cheater banned on one cannot join the others. OFF by default. How to connect: 1) both servers: enabled: true and their own server_name; 2) to SHARE your list: publish.enabled: true and open publish.port — the access token is generated in data/blacklist-token.txt (give it to the partner) or set publish.token; 3) to READ a partner's list: add it to peers (url + their token). Only banned IPs/names and ban expiry are shared — no passwords or player data

| Setting | Default | What it does |
|---|---|---|
| `global_blacklist.enabled` | `false` | main switch |
| `global_blacklist.server_name` | `"myserver"` | this server's name — shown to the player in the block reason |
| `global_blacklist.share_from_tier` | `3` | share anti-bot bans from this tier (3 = 15 min and more) |
| `global_blacklist.share_server_bans` | `true` | also share server bans (/ban, /ban-ip) — e.g. for cheating |
| `global_blacklist.block_names` | `true` | block by name too (not only by IP) |
| `global_blacklist.ignore_private_ips` | `true` | never block local addresses (127.x, 10.x, 192.168.x) |
| `global_blacklist.max_entries_per_peer` | `50000` | protection against a "bloated" partner list |
| `global_blacklist.pull_interval_seconds` | `60` | how often to fetch partner lists |
| **`global_blacklist.publish`** | — | Publish this server's bans to partner servers (opens an HTTP port protected by a token) |
| `global_blacklist.publish.enabled` | `false` | share your list with partners |
| `global_blacklist.publish.bind` | `"0.0.0.0"` | address to listen on |
| `global_blacklist.publish.port` | `8765` | port (open it in your host's firewall) |
| `global_blacklist.publish.token` | `""` | access password; empty = generate in data/blacklist-token.txt |
| `global_blacklist.peers` | `[]` | Partners whose lists we read: - url: "http://1.2.3.4:8765/vtregister/blacklist" token: "partner token" |

### `proxy`

Proxy (BungeeCord / Velocity)

| Setting | Default | What it does |
|---|---|---|
| `proxy.warn_misconfigured` | `true` | Write to the console once a minute if the server is behind a proxy but the proxy/forwarding is not configured (or looks so). false — no reminders |
| `proxy.type` | `"auto"` | auto \| bungeecord \| velocity \| none (none — no proxy, transfer off) |
| `proxy.known_proxy_ips` | `["127.0.0.1", "::1", "0:0:0:0:0:0:0:1"]` | Proxy/protection IPs (CIDR allowed, e.g. 10.0.0.0/8): limits, bans and IP auto-login do not apply to them |
| **`proxy.firewall`** | — | Allow connections ONLY from proxy addresses (protection against bypassing the proxy) |
| `proxy.firewall.enabled` | `false` | Allow only connections from proxy IPs (protection against direct joins). REQUIRED with spigot.yml bungeecord: true without BungeeGuard — otherwise UUIDs/IPs can be spoofed; allowed_ips — proxy IPs as this server sees them, CIDR allowed |
| `proxy.firewall.allowed_ips` | `["127.0.0.1"]` | proxy IPs as this server sees them |

### `proxy_server`

Sending the player to a lobby behind a proxy (BungeeCord/Waterfall and Velocity; in velocity.toml enable bungee-plugin-message-channel = true). Target: if lobby_address (host:port) is set and auto_detect_name: true, the plugin asks the proxy for its server list and finds the name with the same IP:port; otherwise server_name is used. While transferring, the player is invulnerable and stands on a safe point; if the proxy does not answer within transfer_timeout_seconds, the player stays on this server at the lobby location

| Setting | Default | What it does |
|---|---|---|
| `proxy_server.enabled` | `false` | turn proxy transfer on/off |
| `proxy_server.server_name` | `"lobby"` | Server name in the proxy config (BungeeCord: servers.<name>, Velocity: [servers].<name>) |
| `proxy_server.lobby_address` | `""` | Lobby IP:port, e.g. "10.0.0.5:25566" or "play.example.com:25565". Only needed for name auto-detection; may stay empty |
| `proxy_server.auto_detect_name` | `true` | Ask the proxy for the server name matching lobby_address |
| `proxy_server.transfer_timeout_seconds` | `8` | After how many seconds a transfer counts as failed and the player stays here |
| `proxy_server.retry_seconds` | `3` | After how many seconds to repeat Connect if the player is still here |
| `proxy_server.safe_transfer` | `true` | During the transfer: invulnerability + no falling (the player cannot die) |
| `proxy_server.fallback_to_lobby_location` | `true` | If the transfer failed — teleport to the lobby location below (instead of leaving the player in place) |
| **`proxy_server.wait_for_server`** | — | If the target server is down: wait for it in the queue lobby. TCP check of host:port every check_interval_seconds; after timeout_minutes — a kick with a message. The bossbar shows a countdown |
| `proxy_server.wait_for_server.enabled` | `false` | turn waiting for the server on/off |
| `proxy_server.wait_for_server.host` | `""` | target server IP/host to check (empty = off) |
| `proxy_server.wait_for_server.port` | `25565` | target server port |
| `proxy_server.wait_for_server.timeout_minutes` | `15` | how many minutes to wait for it to start |
| `proxy_server.wait_for_server.check_interval_seconds` | `10` | how often to check (seconds) |
| `proxy_server.wait_for_server.bossbar` | `true` | show the waiting in a bossbar |

### `lobby`

Teleport inside the server after login

| Setting | Default | What it does |
|---|---|---|
| `lobby.enabled` | `false` | turn the lobby teleport on/off |
| `lobby.world` | `"world"` | lobby point world |
| `lobby.x` | `0` | X |
| `lobby.y` | `100` | Y |
| `lobby.z` | `0` | Z |
| `lobby.yaw` | `0` | head rotation |
| `lobby.pitch` | `0` | head pitch |

### `selfdefense`

State watchdog: watches for replaced commands/listeners and plugin disabling. By default only warns and restores — stopping the server may hurt availability, enable consciously

| Setting | Default | What it does |
|---|---|---|
| `selfdefense.enabled` | `false` | turn the watchdog's server action on/off |
| `selfdefense.action` | `"stop"` | stop \| restart |
| `selfdefense.integrity_check` | `true` | self-check of the plugin files |
| `selfdefense.check_interval_seconds` | `10` | how often (seconds) |
| `selfdefense.max_tamper_strikes` | `3` | how many detections before disabling |
| `allowed_commands_unauthorized` | `["login", "l", "register", "reg"]` | Commands allowed BEFORE login |

### `metrics`

bStats statistics (optional)

| Setting | Default | What it does |
|---|---|---|
| `metrics.enabled` | `false` | true — every 30 minutes send ANONYMOUS statistics to bstats.org: server, Java and plugin versions, OS, online player count, server country, anti-bot mode, storage, language and password input mode. No names, IPs or passwords. Helps the author see what the plugin runs on. The global switch for all plugins is plugins/bStats/config.yml |

### `console_reminder`

Console reminder about updates — written ONLY to the console (players do not see it). Can be disabled

| Setting | Default | What it does |
|---|---|---|
| `console_reminder.enabled` | `true` | turn the console reminder on/off |
| `console_reminder.delay_seconds` | `10` | seconds after startup |
| `console_reminder.times` | `2` | how many times to repeat |
| `console_reminder.message` | `"VTRegister — новые версии выходят на GitHub: github.com/Vitaliy222121/VTRegister/releases"` | console text |
| `console_reminder.messages` | `["Нравится VTRegister? Поставь звезду на GitHub — это помогает проекту."]` | Extra lines after the main message (console only too) |

### `messages`

Messages. All texts live in lang/ru.yml and lang/en.yml. Any key can be overridden here: messages.<key> takes priority over the lang file

| Setting | Default | What it does |
|---|---|---|
| `messages.use_hex` | `true` | use HEX colors (&#RRGGBB) |
| `messages.prefix` | `"&#FF66CC[Auth] &#FFFFFF"` | message prefix |
| **`messages.custom_instructions`** | — | Your own instructions on join (any number of lines; empty = none) |
| **`messages.custom_instructions_register`** | — | extra instructions for players who must register |
| **`messages.custom_instructions_login`** | — | extra instructions for players who must log in |

## advanced.yml — database, limits, scheduled restart

### `storage`

| Setting | Default | What it does |
|---|---|---|
| `storage.type` | `yaml` | Storage type: yaml — data/accounts.yml (default, human-readable); sqlite — data/accounts.db (more compact and faster on big databases); mysql — a MySQL database (shared by a network); mariadb — a MariaDB database (MySQL driver); postgresql — a PostgreSQL database (the driver is downloaded automatically and verified by SHA-256) |
| `storage.host` | `"localhost"` | SQL parameters (ignored for yaml/sqlite): host |
| `storage.port` | `3306` | port (PostgreSQL default is 5432) |
| `storage.database` | `"minecraft"` | database name |
| `storage.user` | `"root"` | database user |
| `storage.password` | `""` | database password |
| `storage.ssl` | `false` | encrypt the database connection |
| `storage.table_prefix` | `"rp_"` | Table prefix (several servers can share one database) |
| `storage.pool_size` | `4` | Connection pool size (1–16). More than 8 is rarely needed |
| `storage.sqlite_file` | `"accounts.db"` | SQLite file name |
| `storage.sqlite_busy_timeout_ms` | `5000` | SQLite: how many milliseconds to wait while the database is busy writing instead of a "database is locked" error (WAL and synchronous=NORMAL are always on) |
| `storage.match_by_name` | `true` | Find an account by name if the UUID did not match (online-mode change, migration from another core, a licensed player's name change) |

### `maintenance`

Maintenance: how often account changes are written to disk

| Setting | Default | What it does |
|---|---|---|
| `maintenance.flush_interval_ticks` | `40` | How often changed accounts are written to disk/database (ticks, 20 = 1 s) |
| `maintenance.warn_sync_reads` | `false` | Log when an account is read from the database synchronously (may lag) |

### `purge`

Auto-purge of inactive accounts (OFF by default — deletion is irreversible). Accounts that have not logged in for inactive_days are deleted, so the database does not pile up "dead" registrations. Online players are never touched. Applied after a restart

| Setting | Default | What it does |
|---|---|---|
| `purge.enabled` | `false` | on/off |
| `purge.inactive_days` | `180` | days without a login (minimum 30) |
| `purge.keep_with_email` | `true` | do not delete accounts with a linked e-mail |
| `purge.keep_with_2fa` | `true` | do not delete accounts with 2FA enabled |
| `purge.check_hours` | `24` | how often to check (hours) |

### `limits`

Load limits: password hashing and e-mails

| Setting | Default | What it does |
|---|---|---|
| `limits.max_parallel_hash_checks` | `12` | Maximum simultaneous password checks (protects the CPU from floods) |
| `limits.max_parallel_per_ip` | `2` | Maximum simultaneous password checks from one IP |
| `limits.max_emails_per_minute` | `5` | Maximum e-mails per minute (protects SMTP from floods) |

### `database_backup`

Account database backups

| Setting | Default | What it does |
|---|---|---|
| `database_backup.enabled` | `false` | Automatic backup on startup (yaml/sqlite only) |
| `database_backup.keep_last` | `5` | How many latest copies to keep in data/backups/ |

### `antibot`

Anti-bot expert knobs (advanced). A normal server does not need to touch them — the defaults are tuned

| Setting | Default | What it does |
|---|---|---|
| `antibot.tick_ticks` | `10` | Period of the anti-bot ticker in ticks (20 = 1 s). Lower = more precise timeouts and countdowns, higher = cheaper on weak servers |
| `antibot.jolt_interval_ticks` | `8` | Pause between camera jolts on the SLOTS stage (ticks). Shorter = more aggressive |
| `antibot.packet_window_ticks` | `3` | Move-packet counting window (ticker ticks): packets are summed over the window, exceeding packet_max_per_tick = bot. 1 = strict, 5 = lag-tolerant |

### `auto_restart`

Scheduled server restart. A long uptime piles up "garbage" in the server and plugins. Default: every 48 hours at 00:00 Moscow time. Players are warned in chat beforehand

| Setting | Default | What it does |
|---|---|---|
| `auto_restart.enabled` | `true` | turn scheduled restarts on/off |
| `auto_restart.mode` | `time` | time — at a set time of day (time + timezone), every every_days days; uptime — exactly after uptime_hours of server uptime |
| `auto_restart.time` | `"00:00"` | restart time (HH:MM) — for mode: time |
| `auto_restart.timezone` | `"Europe/Moscow"` | Time zone: Europe/Moscow, Europe/Kyiv, Europe/Minsk, Europe/Berlin, Asia/Almaty, Asia/Yekaterinburg, Asia/Novosibirsk, Asia/Vladivostok, UTC or server — the time zone of the machine running the server |
| `auto_restart.every_days` | `2` | every N days (2 = every 48 hours) — for mode: time |
| `auto_restart.uptime_hours` | `48` | after how many hours of uptime — for mode: uptime |
| `auto_restart.warn_seconds` | `[900, 600, 300, 120, 60, 30, 10, 5, 4, 3, 2, 1]` | How many seconds before the restart to warn in chat |
| `auto_restart.warn_message` | `"&c⚠ Перезапуск сервера через &f{time}&c."` | chat warning text ({time} = time left) |
| `auto_restart.kick_message` | `"&eСервер перезапускается. Зайди через минуту!"` | message shown to players when the server restarts |
| `auto_restart.command` | `"restart"` | restart — restart command (spigot.yml → restart-script; without a script the server just stops and the host usually starts it again); stop — shut down |

## proxy-config.yml — proxy settings (Velocity/BungeeCord)

| Setting | Default | What it does |
|---|---|---|
| `auth_servers` | `[auth]` | Servers (names from velocity.toml / the proxy config.yml) where VTRegister runs and the player types the password. A player who has not logged in always goes to the first available server in the list. Empty [] = login on any server, but switching servers before login is blocked. Name not found among the proxy's servers? The player is NOT kicked (goes where the proxy sends them) and the console warns once a minute until you fix it |
| `after_login_server` | `[]` | Where to send the player after login. A list — the first one that accepts the connection is used. [] = stay on the login server. IMPORTANT: if you use this, turn off proxy_server.enabled on the Paper server, otherwise the player is sent twice |
| `allowed_proxy_commands` | `[]` | Proxy commands allowed BEFORE login (without '/'). Other proxy commands are blocked. Server commands (/login, /register) are not affected |
| `secret` | `''` | Shared secret with the Paper servers (security.proxy_bridge.secret in their config.yml). Set the same long random string on the proxy and on all servers — then logging in on one VTRegister server counts on the others. Empty = every VTRegister server asks for the password itself |
| `warn_misconfigured` | `true` | Write to the console once a minute if auth_servers/after_login_server point to servers that do not exist. false — no reminders. (IP limits: if HAProxy/TCPShield without the PROXY protocol stands in front of the proxy, all players share one IP — set per_ip_per_minute: 0 and max_online_per_ip: 0) |

### `connection_limit`

| Setting | Default | What it does |
|---|---|---|
| `connection_limit.per_ip_per_minute` | `8` | Connections from one IP per minute (0 = no limit). Verified IPs are not counted |
| `connection_limit.max_online_per_ip` | `4` | Online at the same time from one IP (0 = no limit) |
| `connection_limit.global_per_second` | `25` | More connections per second to the whole proxy = a bot attack (0 = off) |
| `connection_limit.attack_seconds` | `120` | How long attack mode lasts: only verified IPs are admitted |
| `connection_limit.verified_ip_hours` | `72` | How many hours an IP stays verified after a successful login |

### `ping_check`

Ping check: a real client first sees the server in the server list (ping), a bot usually connects directly. off — disabled; attack — only during an attack (recommended); always — always (new players without a ping are asked to refresh the server list and join again)

| Setting | Default | What it does |
|---|---|---|
| `ping_check.mode` | `attack` | off / attack / always |
| `ping_check.window_seconds` | `600` | how many seconds a ping stays valid |

### `messages`

Player texts: & — colors; a line break is two characters: a backslash and n

| Setting | Default | What it does |
|---|---|---|
| `messages.ping_first` | `'&eДобавь сервер в список серверов, обнови список и зайди снова.'` | message for a player who connected without pinging the server list |
| `messages.switch_denied` | `'&cСначала войди в аккаунт: &f/login <пароль>'` | message when switching servers before login |
| `messages.command_denied` | `'&cЭта команда доступна после входа: &f/login <пароль>'` | message when using a blocked proxy command before login |
| `messages.no_auth_server` | `'&cСервер авторизации недоступен. Попробуй зайти через минуту.'` | message when no login server is available |
| `messages.attack` | `'&cСервер отражает атаку ботов.\n&fЗайди через пару минут — вернувшихся игроков пускаем сразу.'` | message during attack mode |
| `messages.too_fast` | `'&cСлишком частые подключения с твоего IP. Подожди минуту.'` | message when reconnecting too fast |
| `messages.too_many_online` | `'&cС твоего IP уже играет слишком много аккаунтов.'` | message when too many players are online from one IP |

