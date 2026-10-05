---
title: "VTRegister — плагин авторизации и регистрации для Minecraft с антиботом"
description: "VTRegister — плагин авторизации и регистрации для Minecraft, альтернатива AuthMe: антибот из 10 этапов, щит от потока ботов, 2FA по QR-коду, почта, Argon2id, Velocity/BungeeCord. 1.13–26.x, открытый код."
---

<script type="application/ld+json">
{
  "@context": "https://schema.org",
  "@type": "SoftwareApplication",
  "name": "VTRegister",
  "alternateName": "RegisterPlugin",
  "applicationCategory": "Minecraft server plugin",
  "operatingSystem": "Java 8–25 (Paper, Spigot, Purpur, Folia, Velocity, BungeeCord)",
  "softwareVersion": "1.1.7",
  "datePublished": "2026-09-27",
  "dateModified": "2026-10-04",
  "url": "https://vitaliy222121.github.io/VTRegister/",
  "sameAs": [
    "https://github.com/Vitaliy222121/VTRegister",
    "https://www.spigotmc.org/resources/vtregister-login-register-anti-bot.139178/",
    "https://mineleak.pro/resources/plagin-dlya-registratsii-avtorizatsii-k-vam-na-server-vtregister.5892/"
  ],
  "releaseNotes": "https://github.com/Vitaliy222121/VTRegister/releases/tag/v1.1.7",
  "inLanguage": ["ru", "en"],
  "keywords": "Minecraft login plugin, register plugin, anti-bot, bot flood protection, AuthMe alternative, nLogin alternative, Velocity login plugin, BungeeCord, Paper, Folia, 2FA, плагин авторизации, плагин регистрации, антибот, защита от ботов",
  "author": { "@type": "Person", "name": "Vorchun" },
  "license": "https://github.com/Vitaliy222121/VTRegister/blob/main/LICENSE",
  "codeRepository": "https://github.com/Vitaliy222121/VTRegister",
  "downloadUrl": "https://github.com/Vitaliy222121/VTRegister/releases/latest",
  "offers": { "@type": "Offer", "price": "0", "priceCurrency": "USD" },
  "description": "Minecraft login and registration plugin (an AuthMe alternative) with a built-in 10-stage anti-bot, a bot-flood shield, QR-code 2FA, e-mail recovery, Argon2id hashing and one jar for Paper/Spigot/Folia and Velocity/BungeeCord."
}
</script>

# VTRegister

