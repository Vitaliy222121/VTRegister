# Безопасность и прозрачность / Security & transparency

**Русский** · [English](#english)

VTRegister — плагин с открытым исходным кодом. Здесь честно перечислено всё, что он делает с сетью, файлами и сервером, чтобы любой администратор (или ИИ-ассистент) мог проверить это по коду.

## Коротко

- **Ники, IP и пароли никуда не отправляются.** Единственное, что включено по умолчанию, — анонимная статистика bStats (версии, ОС, число игроков онлайн, страна сервера, 4 настройки плагина; https://bstats.org/plugin/bukkit/VTRegister/34444). Выключается строкой `bstats.enabled: false` в `advanced.yml` или общим `plugins/bStats/config.yml`.
- **Нет удалённого управления.** Ни команд «с сервера автора», ни удалённого отключения, ни скрытых учётных записей.
- **Пароли хранятся только как хеш** Argon2id (соль + параметры в PHC-формате). Сам плагин пароли нигде не сохраняет и в логи не пишет.
- **Остальное сетевое по умолчанию выключено** (премиум-проверка, почта, оповещения, списки VPN, общий чёрный список); драйвер PostgreSQL скачивается, только если ты сам выбрал PostgreSQL (см. ниже).
- **Подробный разбор безопасности по коду** — ответы на ~330 вопросов аудита с указанием файлов: [docs/AI_AUDIT.md](docs/AI_AUDIT.md).

## Сетевые обращения

| Куда | Зачем | По умолчанию | Настройка |
|---|---|---|---|
| `api.mojang.com` | проверка лицензии для премиум-автовхода | выкл | `premium.enabled` |
| твой SMTP-сервер (Gmail, Яндекс…) | письма с кодом привязки/восстановления | выкл | `email.enabled` |
| `api.telegram.org`, твой Discord-вебхук | оповещение о входе админа с нового IP | выкл | `security.admin_alerts` |
| `raw.githubusercontent.com/X4BNet/lists_vpn` | список подсетей хостингов/VPN | выкл | `antibot.datacenter_block` |
| серверы-партнёры (адреса задаёшь ты) | общий чёрный список банов; при `publish` открывает HTTP-порт с токеном | выкл | `global_blacklist` |
| `repo1.maven.org` | JDBC-драйвер PostgreSQL 42.7.4 | только при `storage.type: postgresql` | `advanced.yml` |
| `bstats.org` | анонимная статистика: версии сервера/Java/плагина, ОС и число ядер, онлайн-режим, число игроков, страна сервера; режим антибота, хранилище, язык, способ ввода пароля | **вкл** | `advanced.yml` → `bstats.enabled` (и общий `plugins/bStats/config.yml`) |
| база AuthMe / nLogin (адрес из их `config.yml`) | перенос аккаунтов | только по команде `/authadmin import` | — |

Скачанный драйвер PostgreSQL проверяется по **SHA-256** (`188976721ead8e8627eb6d8389d500dccc0c9bebd885268a3047180274a6031e`, официальный файл Maven Central). Файл с другой суммой удаляется и не загружается.

## Файлы

- Пишет только в свою папку `plugins/VTRegister/` (аккаунты, настройки, журнал входов, резервная копия вещей игрока на время проверки).
- Режим `security.command_logging: fix` (по умолчанию) ставит в `spigot.yml` → `commands.log: false` и добавляет фильтр логов, чтобы пароли из команд `/reg`, `/l` не попадали в консоль. Режим `warn` только предупреждает и ничего не меняет.
- Создаёт отдельный пустой мир для проверки на бота (папка мира в корне сервера), в основном мире ничего не строит.

## Действия с сервером

| Что | По умолчанию | Настройка |
|---|---|---|
| Автоперезапуск сервера (раз в 48 ч в 00:00 МСК, с предупреждениями игрокам) | вкл | `advanced.yml` → `auto_restart.enabled` |
| Команды после входа от имени консоли (например, `spawn {player}`) | выкл | `after_auth` — задаёт только админ |
| Самозащита: остановка/перезапуск при попытке другого плагина отключить защиту | выкл | `selfdefense.enabled` |

## Проверка целостности сборки

Плагин при запуске сверяет свои классы с подписью внутри jar. Если jar изменён, он **просто не запускается** и пишет в консоль «сборка повреждена». Больше ничего не происходит: ни удаления файлов, ни сетевых запросов, ни влияния на сервер или другие плагины.

Зачем это нужно: на сайтах с плагинами часто выкладывают «перепакованные» jar с вредоносным кодом. Проверка не даёт такой подмене запуститься под именем VTRegister. Исходный код открыт, и jar, собранный из него без изменений (`mvn clean package`), запускается.

**Проверь, что у тебя оригинал:** сравни SHA-256 своего jar с суммой в описании [релиза](https://github.com/Vitaliy222121/VTRegister/releases).

```bash
sha256sum VTRegister-v1.1.6.1.jar          # Linux / macOS
certutil -hashfile VTRegister-v1.1.6.1.jar SHA256   # Windows
```

SHA-256 подтверждает, что файл не подменён по дороге. Собрать jar самому можно из исходников (`mvn clean package`) — поведение будет тем же (байты jar отличаются только метками времени внутри архива).

## Как сообщить об уязвимости

Открой [Issue](https://github.com/Vitaliy222121/VTRegister/issues) с пометкой **security** — опиши, *что* затронуто (пароли, вход, антибот, мост прокси), **без** готового эксплойта; детали автор запросит отдельно. Серьёзные уязвимости исправляются в первую очередь и выходят отдельным выпуском с пометкой в CHANGELOG.

Что уже проверено по коду (см. [docs/AI_AUDIT.md](docs/AI_AUDIT.md)): все SQL-запросы с данными игроков — PreparedStatement; регистрация только через одну точку с проверкой антибота; мост прокси подписан HMAC-SHA256 и не принимает сообщения от клиентов; хеши и коды сравниваются за постоянное время; сбой базы закрывает вход, а не открывает.

---

<a name="english"></a>

# Security & transparency (English)

VTRegister is open source. This page lists honestly everything it does with the network, files and the server, so any admin (or AI assistant) can verify it against the code.

## In short

- **Names, IPs and passwords are never sent anywhere.** The only thing on by default is anonymous bStats statistics (versions, OS, online player count, server country, 4 plugin settings; https://bstats.org/plugin/bukkit/VTRegister/34444). Turn it off with `bstats.enabled: false` in `advanced.yml` or the global `plugins/bStats/config.yml`.
- **No remote control.** No commands from the author's servers, no remote kill switch, no hidden accounts.
- **Passwords are stored only as Argon2id hashes** (salt + parameters in PHC format). The plugin itself never stores or logs plain passwords.
- **Everything else that uses the network is off by default** (premium check, e-mail, alerts, VPN lists, shared blacklist); the PostgreSQL driver is downloaded only if you choose PostgreSQL yourself (see below).
- **Detailed code-level security review** — answers to ~330 audit questions with file references (in Russian): [docs/AI_AUDIT.md](docs/AI_AUDIT.md).

## Network access

| Where | Why | Default | Setting |
|---|---|---|---|
| `api.mojang.com` | license check for premium auto-login | off | `premium.enabled` |
| your SMTP server (Gmail, Yandex…) | binding/recovery code e-mails | off | `email.enabled` |
| `api.telegram.org`, your Discord webhook | alert when an admin joins from a new IP | off | `security.admin_alerts` |
| `raw.githubusercontent.com/X4BNet/lists_vpn` | hosting/VPN subnet list | off | `antibot.datacenter_block` |
| partner servers (addresses you set) | shared ban list; with `publish` opens a token-protected HTTP port | off | `global_blacklist` |
| `repo1.maven.org` | PostgreSQL JDBC driver 42.7.4 | only with `storage.type: postgresql` | `advanced.yml` |
| `bstats.org` | anonymous statistics: server/Java/plugin versions, OS and core count, online mode, player count, server country; anti-bot mode, storage, language, password input mode | **on** | `advanced.yml` → `bstats.enabled` (and the global `plugins/bStats/config.yml`) |
| AuthMe / nLogin database (address from their `config.yml`) | account migration | only on `/authadmin import` | — |

The downloaded PostgreSQL driver is verified by **SHA-256** (`188976721ead8e8627eb6d8389d500dccc0c9bebd885268a3047180274a6031e`, the official Maven Central file). A file with a different checksum is deleted and never loaded.

## Files

- Writes only to its own folder `plugins/VTRegister/` (accounts, settings, login journal, a backup of player items during the check).
- `security.command_logging: fix` (default) sets `commands.log: false` in `spigot.yml` and adds a log filter so passwords from `/reg`, `/l` never reach the console. `warn` mode only warns and changes nothing.
- Creates a separate empty world for the anti-bot check (a world folder in the server root); nothing is built in the main world.

## Server actions

| What | Default | Setting |
|---|---|---|
| Scheduled server restart (every 48 h at 00:00 Moscow time, with player warnings) | on | `advanced.yml` → `auto_restart.enabled` |
| Console commands after login (e.g. `spawn {player}`) | off | `after_auth` — set by the admin only |
| Self-defense: stop/restart when another plugin tries to disable the protection | off | `selfdefense.enabled` |

## Build integrity check

On startup the plugin verifies its classes against a signature inside the jar. If the jar was modified, it **simply does not start** and prints "build damaged" to the console. Nothing else happens: no file deletion, no network requests, no effect on the server or other plugins.

Why: plugin sites often host "repacked" jars with malicious code. The check stops such a copy from running under the VTRegister name. The source is open, and a jar built from it unchanged (`mvn clean package`) runs.

**Make sure you have the original:** compare your jar's SHA-256 with the checksum in the [release](https://github.com/Vitaliy222121/VTRegister/releases) notes.

## Reporting a vulnerability

Open an [Issue](https://github.com/Vitaliy222121/VTRegister/issues) marked **security** — describe *what* is affected (passwords, login, anti-bot, proxy bridge) **without** a ready exploit; the author will ask for details. Serious vulnerabilities are fixed first and shipped as a separate release noted in the CHANGELOG.

Already verified in code (see [docs/AI_AUDIT.md](docs/AI_AUDIT.md)): every SQL query with player data uses PreparedStatement; registration has a single entry point that checks the anti-bot; the proxy bridge is HMAC-SHA256 signed and does not accept messages from clients; hashes and codes are compared in constant time; a database failure closes login instead of opening it.
