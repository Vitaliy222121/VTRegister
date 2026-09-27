# VTRegister 1.1.5

**Русский** · [English](#english)

Плагин регистрации и авторизации для Minecraft-серверов: защищённый ввод пароля, антибот из 10 этапов, 2FA по QR-коду, почта, прокси, базы данных. Один jar — и для сервера, и для прокси.

## Скачать

- **Последняя версия 1.1.5** — вкладка [Releases](https://github.com/Vitaliy222121/VTRegister/releases) или [MineLeak.pro](https://mineleak.pro/resources/plagin-dlya-registratsii-avtorizatsii-k-vam-na-server-vtregister.5892/).
- **Все версии, от 1.0.0 до 1.1.5**, лежат на [MineLeak.pro](https://mineleak.pro/resources/plagin-dlya-registratsii-avtorizatsii-k-vam-na-server-vtregister.5892/) — с него проект и начался.
- **Нужно попроще и полегче** — только безопасная регистрация и вход, без антибота и остального? Бери **1.0.3** с MineLeak.pro.

---

## Поддержка

| Что | Версии |
|---|---|
| Серверы | Paper, Spigot, Purpur, Pufferfish, Leaf, Folia и форки |
| Прокси | Velocity 3.x, BungeeCord, Waterfall и форки (тот же jar) |
| Minecraft (ядро) | 1.13 → 1.21.x → 26.x (новая нумерация Mojang) |
| Java | 8 → 25 |
| Клиенты | 1.7 → новейшие через ViaVersion / ViaBackwards / ViaRewind |
| Bedrock | Geyser + Floodgate |

Сборка проверена против API 1.13.2, 1.14.4, 1.15.2, 1.16.5, 1.21.4 и 26.2, запуск — на Java 8, 17, 21 и 25. Ядра 1.8–1.12 не поддерживаются (другой набор блоков и карт): для старых клиентов ставь ядро 1.13+ с ViaVersion.

## Установка

1. Положи `VTRegister-v1.1.5.jar` в `plugins/` и запусти сервер.
2. В `plugins/VTRegister/` появятся `config.yml`, `advanced.yml`, `lang/ru.yml`, `lang/en.yml` — всё уже настроено.
3. Есть прокси? Тот же jar — в `plugins/` прокси, подробности в `PROXY_SETUP.txt`.

Обновление — замена jar. Новые настройки допишутся сами вместе с описаниями, твои значения и комментарии сохранятся. Папка прошлой версии `plugins/RegisterPlugin` переносится автоматически. Подробная инструкция внутри jar: `INSTRUCTION_RU.txt`.

## Возможности

**Пароль и аккаунты**
- Защищённый ввод: пароль пишется следующим сообщением в чат и не попадает в консоль и логи.
- Argon2id; старые хеши PBKDF2 обновляются сами при следующем входе.
- 2FA (Google Authenticator, Яндекс Ключ): `/2fa on` выдаёт карту с QR-кодом, повтор кода заблокирован. Обязательная 2FA для админов — по желанию.
- Почта: привязка и восстановление пароля; Gmail, Яндекс и Mail.ru — одна строка `email.provider`.
- Премиум-автовход (online-mode), вход по IP-сессии, одна сессия на ник.
- Хранилище: YAML, SQLite, MySQL, MariaDB, PostgreSQL. Импорт из AuthMe и LimboAuth.
- Журнал входов, оповещения в Discord/Telegram о входе админа с нового IP.

**Антибот** — отдельный пустой мир, каждый этап включается отдельно

| Этап | По умолчанию | Суть |
|---|---|---|
| `fall` | вкл | 5 повторов физики по случайному плану: падение, высокое падение, паутина, подброс |
| `puzzle` | вкл | «оставь только свинок» на картах в рамках |
| `block` | вкл | открыть и закрыть сундук, взять инструмент, сломать нужный блок |
| `camera` | выкл | поворот камеры |
| `slots` | выкл | переключение слотов хотбара |
| `captcha` | выкл | код в чате или на карте; «зачёркнутый» вариант против распознавания |
| `click` | выкл | кнопка «Я НЕ БОТ» среди обманок, порядок и цвета меняются |
| `math` | выкл | пример на сложение (умножение — опция) |
| `secret` | выкл | написать секретное слово |
| `air_captcha` | выкл | код из огромных разноцветных блоков по диагонали в воздухе |

**Защита от атак**
- Бан IP только за доказанный провал: первые 2 — кик, дальше 1 → 5 → 15 минут. Таймаут, лаг и AFK не банят никогда.
- Лимит подключений в секунду и минуту, проверка пинга, блок хостингов/VPN (опция).
- Режим наплыва: проверки стартуют дозированно.
- Контроль пакетов с первой секунды, анализ темпа ответов.
- Общий чёрный список между серверами-партнёрами (опция).
- До входа игрок изолирован: чат, команды, мир, телепорты, урон, предметы закрыты; неавторизованные скрыты друг от друга.

**Прокси**
- До входа запрещены `/server` и смена сервера, после — перенос на нужный сервер.
- Подписанный мост: вход на одном сервере засчитывается во всей сети.
- Консоль подсказывает, если переадресация настроена неверно.

**Оптимизация**
- Мир проверки пустой, прорисовка — 3 чанка.
- Игрок появляется сразу на платформе входа, без загрузки основного мира.
- После проверки — прямо на платформу, без перелёта туда-обратно.
- Хеширование и работа с базой вне главного потока, арены восстанавливаются порциями.

**Прочее**
- Русский и английский, автоопределение языка.
- Автоперезапуск сервера (по умолчанию раз в 48 часов в 00:00 МСК, настройка в `advanced.yml`).
- Точки спавна: до входа, после входа, при первом заходе. Лобби ожидания с паркуром и PvP-зоной (опция).

## Чем 1.1.5 отличается от прошлых версий

| | 1.0.1 – 1.0.3 | 1.1.0 | **1.1.5** |
|---|---|---|---|
| Ядра Minecraft | 1.16.5 – 1.21.11 | 1.16.5 → 26.x | **1.13 → 26.x** |
| Java | н/д | 8+ | **8 → 25 (проверено)** |
| Прокси | режим в конфиге (с 1.0.2) | автоопределение | **модуль для Velocity/BungeeCord в том же jar + мост между серверами** |
| Хеш пароля | н/д | PBKDF2-SHA512 + pepper | **Argon2id** (старые хеши обновляются сами) |
| Антибот | нет | 5 этапов | **10 этапов**, умный план физики, пазл, блок, капча в воздухе |
| Баны ботов | нет | нет | **ступени 1/5/15 мин, только по фактам** |
| Защита от наплыва | нет | очередь | **лимиты подключений, пинг, режим наплыва, контроль пакетов** |
| 2FA | нет | нет | **TOTP с QR-кодом на карте** |
| Почта | нет | нет | **привязка и восстановление, Gmail/Яндекс/Mail.ru** |
| Базы данных | YAML | YAML | **YAML, SQLite, MySQL, MariaDB, PostgreSQL** |
| Импорт | нет | нет | **AuthMe, LimboAuth** |
| Языки | ru | ru | **ru, en** |
| Bedrock | Floodgate (с 1.0.3) | Floodgate | Floodgate + мягкая физика в антиботе |
| Нагрузка | — | — | **спавн на платформе, меньше перелётов между мирами, кэш цветов** |

Полный список изменений — [CHANGELOG.md](CHANGELOG.md).

## Команды

| Команда | Кто | Что делает |
|---|---|---|
| `/register`, `/reg` | все | регистрация |
| `/login`, `/l` | все | вход |
| `/changepassword` (`/changepw`, `/cp`, `/passwd`) | все | смена пароля |
| `/2fa on \| off \| cancel \| <код>` | все | двухфакторная защита |
| `/email <адрес> \| <код>` | все | привязка почты |
| `/recover`, затем `/recover <код> <пароль>` | все | восстановление пароля |
| `/vtregister` (`/vtr`, `/vreg`) `help \| status \| cmds` | все | справка |
| `/vtregister reload` | админ | перечитать настройки |
| `/authadmin reload \| status \| info \| list \| reset \| unregister \| setpw \| logout \| forcelogin` | админ | управление аккаунтами |
| `/authadmin setspawn \| import \| unban \| testmail \| pvpkit \| lobby` | админ | спавны, импорт, снятие банов, проверка почты, лобби |

## Права

| Право | По умолчанию | Даёт |
|---|---|---|
| `registerplugin.admin` | OP | `/authadmin`, `/vtregister reload`; признак админа для обязательной 2FA (`twofactor.admin_permission`) |
| `vtregister.afk.bypass` | нет | без AFK-кика после входа |

## PlaceholderAPI

`%vtregister_authenticated%`, `%vtregister_registered%`, `%vtregister_storage%`, `%vtregister_totp%`, `%vtregister_email%`, `%vtregister_waiting%`, `%vtregister_mode%`, `%vtregister_antibot_queue%`, `%vtregister_accounts%`

## Файлы

```
plugins/VTRegister/
├── config.yml          основные настройки (у каждой строки есть описание)
├── advanced.yml        база данных, лимиты, автоперезапуск
├── easy-passwords.yml  разрешённые «лёгкие» пароли
├── lang/ru.yml, en.yml все тексты
└── data/               служебные данные (не править вручную)
```

## Сборка

```bash
mvn clean package
```

Результат: `target/VTRegister-v1.1.5.jar` (байткод Java 8). Сборка защищена от подмены: jar из этих исходников без изменений запускается, изменённый код — нет. Так задумано, подробнее в `LICENSE`.

## Лицензия

Двойная, на выбор: **GPL-3.0 с дополнительными условиями** или **Vorchun MIT-style License (VMIT)**, см. `LICENSE` и `NOTICE`. При распространении обязательны указание автора (Vorchun) и ссылка на официальную страницу; изменённые версии нельзя выпускать под именем «VTRegister».

## Честно о границах

- Ни один плагин не может полностью скрыть пароль от другого плагина в том же процессе; защищённый режим перехватывает ввод раньше остальных.
- Строку `issued server command` пишет само ядро; плагин отключает её (`security.command_logging: fix`) и ставит фильтр логов.
- На Folia платформа входа отключается (Folia ограничивает работу с чужими регионами), авторизация работает.
- Премиум-автовход — только для online-mode или за прокси с защищённой переадресацией.

Поддержка и идеи — обсуждения на MineLeak.pro (автор Vorchun).

---

<a name="english"></a>

# VTRegister 1.1.5 (English)

[Русский](#vtregister-115) · **English**

A registration and login plugin for Minecraft servers: secure password input, a 10-stage anti-bot, QR-code 2FA, e-mail, proxy support, databases. One jar for both the server and the proxy.

## Download

- **Latest version 1.1.5** — the [Releases](https://github.com/Vitaliy222121/VTRegister/releases) tab or [MineLeak.pro](https://mineleak.pro/resources/plagin-dlya-registratsii-avtorizatsii-k-vam-na-server-vtregister.5892/).
- **Every version from 1.0.0 to 1.1.5** is on [MineLeak.pro](https://mineleak.pro/resources/plagin-dlya-registratsii-avtorizatsii-k-vam-na-server-vtregister.5892/) — that's where the project started.
- **Want something simpler and lighter** — just secure registration and login, no anti-bot or extras? Take **1.0.3** from MineLeak.pro.

## Support

| What | Versions |
|---|---|
| Servers | Paper, Spigot, Purpur, Pufferfish, Leaf, Folia and forks |
| Proxies | Velocity 3.x, BungeeCord, Waterfall and forks (same jar) |
| Minecraft (server) | 1.13 → 1.21.x → 26.x (Mojang's new numbering) |
| Java | 8 → 25 |
| Clients | 1.7 → latest via ViaVersion / ViaBackwards / ViaRewind |
| Bedrock | Geyser + Floodgate |

Compiled against API 1.13.2, 1.14.4, 1.15.2, 1.16.5, 1.21.4 and 26.2; tested on Java 8, 17, 21 and 25. 1.8–1.12 servers are not supported (different block and map API) — use a 1.13+ server with ViaVersion for old clients.

## Installation

1. Put `VTRegister-v1.1.5.jar` into `plugins/` and start the server.
2. `config.yml`, `advanced.yml`, `lang/ru.yml`, `lang/en.yml` appear in `plugins/VTRegister/` — everything is preconfigured.
3. Using a proxy? The same jar goes into the proxy's `plugins/` — see `PROXY_SETUP.txt`.

Updating means replacing the jar. New settings are added automatically with their descriptions; your values and comments are kept. The old `plugins/RegisterPlugin` folder is migrated automatically. A detailed guide ships inside the jar: `INSTRUCTION_EN.txt`.

## Features

**Passwords and accounts**
- Secure input: the password is typed as the next chat message and never reaches the console or logs.
- Argon2id; old PBKDF2 hashes are upgraded on the next login.
- 2FA (Google Authenticator, Aegis…): `/2fa on` puts a QR-code map in your hand; code reuse is blocked. Mandatory 2FA for admins — optional.
- E-mail: binding and password recovery; Gmail, Yandex and Mail.ru need a single `email.provider` line.
- Premium auto-login (online-mode), IP sessions, one session per name.
- Storage: YAML, SQLite, MySQL, MariaDB, PostgreSQL. Import from AuthMe and LimboAuth.
- Login journal, Discord/Telegram alerts when an admin joins from a new IP.

**Anti-bot** — a separate empty world, every stage can be toggled

| Stage | Default | What it does |
|---|---|---|
| `fall` | on | 5 physics runs in a random plan: fall, high fall, cobweb, launch |
| `puzzle` | on | "keep only the pigs" on maps in item frames |
| `block` | on | open and close a chest, take the tool, break the right block |
| `camera` | off | turn the camera |
| `slots` | off | switch hotbar slots |
| `captcha` | off | code in chat or on a map; a "crossed-out" variant against OCR |
| `click` | off | "I'M NOT A BOT" button among decoys, order and colors change |
| `math` | off | an addition example (multiplication optional) |
| `secret` | off | type a secret word |
| `air_captcha` | off | a code of huge multi-colored blocks on a diagonal in the sky |

**Attack protection**
- IP bans only for proven fails: the first 2 kick, then 1 → 5 → 15 minutes. Timeouts, lag and AFK never ban.
- Connection limits per second and minute, ping check, hosting/VPN block (optional).
- Surge mode: checks start at a controlled rate.
- Packet watch from the first second, answer-rhythm analysis.
- Shared blacklist between partner servers (optional).
- Before login the player is isolated: chat, commands, world, teleports, damage and items are locked; unauthenticated players are hidden from each other.

**Proxy**
- `/server` and server switching are blocked before login; afterwards the player is sent to the right server.
- Signed bridge: logging in on one server counts across the network.
- The console explains a wrong forwarding setup.

**Optimisation**
- Empty check world, 3-chunk view distance.
- Players spawn right on the login platform — the main world is not loaded for them.
- After the check — straight to the platform, no round trip through the main world.
- Hashing and database work run off the main thread; arenas are restored in portions.

**Other**
- Russian and English, language auto-detection.
- Scheduled server restart (default: every 48 h at 00:00 Moscow time, configurable in `advanced.yml`).
- Spawn points: before login, after login, first join. Waiting lobby with parkour and a PvP zone (optional).

## What 1.1.5 changes compared to earlier versions

| | 1.0.1 – 1.0.3 | 1.1.0 | **1.1.5** |
|---|---|---|---|
| Minecraft servers | 1.16.5 – 1.21.11 | 1.16.5 → 26.x | **1.13 → 26.x** |
| Java | n/a | 8+ | **8 → 25 (tested)** |
| Proxy | config mode (since 1.0.2) | auto-detection | **Velocity/BungeeCord module in the same jar + cross-server bridge** |
| Password hash | n/a | PBKDF2-SHA512 + pepper | **Argon2id** (old hashes upgrade automatically) |
| Anti-bot | none | 5 stages | **10 stages**, smart physics plan, puzzle, block, sky captcha |
| Bot bans | none | none | **1/5/15-minute tiers, proven fails only** |
| Flood protection | none | queue | **connection limits, ping check, surge mode, packet watch** |
| 2FA | none | none | **TOTP with a QR code on a map** |
| E-mail | none | none | **binding and recovery, Gmail/Yandex/Mail.ru** |
| Databases | YAML | YAML | **YAML, SQLite, MySQL, MariaDB, PostgreSQL** |
| Import | none | none | **AuthMe, LimboAuth** |
| Languages | ru | ru | **ru, en** |
| Bedrock | Floodgate (since 1.0.3) | Floodgate | Floodgate + softer anti-bot physics |
| Server load | — | — | **spawn on platform, fewer world changes, color cache** |

Full list — [CHANGELOG.md](CHANGELOG.md).

## Commands

| Command | Who | What |
|---|---|---|
| `/register`, `/reg` | everyone | register |
| `/login`, `/l` | everyone | log in |
| `/changepassword` (`/changepw`, `/cp`, `/passwd`) | everyone | change password |
| `/2fa on \| off \| cancel \| <code>` | everyone | two-factor protection |
| `/email <address> \| <code>` | everyone | bind e-mail |
| `/recover`, then `/recover <code> <password>` | everyone | password recovery |
| `/vtregister` (`/vtr`, `/vreg`) `help \| status \| cmds` | everyone | help |
| `/vtregister reload` | admin | reload settings |
| `/authadmin reload \| status \| info \| list \| reset \| unregister \| setpw \| logout \| forcelogin` | admin | account management |
| `/authadmin setspawn \| import \| unban \| testmail \| pvpkit \| lobby` | admin | spawns, import, unban, mail test, lobby |

## Permissions

| Permission | Default | Grants |
|---|---|---|
| `registerplugin.admin` | OP | `/authadmin`, `/vtregister reload`; counts as admin for mandatory 2FA (`twofactor.admin_permission`) |
| `vtregister.afk.bypass` | no | no AFK kick after login |

## PlaceholderAPI

`%vtregister_authenticated%`, `%vtregister_registered%`, `%vtregister_storage%`, `%vtregister_totp%`, `%vtregister_email%`, `%vtregister_waiting%`, `%vtregister_mode%`, `%vtregister_antibot_queue%`, `%vtregister_accounts%`

## Files

```
plugins/VTRegister/
├── config.yml          main settings (every line has a description)
├── advanced.yml        database, limits, scheduled restart
├── easy-passwords.yml  allowed "easy" passwords
├── lang/ru.yml, en.yml all texts
└── data/               internal data (do not edit)
```

## Building

```bash
mvn clean package
```

Output: `target/VTRegister-v1.1.5.jar` (Java 8 bytecode). The build is tamper-protected: a jar built from these unmodified sources runs, modified code does not. This is intentional — see `LICENSE`.

## License

Dual, your choice: **GPL-3.0 with additional terms** or **Vorchun MIT-style License (VMIT)** — see `LICENSE` and `NOTICE`. Redistribution must credit the author (Vorchun) and link the official page; modified versions must not be released under the name "VTRegister".

## Honest limits

- No plugin can fully hide a password from another plugin in the same process; secure mode intercepts input earlier than the others.
- The `issued server command` line is written by the server itself; the plugin disables it (`security.command_logging: fix`) and adds a log filter.
- On Folia the login platform is disabled (Folia restricts cross-region work); authentication works.
- Premium auto-login requires online-mode or a proxy with secure forwarding.

Support and ideas — MineLeak.pro discussions (author Vorchun).