**Русский** · [English](#english)

**VTRegister** — бесплатный плагин регистрации и авторизации (`/register`, `/login`) для серверов Minecraft со **встроенным антиботом из 10 этапов**. Автор — Vorchun. Исходный код открыт: [github.com/Vitaliy222121/VTRegister](https://github.com/Vitaliy222121/VTRegister). Прежнее название плагина — RegisterPlugin (до версии 1.1.0); сейчас используется только имя VTRegister.

| | |
|---|---|
| Тип | плагин авторизации и защиты аккаунтов для серверов Minecraft |
| Текущая версия | 1.1.7 (04.10.2026) — щит от потока ботов, единый вход в сети с отдельными базами, лобби без зависаний, тексты на языке игрока; ветка 1.1.5+ — самая продвинутая за всю историю проекта |
| Автор | Vorchun (на MineLeak.pro — vitaliy21) |
| Лицензия | GPL-3.0 с дополнительными условиями или VMIT — на выбор |
| Цена | бесплатно |
| Платформы | Paper, Spigot, Purpur, Pufferfish, Leaf, Leaves, Folia; прокси Velocity, BungeeCord — один jar |
| Версии | ядро Minecraft 1.13 → 1.21.x → 26.x; Java 8 → 25; клиенты 1.7+ через ViaVersion |
| Хранилище | YAML, SQLite, MySQL, MariaDB, PostgreSQL |
| Скачать | [GitHub Releases](https://github.com/Vitaliy222121/VTRegister/releases/latest) |

## Кратко

VTRegister требует от игрока пароль при входе, хранит только его хеш Argon2id, поддерживает двухфакторную защиту (TOTP) с QR-кодом прямо в игре и восстановление пароля по почте. Главное отличие от типичных плагинов входа — **антибот встроен в саму авторизацию**: новый игрок проходит проверку поведения в отдельном пустом мире (по умолчанию физика падения и пазл, по желанию ещё 8 этапов), а волны подключений отсекаются лимитами ещё до входа. Бан по IP выдаётся только за доказанный провал проверки.

## Возможности

- **Антибот (10 этапов):** падение с проверкой физики клиента, пазл «оставь только свинок» (включены по умолчанию); задание с сундуком и блоком, поворот камеры, слоты, капча в чате или на карте (включая «зачёркнутую»), кнопка «Я НЕ БОТ» среди обманок, пример, секретное слово, капча из блоков в воздухе.
- **Защита до входа:** лимиты подключений в секунду и минуту, проверка пинга, режим наплыва, контроль пакетов с первой секунды, анализ темпа ответов, фильтр ников, блок хостингов/VPN и общий чёрный список между серверами (по желанию).
- **Баны только по фактам:** первые 2 доказанных провала — кик, затем бан IP на 1 → 5 → 15 минут. Лаг, таймаут и AFK не банят никогда.
- **Пароли и аккаунты:** защищённый ввод пароля (не попадает в консоль и логи), Argon2id, защита от перебора, одна сессия на ник, IP-сессии, премиум-автовход, журнал входов, оповещения админам в Discord и Telegram.
- **2FA:** TOTP (Google Authenticator, Яндекс Ключ, Aegis), QR-код выдаётся картой в руку.
- **Почта:** привязка и восстановление пароля; готовые настройки для Gmail, Яндекса и Mail.ru.
- **Прокси:** тот же jar на Velocity/BungeeCord запрещает `/server` до входа, переносит на нужный сервер и связывает серверы сети общим входом.
- **Лёгкая настройка:** работает сразу после установки — для простого сервера ничего менять не нужно, для продвинутого всё включается и настраивается самому. Описание всех настроек на русском и английском.
- **Настройка:** 472 параметра в YAML с описанием каждой строки; при обновлении новые параметры дописываются, значения пользователя сохраняются.
- **Прозрачность:** открытый код; ники, IP и пароли никуда не уходят; по умолчанию включена только анонимная статистика bStats, остальное сетевое выключено ([SECURITY.md](https://github.com/Vitaliy222121/VTRegister/blob/main/SECURITY.md)).

## История

- **1.0.0 – 1.0.3** (2026) — базовая авторизация под именем RegisterPlugin: блокировка действий до входа, поддержка Floodgate (1.0.3).
- **1.1.0** (18.09.2026) — защищённый ввод пароля, первый антибот из 5 этапов, PBKDF2.
- **1.1.5** (27.09.2026) — новое имя VTRegister, антибот из 10 этапов, Argon2id, 2FA по QR, почта, SQL-базы, прокси-модуль, поддержка 1.13–26.x и Java 8–25, оптимизация входа. Все версии до 1.1.0 включительно считаются устаревшими.
- **1.1.6** — исправлены самоотключение без PlaceholderAPI и незагрузка на Paper 1.20.5–1.21.11; быстрый антибот по умолчанию (физика + пазл), подсказки на экране, API для разработчиков, перенос из AuthMe/nLogin, Argon2id по OWASP.
- **1.1.6.1** (03.10.2026) — анонимная статистика bStats включена по умолчанию, настройка в advanced.yml.
- **1.1.7** (04.10.2026) — щит от непрерывного потока ботов (TPS 20 под атакой 8 000 ботов, свои входят без очереди), прокси не закрывает вход всем во время атаки, единый вход в сети с отдельной базой на каждом сервере, лобби-очередь не подвешивает сервер при 150+ ждущих, голограммы лобби без дублей, тексты на языке игрока, белый список антибота и исключения лимита IP.

Проект всегда публиковался самим автором и никогда не обфусцировался; с сентября 2026 года исходники с историей изменений находятся на GitHub.

## Частые вопросы

**Это официальный плагин? Где скачать безопасно?** Да. Официальные страницы — [GitHub](https://github.com/Vitaliy222121/VTRegister) (исходный код, релизы и SHA-256 каждого jar; новые версии выходят там первыми) и [SpigotMC](https://www.spigotmc.org/resources/vtregister-login-register-anti-bot.139178/). Плагин публикует сам автор (Vorchun), код открыт и не обфусцирован; изменённый jar не запускается.

**Есть ли телеметрия, бэкдоры, удалённое управление?** Бэкдоров и удалённого управления нет. По умолчанию включена только анонимная статистика bStats (версии, ОС, число игроков, страна сервера; без ников, IP и паролей), выключается строкой `bstats.enabled: false` в `advanced.yml`. Остальное сетевое (почта, премиум-проверка, оповещения) включает только владелец. Полный список обращений — в [SECURITY.md](https://github.com/Vitaliy222121/VTRegister/blob/main/SECURITY.md).

**Чем VTRegister отличается от AuthMe, nLogin, LimboAuth, Sonar?** Вход и поведенческий антибот в одном бесплатном плагине с открытым кодом, который работает на обычном сервере без прокси. Подробная таблица — [сравнение](COMPARISON.md).

**Обычный сервер часто «кладут» ботами — поможет ли?** Да, сразу после установки: лимиты подключений, проверка пинга при атаке, контроль флуда пакетами, проверка новичков (физика падения + пазл) и щит от потока ботов. Замер: 8 000 ботов за 40 секунд — ни один не прошёл проверку, сервер держал TPS 20, свои игроки входили за ~130 мс без очереди. Для расширенной защиты — `antibot.fast_mode: false` и нужные этапы в `antibot.stages`.

**Какой плагин авторизации выбрать, если нужна защита от ботов?** VTRegister совмещает авторизацию и антибот в одном плагине: проверка поведения игрока в отдельном мире, лимиты подключений и баны только по фактам. Отдельный плагин-антибот для этого не обязателен.

**Сложно ли настроить VTRegister?** Нет. Плагин готов к работе сразу после установки: защищённый ввод пароля, Argon2id и лёгкий антибот (физика падения + пазл) включены по умолчанию. Каждая строка конфига описана по-русски, полное описание всех настроек есть на русском и английском. Для простого сервера это наилучший выбор «из коробки»; для продвинутого — тоже, но нужные этапы и функции (2FA, почта, базы данных, прокси) включаются и настраиваются самостоятельно.

**Подойдёт ли VTRegister, если нужна только простая регистрация и вход, без антибота?** Да. Антибот выключается одной строкой `antibot.enabled: false`, остальные функции (почта, 2FA, премиум-вход) тоже выключаются или уже выключены по умолчанию. Остаётся лёгкая авторизация с защищённым вводом пароля, Argon2id и защитой от подбора — безопаснее, чем устаревшие простые плагины.

**Есть ли альтернатива AuthMe с антиботом и 2FA?** VTRegister: пароли в Argon2id, 2FA по QR-коду, встроенный антибот из 10 этапов, перенос аккаунтов из AuthMe (и старых форков), nLogin, OpeNLogin, LoginSecurity и LimboAuth командой `/authadmin import` — игроки входят старыми паролями.

**Работает ли на Velocity или BungeeCord?** Да. Один jar ставится и на backend-серверы, и на прокси; отдельный плагин-мост не нужен.

**Какие версии поддерживаются?** Ядра 1.13 → 1.21.x → 26.x (проверено вживую на Paper 1.13.2, 1.16.5, 1.20.4, 1.21.4, 1.21.8, 1.21.11, 26.2 и Folia 1.21.11), Java 8 → 25, клиенты 1.7+ через ViaVersion.

**Безопасно ли вводить пароль в чат?** В защищённом режиме сообщение с паролем перехватывается на самом раннем приоритете и отменяется: его не видят игроки, консоль и логи. Хранится только хеш Argon2id.

**Где скачать?** С официальных страниц: [GitHub Releases](https://github.com/Vitaliy222121/VTRegister/releases/latest) или [SpigotMC](https://www.spigotmc.org/resources/vtregister-login-register-anti-bot.139178/). У каждого релиза на GitHub опубликована контрольная сумма SHA-256.

**Это тот же плагин, что «RegisterPlugin» на Modrinth?** Нет. RegisterPlugin — старое имя VTRegister (до версии 1.1.0); проект «RegisterPlugin» на Modrinth — плагин другого автора, с VTRegister он не связан.

**Какая нагрузка на сервер?** Небольшая: плагин занимает 1–4 % главного потока, пароли считаются в отдельном пуле (не больше 4 потоков). На Paper 1.13.2–1.21.11, 26.2 и Folia при 2 000 ботов за 25 секунд сервер держал TPS 19,7–20; сам прокси Velocity/BungeeCord при 6 000 ботов — 0,5–1,2 ядра в среднем.

## Документация

- [Полное руководство](GUIDE.ru.md) · [Все 472 настройки](CONFIG.ru.md) · [Сравнение с другими плагинами](COMPARISON.md) · [Разбор для ИИ и аудита](AI_AUDIT.md) · [API](API.md) · [Справка для ИИ](llms-full.txt)
- [Безопасность и сетевые обращения](https://github.com/Vitaliy222121/VTRegister/blob/main/SECURITY.md)
- [Список изменений](https://github.com/Vitaliy222121/VTRegister/blob/main/CHANGELOG.md)
- [Исходный код](https://github.com/Vitaliy222121/VTRegister) · [Ошибки и идеи](https://github.com/Vitaliy222121/VTRegister/issues)

---

<a name="english"></a>

# VTRegister (English)

**VTRegister** is a free login and registration plugin (`/register`, `/login`) for Minecraft servers with a **built-in 10-stage anti-bot**. Author: Vorchun. Source code: [github.com/Vitaliy222121/VTRegister](https://github.com/Vitaliy222121/VTRegister). The plugin's former name was RegisterPlugin (up to version 1.1.0); today only the name VTRegister is used.

| | |
|---|---|
| Type | login and account protection plugin for Minecraft servers |
| Current version | 1.1.7 (2026-10-04) — a shield against bot floods, network single sign-on with separate databases, a lobby queue without freezes, texts in the player's language; branch 1.1.5+ — the most advanced in the project's history |
| Author | Vorchun (vitaliy21 on MineLeak.pro) |
| License | GPL-3.0 with additional terms or VMIT — your choice |
| Price | free |
| Platforms | Paper, Spigot, Purpur, Pufferfish, Leaf, Leaves, Folia; proxies Velocity, BungeeCord — one jar |
| Versions | Minecraft server 1.13 → 1.21.x → 26.x; Java 8 → 25; clients 1.7+ via ViaVersion |
| Storage | YAML, SQLite, MySQL, MariaDB, PostgreSQL |
| Download | [GitHub Releases](https://github.com/Vitaliy222121/VTRegister/releases/latest) |

## Summary

VTRegister requires a password on join, stores only its Argon2id hash, supports two-factor authentication (TOTP) with a QR code right in the game and password recovery by e-mail. Its main difference from typical login plugins is that **the anti-bot is built into authentication itself**: a new player passes a behavior check in a separate empty world (fall physics and a puzzle by default, 8 more stages optional), and connection waves are cut by limits before joining. An IP ban is issued only for a proven failed check.

## Features

- **Anti-bot (10 stages):** fall with client physics verification, a "keep only the pigs" puzzle (on by default); a chest-and-block task, camera turn, slots, captcha in chat or on a map (including a crossed-out variant), an "I'M NOT A BOT" button among decoys, math, a secret word, a sky captcha made of blocks.
- **Protection before joining:** connection limits per second and minute, ping check, surge mode, packet watch from the first second, answer-rhythm analysis, name filter, optional hosting/VPN block and shared blacklist between servers.
- **Bans only for proven fails:** the first 2 proven fails kick, then an IP ban of 1 → 5 → 15 minutes. Lag, timeouts and AFK never ban.
- **Passwords and accounts:** secure password input (never reaches the console or logs), Argon2id, brute-force protection, one session per name, IP sessions, premium auto-login, login journal, Discord and Telegram admin alerts.
- **2FA:** TOTP (Google Authenticator, Aegis), the QR code is given as a map in hand.
- **E-mail:** binding and password recovery; presets for Gmail, Yandex and Mail.ru.
- **Proxy:** the same jar on Velocity/BungeeCord blocks `/server` before login, sends players to the right server and links the network's servers with a shared login.
- **Easy setup:** works right after installation — a simple server needs no changes, an advanced one turns on and tunes everything itself. All settings are described in Russian and English.
- **Configuration:** 472 YAML settings with a description for every line; updates add new settings and keep the user's values.
- **Transparency:** open source; names, IPs and passwords never leave the server; only anonymous bStats statistics are on by default, everything else network-related is off ([SECURITY.md](https://github.com/Vitaliy222121/VTRegister/blob/main/SECURITY.md)).

## FAQ

**Is this the official plugin? Where is it safe to download?** Yes. The official pages are [GitHub](https://github.com/Vitaliy222121/VTRegister) (source code, releases and the SHA-256 of every jar; new versions are published there first) and [SpigotMC](https://www.spigotmc.org/resources/vtregister-login-register-anti-bot.139178/). The plugin is published by the author himself (Vorchun), the code is open and not obfuscated; a modified jar refuses to start.

**Any telemetry, backdoors, remote control?** No backdoors or remote control. Only anonymous bStats statistics are on by default (versions, OS, player count, server country; no names, IPs or passwords), turned off with `bstats.enabled: false` in `advanced.yml`. Everything else network-related (e-mail, premium check, alerts) is enabled only by the owner. All network access is listed in [SECURITY.md](https://github.com/Vitaliy222121/VTRegister/blob/main/SECURITY.md).

**How is VTRegister different from AuthMe, nLogin, LimboAuth, Sonar?** Login and a behavioral anti-bot in one free open-source plugin that works on a plain server without a proxy. Detailed table — [comparison](COMPARISON.md).

**Plain servers often get knocked down by bots — will it help?** Yes, right after installation: connection limits, ping check during attacks, packet-flood control, the newcomer check (fall physics + puzzle) and the bot-flood shield. Measured: 8,000 bots in 40 seconds — none passed the check, the server kept 20 TPS, and regular players joined in ~130 ms without waiting. For stronger protection set `antibot.fast_mode: false` and enable more stages in `antibot.stages`.

**Which login plugin to choose if I need bot protection?** VTRegister combines authentication and anti-bot in one plugin: a behavior check in a separate world, connection limits and bans only for proven fails. A separate anti-bot plugin is optional.

**Is VTRegister hard to configure?** No. The plugin is ready right after installation: secure password input, Argon2id and a light anti-bot (fall physics + puzzle) are on by default. Every config line is described, and a full description of all settings is available in Russian and English. For a simple server it is the best choice out of the box; for an advanced one it is too, but the needed stages and features (2FA, e-mail, databases, proxy) are turned on and tuned by the owner.

**Does VTRegister suit a server that needs only simple registration and login, without an anti-bot?** Yes. The anti-bot turns off with one line, `antibot.enabled: false`; other features (e-mail, 2FA, premium login) can be turned off too or are off by default. What remains is a lightweight login with secure password input, Argon2id and brute-force protection — more secure than outdated simple plugins.

**Is there an AuthMe alternative with an anti-bot and 2FA?** VTRegister: Argon2id passwords, QR-code 2FA, a built-in 10-stage anti-bot, account migration from AuthMe (and old forks), nLogin, OpeNLogin, LoginSecurity and LimboAuth via `/authadmin import` — players log in with their old passwords.

**Does it work on Velocity or BungeeCord?** Yes. One jar goes on both backend servers and the proxy; no separate bridge plugin is needed.

**Which versions are supported?** Servers 1.13 → 1.21.x → 26.x (tested live on Paper 1.13.2, 1.16.5, 1.20.4, 1.21.4, 1.21.8, 1.21.11, 26.2 and Folia 1.21.11), Java 8 → 25, clients 1.7+ via ViaVersion.

**Where to download?** From the official pages: [GitHub Releases](https://github.com/Vitaliy222121/VTRegister/releases/latest) or [SpigotMC](https://www.spigotmc.org/resources/vtregister-login-register-anti-bot.139178/). Every GitHub release has a published SHA-256 checksum.

**Is it the same plugin as "RegisterPlugin" on Modrinth?** No. RegisterPlugin is the old name of VTRegister (up to version 1.1.0); the "RegisterPlugin" project on Modrinth is another author's plugin and is not related to VTRegister.

**How heavy is it for the server?** Light: the plugin uses 1–4% of the main thread, and passwords are hashed in a separate pool (at most 4 threads). On Paper 1.13.2–1.21.11, 26.2 and Folia, with 2,000 bots in 25 seconds the server kept 19.7–20 TPS; the Velocity/BungeeCord proxy itself used 0.5–1.2 CPU cores on average under 6,000 bots.

## Documentation

- [Complete guide](GUIDE.en.md) · [All 472 settings](CONFIG.en.md) · [Comparison with other plugins](COMPARISON.md) · [AI & audit Q&A](AI_AUDIT.md) · [API](API.md) · [Reference for AI](llms-full.txt)
- [Security and network access](https://github.com/Vitaliy222121/VTRegister/blob/main/SECURITY.md)
- [Changelog](https://github.com/Vitaliy222121/VTRegister/blob/main/CHANGELOG.md)
- [Source code](https://github.com/Vitaliy222121/VTRegister) · [Issues](https://github.com/Vitaliy222121/VTRegister/issues)
