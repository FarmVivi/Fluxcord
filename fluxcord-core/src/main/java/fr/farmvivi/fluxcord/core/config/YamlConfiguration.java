package fr.farmvivi.fluxcord.core.config;

import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.config.ConfigurationException;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic YAML configuration implementation.
 */
public class YamlConfiguration implements Configuration {
    protected File configFile;
    protected Map<String, Object> values = new HashMap<>();

    /**
     * Creates a new YAML configuration from a file.
     *
     * @param configFile the configuration file
     * @throws ConfigurationException if there's an error loading the file
     */
    public YamlConfiguration(File configFile) throws ConfigurationException {
        this.configFile = configFile;
        reload();
    }

    /**
     * Empty constructor for subclasses.
     */
    protected YamlConfiguration() {
        // Empty constructor for subclasses
    }

    /**
     * Resolves a nested key (dot-separated) to its value.
     */
    private Object getValueForKey(String key) throws ConfigurationException {
        String[] parts = key.split("\\.");
        Object current = values;
        for (String part : parts) {
            if (!(current instanceof Map)) {
                throw new ConfigurationException("Key not found: " + key);
            }
            Map<?, ?> map = (Map<?, ?>) current;
            if (!map.containsKey(part) || map.get(part) == null) {
                throw new ConfigurationException("Key not found: " + key);
            }
            current = map.get(part);
        }
        return current;
    }

    @Override
    public String getString(String key) throws ConfigurationException {
        Object value = getValueForKey(key);
        return value.toString();
    }

    @Override
    public String getString(String key, String defaultValue) {
        try {
            return getString(key);
        } catch (ConfigurationException e) {
            return defaultValue;
        }
    }

    @Override
    public int getInt(String key) throws ConfigurationException {
        String value = getString(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ConfigurationException("Value is not an integer: " + key);
        }
    }

    @Override
    public int getInt(String key, int defaultValue) {
        try {
            return getInt(key);
        } catch (ConfigurationException e) {
            return defaultValue;
        }
    }

    @Override
    public boolean getBoolean(String key) throws ConfigurationException {
        String value = getString(key);
        return Boolean.parseBoolean(value);
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        try {
            return getBoolean(key);
        } catch (ConfigurationException e) {
            return defaultValue;
        }
    }

    @Override
    public List<String> getStringList(String key) throws ConfigurationException {
        Object raw = getValueForKey(key);
        if (raw instanceof List) {
            List<Object> list = (List<Object>) raw;
            List<String> stringList = new ArrayList<>();
            for (Object obj : list) {
                stringList.add(obj.toString());
            }
            return stringList;
        }
        throw new ConfigurationException("Value is not a list: " + key);
    }

    @Override
    public List<String> getStringList(String key, List<String> defaultValue) {
        try {
            return getStringList(key);
        } catch (ConfigurationException e) {
            return defaultValue;
        }
    }

    @Override
    public void set(String key, Object value) {
        if (key == null || key.isEmpty()) {
            return;
        }

        String[] parts = key.split("\\.");
        Map<String, Object> current = values;

        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            Object next = current.get(part);
            if (!(next instanceof Map)) {
                // Remplace toute valeur non-Map par une Map imbriquée
                Map<String, Object> child = new HashMap<>();
                current.put(part, child);
                current = child;
            } else {
                Map<String, Object> nextMap = (Map<String, Object>) next;
                current = nextMap;
            }
        }

        String last = parts[parts.length - 1];
        current.put(last, value);
    }

    @Override
    public boolean contains(String key) {
        try {
            getValueForKey(key);
            return true;
        } catch (ConfigurationException e) {
            return false;
        }
    }

    @Override
    public List<String> getKeys() {
        return new ArrayList<>(values.keySet());
    }

    @Override
    public Map<String, Object> getValues() {
        return new HashMap<>(values);
    }

    @Override
    public void save() throws ConfigurationException {
        if (configFile == null) {
            throw new ConfigurationException("No file set");
        }

        try {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);

            Yaml yaml = new Yaml(options);
            try (FileWriter writer = new FileWriter(configFile)) {
                yaml.dump(values, writer);
            }
        } catch (IOException e) {
            throw new ConfigurationException("Failed to save configuration", e);
        }
    }

    @Override
    public void reload() throws ConfigurationException {
        if (configFile == null) {
            values.clear();
            return;
        }

        if (!configFile.exists()) {
            values.clear();
            return;
        }

        try {
            Yaml yaml = new Yaml();
            try (FileReader reader = new FileReader(configFile)) {
                Object loaded = yaml.load(reader);
                if (loaded instanceof Map) {
                    Map<String, Object> root = (Map<String, Object>) loaded;
                    values = root;
                } else if (loaded == null) {
                    values.clear();
                } else {
                    // Non-map root not supported; store nothing
                    values.clear();
                }
            }
        } catch (IOException e) {
            throw new ConfigurationException("Failed to load configuration", e);
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            // a syntax error in the file is a configuration error, not an internal one
            throw new ConfigurationException("Malformed configuration file " + configFile.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Gets the configuration file.
     *
     * @return the configuration file
     */
    public File getConfigFile() {
        return configFile;
    }

    /**
     * Sets the configuration file.
     *
     * @param configFile the configuration file
     */
    public void setConfigFile(File configFile) {
        this.configFile = configFile;
    }
}