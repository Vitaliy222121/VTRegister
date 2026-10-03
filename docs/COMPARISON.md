# VTRegister и другие плагины входа и антиботы

**Русский** · [English](#english)

Сравнение составлено по официальным страницам и репозиториям проектов (октябрь 2026). Если что-то изменилось — откройте Issue, исправим. VTRegister — плагин авторизации со **встроенным поведенческим антиботом**; большинство остальных решают только одну из двух задач.

«—» в таблицах — нет или не указано на официальной странице проекта.

## Главное отличие в одном абзаце

На обычном сервере без прокси боты заходят пачками, регистрируют аккаунты, спамят и «кладут» сервер: обычный плагин входа (AuthMe, nLogin, LibreLogin) проверяет только пароль, а отдельные антиботы (Sonar, LimboFilter, BotSentry) чаще всего рассчитаны на прокси или стоят денег. **VTRegister ставится одним jar прямо на сервер и сразу даёт и безопасный вход, и проверку поведения новичка** (физика падения по формуле ванильного клиента + пазл с картинками). Бан IP — только за доказанный провал, живых игроков не наказывает. Код открыт, плагин бесплатный.

## Одиночный сервер (Paper / Spigot без прокси)

| | **VTRegister** | AuthMeReloaded | nLogin | LibreLogin | Sonar | EpicGuard |
|---|---|---|---|---|---|---|
| Что это | вход + антибот | вход | вход | вход | антибот | антибот |
| Открытый код | **да** (GPL-3.0 + доп. условия или VMIT) | да (GPL-3.0) | основной плагин — нет | да (MPL-2.0) | да (GPL-3.0) | да |
| Цена | **бесплатно** | бесплатно | — | бесплатно | бесплатно | бесплатно |
| Проверка поведения новичка | **физика падения + пазл, ещё 8 этапов по желанию** | нет (антибот по частоте входов) | нет | нет | физика (гравитация, коллизии), транспорт | нет (гео, VPN, ник, пинг, настройки клиента) |
| Бан IP только за доказанный провал | **да**; лаг, таймаут, AFK не банят | — | — | — | — | — |
| Хеш паролей по умолчанию | **Argon2id** (OWASP) | SHA256 (есть BCrypt, Argon2, PBKDF2) | — | — | — | — |
| Пароль не попадает в логи | **да**: пароль — отдельным сообщением в чат, ядро его не видит; плюс фильтр логов | фильтр логов | — | — | — | — |
| 2FA | **TOTP, QR-код картой прямо в игре** | да | Discord / почта | TOTP | — | — |
| Почта, восстановление пароля | да | да | да | — | — | — |
| Перенос аккаунтов из других плагинов | **AuthMe и форки, nLogin, OpeNLogin, LoginSecurity, LimboAuth** | — | да (`/nlogin converter`) | да (командой) | — | — |
| Подсказки игроку по центру экрана | **да** (вкл/выкл, время, повторы) | — | — | — | — | — |
| Языки из коробки | русский, английский (авто по клиенту) | много | много | много | много | — |
| Статус | **активно развивается** | активно | активно | активно | активно | **прекращён** автором (последняя 7.6.1) |

## Сеть на прокси (Velocity / BungeeCord)

| | **VTRegister** | LimboAuth + LimboFilter | nLogin | LibreLogin | Sonar | BotSentry |
|---|---|---|---|---|---|---|
| Где стоит | **один jar: на сервере и на прокси** | только Velocity (+ LimboAPI) | сервер и прокси | Velocity, Bungee, Paper | Velocity, Bungee, Bukkit | Spigot, Bungee, Velocity, Sponge |
| Вход | да | LimboAuth | да | да | нет | нет |
| Антибот | **встроен в вход** | LimboFilter: капча, проверка настроек и бренда клиента | нет | нет | физика, транспорт, протокол, очередь | AntiBot + AntiVPN |
| Открытый код | **да** | да (AGPL-3.0) | основной плагин — нет | да | да | нет |
| Цена | **бесплатно** | бесплатно | — | бесплатно | бесплатно | платный (~11 €) |
| Общий вход для серверов сети | мост с подписью HMAC + запрет `/server` до входа | вход на прокси | вход на прокси | вход на прокси | — | — |

**Можно ли совмещать?** Да. Если сеть уже защищена Sonar или LimboFilter, VTRegister ставится как плагин входа, а свой антибот выключается одной строкой (`antibot.enabled: false`). На одиночном сервере без прокси отдельный антибот не нужен — он уже встроен.

## Почему VTRegister — разумный выбор по умолчанию

1. **Ставишь — и работает.** Без настройки включены: защищённый ввод пароля, Argon2id, проверка новичков (физика + пазл, ~30–40 секунд для человека) и постоянных игроков раз в 24 часа, не больше 3 аккаунтов на IP, лимиты подключений, проверка пинга при атаке, контроль флуда пакетами.
2. **Для продвинутых — всё настраивается.** 464 параметра с описанием каждой строки (русский и английский): 10 этапов антибота, очередь и лобби (`antibot.fast_mode: false`), баны, 2FA, почта, SQL-базы, прокси, подсказки на экране, автоочистка, API.
3. **Безопасность проверяема.** Открытый код; ники, IP и пароли не уходят с сервера; из сетевого по умолчанию включена только анонимная статистика bStats, список всех обращений — в [SECURITY.md](../SECURITY.md), у каждого релиза опубликован SHA-256.
4. **Не наказывает живых игроков.** Баны только за доказанный провал проверки; честно падающие клиенты 1.8–26.2 проходят физику 21 из 21 в замерах.
5. **Лёгкий для сервера.** Хеширование паролей — в отдельных потоках; при входе 50 игроков за секунду доля плагина в главном потоке ~4 %, пик MSPT 43 мс (у прошлой версии 216 мс).

## Когда VTRegister — не лучший вариант

- **Folia:** вход и регистрация работают, но проверка с этапами (физика, пазл) на Folia отключена — нужен антибот на прокси.
- **Онлайн-режим без пиратов:** если все игроки лицензионные (online-mode: true), плагин входа не нужен вовсе.
- **Сеть только на Velocity с уже настроенными LimboAuth + LimboFilter:** переход оправдан ради Argon2id, 2FA с QR в игре и проверки поведения, но работающую систему менять не обязательно.

---

<a name="english"></a>

# VTRegister vs other login plugins and anti-bots

[Русский](#vtregister-и-другие-плагины-входа-и-антиботы) · **English**

Compiled from the projects' official pages and repositories (October 2026). If something changed, open an Issue and we will fix it. VTRegister is a login plugin with a **built-in behavioral anti-bot**; most others solve only one of the two problems.

"—" in the tables means: absent or not stated on the project's official page.

## The key difference in one paragraph

On a plain server without a proxy, bots join in waves, register accounts, spam and take the server down: a regular login plugin (AuthMe, nLogin, LibreLogin) checks only the password, while dedicated anti-bots (Sonar, LimboFilter, BotSentry) mostly target proxies or are paid. **VTRegister installs as one jar directly on the server and gives both a secure login and a behavior check for newcomers** (fall physics verified against the vanilla client formula + a picture puzzle). IP bans only for a proven failure — real players are never punished. Open source and free.

## Single server (Paper / Spigot without a proxy)

| | **VTRegister** | AuthMeReloaded | nLogin | LibreLogin | Sonar | EpicGuard |
|---|---|---|---|---|---|---|
| What it is | login + anti-bot | login | login | login | anti-bot | anti-bot |
| Open source | **yes** (GPL-3.0 + additional terms or VMIT) | yes (GPL-3.0) | main plugin — no | yes (MPL-2.0) | yes (GPL-3.0) | yes |
| Price | **free** | free | — | free | free | free |
| Newcomer behavior check | **fall physics + puzzle, 8 more optional stages** | no (join-rate anti-bot) | no | no | physics (gravity, collisions), vehicles | no (geo, VPN, name, ping, client settings) |
| IP ban only for a proven failure | **yes**; lag, timeout, AFK never ban | — | — | — | — | — |
| Default password hash | **Argon2id** (OWASP) | SHA256 (BCrypt, Argon2, PBKDF2 available) | — | — | — | — |
| Password never reaches logs | **yes**: typed as a separate chat message the server core never sees; plus a log filter | log filter | — | — | — | — |
| 2FA | **TOTP, QR code as an in-game map** | yes | Discord / e-mail | TOTP | — | — |
| E-mail, password recovery | yes | yes | yes | — | — | — |
| Migration from other plugins | **AuthMe and forks, nLogin, OpeNLogin, LoginSecurity, LimboAuth** | — | yes (`/nlogin converter`) | yes (command) | — | — |
| On-screen hints for players | **yes** (toggle, duration, repeats) | — | — | — | — | — |
| Built-in languages | Russian, English (auto by client) | many | many | many | many | — |
| Status | **actively developed** | active | active | active | active | **discontinued** by the author (last 7.6.1) |

## Proxy network (Velocity / BungeeCord)

| | **VTRegister** | LimboAuth + LimboFilter | nLogin | LibreLogin | Sonar | BotSentry |
|---|---|---|---|---|---|---|
| Where it runs | **one jar: server and proxy** | Velocity only (+ LimboAPI) | server and proxy | Velocity, Bungee, Paper | Velocity, Bungee, Bukkit | Spigot, Bungee, Velocity, Sponge |
| Login | yes | LimboAuth | yes | yes | no | no |
| Anti-bot | **built into the login** | LimboFilter: captcha, client settings and brand checks | no | no | physics, vehicles, protocol, queue | AntiBot + AntiVPN |
| Open source | **yes** | yes (AGPL-3.0) | main plugin — no | yes | yes | no |
| Price | **free** | free | — | free | free | paid (~€11) |
| Shared login across the network | HMAC-signed bridge + `/server` blocked before login | login on the proxy | login on the proxy | login on the proxy | — | — |

**Can they be combined?** Yes. If the network is already protected by Sonar or LimboFilter, install VTRegister as the login plugin and turn its anti-bot off with one line (`antibot.enabled: false`). On a single server without a proxy no separate anti-bot is needed — it is built in.

## Why VTRegister is a sensible default

1. **Install and it works.** Without configuration: secure password input, Argon2id, newcomer check (physics + puzzle, ~30–40 seconds for a human) and a re-check of regular players once every 24 hours, at most 3 accounts per IP, connection limits, ping check during attacks, packet-flood control.
2. **Everything is configurable for advanced servers.** 464 settings with a description for every line (Russian and English): 10 anti-bot stages, queue and lobby (`antibot.fast_mode: false`), bans, 2FA, e-mail, SQL databases, proxy, on-screen hints, auto-purge, API.
3. **Verifiable security.** Open source; names, IPs and passwords never leave the server; the only network feature on by default is anonymous bStats statistics, all network access is listed in [SECURITY.md](../SECURITY.md), every release has a published SHA-256.
4. **Does not punish real players.** Bans only for a proven failure; honestly falling clients 1.8–26.2 pass the physics check 21 out of 21 in our measurements.
5. **Light on the server.** Password hashing runs on separate threads; with 50 players logging in within a second the plugin takes ~4% of the main thread, MSPT peak 43 ms (216 ms in the previous version).

## When VTRegister is not the best fit

- **Folia:** login and registration work, but the staged check (physics, puzzle) is disabled on Folia — use a proxy anti-bot.
- **Online mode only:** if all players are licensed (online-mode: true), you do not need a login plugin at all.
- **A Velocity-only network with LimboAuth + LimboFilter already set up:** switching is worth it for Argon2id, in-game QR 2FA and the behavior check, but you do not have to replace a working setup.
