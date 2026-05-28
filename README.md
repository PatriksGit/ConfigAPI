# ConfigAPI

Typed YAML config loader for Paper / Velocity Minecraft plugins.

- Dot-notation key access (`"database.host"`)
- Typed getters with defaults and bounds validation
- Auto-extract bundled default to disk on first run
- Hot-reload safe — immutable snapshot, swap the reference atomically

## Telepítés

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.PatriksGit</groupId>
    <artifactId>ConfigAPI</artifactId>
    <version>v1.0.0</version>
</dependency>
```

SnakeYAML és SLF4J `provided` — Paper és Velocity runtime-ban szállítja őket.

---

## Használat

### Betöltés

**Paper:**
```java
private volatile ConfigFile config;

@Override
public void onEnable() {
    try {
        config = ConfigFile.load(
            getDataFolder().toPath().resolve("config.yml"),
            getResource("config.yml"),   // bundled default, auto-extract ha hiányzik
            getSLF4JLogger()
        );
    } catch (IOException e) {
        getSLF4JLogger().error("Failed to load config", e);
        getServer().getPluginManager().disablePlugin(this);
    }
}
```

**Velocity:**
```java
config = ConfigFile.load(
    dataDir.resolve("config.yml"),
    getClass().getResourceAsStream("/config.yml"),
    logger
);
```

### Hot-reload

A `ConfigFile` immutable — reloadkor új példányt hozunk létre és atomikusan cseréljük a referenciát:

```java
void reload() throws IOException {
    config = ConfigFile.load(
        dataDir.resolve("config.yml"),
        getClass().getResourceAsStream("/config.yml"),
        logger
    );
}
```

Olvasók mindig konzisztens snapshot-ot látnak — soha nem látnak félig kicserélt értékeket.

### Getterek

```java
// Alap típusok — hiányos vagy rossz típusú kulcs esetén a default-ot adja vissza + WARN log
String  host    = config.getString("database.host", "localhost");
int     port    = config.getInt("database.port", 3306);
long    timeout = config.getLong("database.timeout-ms", 5000L);
double  ratio   = config.getDouble("limiter.ratio", 1.0);
boolean enabled = config.getBoolean("feature.geo-filter", false);

// Bounded int — ha kívül esik a [min, max] tartományon, default + WARN log
int iterations = config.getInt("argon2.iterations", 3, 1, 10);

// String lista — YAML lista és egysoros string is működik
List<String> allowed = config.getStringList("maintenance.allowed-commands", List.of());

// Enum — case-insensitive
BarColor color = config.getEnum(BarColor.class, "bossbar.color", BarColor.GREEN);

// Kötelező kulcs — ConfigException (IOException) ha hiányzik
String password = config.require("database.password");
```

### Section — szekció-alapú olvasás

```java
ConfigFile db = config.section("database");
String host = db.getString("host", "localhost"); // reads "database.host"
int    port = db.getInt("port", 3306);           // reads "database.port"
```

### contains

```java
if (config.contains("feature.geo-filter")) { ... }
```

---

## config.yml példa

```yaml
database:
  host: localhost
  port: 3306
  password: changeme
  timeout-ms: 5000

argon2:
  iterations: 3   # 1–10

bossbar:
  enabled: true
  color: GREEN    # BLUE, GREEN, PINK, PURPLE, RED, WHITE, YELLOW
  style: SOLID

maintenance:
  enabled: false
  allowed-commands:
    - /login
    - /register
```

---

## Teljes API

```java
// Betöltés
static ConfigFile load(Path file, InputStream defaultResource, Logger logger) throws IOException
static ConfigFile load(Path file, Logger logger) throws IOException

// Szekció nézet
ConfigFile section(String path)

// Kulcs jelenlét
boolean contains(String key)

// Kötelező kulcs
String require(String key) throws ConfigException

// Getterek
String          getString(String key, String def)
int             getInt(String key, int def)
int             getInt(String key, int def, int min, int max)
long            getLong(String key, long def)
double          getDouble(String key, double def)
boolean         getBoolean(String key, boolean def)
List<String>    getStringList(String key, List<String> def)
<E extends Enum<E>> E getEnum(Class<E> type, String key, E def)
```

**Null-biztonság:** minden getter null-safe, hiányzó vagy rossz típusú kulcsnál a default értéket adja vissza.
**Reload:** hozz létre új `ConfigFile` példányt és cseréld le a `volatile` fieldet.

---

## License

MIT
