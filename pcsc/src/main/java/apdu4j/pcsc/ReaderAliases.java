/*
 * Copyright (c) 2019-present Martin Paljak
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
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
import java.util.stream.Collectors;

public final class ReaderAliases {
    private static transient final Logger logger = LoggerFactory.getLogger(ReaderAliases.class);
    public static final String ENV_APDU4J_ALIASES = "APDU4J_ALIASES";
    private static transient volatile ReaderAliases INSTANCE;
    private transient final HashMap<String, String> aliases;

    // Translates full name to alias name, if it matches any
    public String translate(String name) {
        return aliases.entrySet().stream().filter(e -> matches(name, e.getKey())).findFirst().map(Entry::getValue).orElse(name);
    }

    // Return the alias for name
    public Optional<String> alias(String name) {
        return aliases.entrySet().stream().filter(e -> matches(name, e.getKey())).findFirst().map(Entry::getValue);
    }

    // Returns the alias + fullname if it matches, fullname otherwise
    public String extended(String name) {
        return alias(name).map(a -> String.format("%s (%s)", a, name)).orElse(name);
    }

    public String extended2(String name) {
        return alias(name).map(a -> String.format("%s (%s)", name, a)).orElse(name);
    }

    private boolean matches(String name, String match) {
        return name.toLowerCase().contains(match.toLowerCase());
    }

    static boolean verify(HashMap<String, String> aliases) {
        // Matches must be unique
        Set<String> matches = aliases.keySet().stream().map(String::toLowerCase).collect(Collectors.toSet());
        if (matches.size() != aliases.keySet().size()) {
            logger.error("Matches are not unique");
            return false;
        }

        // Aliases must be unique
        Set<String> all = aliases.values().stream().map(String::toLowerCase).collect(Collectors.toSet());
        if (aliases.values().size() != all.size()) {
            logger.error("Aliases are not unique");
            return false;
        }
        return true;
    }

    private ReaderAliases(HashMap<String, String> aliases) {
        this.aliases = aliases;
    }

    public ReaderAliases apply(Collection<String> names) {
        // Loop aliases and see if some matches more than one, in which case
        HashMap<String, String> uniq = new HashMap<>();
        for (Entry<String, String> alias : aliases.entrySet()) {
            if (names.stream().filter(e -> matches(e, alias.getKey())).count() > 1) {
                logger.trace("{} matches more than one, disabling", alias.getKey());
            } else {
                uniq.put(alias.getKey(), alias.getValue());
            }
        }

        return new ReaderAliases(uniq);
    }

    public static ReaderAliases load(Path p) throws IOException {
        HashMap<String, String> loaded = new HashMap<>();
        if (!Files.exists(p))
            return new ReaderAliases(loaded);

        try (InputStream in = Files.newInputStream(p)) {
            ArrayList<Map<String, String>> content = new Yaml().load(in);
            for (Map<String, String> e : content) {
                loaded.put(e.get("match"), e.get("alias"));
            }
        } catch (IOException e) {
            logger.error("Could not parse reader name aliases: " + e.getMessage(), e);
        }
        if (!verify(loaded))
            throw new IOException("Matches or aliases are not uniq!");
        logger.info("Loaded aliases: {}", loaded);
        return new ReaderAliases(loaded);
    }


    public static ReaderAliases getDefault() {
        if (INSTANCE == null) {
            try {
                if (System.getenv().containsKey(ENV_APDU4J_ALIASES)) {
                    INSTANCE = load(Paths.get(System.getenv(ENV_APDU4J_ALIASES)));
                } else {
                    // TODO: Windows has appdata
                    INSTANCE = load(Paths.get(System.getProperty("user.home"), ".apdu4j", "aliases.yaml"));
                }
            } catch (IOException e) {
                logger.error("Could not load reader aliases: " + e.getMessage(), e);
                INSTANCE = new ReaderAliases(new HashMap<>());
            }
        }
        return INSTANCE;
    }
}
