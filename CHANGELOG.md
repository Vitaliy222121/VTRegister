# Changelog

**Русский** · [English](#english)

## [1.1.5] — 2026-09-27

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

## [1.1.0] — 2026-09-18
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

## [1.1.5] — 2026-09-27

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

## [1.1.0] — 2026-09-18
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
