# Changelog

**Русский** · [English](#english)

## [1.1.6.1] — 2026-10-03 (лёгкий фикс)

- Анонимная статистика bStats включена по умолчанию; настройка перенесена в `advanced.yml` (`bstats.enabled`). Отправляются версии, ОС, число игроков, страна сервера и 4 настройки плагина — без ников, IP и паролей. Старая строка `metrics` из config.yml 1.1.6 убирается при обновлении автоматически.

## [1.1.6] — 2026-10-01

**Исправление двух критических ошибок 1.1.5 — обновиться всем.**

### Критические исправления
- Без PlaceholderAPI плагин выключал сам себя при запуске (проверка целостности принимала отсутствие PlaceholderAPI за повреждённый jar) — сервер оставался без авторизации.
- Paper 1.20.5–1.21.11 не загружал плагин: ядро переписывает («ремапит») jar, и проверка целостности его отвергала. Теперь jar помечен как не требующий ремапа.

### Настройки по умолчанию — «поставил и работает»
- Антибот в быстром режиме: проверка сразу при входе, без очередей; этапы — физика падения и пазл (задание с блоком выключено, включается одной строкой).
- Постоянные игроки проходят проверку на бота снова раз в 24 часа — при первом входе после истечения срока (`antibot.recheck_hours: 24`, 0 — не перепроверять); кто в игре дольше суток, не выкидывается. Защита от «спящих» бот-аккаунтов. Перепроверка после каждого рестарта (`recheck_on_restart`) выключена.
- Лимит аккаунтов на один IP — 3, включён (`ip_limit`: выключатель и число). Новый ник сверх лимита получает отказ при входе и при /register; в свои аккаунты входить можно всегда. Одновременные регистрации с одного IP не обходят лимит.
- Argon2id по рекомендации OWASP (19 МБ, 2 прохода): вход в ~5 раз легче для процессора, сервер не подвисает при массовом входе.
- Напоминание «/reg» в чат — раз в 10 секунд (в actionbar — как раньше).
- **Обновление с 1.1.5:** новые значения по умолчанию (быстрый антибот физика + пазл, Argon2 OWASP, без перепроверки после каждого рестарта) применяются один раз — только там, где в конфиге стояли прежние значения по умолчанию; свои настройки владельца не меняются. Обычный режим с настроенными под себя этапами или очередью остаётся. Что поменялось — одна строка в консоли; после этого строки можно менять как угодно. Свой старый лимит аккаунтов на IP (`antibot.guard.max_accounts_per_ip`) переносится в `ip_limit`.

### Новое
- **Подсказки по центру экрана:** что делать прямо сейчас — зарегистрироваться, войти, ввести пароль, пройти этап. Отдельные выключатели для входа, этапов, отсчёта и «Готово!», время на экране, частота и число повторов, плавность (`screen_hints`). Отсчёт «Готовься!» и боссбар перед проверкой больше не вшиты в код по-русски — тексты в `lang`.
- **API для разработчиков** ([docs/API.md](docs/API.md)): события входа (любым способом, с указанием способа), прохождения и провала антибота; вход паролем из своего GUI, проверка и смена пароля; доступ через ServicesManager.
- **Перенос аккаунтов:** AuthMe и старые форки (SQLite, MySQL, MariaDB, PostgreSQL по их config.yml, текстовый auths.db), nLogin и OpeNLogin. 23 формата хешей: SHA256/SHA512/MD5/SHA1 в разных вариантах, BCrypt, Argon2i/Argon2id, PBKDF2; игроки входят старым паролем, после входа он перехешируется в Argon2id. Хеши, которые проверить нельзя, не переносятся и считаются в отчёте.
- **`/vtregister reset <config|advanced|lang|all>`** — вернуть настройки по умолчанию: основные, расширенные, тексты или всё. С подтверждением, старые файлы — копией в `backups/`; «перец» паролей и подключение к базе сохраняются, чтобы игроки не потеряли аккаунты.
- Справка `/vtr help` на языке игрока (тексты в `lang`), без несуществующих русских сокращений; после регистрации подсказка «Сменить пароль — /cp».
- **bStats — по желанию**, по умолчанию выключено (`metrics.enabled`). Официальный класс bStats, страница статистики: https://bstats.org/plugin/bukkit/VTRegister/34444.
- **Автоочистка неактивных аккаунтов** — по желанию, по умолчанию выключена (`purge` в advanced.yml); аккаунты с почтой и 2FA не трогает.
- **Смена хранилища YAML → SQLite/MySQL** переносит аккаунты автоматически (если новая база пуста); старый файл сохраняется как `accounts.yml.migrated`.
- **SQLite:** ожидание занятой базы (`storage.sqlite_busy_timeout_ms`) вместо ошибки «database is locked».

### Оптимизация (замер: Paper 26.2, Java 25, боты 1.8–26.2)
- Вход 50 зарегистрированных за 1 секунду: процессор на хеш паролей −84%, паузы сборщика мусора −79% (53 мс против 258), пик MSPT 43 мс против 216.
- Paper 1.21.9+: спавн на платформе входа через асинхронное событие — без раннего создания игрока и предупреждения ядра.

### Исправления
- Фильтр, убирающий пароли из логов, не устанавливался ни на одном ядре — теперь работает (и корректно снимается на старых ядрах).
- Игрока, прошедшего проверку, кикало за AFK через 15 секунд, пока он читал «/reg».
- Этапы «пример» и «секретное слово» показывали «{stage_num}/{stage_total}» и не учитывали язык игрока.
- Событие входа для других плагинов не приходило при входе по IP-сессии, премиуму, Bedrock и через API.
- На ядрах 26.x мир проверки не держал вечный день (Mojang переименовал игровые правила) — ночью на платформах могли появляться монстры; правила ставятся с учётом новых имён.
- В документации было сказано, что на Folia антибот работает при ручном создании мира, — это не так: этапы на Folia отключены, текст исправлен.
- Ложная ошибка «ПРОКСИ НЕ НАСТРОЕН» раз в минуту, если игрок заходил с localhost/LAN.
- Реклама плагина в боссбаре игроков убрана, ссылки MineLeak заменены на GitHub (у установленных серверов — автоматически, свои тексты не трогаются).

### Проверено
- Клиенты 1.8, 1.12.2, 1.16.5, 1.21.4, 1.21.8, 1.21.11, 26.2; модовые клиенты Fabric, Forge, NeoForge.
- Ядра Paper 1.13.2 и 1.16.5 (Java 8), 1.20.4 (Java 17), 1.21.4, 1.21.8, 1.21.11 (Java 21), 26.2 (Java 25); Folia 1.21.11 (вход и регистрация; этапы антибота на Folia не работают).

## [1.1.5] — 2026-09-27

**Самая продвинутая версия за всю историю плагина** — переработанное ядро, поведенческий антибот нового поколения, безопасность по умолчанию, оптимизированный вход. Версии 1.1.0 и ниже устарели и не поддерживаются.

Новое имя: RegisterPlugin → VTRegister. Папка `plugins/RegisterPlugin` переносится автоматически, аккаунты и настройки сохраняются. Обновление — замена jar: недостающие параметры допишутся сами вместе с описаниями.

### Совместимость
- Один jar для Paper, Spigot, Purpur, Folia и форков, а также Velocity и BungeeCord/Waterfall.
- Ядра 1.13 → 1.21.x → 26.x, Java 8 → 25.
- Клиенты 1.7 → новейшие через ViaVersion / ViaBackwards / ViaRewind; Bedrock — мягкие допуски физики.

### Антибот (10 этапов, каждый включается отдельно)
- Падение: 5 повторов по случайному плану — обычное, высокое, паутина, подброс.
- Камера и слоты хотбара.
- Капча в чате или на карте; «зачёркнутый» вариант против распознавания.
- Кнопка «Я НЕ БОТ» среди обманок, порядок и цвета меняются.
- Пазл «оставь только свинок».
- Блок: открыть и закрыть сундук, взять инструмент, сломать нужный блок.
- Пример на сложение (умножение — опция), секретное слово.
- Код из огромных разноцветных блоков по диагонали в воздухе.
- Быстрый режим без очередей; лобби ожидания с паркуром и PvP-зоной.

### Защита от атак
- Бан IP только за доказанный провал: первые 2 — кик, затем 1 → 5 → 15 минут; навсегда — по желанию.
- Лимит подключений в секунду и минуту, проверка пинга, блок хостингов/VPN.
- Режим наплыва, контроль пакетов с первой секунды, анализ темпа ответов.
- Общий чёрный список между серверами-партнёрами.

### Аккаунты
- Argon2id; старые хеши PBKDF2 обновляются при входе.
- 2FA: QR-код на карте в руке, защита от повтора кода; обязательная 2FA для админов — опция.
- Почта: привязка и восстановление; Gmail, Яндекс, Mail.ru одной строкой; `/authadmin testmail`.
- Премиум-автовход, IP-сессии, одна сессия на ник.
- Журнал входов, оповещения в Discord/Telegram.
- YAML, SQLite, MySQL, MariaDB, PostgreSQL; импорт из AuthMe и LimboAuth.

### Прокси
- Модуль Velocity/BungeeCord: до входа запрещены `/server` и смена сервера, после — перенос.
- Подписанный мост между серверами сети, подсказки при неверной переадресации.

### Оптимизация
- Спавн сразу на платформе входа, без загрузки основного мира.
- После проверки — прямо на платформу, без перелёта туда-обратно.
- Кэш цветного текста, быстрый шум на картах, арены восстанавливаются порциями.
- Хеширование и база вне главного потока.

### Прочее
- Русский и английский, автоопределение.
- Автоперезапуск сервера (по умолчанию каждые 48 часов в 00:00 МСК, `advanced.yml`).
- Новые команды: `/2fa on|off|cancel|<код>`, `/email <адрес>|<код>`, `/recover`, `/vtregister help|status|cmds|reload`, `/authadmin setspawn|import|unban|testmail|pvpkit|lobby`.
- Новое право: `vtregister.afk.bypass`.
- Новые файлы: `advanced.yml`, `lang/ru.yml`, `lang/en.yml`, `proxy-config.yml`, `INSTRUCTION_RU.txt`, `INSTRUCTION_EN.txt`.

### Безопасность
- Скачиваемый драйвер PostgreSQL проверяется по SHA-256; файл с другой суммой не загружается.
- Добавлен [SECURITY.md](SECURITY.md): сеть, файлы, действия с сервером, проверка целостности.

### Исправлено
- Обход антибота через `/reg` из очереди.
- Перезапись чужого аккаунта при сбое базы; MySQL не переподключался.
- Письма не уходили (многострочный ответ Gmail/Яндекса); код `/recover` пропадал при долгой доставке.
- Ссылка 2FA не открывалась — теперь QR; админа кикало за отсутствие 2FA.
- Velocity не загружал плагин.
- AFK-кик после входа и ложные кики в очереди.
- Повтор блоков в физике, «лишняя свинья» в пазле.
- API не находил плагин после переименования.

## [1.1.0] — 2026-09-18 · устаревшая, не поддерживается
- Защищённый и обычный режимы ввода пароля, режим всегда показывается игроку.
- Антибот в пустом мире: падение, камера, слоты, капча, клик; очередь при перегрузе.
- Полная изоляция до входа, скрытие неавторизованных, защита вещей при смерти.
- `/changepassword`, файл `easy-passwords.yml`.
- PBKDF2-SHA512 (200 000 итераций) + pepper, константное сравнение, автоматический rehash.
- Автоопределение прокси, `PROXY_SETUP.txt`; самозащита плагина.
- Ядра 1.16.5 → 26.x; HEX-цвета, свои инструкции, ConfigMerger.

## [1.0.3] — 2026-04-18
- Bedrock через Floodgate (опционально), безопасный bypass.
- Закрыты обходы до авторизации, исправлен `trim()` пароля, безопасный телепорт.
- Журнал действий админа, усиленная парольная политика, поддержка 1.21.11.

## [1.0.2] — 2026-02-10
- Эффекты, голод и прочность предметов не тратятся до входа.
- Запрет кроватей, опыта, полёта, крафта, зачарования и наковальни; защита от бездны.
- Режим прокси (Velocity/BungeeCord) в конфиге.

## [1.0.1] — 2026-01-31
- Поддержка 1.21.11; регистрация срабатывала только со второй попытки — исправлено.
- Дополнительная блокировка взаимодействий до входа.

---

<a name="english"></a>

# Changelog (English)

[Русский](#changelog) · **English**

## [1.1.6.1] — 2026-10-03 (small fix)

- Anonymous bStats statistics are on by default; the setting moved to `advanced.yml` (`bstats.enabled`). Sent: versions, OS, player count, server country and 4 plugin settings — no names, IPs or passwords. The old `metrics` line from the 1.1.6 config.yml is removed automatically on update.

## [1.1.6] — 2026-10-01

**Fixes two critical bugs of 1.1.5 — everyone should update.**

### Critical fixes
- Without PlaceholderAPI the plugin disabled itself on startup (the integrity check treated the missing PlaceholderAPI as a damaged jar), leaving the server without authentication.
- Paper 1.20.5–1.21.11 did not load the plugin: the server rewrites ("remaps") the jar and the integrity check rejected it. The jar is now marked as not needing remapping.

### Defaults — "install and it works"
- Fast anti-bot mode: the check starts right on join, no queues; stages are fall physics and the puzzle (the block task is off, one line to enable).
- Regular players pass the anti-bot check again once every 24 hours — on the first join after the period expires (`antibot.recheck_hours: 24`, 0 — never re-check); players online longer than a day are not kicked. Protects against "sleeping" bot accounts. Re-check after every restart (`recheck_on_restart`) is off.
- Account limit per IP — 3, on (`ip_limit`: switch and number). A new nickname over the limit is refused on join and on /register; existing accounts can always log in. Simultaneous registrations from one IP cannot bypass the limit.
- Argon2id with the OWASP recommendation (19 MB, 2 passes): logins are ~5x lighter on the CPU, no stutter when many players join.
- The "/reg" chat reminder is sent every 10 seconds (action bar unchanged).
- **Updating from 1.1.5:** the new defaults (fast anti-bot with physics + puzzle, OWASP Argon2, no re-check after every restart) are applied once — only where the config still had the old default values; the owner's own settings are not touched. A normal mode with customised stages or queue stays. One console line lists what changed; after that the lines can be edited freely. A custom old per-IP account limit (`antibot.guard.max_accounts_per_ip`) moves to `ip_limit`.

### New
- **On-screen hints:** what to do right now — register, log in, type the password, pass a stage. Separate switches for login, stages, countdown and "Done!", time on screen, repeat interval and count, fades (`screen_hints`). The "Get ready!" countdown and pre-check boss bar are no longer hard-coded in Russian — texts live in `lang`.
- **Developer API** ([docs/API.md](docs/API.md)): login events (any method, with the method), anti-bot pass/fail events; password login from your own GUI, password check and change; available via the ServicesManager.
- **Account migration:** AuthMe and old forks (SQLite, MySQL, MariaDB, PostgreSQL from their config.yml, the text auths.db), nLogin and OpeNLogin. 23 hash formats: SHA256/SHA512/MD5/SHA1 variants, BCrypt, Argon2i/Argon2id, PBKDF2; players log in with their old password, which is then rehashed to Argon2id. Hashes that cannot be verified are not transferred and are counted in the report.
- **`/vtregister reset <config|advanced|lang|all>`** — restore default settings: main, advanced, texts or everything. Asks for confirmation, the old files are copied to `backups/`; the password pepper and the database connection are kept so players do not lose their accounts.
- `/vtr help` in the player's language (texts in `lang`), without non-existent Russian shortcuts; after registration a hint "Change the password — /cp".
- **bStats — optional**, off by default (`metrics.enabled`). The official bStats class; statistics page: https://bstats.org/plugin/bukkit/VTRegister/34444.
- **Auto-purge of inactive accounts** — optional, off by default (`purge` in advanced.yml); accounts with e-mail or 2FA are kept.
- **Switching storage YAML → SQLite/MySQL** migrates accounts automatically (if the new database is empty); the old file is kept as `accounts.yml.migrated`.
- **SQLite:** waits for a busy database (`storage.sqlite_busy_timeout_ms`) instead of a "database is locked" error.

### Performance (measured on Paper 26.2, Java 25, bots 1.8–26.2)
- 50 registered players logging in within 1 second: password hashing CPU −84%, GC pauses −79% (53 ms vs 258), MSPT peak 43 ms vs 216.
- Paper 1.21.9+: spawning on the login platform via the async event — no early player creation, no server warning.

### Fixes
- The filter that hides passwords from logs was never installed on any server — now it works (and is removed correctly on old servers).
- A player who had passed the check was kicked for AFK after 15 seconds while reading "/reg".
- The math and secret stages showed "{stage_num}/{stage_total}" and ignored the player's language.
- The login event for other plugins did not fire for IP-session, premium, Bedrock and API logins.
- On 26.x servers the check world did not keep permanent day (Mojang renamed game rules), so monsters could spawn on platforms at night; rules are now set with the new names too.
- The docs said the anti-bot works on Folia if the world is created manually — it does not: stages are disabled on Folia, the text is fixed.
- A false "PROXY NOT CONFIGURED" error every minute when a player joined from localhost/LAN.
- Plugin ads were removed from the players' boss bar, MineLeak links replaced with GitHub (automatically on existing servers; custom texts are kept).

### Tested
- Clients 1.8, 1.12.2, 1.16.5, 1.21.4, 1.21.8, 1.21.11, 26.2; modded clients Fabric, Forge, NeoForge.
- Paper 1.13.2 and 1.16.5 (Java 8), 1.20.4 (Java 17), 1.21.4, 1.21.8, 1.21.11 (Java 21), 26.2 (Java 25); Folia 1.21.11 (login and registration; anti-bot stages do not run on Folia).

## [1.1.5] — 2026-09-27

**The most advanced version in the plugin's history** — a reworked core, a next-generation behavioral anti-bot, security by default, an optimized login. Versions 1.1.0 and older are outdated and unsupported.

Renamed: RegisterPlugin → VTRegister. The `plugins/RegisterPlugin` folder is migrated automatically; accounts and settings are kept. Updating means replacing the jar — missing settings are added with their descriptions.

### Compatibility
- One jar for Paper, Spigot, Purpur, Folia and forks, plus Velocity and BungeeCord/Waterfall.
- Servers 1.13 → 1.21.x → 26.x, Java 8 → 25.
- Clients 1.7 → latest via ViaVersion / ViaBackwards / ViaRewind; softer physics tolerances for Bedrock.

### Anti-bot (10 stages, each can be toggled)
- Fall: 5 runs in a random plan — normal, high, cobweb, launch.
- Camera and hotbar slots.
- Captcha in chat or on a map; a "crossed-out" variant against OCR.
- "I'M NOT A BOT" button among decoys, order and colors change.
- "Keep only the pigs" puzzle.
- Block: open and close a chest, take the tool, break the right block.
- Addition example (multiplication optional), secret word.
- A code of huge multi-colored blocks on a diagonal in the sky.
- Fast mode without queues; waiting lobby with parkour and a PvP zone.

### Attack protection
- IP bans only for proven fails: the first 2 kick, then 1 → 5 → 15 minutes; permanent — optional.
- Connection limits per second and minute, ping check, hosting/VPN block.
- Surge mode, packet watch from the first second, answer-rhythm analysis.
- Shared blacklist between partner servers.

### Accounts
- Argon2id; old PBKDF2 hashes upgrade on login.
- 2FA: QR code on a map in hand, code-reuse protection; mandatory admin 2FA — optional.
- E-mail binding and recovery; Gmail, Yandex, Mail.ru in one line; `/authadmin testmail`.
- Premium auto-login, IP sessions, one session per name.
- Login journal, Discord/Telegram alerts.
- YAML, SQLite, MySQL, MariaDB, PostgreSQL; import from AuthMe and LimboAuth.

### Proxy
- Velocity/BungeeCord module: `/server` and switching blocked before login, transfer afterwards.
- Signed bridge between network servers, hints for a wrong forwarding setup.

### Optimisation
- Spawn right on the login platform — no main-world load.
- After the check — straight to the platform, no round trip.
- Colored-text cache, fast map noise, arenas restored in portions.
- Hashing and database work off the main thread.

### Other
- Russian and English, auto-detection.
- Scheduled server restart (default every 48 h at 00:00 Moscow time, `advanced.yml`).
- New commands: `/2fa on|off|cancel|<code>`, `/email <address>|<code>`, `/recover`, `/vtregister help|status|cmds|reload`, `/authadmin setspawn|import|unban|testmail|pvpkit|lobby`.
- New permission: `vtregister.afk.bypass`.
- New files: `advanced.yml`, `lang/ru.yml`, `lang/en.yml`, `proxy-config.yml`, `INSTRUCTION_RU.txt`, `INSTRUCTION_EN.txt`.

### Security
- The downloaded PostgreSQL driver is verified by SHA-256; a file with a different checksum is never loaded.
- Added [SECURITY.md](SECURITY.md): network, files, server actions, integrity check.

### Fixed
- Anti-bot bypass via `/reg` from the queue.
- Overwriting someone else's account on a database error; MySQL did not reconnect.
- E-mails were not sent (multi-line Gmail/Yandex replies); the `/recover` code was lost on slow delivery.
- The 2FA link could not be opened — now a QR code; admins were kicked for having no 2FA.
- Velocity did not load the plugin.
- AFK kicks after login and false kicks in the queue.
- Repeated physics blocks, "extra pig" in the puzzle.
- The API could not find the plugin after the rename.

## [1.1.0] — 2026-09-18 · outdated, unsupported
- Secure and classic password input modes; the mode is always shown to the player.
- Anti-bot in an empty world: fall, camera, slots, captcha, click; queue under load.
- Full isolation before login, hiding unauthenticated players, item protection on death.
- `/changepassword`, `easy-passwords.yml`.
- PBKDF2-SHA512 (200,000 iterations) + pepper, constant-time comparison, automatic rehash.
- Proxy auto-detection, `PROXY_SETUP.txt`; plugin self-defense.
- Servers 1.16.5 → 26.x; HEX colors, custom instructions, ConfigMerger.

## [1.0.3] — 2026-04-18
- Bedrock via Floodgate (optional), safe bypass.
- Pre-auth bypasses closed, password `trim()` bug fixed, safe teleport.
- Admin audit trail, stronger password policy, 1.21.11 support.

## [1.0.2] — 2026-02-10
- Effects, hunger and item durability are not consumed before login.
- Beds, XP, flight, crafting, enchanting and anvils blocked; void protection.
- Proxy mode (Velocity/BungeeCord) in the config.

## [1.0.1] — 2026-01-31
- 1.21.11 support; registration only worked on the second try — fixed.
- Extra interaction blocking before login.
