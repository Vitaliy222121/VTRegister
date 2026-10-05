# VTRegister 1.1.7

**Русский** · [English](#english)

Плагин регистрации и авторизации для Minecraft-серверов — альтернатива AuthMe со встроенным антиботом. Защищённый ввод пароля, антибот из 10 этапов, 2FA по QR-коду, почта, базы данных. Один jar работает и на сервере (Paper/Spigot/Folia), и на прокси (Velocity/BungeeCord).

> **VTRegister — основное и единственное название плагина.** «RegisterPlugin» — его старое имя (до версии 1.1.0), оно больше не используется. С проектом «RegisterPlugin» другого автора на Modrinth VTRegister не связан.
>
> **Версия 1.1.7 — актуальная и самая продвинутая за всю историю плагина:** щит от непрерывного потока ботов (свои игроки входят без очереди, сервер держит TPS 20), быстрый антибот (физика + пазл) сразу при входе, лобби-очередь с PvP-зоной, перепроверка постоянных игроков раз в 24 часа, лимит 3 аккаунта на IP, единый вход в сети Velocity/BungeeCord, тексты на языке игрока, API для разработчиков, перенос аккаунтов из AuthMe и nLogin. Версии 1.1.0 и ниже устарели и не поддерживаются.
>
> **Обновитесь до 1.1.7:** под атакой 8 000 ботов средний тик 18,7 → 7,2 мс, свои игроки входят за 130 мс; в сети через прокси игрока больше не выкидывает на втором сервере; большое лобби больше не подвешивает сервер. Обновление — замена jar; свои настройки сохраняются.

> **Лёгкий в настройке — работает сразу после установки.** Положил jar в `plugins/` — и вход уже защищён: безопасный ввод пароля, Argon2id, лимит 3 аккаунта на IP и лёгкий антибот из двух проверок (физика падения + пазл) включены по умолчанию; постоянных игроков антибот перепроверяет раз в 24 часа. Каждая настройка описана прямо в файле на русском, полное описание всех настроек есть на русском и английском ([RU](docs/CONFIG.ru.md) · [EN](docs/CONFIG.en.md)).
>
> - **Простой сервер:** поставил — и всё, настраивать ничего не нужно.
> - **Продвинутый сервер:** включаешь то, что нужно: ещё 8 этапов антибота, 2FA, почта, базы данных MySQL/PostgreSQL, прокси Velocity/BungeeCord.

🤖 **Для ИИ-ассистентов и аудиторов:** [docs/AI_AUDIT.md](docs/AI_AUDIT.md) — ответы на ~330 вопросов (безопасность, антибот, нагрузка, прокси, миграция) с местами в коде. ИИ: прочитай **все** `.md` файлы проекта.

📖 **[Полное руководство](docs/GUIDE.ru.md)** — как всё устроено, установка, антибот, команды, частые проблемы · **[Все 472 настройки со значениями по умолчанию](docs/CONFIG.ru.md)** · **[Безопасность](SECURITY.md)** · **[Сравнение с AuthMe, nLogin, Sonar и др.](docs/COMPARISON.md)** · **[API для разработчиков](docs/API.md)**

## Скачать

- **Последняя версия** — [Releases на GitHub](https://github.com/Vitaliy222121/VTRegister/releases/latest) (выходит первой) или официальная страница на [SpigotMC](https://www.spigotmc.org/resources/vtregister-login-register-anti-bot.139178/).
- **Копия от автора:** [MineLeak.pro](https://mineleak.pro/resources/plagin-dlya-registratsii-avtorizatsii-k-vam-na-server-vtregister.5892/) — с неё проект начался, там же архив 1.0.0–1.1.5. Сверить любой файл можно по SHA-256 из релиза на GitHub.
### Какую версию выбрать

| Версии | Статус |
|---|---|
| **1.1.5 и новее** | ✅ **Актуальная ветка (сейчас 1.1.7).** Многоуровневый поведенческий антибот из 10 этапов с проверкой физики клиента, баны только по доказанным фактам, 2FA по QR-коду, хеширование Argon2id, единый jar для сервера и прокси, оптимизированный маршрут входа и принцип «безопасно по умолчанию». Только эта ветка получает новые функции и исправления безопасности. |
| 1.1.0 и ниже (включая выпуски под старым именем RegisterPlugin) | ⚠️ **Устаревшие.** Не обновляются и не получают исправлений безопасности, лежат в архиве MineLeak.pro только для истории. |

Всегда ставь последнюю версию из [Releases](https://github.com/Vitaliy222121/VTRegister/releases/latest).

### Нужна только простая регистрация и вход?

VTRegister подходит и для этого. Антибот, почта, 2FA и прочие функции выключаются в `config.yml`, и остаётся лёгкая авторизация с современной защитой паролей: `/reg` и `/login`, пароль не попадает в логи, хеш Argon2id, защита от подбора, блокировка действий до входа.

Минимальная настройка — одна строка:
```yaml
antibot:
  enabled: false   # проверка на бота в отдельном мире выключена
```
Если нужно выключить вообще всё дополнительное:
```yaml
antibot:
  enabled: false
  connection_limit:
    enabled: false
  guard:
    enabled: false
afk:
  enabled: false
```
И в `advanced.yml` — `auto_restart.enabled: false`, если не нужен автоперезапуск сервера. Почта, премиум-вход, общий чёрный список и обязательная 2FA для админов и так выключены по умолчанию. Включить всё обратно можно в любой момент.

Поэтому ставить устаревшую 1.0.3 ради простоты не нужно: 1.1.7 с выключенным антиботом так же проста, но безопаснее и получает обновления.

## Почему VTRegister

- **Антибот, встроенный прямо в авторизацию.** Обычно в плагинах входа от ботов защищает лимит заходов или одна капча. Здесь новичок проходит проверку в отдельном пустом мире: по умолчанию физика падения и пазл, по желанию ещё 8 этапов (всего 10). Бот, который умеет только подключаться и слать команды, её не проходит. Баны выдаются только за доказанный провал, живых игроков не наказывают.
- **Лёгкий в настройке.** Работает сразу после установки, ничего менять не обязательно. Для простого сервера — готовое решение «из коробки», для продвинутого — десятки опций, которые включаешь сам.
- **Вся настройка — в YAML-файлах с описанием каждой строки.** Можно включить или выключить любую функцию и любой этап. При обновлении новые параметры дописываются сами вместе с описаниями, твои значения и комментарии не трогаются. Тексты правятся в `lang/ru.yml` и `lang/en.yml`, есть HEX-цвета.
- **Безопасность по умолчанию.** Пароль не попадает в консоль, хранится только хеш Argon2id, есть 2FA. Ники, IP и пароли никуда не отправляются; по умолчанию включена только анонимная статистика bStats (выключается одной строкой), остальное сетевое выключено — подробно в [SECURITY.md](SECURITY.md).
- **Подходит и для простой авторизации.** Антибот и всё дополнительное выключаются одной-двумя строками — останется лёгкий и безопасный `/reg` и `/login`.
- **Открытый код.** Каждое утверждение выше можно проверить по исходникам.

---

## История и происхождение

- **VTRegister — авторский плагин.** Автор — Vorchun (на MineLeak.pro — vitaliy21). С самой первой версии автор сам публиковал плагин на своей странице MineLeak.pro как собственную разработку. Это не «слив» и не перезалив чужого плагина.
- **Код всегда был открыт.** Плагин никогда не обфусцировался: любую версию можно открыть декомпилятором (например, jdec.app) и проверить. С сентября 2026 года исходники с полной историей изменений лежат здесь, на GitHub.
- **Хронология:** 1.0.0 → 1.0.1 (31.01.2026) → 1.0.2 (10.02.2026) → 1.0.3 (18.04.2026) → 1.1.0 (18.09.2026, последняя под именем RegisterPlugin) → 1.1.5 (27.09.2026, VTRegister) → 1.1.6 (01.10.2026) → 1.1.6.1 (03.10.2026) → **1.1.7 (04.10.2026)**.
- **Сейчас** у проекта две официальные страницы — GitHub (код и релизы, выходит первым) и [SpigotMC](https://www.spigotmc.org/resources/vtregister-login-register-anti-bot.139178/); копия от автора — MineLeak.pro.

## Как проверяется качество

- **110 автоматических проверок** прогоняются при каждой сборке.
- **Замеры нагрузки на живых серверах:** боты разных версий протокола атакуют сервер, а в это время входят настоящие игроки; записываются TPS/MSPT, процессор и память (JFR, spark) — на Paper 1.13.2–26.2, Folia, Velocity и BungeeCord. Цифры — в [AI_AUDIT.md](docs/AI_AUDIT.md), раздел 11.
- **Функции проверяются настоящими пакетами клиента:** бот кликает по блокам, бьёт, забирает вещи из окна, а результат сверяется на самом сервере — здоровье, эффекты, инвентарь, место в очереди.
- **Каждая версия сравнивается с прошлой** в одинаковых условиях — так ловятся ухудшения; найденное исправляется до выпуска.
- Код собирается против API **1.13.2, 1.14.4, 1.15.2, 1.16.5, 1.21.4 и 26.2**, запуск проверяется на **Java 8, 17, 21 и 25**.
- **Воспроизводимая сборка:** jar, собранный из этих исходников без изменений, проходит ту же проверку подписи, что и официальный релиз. SHA-256 каждого релиза опубликован.
- **Прозрачность:** [SECURITY.md](SECURITY.md) — всё, что плагин делает с сетью, файлами и сервером; ники, IP и пароли никуда не уходят.
- Ошибки и предложения — во вкладке [Issues](https://github.com/Vitaliy222121/VTRegister/issues).

## Поддержка

| Что | Версии |
|---|---|
| Серверы | CraftBukkit, Spigot, Paper, Purpur, Pufferfish, Leaf, Leaves, Folia и другие форки Paper/Spigot |
| Гибридные ядра (моды + плагины) | Mohist, Arclight, CatServer, Magma — работают, часть API ядра может вести себя иначе |
| Прокси | Velocity 3.x–4.x, BungeeCord (тот же jar; проверено на Velocity 4.2.1 и BungeeCord; Waterfall закрыт и отдельно не проверялся) |
| Minecraft (ядро) | 1.13 → 1.21.x → 26.x (новая нумерация Mojang) |
| Java | 8 → 25 |
| Клиенты | 1.7 → новейшие через ViaVersion / ViaBackwards / ViaRewind |
| Bedrock | Geyser + Floodgate |

Сборка проверена против API 1.13.2, 1.14.4, 1.15.2, 1.16.5, 1.21.4 и 26.2, запуск — на Java 8, 17, 21 и 25. Ядра 1.8–1.12 не поддерживаются (другой набор блоков и карт): для старых клиентов ставь ядро 1.13+ с ViaVersion.

## Установка

1. Положи `VTRegister-v1.1.7.jar` в `plugins/` и запусти сервер.
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
- Хранилище: YAML, SQLite, MySQL, MariaDB, PostgreSQL; перенос аккаунтов из AuthMe (и старых форков: SQLite, MySQL, файл auths.db), nLogin, OpeNLogin, LoginSecurity, LimboAuth — игроки входят старыми паролями.
- Подсказки по центру экрана: что делать прямо сейчас (зарегистрироваться, войти, пройти этап) — `screen_hints.enabled`.
- [API для разработчиков](docs/API.md): события входа и антибота, свои окна входа, проверка пароля. Анонимная статистика bStats включена по умолчанию (`advanced.yml` → `bstats.enabled`).
- Журнал входов, оповещения в Discord/Telegram о входе админа с нового IP.

**Антибот** — отдельный пустой мир, каждый этап включается отдельно. По умолчанию — быстрый режим (`fast_mode: true`): проверка сразу при входе, без очередей, этапы физика + пазл

| Этап | По умолчанию | Суть |
|---|---|---|
| `fall` | вкл | 5 повторов физики по случайному плану: падение, высокое падение, паутина, подброс |
| `puzzle` | вкл | «оставь только свинок» на картах в рамках |
| `block` | выкл | открыть и закрыть сундук, взять инструмент, сломать нужный блок |
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
- **Щит от потока ботов:** режим атаки включается сразу, незнакомые новички пускаются дозированно, свои игроки и проверенные IP — без очереди; если сервер не успевает за тиком, новички ждут. Замер: 8 000 ботов за 40 секунд — TPS 20, ни один бот не прошёл.
- Режим наплыва: проверки стартуют дозированно.
- Лобби-очередь (по желанию): паркур, PvP-зона с набором, кнопка скорости; большое лобби не перегружает сеть (`queue_hide_above`).
- Белый список (`antibot.bypass`) и исключения лимита аккаунтов на IP (`ip_limit.exempt_ips`).
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

## Чем ветка 1.1.5+ (сейчас 1.1.7) отличается от прошлых версий

| | 1.0.1 – 1.0.3 | 1.1.0 | **1.1.5+** |
|---|---|---|---|
| Ядра Minecraft | 1.16.5 – 1.21.11 | 1.16.5 → 26.x | **1.13 → 26.x** |
| Java | н/д | 8+ | **8 → 25 (проверено)** |
| Прокси | режим в конфиге (с 1.0.2) | автоопределение | **модуль для Velocity/BungeeCord в том же jar + мост между серверами** |
| Хеш пароля | н/д | PBKDF2-SHA512 + pepper | **Argon2id** (старые хеши обновляются сами) |
| Антибот | нет | 5 этапов | **10 этапов**, умный план физики, пазл, блок, капча в воздухе |
| Баны ботов | нет | нет | **ступени 1/5/15 мин, только по фактам** |
| Защита от наплыва | нет | очередь | **лимиты подключений, пинг, режим наплыва, контроль пакетов** |
| Щит от потока ботов | нет | нет | **1.1.7: под атакой 8 000 ботов TPS 20, свои игроки входят без очереди** |
| 2FA | нет | нет | **TOTP с QR-кодом на карте** |
| Почта | нет | нет | **привязка и восстановление, Gmail/Яндекс/Mail.ru** |
| Базы данных | YAML | YAML | **YAML, SQLite, MySQL, MariaDB, PostgreSQL** |
| Импорт | нет | нет | **AuthMe и форки, nLogin, OpeNLogin, LoginSecurity, LimboAuth** |
| Языки | ru | ru | **ru, en** |
| Bedrock | Floodgate (с 1.0.3) | Floodgate | Floodgate + мягкая физика в антиботе |
| Нагрузка | — | — | **спавн на платформе, меньше перелётов между мирами, кэш цветов** |

Полный список изменений — [CHANGELOG.md](CHANGELOG.md).

## Команды

| Команда | Кто | Что делает |
|---|---|---|
| `/register`, `/reg` | все | регистрация |
| `/login`, `/l` | все | вход |
| `/changepassword` (`/changepw`, `/cp`, `/passwd`) | все | смена своего пароля: старый, потом новый — в чат |
| `/2fa on \| off \| cancel \| <код>` | все | двухфакторная защита |
| `/email <адрес> \| <код>` | все | привязка почты |
| `/recover`, затем `/recover <код> <пароль>` | все | восстановление пароля |
| `/vtregister` (`/vtr`, `/vreg`) `help \| status \| cmds` | все | справка |
| `/vtregister reload` | админ | перечитать настройки |
| `/vtregister reset <config\|advanced\|lang\|all>` | админ | вернуть настройки по умолчанию: config.yml, advanced.yml, тексты lang или всё; с подтверждением и копией в `backups/`, «перец» паролей и подключение к базе сохраняются |
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

Результат: `target/VTRegister-v1.1.7.jar` (байткод Java 8). Jar из этих исходников без изменений запускается. Изменённую сборку плагин не запускает: так «перепакованная» копия с вредоносным кодом не выдаст себя за VTRegister. Ничего другого проверка не делает, подробнее в [SECURITY.md](SECURITY.md).

## Лицензия

Двойная, на выбор: **GPL-3.0 с дополнительными условиями** или **Vorchun MIT-style License (VMIT)**, см. `LICENSE` и `NOTICE`. При распространении обязательны указание автора (Vorchun) и ссылка на официальную страницу; изменённые версии нельзя выпускать под именем «VTRegister».

## Частые вопросы

**Где официальный источник?** Исходники и релизы — этот репозиторий: https://github.com/Vitaliy222121/VTRegister. Вторая официальная страница — [SpigotMC](https://www.spigotmc.org/resources/vtregister-login-register-anti-bot.139178/); новые версии выходят на GitHub первыми. Копия от автора — его страница [MineLeak.pro](https://mineleak.pro/resources/plagin-dlya-registratsii-avtorizatsii-k-vam-na-server-vtregister.5892/) (Vorchun, там ник vitaliy21; там же архив 1.0.0–1.1.5). Копии на других сайтах выкладывал не автор — сверяй SHA-256 с [релизом](https://github.com/Vitaliy222121/VTRegister/releases).

**Можно распространять и проверять код?** Да. Код открыт, лицензия (GPL-3.0 с доп. условиями или VMIT) разрешает использовать, изучать и распространять плагин с указанием автора. История изменений — в коммитах этого репозитория.

**Это плагин для Velocity/BungeeCord или для backend?** Для обоих, начиная с 1.1.5: один и тот же jar ставится и на backend-серверы, и на прокси. На прокси он запрещает `/server` до входа, после входа отправляет на нужный сервер и связывает серверы сети. Отдельный плагин-мост вроде AuthMeVelocity не нужен. До 1.1.0 включительно плагин работал только на backend. Для фильтрации флуда подключений прямо на прокси можно поставить рядом Sonar.

**Пароль вводится в чат — это безопасно?** В защищённом режиме сообщение с паролем перехватывается на самом раннем приоритете и отменяется: его не видят ни другие игроки, ни консоль, ни логи. Плагин, который слушает сетевые пакеты, теоретически может его увидеть. Это ограничение любого плагина авторизации на том же сервере: команду `/login <пароль>` в AuthMe другие плагины видят точно так же. Сверху — хранение только в виде хеша Argon2id и 2FA.

**Чем отличается от AuthMe и похожих?** Антибот встроен прямо в авторизацию и проверяет физику в отдельном мире. 2FA настраивается QR-кодом прямо в игре. Один jar работает и на сервере, и на прокси. Все настройки — в YAML с описанием каждой строки.

**Кто автор?** Vorchun (на MineLeak.pro — vitaliy21). С другими плагинами и авторами с похожими названиями проект не связан.

## Честно о границах

- Ни один плагин не может полностью скрыть пароль от другого плагина в том же процессе; защищённый режим перехватывает ввод раньше остальных.
- Строку `issued server command` пишет само ядро; плагин отключает её (`security.command_logging: fix`) и ставит фильтр логов.
- На Folia проверка на бота в отдельном мире (этапы) и платформа входа отключены — Folia не даёт вести арены из одного потока в разных регионах. Регистрация, вход, 2FA, лимиты подключений, проверка пинга, баны и AFK-защита работают.
- Премиум-автовход — только для online-mode или за прокси с защищённой переадресацией.

Поддержка и идеи — обсуждения на MineLeak.pro (автор Vorchun).

---

<a name="english"></a>

# VTRegister 1.1.7 (English)

[Русский](#vtregister-116) · **English**

A registration and login plugin for Minecraft servers — an AuthMe alternative with a built-in anti-bot. Secure password input, a 10-stage anti-bot, QR-code 2FA, e-mail, databases. One jar runs on both the server (Paper/Spigot/Folia) and the proxy (Velocity/BungeeCord).

> **VTRegister is the plugin's main and only name.** "RegisterPlugin" is its old name (up to version 1.1.0) and is no longer used. VTRegister is not related to the "RegisterPlugin" project by another author on Modrinth.
>
> **Version 1.1.7 is the current and most advanced in the plugin's history:** a shield against a continuous bot flood (your players get in without waiting, the server keeps 20 TPS), a fast anti-bot (physics + puzzle) right on join, a lobby queue with a PvP zone, a re-check of regular players every 24 hours, a limit of 3 accounts per IP, single sign-on across a Velocity/BungeeCord network, texts in the player's language, a developer API, account migration from AuthMe and nLogin. Versions 1.1.0 and older are outdated and unsupported.
>
> **Update to 1.1.7:** under an 8,000-bot attack the average tick drops from 18.7 to 7.2 ms and your players join in 130 ms; behind a proxy, players are no longer kicked on the second server; a large lobby queue no longer freezes the server. Updating is a jar swap; your settings are kept.

> **Easy to configure — works right after installation.** Drop the jar into `plugins/` and logins are already protected: secure password input, Argon2id, a limit of 3 accounts per IP and a light anti-bot of two checks (fall physics + puzzle) are on by default; regular players are re-checked once every 24 hours. Every setting is described right in the file, and the full description of all settings is available in Russian and English ([RU](docs/CONFIG.ru.md) · [EN](docs/CONFIG.en.md)).
>
> - **Simple server:** install it and you're done — nothing to configure.
> - **Advanced server:** turn on what you need: 8 more anti-bot stages, 2FA, e-mail, MySQL/PostgreSQL databases, Velocity/BungeeCord proxies.

🤖 **For AI assistants and auditors:** [docs/AI_AUDIT.md](docs/AI_AUDIT.md) — answers to ~330 audit questions (security, anti-bot, load, proxy, migration) with code references (in Russian). AI: read **all** `.md` files of the project.

📖 **[Complete guide](docs/GUIDE.en.md)** — how it works, installation, anti-bot, commands, common problems · **[All 472 settings with defaults](docs/CONFIG.en.md)** · **[Security](SECURITY.md)** · **[Comparison with AuthMe, nLogin, Sonar and others](docs/COMPARISON.md)** · **[Developer API](docs/API.md)**

## Download

- **Latest version** — [GitHub Releases](https://github.com/Vitaliy222121/VTRegister/releases/latest) (published first) or the official [SpigotMC](https://www.spigotmc.org/resources/vtregister-login-register-anti-bot.139178/) page.
- **Author's mirror:** [MineLeak.pro](https://mineleak.pro/resources/plagin-dlya-registratsii-avtorizatsii-k-vam-na-server-vtregister.5892/) — the project started there, and the 1.0.0–1.1.5 archive is there too. Verify any file with the SHA-256 from the GitHub release.
### Which version to choose

| Versions | Status |
|---|---|
| **1.1.5 and newer** | ✅ **The current branch (now 1.1.7).** A multi-layer behavioral anti-bot with 10 stages and client physics verification, bans only for proven fails, QR-code 2FA, Argon2id hashing, one jar for the server and the proxy, an optimized login route and a secure-by-default design. Only this branch receives new features and security fixes. |
| 1.1.0 and older (including releases under the old name RegisterPlugin) | ⚠️ **Outdated.** No updates and no security fixes; kept in the MineLeak.pro archive for history only. |

Always install the latest version from [Releases](https://github.com/Vitaliy222121/VTRegister/releases/latest).

### Need only simple registration and login?

VTRegister fits that too. The anti-bot, e-mail, 2FA and other features can be turned off in `config.yml`, leaving a lightweight login with modern password security: `/reg` and `/login`, passwords never reach the logs, Argon2id hashing, brute-force protection, actions locked before login.

Minimal setup — one line:
```yaml
antibot:
  enabled: false   # the anti-bot check in a separate world is off
```
To turn off everything extra:
```yaml
antibot:
  enabled: false
  connection_limit:
    enabled: false
  guard:
    enabled: false
afk:
  enabled: false
```
And in `advanced.yml` — `auto_restart.enabled: false` if you do not need scheduled server restarts. E-mail, premium login, the shared blacklist and mandatory admin 2FA are already off by default. Everything can be turned back on at any time.

So there is no need to install the outdated 1.0.3 for simplicity: 1.1.7 with the anti-bot off is just as simple, but more secure and still updated.

## Why VTRegister

- **An anti-bot built right into authentication.** Most login plugins stop bots with a join limit or a single captcha. Here a newcomer is checked in a separate empty world: fall physics and a puzzle by default, 8 more stages optional (10 in total). A bot that can only connect and send commands does not pass. Bans are issued only for proven fails; real players are not punished.
- **Easy to configure.** Works right after installation, nothing has to be changed. For a simple server it is a ready out-of-the-box solution; for an advanced one there are dozens of options you turn on yourself.
- **All configuration lives in YAML files with a description for every line.** Any feature and any stage can be turned on or off. On update, new settings are added with their descriptions; your values and comments are never touched. Texts are edited in `lang/ru.yml` and `lang/en.yml`, with HEX colors.
- **Secure by default.** Passwords never reach the console, only Argon2id hashes are stored, 2FA is available. Names, IPs and passwords are never sent anywhere; only anonymous bStats statistics are on by default (one line to turn off), everything else network-related is off — details in [SECURITY.md](SECURITY.md).
- **Also fits a simple login.** The anti-bot and all extras turn off with a line or two, leaving a lightweight, secure `/reg` and `/login`.
- **Open source.** Every statement above can be checked in the code.

## History and origin

- **VTRegister is an author's own plugin.** The author is Vorchun (vitaliy21 on MineLeak.pro). From the very first version the author himself published it on his MineLeak.pro page as his own work. It is not a "leak" or a re-upload of someone else's plugin.
- **The code has always been open.** The plugin was never obfuscated: any version can be opened with a decompiler (for example, jdec.app) and checked. Since September 2026 the source code with its full change history lives here on GitHub.
- **Timeline:** 1.0.0 → 1.0.1 (2026-01-31) → 1.0.2 (2026-02-10) → 1.0.3 (2026-04-18) → 1.1.0 (2026-09-18, the last one named RegisterPlugin) → 1.1.5 (2026-09-27, VTRegister) → 1.1.6 (2026-10-01) → 1.1.6.1 (2026-10-03) → **1.1.7 (2026-10-04)**.
- **Today** the project has two official pages — GitHub (code and releases, published first) and [SpigotMC](https://www.spigotmc.org/resources/vtregister-login-register-anti-bot.139178/); the author's mirror is MineLeak.pro.

## How quality is checked

- **110 automated checks** run on every build.
- **Load measurements on live servers:** bots of different protocol versions attack the server while real players join; TPS/MSPT, CPU and memory are recorded (JFR, spark) on Paper 1.13.2–26.2, Folia, Velocity and BungeeCord. The numbers are in [AI_AUDIT.md](docs/AI_AUDIT.md), section 11 (in Russian).
- **Features are tested with real client packets:** a bot clicks blocks, hits, takes items from a window, and the result is checked on the server itself — health, effects, inventory, queue position.
- **Every version is compared with the previous one** under the same conditions to catch regressions; what's found is fixed before the release.
- The code is compiled against API **1.13.2, 1.14.4, 1.15.2, 1.16.5, 1.21.4 and 26.2**; startup is tested on **Java 8, 17, 21 and 25**.
- **Reproducible build:** a jar built from these unmodified sources passes the same signature check as the official release. Every release's SHA-256 is published.
- **Transparency:** [SECURITY.md](SECURITY.md) lists everything the plugin does with the network, files and the server; names, IPs and passwords never leave the server.
- Bugs and ideas — the [Issues](https://github.com/Vitaliy222121/VTRegister/issues) tab.

## Support

| What | Versions |
|---|---|
| Servers | CraftBukkit, Spigot, Paper, Purpur, Pufferfish, Leaf, Leaves, Folia and other Paper/Spigot forks |
| Hybrid servers (mods + plugins) | Mohist, Arclight, CatServer, Magma — work, some server API may behave differently |
| Proxies | Velocity 3.x–4.x, BungeeCord (same jar; tested on Velocity 4.2.1 and BungeeCord; Waterfall is discontinued and not tested separately) |
| Minecraft (server) | 1.13 → 1.21.x → 26.x (Mojang's new numbering) |
| Java | 8 → 25 |
| Clients | 1.7 → latest via ViaVersion / ViaBackwards / ViaRewind |
| Bedrock | Geyser + Floodgate |

Compiled against API 1.13.2, 1.14.4, 1.15.2, 1.16.5, 1.21.4 and 26.2; tested on Java 8, 17, 21 and 25. 1.8–1.12 servers are not supported (different block and map API) — use a 1.13+ server with ViaVersion for old clients.

## Installation

1. Put `VTRegister-v1.1.7.jar` into `plugins/` and start the server.
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
- Storage: YAML, SQLite, MySQL, MariaDB, PostgreSQL; account migration from AuthMe (and old forks: SQLite, MySQL, the auths.db file), nLogin, OpeNLogin, LoginSecurity, LimboAuth — players log in with their old passwords.
- On-screen hints in the middle of the screen: what to do right now (register, log in, pass a stage) — `screen_hints.enabled`.
- [Developer API](docs/API.md): login and anti-bot events, custom login windows, password checks. Anonymous bStats statistics are on by default (`advanced.yml` → `bstats.enabled`).
- Login journal, Discord/Telegram alerts when an admin joins from a new IP.

**Anti-bot** — a separate empty world, every stage can be toggled. Default is fast mode (`fast_mode: true`): the check starts right on join, no queues, stages fall + puzzle

| Stage | Default | What it does |
|---|---|---|
| `fall` | on | 5 physics runs in a random plan: fall, high fall, cobweb, launch |
| `puzzle` | on | "keep only the pigs" on maps in item frames |
| `block` | off | open and close a chest, take the tool, break the right block |
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
- **Bot-flood shield:** attack mode turns on immediately, unknown newcomers are admitted at a fixed rate, your players and verified IPs get in without waiting; if the server can't keep up with the tick, newcomers wait. Measured: 8,000 bots in 40 seconds — 20 TPS, no bot passed.
- Surge mode: checks start at a controlled rate.
- Lobby queue (optional): parkour, a PvP zone with a kit, a speed button; a large lobby doesn't overload the network (`queue_hide_above`).
- Whitelist (`antibot.bypass`) and per-IP account limit exceptions (`ip_limit.exempt_ips`).
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

## What the 1.1.5+ branch (now 1.1.7) changes compared to earlier versions

| | 1.0.1 – 1.0.3 | 1.1.0 | **1.1.5+** |
|---|---|---|---|
| Minecraft servers | 1.16.5 – 1.21.11 | 1.16.5 → 26.x | **1.13 → 26.x** |
| Java | n/a | 8+ | **8 → 25 (tested)** |
| Proxy | config mode (since 1.0.2) | auto-detection | **Velocity/BungeeCord module in the same jar + cross-server bridge** |
| Password hash | n/a | PBKDF2-SHA512 + pepper | **Argon2id** (old hashes upgrade automatically) |
| Anti-bot | none | 5 stages | **10 stages**, smart physics plan, puzzle, block, sky captcha |
| Bot bans | none | none | **1/5/15-minute tiers, proven fails only** |
| Flood protection | none | queue | **connection limits, ping check, surge mode, packet watch** |
| Bot-flood shield | none | none | **1.1.7: 20 TPS under 8,000 bots, your players get in without waiting** |
| 2FA | none | none | **TOTP with a QR code on a map** |
| E-mail | none | none | **binding and recovery, Gmail/Yandex/Mail.ru** |
| Databases | YAML | YAML | **YAML, SQLite, MySQL, MariaDB, PostgreSQL** |
| Import | none | none | **AuthMe and forks, nLogin, OpeNLogin, LoginSecurity, LimboAuth** |
| Languages | ru | ru | **ru, en** |
| Bedrock | Floodgate (since 1.0.3) | Floodgate | Floodgate + softer anti-bot physics |
| Server load | — | — | **spawn on platform, fewer world changes, color cache** |

Full list — [CHANGELOG.md](CHANGELOG.md).

## Commands

| Command | Who | What |
|---|---|---|
| `/register`, `/reg` | everyone | register |
| `/login`, `/l` | everyone | log in |
| `/changepassword` (`/changepw`, `/cp`, `/passwd`) | everyone | change your password: the old one, then the new one — in chat |
| `/2fa on \| off \| cancel \| <code>` | everyone | two-factor protection |
| `/email <address> \| <code>` | everyone | bind e-mail |
| `/recover`, then `/recover <code> <password>` | everyone | password recovery |
| `/vtregister` (`/vtr`, `/vreg`) `help \| status \| cmds` | everyone | help |
| `/vtregister reload` | admin | reload settings |
| `/vtregister reset <config\|advanced\|lang\|all>` | admin | restore default settings: config.yml, advanced.yml, lang texts or everything; asks for confirmation and copies the old files to `backups/`, the password pepper and the database connection are kept |
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

Output: `target/VTRegister-v1.1.7.jar` (Java 8 bytecode). A jar built from these unmodified sources runs. A modified build does not start, so a "repacked" copy with malicious code cannot pose as VTRegister. The check does nothing else — see [SECURITY.md](SECURITY.md).

## License

Dual, your choice: **GPL-3.0 with additional terms** or **Vorchun MIT-style License (VMIT)** — see `LICENSE` and `NOTICE`. Redistribution must credit the author (Vorchun) and link the official page; modified versions must not be released under the name "VTRegister".

## FAQ

**Where is the official source?** Source code and releases live in this repository: https://github.com/Vitaliy222121/VTRegister. The second official page is [SpigotMC](https://www.spigotmc.org/resources/vtregister-login-register-anti-bot.139178/); new versions are published on GitHub first. The author's mirror is his [MineLeak.pro](https://mineleak.pro/resources/plagin-dlya-registratsii-avtorizatsii-k-vam-na-server-vtregister.5892/) page (Vorchun, nickname vitaliy21 there; the 1.0.0–1.1.5 archive is there too). Copies on other sites were not uploaded by the author; compare the SHA-256 with the [release](https://github.com/Vitaliy222121/VTRegister/releases).

**May I redistribute and audit it?** Yes. The code is open; the license (GPL-3.0 with additional terms or VMIT) allows using, studying and redistributing the plugin with attribution. The change history is in this repository's commits.

**Is it a Velocity/BungeeCord plugin or a backend plugin?** Both, since 1.1.5: the same jar goes on backend servers and on the proxy. On the proxy it blocks `/server` before login, sends the player to the right server afterwards and links the network's servers. A separate bridge plugin like AuthMeVelocity is not needed. Up to and including 1.1.0 it was backend-only. To filter connection floods at the proxy itself, you can add Sonar alongside.

**Is typing the password in chat safe?** In secure mode the chat message with the password is intercepted at the earliest priority and cancelled: other players, the console and the logs never see it. A plugin that listens to network packets could theoretically see it — a limit shared by every login plugin on the same server (other plugins see AuthMe's `/login <password>` the same way). On top of that, only Argon2id hashes are stored, and 2FA is available.

**How is it different from AuthMe and similar plugins?** The anti-bot is built into authentication and checks physics in a separate world. 2FA is set up with a QR code right in the game. One jar runs on both the server and the proxy. Every setting is in YAML with a description for each line.

**Who is the author?** Vorchun (vitaliy21 on MineLeak.pro). The project is not related to other plugins or authors with similar names.

## Honest limits

- No plugin can fully hide a password from another plugin in the same process; secure mode intercepts input earlier than the others.
- The `issued server command` line is written by the server itself; the plugin disables it (`security.command_logging: fix`) and adds a log filter.
- On Folia the anti-bot check world (stages) and the login platform are disabled — Folia does not allow running arenas from one thread across regions. Registration, login, 2FA, connection limits, ping check, bans and AFK protection work.
- Premium auto-login requires online-mode or a proxy with secure forwarding.

Support and ideas — MineLeak.pro discussions (author Vorchun).
