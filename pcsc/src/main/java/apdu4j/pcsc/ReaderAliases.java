// SPDX-FileCopyrightText: 2019 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public final class ReaderAliases {
    private static final Logger logger = LoggerFactory.getLogger(ReaderAliases.class);
    public static final String ENV_APDU4J_ALIASES = "APDU4J_ALIASES";
    private static final AtomicReference<ReaderAliases> INSTANCE = new AtomicReference<>();
    private final Map<String, String> aliases;

    // Return the alias for name
    public Optional<String> alias(String name) {
        return aliases.entrySet().stream().filter(e -> matches(name, e.getKey())).findFirst().map(Entry::getValue);
    }

    // Returns the alias + fullname if it matches, fullname otherwise
    public String extended(String name) {
        return alias(name).map(a -> "%s (%s)".formatted(a, name)).orElse(name);
    }

    private boolean matches(String name, String match) {
        return name.toLowerCase().contains(match.toLowerCase());
    }

    static boolean verify(Map<String, String> aliases) {
        // Matches must be unique
        var matches = aliases.keySet().stream().map(String::toLowerCase).collect(Collectors.toSet());
        if (matches.size() != aliases.size()) {
            logger.error("Matches are not unique");
            return false;
        }

        // Aliases must be unique
        var all = aliases.values().stream().map(String::toLowerCase).collect(Collectors.toSet());
        if (aliases.size() != all.size()) {
            logger.error("Aliases are not unique");
            return false;
        }
        return true;
    }

    private ReaderAliases(Map<String, String> aliases) {
        this.aliases = Map.copyOf(aliases);
    }

    public ReaderAliases apply(Collection<String> names) {
        // Loop aliases and see if some matches more than one, in which case
        var uniq = new HashMap<String, String>();
        for (Entry<String, String> alias : aliases.entrySet()) {
            if (names.stream().filter(e -> matches(e, alias.getKey())).limit(2).count() > 1) {
                logger.trace("{} matches more than one, disabling", alias.getKey());
            } else {
                uniq.put(alias.getKey(), alias.getValue());
            }
        }

        return new ReaderAliases(uniq);
    }

    public static ReaderAliases load(Path p) throws IOException {
        var loaded = new HashMap<String, String>();
        if (!Files.exists(p)) {
            return new ReaderAliases(Map.of());
        }

        try (InputStream in = Files.newInputStream(p)) {
            var content = new Yaml().<List<Map<String, String>>>load(in);
            for (var e : content) {
                loaded.put(e.get("match"), e.get("alias"));
            }
        } catch (IOException e) {
            logger.error("Could not parse reader name aliases: " + e.getMessage(), e);
        }
        if (!verify(loaded)) {
            throw new IOException("Matches or aliases are not uniq!");
        }
        logger.info("Loaded aliases: {}", loaded);
        return new ReaderAliases(loaded);
    }


    public static ReaderAliases getDefault() {
        return INSTANCE.updateAndGet(current -> current != null ? current : loadDefault());
    }

    private static ReaderAliases loadDefault() {
        try {
            if (System.getenv().containsKey(ENV_APDU4J_ALIASES)) {
                return load(Paths.get(System.getenv(ENV_APDU4J_ALIASES)));
            } else {
                // TODO: Windows has appdata
                return load(Paths.get(System.getProperty("user.home"), ".apdu4j", "aliases.yaml"));
            }
        } catch (IOException e) {
            logger.error("Could not load reader aliases: " + e.getMessage(), e);
            return new ReaderAliases(Map.of());
        }
    }
}
