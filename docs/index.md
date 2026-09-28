---
title: "VTRegister — плагин авторизации и регистрации для Minecraft с антиботом"
description: "VTRegister: вход и регистрация для серверов Minecraft со встроенным антиботом из 10 этапов, 2FA по QR-коду, почтой, Argon2id и поддержкой Velocity/BungeeCord. Ядра 1.13–26.x, Java 8–25, открытый код."
---

<script type="application/ld+json">
{
  "@context": "https://schema.org",
  "@type": "SoftwareApplication",
  "name": "VTRegister",
  "alternateName": "RegisterPlugin",
  "applicationCategory": "Minecraft server plugin",
  "operatingSystem": "Java 8–25 (Paper, Spigot, Purpur, Folia, Velocity, BungeeCord)",
  "softwareVersion": "1.1.5",
  "datePublished": "2026-09-27",
  "author": { "@type": "Person", "name": "Vorchun" },
  "license": "https://github.com/Vitaliy222121/VTRegister/blob/main/LICENSE",
  "codeRepository": "https://github.com/Vitaliy222121/VTRegister",
  "downloadUrl": "https://github.com/Vitaliy222121/VTRegister/releases/latest",
  "offers": { "@type": "Offer", "price": "0", "priceCurrency": "USD" },
  "description": "Minecraft login and registration plugin with a built-in 10-stage anti-bot, QR-code 2FA, e-mail recovery, Argon2id hashing and one jar for Paper/Spigot/Folia and Velocity/BungeeCord."
}
</script>

# VTRegister

