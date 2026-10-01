# VTRegister API для разработчиков

**Русский** · [English](#english)

Публичный API VTRegister позволяет другим плагинам узнавать, вошёл ли игрок, реагировать на вход, регистрацию и проверку на бота, а также строить свои окна входа (GUI, наковальня, NPC). Пакет — `me.vorchun.registerplugin.api` (исторический, от старого имени RegisterPlugin; название плагина — VTRegister).

## Подключение

1. Скачай `VTRegister-vX.Y.Z.jar` из [Releases](https://github.com/Vitaliy222121/VTRegister/releases/latest) и подключи его **только для компиляции**:

   **Gradle (Kotlin DSL)**
   ```kotlin
   dependencies {
       compileOnly(files("libs/VTRegister-v1.1.6.jar"))
   }
   ```
   **Maven**
   ```xml
   <dependency>
       <groupId>me.vorchun</groupId>
       <artifactId>vtregister</artifactId>
       <version>1.1.6</version>
       <scope>system</scope>
       <systemPath>${project.basedir}/libs/VTRegister-v1.1.6.jar</systemPath>
   </dependency>
   ```
   Не встраивай (не shade) jar VTRegister в свой плагин.

2. В `plugin.yml` своего плагина:
   ```yaml
   softdepend: [VTRegister]
   ```

## Получить API

```java
RegisterPluginAPI api = RegisterPluginAPI.get();          // null, если VTRegister не установлен
// или через Bukkit ServicesManager:
RegisterPluginAPI api2 = Bukkit.getServicesManager().load(RegisterPluginAPI.class);
if (api != null && api.isAuthenticated(player)) {
    // игрок вошёл
}
```

## Методы

| Метод | Что делает |
|---|---|
| `isAuthenticated(Player / UUID)` | вошёл ли игрок сейчас |
| `isRegistered(UUID)` | есть ли аккаунт (онлайн-игрок — из кэша) |
| `isRegistered(String name)` | по нику; для офлайн-ника из главного потока — всегда `false`, используй `isRegisteredAsync` |
| `isRegisteredAsync(String name, Consumer<Boolean>)` | то же асинхронно, колбэк в главном потоке |
| `forceLogin(Player)` | авторизовать без пароля (после своей проверки) |
| `forceLogout(Player, boolean kick)` | выйти из аккаунта, по желанию кикнуть |
| `submitPassword(Player, String)` → `CompletableFuture<Boolean>` | вход или регистрация паролем из своего GUI: те же проверки, лимиты попыток и сообщения, что у `/login` и `/reg`. `true` — вошёл |
| `checkPassword(UUID, String)` → `CompletableFuture<Boolean>` | проверить пароль, не входя (хеширование в фоновом потоке) |
| `setPassword(UUID, String)` → `CompletableFuture<Boolean>` | задать новый пароль без старого (как `/authadmin setpw`) |
| `unregister(UUID)` | удалить аккаунт и закрыть сессию |
| `hasTwoFactor(UUID)` | включена ли 2FA |
| `getRegistrationDate(UUID)`, `getLastLogin(UUID)` | время в мс (0 — неизвестно) |
| `isChecking(UUID)` | идёт ли сейчас проверка на бота |
| `isInAntiBot(UUID)` | занят ли игрок антиботом (очередь, проверка, ожидание) |
| `hasPassedAntiBot(UUID)` | прошёл ли проверку с момента старта сервера |
| `getStorageName()` | хранилище: YAML, SQLite, MySQL… |
| `getVersion()` | версия VTRegister |
| `isAvailable()`, `RegisterPluginAPI.isPluginPresent()` | плагин установлен и включён |

Методы с паролями выполняют хеширование в фоновом пуле и никогда не тормозят главный поток. Результат `CompletableFuture` приходит в главном потоке.

## События

Все события вызываются в главном потоке (на Folia — в потоке игрока).

| Событие | Когда |
|---|---|
| `AuthLoginEvent` | игрок вошёл **любым способом**. `getMethod()`: `password`, `register`, `session` (IP-сессия), `premium`, `bedrock`, `other` (2FA, API, forcelogin); `isFirstTime()` — это регистрация |
| `AuthRegisterEvent` | создан новый аккаунт |
| `AuthLogoutEvent` | игрок вышел из аккаунта |
| `AntiBotPassEvent` | игрок прошёл проверку на бота |
| `AntiBotFailEvent` | игрок не прошёл проверку: `getReason()` — текст кика, `isProven()` — `true` для доказанного бота (идёт в баны по IP), `false` для таймаута |

```java
@EventHandler
public void onLogin(AuthLoginEvent e) {
    if (e.isFirstTime()) {
        e.getPlayer().getInventory().addItem(new ItemStack(Material.BREAD, 16)); // стартовый набор
    }
}

@EventHandler
public void onBot(AntiBotFailEvent e) {
    if (e.isProven()) {
        getLogger().info("Бот отсечён: " + e.getUniqueId());
    }
}
```

## Своё окно входа

```java
api.submitPassword(player, typedPassword).thenAccept(ok -> {
    if (!ok) {
        // неверный пароль, слишком простой пароль, нужен код 2FA —
        // VTRegister уже написал игроку, что не так
    }
});
```

---

<a name="english"></a>

# VTRegister developer API

[Русский](#vtregister-api-для-разработчиков) · **English**

The public API lets other plugins check whether a player is logged in, react to login, registration and the anti-bot check, and build their own login windows (GUI, anvil, NPC). Package: `me.vorchun.registerplugin.api` (historical, from the old name RegisterPlugin; the plugin is called VTRegister).

## Setup

1. Download `VTRegister-vX.Y.Z.jar` from [Releases](https://github.com/Vitaliy222121/VTRegister/releases/latest) and add it **for compilation only**:

   **Gradle (Kotlin DSL)**
   ```kotlin
   dependencies {
       compileOnly(files("libs/VTRegister-v1.1.6.jar"))
   }
   ```
   **Maven**
   ```xml
   <dependency>
       <groupId>me.vorchun</groupId>
       <artifactId>vtregister</artifactId>
       <version>1.1.6</version>
       <scope>system</scope>
       <systemPath>${project.basedir}/libs/VTRegister-v1.1.6.jar</systemPath>
   </dependency>
   ```
   Do not shade the VTRegister jar into your plugin.

2. In your `plugin.yml`:
   ```yaml
   softdepend: [VTRegister]
   ```

## Getting the API

```java
RegisterPluginAPI api = RegisterPluginAPI.get();          // null if VTRegister is not installed
// or via the Bukkit ServicesManager:
RegisterPluginAPI api2 = Bukkit.getServicesManager().load(RegisterPluginAPI.class);
if (api != null && api.isAuthenticated(player)) {
    // logged in
}
```

## Methods

| Method | What it does |
|---|---|
| `isAuthenticated(Player / UUID)` | is the player logged in now |
| `isRegistered(UUID)` | does the account exist (online player — from cache) |
| `isRegistered(String name)` | by name; for an offline name on the main thread always `false`, use `isRegisteredAsync` |
| `isRegisteredAsync(String name, Consumer<Boolean>)` | the same, asynchronously; callback on the main thread |
| `forceLogin(Player)` | log in without a password (after your own check) |
| `forceLogout(Player, boolean kick)` | log out, optionally kick |
| `submitPassword(Player, String)` → `CompletableFuture<Boolean>` | login or registration with a password from your own GUI: the same checks, attempt limits and messages as `/login` and `/reg`. `true` — logged in |
| `checkPassword(UUID, String)` → `CompletableFuture<Boolean>` | check a password without logging in (hashing on a background thread) |
| `setPassword(UUID, String)` → `CompletableFuture<Boolean>` | set a new password without the old one (like `/authadmin setpw`) |
| `unregister(UUID)` | delete the account and close the session |
| `hasTwoFactor(UUID)` | is 2FA enabled |
| `getRegistrationDate(UUID)`, `getLastLogin(UUID)` | time in ms (0 — unknown) |
| `isChecking(UUID)` | is the anti-bot check running now |
| `isInAntiBot(UUID)` | is the player busy with the anti-bot (queue, check, waiting) |
| `hasPassedAntiBot(UUID)` | has the player passed the check since server start |
| `getStorageName()` | storage: YAML, SQLite, MySQL… |
| `getVersion()` | VTRegister version |
| `isAvailable()`, `RegisterPluginAPI.isPluginPresent()` | the plugin is installed and enabled |

Password methods hash on a background pool and never block the main thread. `CompletableFuture` results arrive on the main thread.

## Events

All events are called on the main thread (on Folia — on the player's thread).

| Event | When |
|---|---|
| `AuthLoginEvent` | the player logged in **by any method**. `getMethod()`: `password`, `register`, `session` (IP session), `premium`, `bedrock`, `other` (2FA, API, forcelogin); `isFirstTime()` — it was a registration |
| `AuthRegisterEvent` | a new account was created |
| `AuthLogoutEvent` | the player logged out |
| `AntiBotPassEvent` | the player passed the anti-bot check |
| `AntiBotFailEvent` | the player failed the check: `getReason()` — kick text, `isProven()` — `true` for a proven bot (counts towards IP bans), `false` for a timeout |

```java
@EventHandler
public void onLogin(AuthLoginEvent e) {
    if (e.isFirstTime()) {
        e.getPlayer().getInventory().addItem(new ItemStack(Material.BREAD, 16)); // starter kit
    }
}
```

## Your own login window

```java
api.submitPassword(player, typedPassword).thenAccept(ok -> {
    if (!ok) {
        // wrong password, weak password, 2FA code required —
        // VTRegister has already told the player what is wrong
    }
});
```
