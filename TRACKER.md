# VTRegister — глобальный трекер задач

Статус обновляется по факту проверки (не по факту правки).
Легенда: ✅ проверено · 🟡 сделано, ждёт верификации · ⬜ не сделано

## Критические (продакшн-логи)

| # | Задача | Статус | Что сделано |
|---|--------|--------|-------------|
| 1 | `Asynchronous block remove` из AsyncPlayerChatEvent | ✅ | `onCheckChat` маршаллируется на main через `Scheduler.runAtEntity`; `passStage`, `failCheck` — тоже с guard. Результат применяется `applyChatResult` |
| 2 | Чит с блокировкой слотов проходит SLOTS | ✅ | Re-lock на `preForceSlot` считается попыткой (лимит `slot_relock_max`, деф. 4); проход только на НОВЫЙ слот ≠ forced и ≠ pre-force |
| 3 | Чит с камерой набирает joltAcks | ✅ | Эхо нашего jolt-телепорта (`joltEchoPending` + ожидаемые yaw/pitch) больше не засчитывается как ответ клиента |
| 4 | Кнопка скорости не выдаёт Speed II 30с | ✅ | Приём любого `*_BUTTON`, точная зона `speedButtonLoc` (≤2 блока) или лобби-зона; лог ошибки `addPotionEffect`; `level-1` усилитель, `seconds*20` тиков |
| 5 | Падение в бездну во время проверки → fly-кик | ✅ | Void-guard в ветке `preparing` (stage==null): возврат на `arenaSpawn` вместо фриза в пустоте; FALL уже имеет `voidFalls`/`startFallRep` |
| 6 | Телепорт на платформу теряется (этапы 6-8) | ✅ | Трекер `tpTarget`/`tpRetries`: до 3 повторных телепортов по move-пакетам, потерянным из-за лага |
| 7 | Сломанная табличка/стена пазла не восстанавливается | ✅ | PUZZLE-watchdog каждые 2с переставляет кварцевую стену и табличку с текстом `puzzle_sign_lines` |
| 8 | `/reg` `/l` не блокируются во время проверки | ✅ | Во время `isChecking` auth-команды отменяются; из очереди (не проверки) — разрешены |
| 9 | Логин в очереди не выводит на сервер/мир | ✅ | `afterLoginSuccess` → `leaveQueues` (снятие с queue+entryQueue, restorePlayer, teleport `lobbyReturn`) + after_auth-маршрутизация |

## Защита целостности

| # | Задача | Статус |
|---|--------|--------|
| 10 | `sigOkLocal` — независимая проверка хеша классов jar vs `vrk.dat` (XOR) | ✅ `s()=true`, `sigOkLocal=true`, tamper→false (проверено на финальном jar) |
| 11 | `IntegrityGuard`: `EXPECTED_FINGERPRINT` (raw) + `EXPECTED2` (sig) + p7-домены ×45 | ✅ |

## Конфигурация (новые ключи)

- `antibot.slot_relock_max: 4` — возвратов на pre-force слот до кика
- `antibot.slots_response_ms: 8000` — таймаут ответа на forced-слот
- `antibot.camera_snap_degrees: 60` / `camera_snap_max: 4` — спин-бот детект
- `antibot.queue_pvp.speed_button.{enabled,seconds:30,level:2}` — Speed II 30с

## Верификация (финальный прогон)

| Шаг | Статус |
|-----|--------|
| `mvn package` | ✅ |
| StandaloneTest 49/49 | ✅ |
| YAML валидация (config/lang/advanced/plugin) | ✅ 6 файлов OK |
| `Sec.s()` на собранном jar | ✅ sigValue=356f02a1 совпал |
| `sigOkLocal` tamper-test | ✅ финальный jar: true; подделанный: другой хеш |
| git diff review | ✅ 49 файлов, только целевые правки + p7-константы |
| Коммит + пуш | ✅ 5165d99 → main |

## Известные ограничения

- SLOTS: бот, честно шлющий произвольные слот-пакеты, неотличим от человека
  на этом слое — его ловят camera-linearity/snap/packet-rate этапы.
- Скорость кнопки требует мир `auth_verify`/check-world — в другом мире не сработает.
- `queueMode == 2` обязателен для лобби-очереди (`isInQueueLobby`).
