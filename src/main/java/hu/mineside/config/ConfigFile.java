package hu.mineside.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable YAML configuration snapshot with typed getters and dot-notation key access.
 *
 * <p>Load once and hold as a {@code volatile} field — on reload, replace the reference
 * atomically so readers always see a consistent snapshot:
 * <pre>{@code
 * private volatile ConfigFile config;
 *
 * void onEnable() throws IOException {
 *     config = ConfigFile.load(dataDir.resolve("config.yml"), getResource("config.yml"), logger);
 * }
 *
 * void reload() throws IOException {
 *     config = ConfigFile.load(dataDir.resolve("config.yml"), getResource("config.yml"), logger);
 * }
 * }</pre>
 *
 * <p>If the file doesn't exist and a default {@link InputStream} is provided,
 * it is automatically extracted to disk before loading.
 *
 * <p>Dot-notation is used for nested keys: {@code "database.host"} resolves
 * {@code database → host} in the YAML tree.
 *
 * <p>All getters are null-safe — a missing or wrong-typed key returns the default value
 * with a WARN log. Use {@link #require(String)} for mandatory keys that should halt startup.
 */
public final class ConfigFile {

    private final Map<String, Object> root;
    private final String pathPrefix;
    private final Logger logger;

    private ConfigFile(Map<String, Object> root, String pathPrefix, Logger logger) {
        this.root = root;
        this.pathPrefix = pathPrefix;
        this.logger = logger;
    }

    // ── load ─────────────────────────────────────────────────────────────────

    /**
     * Loads a YAML file, auto-extracting {@code defaultResource} to disk if the file
     * doesn't exist yet.
     *
     * @param file            full path to the config file
     * @param defaultResource bundled default; copied to {@code file} if missing. May be {@code null}
     *                        if auto-extract is not needed (throws if file is also missing).
     * @param logger          SLF4J logger for type-mismatch and bounds warnings
     */
    public static ConfigFile load(Path file, InputStream defaultResource, Logger logger) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(logger, "logger");
        ensureExists(file, defaultResource);
        return new ConfigFile(readYaml(file, logger), "", logger);
    }

    /**
     * Loads a YAML file without auto-extract. The file must already exist.
     */
    public static ConfigFile load(Path file, Logger logger) throws IOException {
        return load(file, null, logger);
    }

    // ── section view ──────────────────────────────────────────────────────────

    /**
     * Returns a view of this config scoped to {@code path}.
     * Subsequent key lookups are relative to that path.
     *
     * <pre>{@code
     * ConfigFile db = config.section("database");
     * String host = db.getString("host", "localhost"); // reads "database.host"
     * }</pre>
     */
    public ConfigFile section(String path) {
        Objects.requireNonNull(path, "path");
        String full = pathPrefix.isEmpty() ? path : pathPrefix + "." + path;
        return new ConfigFile(root, full, logger);
    }

    // ── key presence ─────────────────────────────────────────────────────────

    /** Returns {@code true} if the key exists and is non-null in the YAML. */
    public boolean contains(String key) {
        return getRaw(key) != null;
    }

    // ── required key ─────────────────────────────────────────────────────────

    /**
     * Returns the raw string value of {@code key}.
     * Throws {@link ConfigException} if the key is missing or null — use this
     * for mandatory fields (database password, API key, etc.) that should halt startup.
     */
    public String require(String key) throws ConfigException {
        Object val = getRaw(key);
        if (val == null) throw new ConfigException("Required config key missing: '" + fullKey(key) + "'");
        return String.valueOf(val);
    }

    // ── typed getters ─────────────────────────────────────────────────────────

    /** Returns the string value at {@code key}, or {@code def} if missing or wrong type. */
    public String getString(String key, String def) {
        Object val = getRaw(key);
        return val != null ? String.valueOf(val) : def;
    }

    /** Returns the int value at {@code key}, or {@code def} if missing or wrong type. */
    public int getInt(String key, int def) {
        Object val = getRaw(key);
        if (val instanceof Number n) return n.intValue();
        if (val != null) warnType(key, "int", val);
        return def;
    }

    /**
     * Returns the int value at {@code key} clamped to [{@code min}, {@code max}].
     * If the value is outside the range, logs a warning and returns {@code def}.
     */
    public int getInt(String key, int def, int min, int max) {
        int v = getInt(key, def);
        if (v < min || v > max) {
            logger.warn("Config '{}' value {} is outside allowed range [{}, {}] — using {}.",
                    fullKey(key), v, min, max, def);
            return def;
        }
        return v;
    }

    /** Returns the long value at {@code key}, or {@code def} if missing or wrong type. */
    public long getLong(String key, long def) {
        Object val = getRaw(key);
        if (val instanceof Number n) return n.longValue();
        if (val != null) warnType(key, "long", val);
        return def;
    }

    /** Returns the double value at {@code key}, or {@code def} if missing or wrong type. */
    public double getDouble(String key, double def) {
        Object val = getRaw(key);
        if (val instanceof Number n) return n.doubleValue();
        if (val != null) warnType(key, "double", val);
        return def;
    }

    /** Returns the boolean value at {@code key}, or {@code def} if missing or wrong type. */
    public boolean getBoolean(String key, boolean def) {
        Object val = getRaw(key);
        if (val instanceof Boolean b) return b;
        if (val != null) warnType(key, "boolean", val);
        return def;
    }

    /**
     * Returns a string list at {@code key}.
     * Handles both YAML list values and single-string values (wraps to 1-element list).
     * Returns {@code def} if the key is missing.
     */
    public List<String> getStringList(String key, List<String> def) {
        Object val = getRaw(key);
        if (val instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(item != null ? String.valueOf(item) : "");
            return Collections.unmodifiableList(result);
        }
        if (val instanceof String s) return List.of(s);
        if (val != null) warnType(key, "list", val);
        return def;
    }

    /**
     * Returns the enum value at {@code key} (case-insensitive), or {@code def} if missing
     * or not a valid enum constant.
     */
    public <E extends Enum<E>> E getEnum(Class<E> type, String key, E def) {
        String val = getString(key, null);
        if (val == null) return def;
        try {
            return Enum.valueOf(type, val.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warn("Config '{}' value '{}' is not a valid {} — using {}.",
                    fullKey(key), val, type.getSimpleName(), def);
            return def;
        }
    }

    // ── internal ──────────────────────────────────────────────────────────────

    private static void ensureExists(Path file, InputStream defaultResource) throws IOException {
        if (Files.exists(file)) return;
        if (defaultResource == null)
            throw new IOException("Config file not found and no default provided: " + file);
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.copy(defaultResource, file);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readYaml(Path file, Logger logger) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            Object parsed = new Yaml().load(in);
            if (parsed == null) {
                logger.warn("{} is empty — using empty config.", file.getFileName());
                return Map.of();
            }
            if (!(parsed instanceof Map)) {
                throw new ConfigException("Config file root must be a YAML mapping: " + file);
            }
            return Collections.unmodifiableMap((Map<String, Object>) parsed);
        }
    }

    private String fullKey(String key) {
        return pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
    }

    @SuppressWarnings("unchecked")
    private Object getRaw(String key) {
        String[] parts = fullKey(key).split("\\.", -1);
        Object current = root;
        for (String part : parts) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(part);
        }
        return current;
    }

    private void warnType(String key, String expected, Object actual) {
        logger.warn("Config '{}' expected {} but got {} — using default.",
                fullKey(key), expected, actual.getClass().getSimpleName());
    }
}