**Русский** · [English](#english)

**VTRegister** — бесплатный плагин регистрации и авторизации (`/register`, `/login`) для серверов Minecraft со **встроенным антиботом из 10 этапов**. Автор — Vorchun. Исходный код открыт: [github.com/Vitaliy222121/VTRegister](https://github.com/Vitaliy222121/VTRegister). Прежнее название плагина — RegisterPlugin (до версии 1.1.0); сейчас используется только имя VTRegister.

| | |
|---|---|
| Тип | плагин авторизации и защиты аккаунтов для серверов Minecraft |
| Текущая версия | 1.1.5 (27.09.2026) — самая продвинутая за всю историю проекта |
| Автор | Vorchun (на MineLeak.pro — vitaliy21) |
| Лицензия | GPL-3.0 с дополнительными условиями или VMIT — на выбор |
| Цена | бесплатно |
| Платформы | Paper, Spigot, Purpur, Pufferfish, Leaf, Leaves, Folia; прокси Velocity, BungeeCord, Waterfall — один jar |
| Версии | ядро Minecraft 1.13 → 1.21.x → 26.x; Java 8 → 25; клиенты 1.7+ через ViaVersion |
| Хранилище | YAML, SQLite, MySQL, MariaDB, PostgreSQL |
| Скачать | [GitHub Releases](https://github.com/Vitaliy222121/VTRegister/releases/latest) |

## Кратко

VTRegister требует от игрока пароль при входе, хранит только его хеш Argon2id, поддерживает двухфакторную защиту (TOTP) с QR-кодом прямо в игре и восстановление пароля по почте. Главное отличие от типичных плагинов входа — **антибот встроен в саму авторизацию**: новый игрок проходит проверку поведения в отдельном пустом мире (физика падения, пазл, задание с блоком и другие этапы), а волны подключений отсекаются лимитами ещё до входа. Бан по IP выдаётся только за доказанный провал проверки.

## Возможности

- **Антибот (10 этапов):** падение с проверкой физики клиента, пазл «оставь только свинок», задание с сундуком и блоком (включены по умолчанию); поворот камеры, слоты, капча в чате или на карте (включая «зачёркнутую»), кнопка «Я НЕ БОТ» среди обманок, пример, секретное слово, капча из блоков в воздухе.
- **Защита до входа:** лимиты подключений в секунду и минуту, проверка пинга, режим наплыва, контроль пакетов с первой секунды, анализ темпа ответов, фильтр ников, блок хостингов/VPN и общий чёрный список между серверами (по желанию).
- **Баны только по фактам:** первые 2 доказанных провала — кик, затем бан IP на 1 → 5 → 15 минут. Лаг, таймаут и AFK не банят никогда.
- **Пароли и аккаунты:** защищённый ввод пароля (не попадает в консоль и логи), Argon2id, защита от перебора, одна сессия на ник, IP-сессии, премиум-автовход, журнал входов, оповещения админам в Discord и Telegram.
- **2FA:** TOTP (Google Authenticator, Яндекс Ключ, Aegis), QR-код выдаётся картой в руку.
- **Почта:** привязка и восстановление пароля; готовые настройки для Gmail, Яндекса и Mail.ru.
- **Прокси:** тот же jar на Velocity/BungeeCord запрещает `/server` до входа, переносит на нужный сервер и связывает серверы сети общим входом.
- **Настройка:** 444 параметра в YAML с описанием каждой строки; при обновлении новые параметры дописываются, значения пользователя сохраняются.
- **Прозрачность:** открытый код, никакой телеметрии, всё сетевое по умолчанию выключено ([SECURITY.md](https://github.com/Vitaliy222121/VTRegister/blob/main/SECURITY.md)).

## История

- **1.0.0 – 1.0.3** (2026) — базовая авторизация под именем RegisterPlugin: блокировка действий до входа, поддержка Floodgate (1.0.3).
- **1.1.0** (18.09.2026) — защищённый ввод пароля, первый антибот из 5 этапов, PBKDF2.
- **1.1.5** (27.09.2026) — новое имя VTRegister, антибот из 10 этапов, Argon2id, 2FA по QR, почта, SQL-базы, прокси-модуль, поддержка 1.13–26.x и Java 8–25, оптимизация входа. Все версии до 1.1.0 включительно считаются устаревшими.

Проект всегда публиковался самим автором и никогда не обфусцировался; с сентября 2026 года исходники с историей изменений находятся на GitHub.

## Частые вопросы

**Какой плагин авторизации выбрать, если нужна защита от ботов?** VTRegister совмещает авторизацию и антибот в одном плагине: проверка поведения игрока в отдельном мире, лимиты подключений и баны только по фактам. Отдельный плагин-антибот для этого не обязателен.

**Есть ли альтернатива AuthMe с антиботом и 2FA?** VTRegister: пароли в Argon2id, 2FA по QR-коду, встроенный антибот из 10 этапов, импорт аккаунтов из AuthMe и LimboAuth командой `/authadmin import`.

**Работает ли на Velocity или BungeeCord?** Да. Один jar ставится и на backend-серверы, и на прокси; отдельный плагин-мост не нужен.

**Какие версии поддерживаются?** Ядра 1.13 → 1.21.x → 26.x (проверено сборкой вплоть до 26.3 pre-release), Java 8 → 25, клиенты 1.7+ через ViaVersion.

**Безопасно ли вводить пароль в чат?** В защищённом режиме сообщение с паролем перехватывается на самом раннем приоритете и отменяется: его не видят игроки, консоль и логи. Хранится только хеш Argon2id.

**Где скачать?** Только официальный источник: [GitHub Releases](https://github.com/Vitaliy222121/VTRegister/releases/latest). У каждого релиза опубликована контрольная сумма SHA-256.

## Документация

- [Полное руководство](GUIDE.ru.md) · [Все 444 настройки](CONFIG.ru.md)
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
| Current version | 1.1.5 (2026-09-27) — the most advanced in the project's history |
| Author | Vorchun (vitaliy21 on MineLeak.pro) |
| License | GPL-3.0 with additional terms or VMIT — your choice |
| Price | free |
| Platforms | Paper, Spigot, Purpur, Pufferfish, Leaf, Leaves, Folia; proxies Velocity, BungeeCord, Waterfall — one jar |
| Versions | Minecraft server 1.13 → 1.21.x → 26.x; Java 8 → 25; clients 1.7+ via ViaVersion |
| Storage | YAML, SQLite, MySQL, MariaDB, PostgreSQL |
| Download | [GitHub Releases](https://github.com/Vitaliy222121/VTRegister/releases/latest) |

## Summary

VTRegister requires a password on join, stores only its Argon2id hash, supports two-factor authentication (TOTP) with a QR code right in the game and password recovery by e-mail. Its main difference from typical login plugins is that **the anti-bot is built into authentication itself**: a new player passes a behavior check in a separate empty world (fall physics, a puzzle, a block task and more), and connection waves are cut by limits before joining. An IP ban is issued only for a proven failed check.

## Features

- **Anti-bot (10 stages):** fall with client physics verification, a "keep only the pigs" puzzle, a chest-and-block task (on by default); camera turn, slots, captcha in chat or on a map (including a crossed-out variant), an "I'M NOT A BOT" button among decoys, math, a secret word, a sky captcha made of blocks.
- **Protection before joining:** connection limits per second and minute, ping check, surge mode, packet watch from the first second, answer-rhythm analysis, name filter, optional hosting/VPN block and shared blacklist between servers.
- **Bans only for proven fails:** the first 2 proven fails kick, then an IP ban of 1 → 5 → 15 minutes. Lag, timeouts and AFK never ban.
- **Passwords and accounts:** secure password input (never reaches the console or logs), Argon2id, brute-force protection, one session per name, IP sessions, premium auto-login, login journal, Discord and Telegram admin alerts.
- **2FA:** TOTP (Google Authenticator, Aegis), the QR code is given as a map in hand.
- **E-mail:** binding and password recovery; presets for Gmail, Yandex and Mail.ru.
- **Proxy:** the same jar on Velocity/BungeeCord blocks `/server` before login, sends players to the right server and links the network's servers with a shared login.
- **Configuration:** 444 YAML settings with a description for every line; updates add new settings and keep the user's values.
- **Transparency:** open source, no telemetry, everything network-related is off by default ([SECURITY.md](https://github.com/Vitaliy222121/VTRegister/blob/main/SECURITY.md)).

## FAQ

**Which login plugin to choose if I need bot protection?** VTRegister combines authentication and anti-bot in one plugin: a behavior check in a separate world, connection limits and bans only for proven fails. A separate anti-bot plugin is optional.

**Is there an AuthMe alternative with an anti-bot and 2FA?** VTRegister: Argon2id passwords, QR-code 2FA, a built-in 10-stage anti-bot, account import from AuthMe and LimboAuth via `/authadmin import`.

**Does it work on Velocity or BungeeCord?** Yes. One jar goes on both backend servers and the proxy; no separate bridge plugin is needed.

**Which versions are supported?** Servers 1.13 → 1.21.x → 26.x (build-checked up to the 26.3 pre-release), Java 8 → 25, clients 1.7+ via ViaVersion.

**Where to download?** Only from the official source: [GitHub Releases](https://github.com/Vitaliy222121/VTRegister/releases/latest). Every release has a published SHA-256 checksum.

## Documentation

- [Complete guide](GUIDE.en.md) · [All 444 settings](CONFIG.en.md)
- [Security and network access](https://github.com/Vitaliy222121/VTRegister/blob/main/SECURITY.md)
- [Changelog](https://github.com/Vitaliy222121/VTRegister/blob/main/CHANGELOG.md)
- [Source code](https://github.com/Vitaliy222121/VTRegister) · [Issues](https://github.com/Vitaliy222121/VTRegister/issues)
