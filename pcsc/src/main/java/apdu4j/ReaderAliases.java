/*
 * Copyright (c) 2019-2020 Martin Paljak
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
package apdu4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class ReaderAliases extends HashMap<String, String> {
    private static final long serialVersionUID = -2039373752191750500L;
    private static transient final Logger logger = LoggerFactory.getLogger(ReaderAliases.class);
    private static transient volatile ReaderAliases INSTANCE;

    public List<String> translate(List<String> readerNames) {
        return readerNames.stream().map(this::translate).collect(Collectors.toList());
    }

    // Translates full name to alias name, if it matches any
    public String translate(String name) {
        return entrySet().stream().filter(e -> matches(name, e.getKey())).findFirst().map(Entry::getValue).orElse(name);
    }

    // Return the alias for name
    public Optional<String> alias(String name) {
        return entrySet().stream().filter(e -> matches(name, e.getKey())).findFirst().map(Entry::getValue);
    }

    // Returns the alias, if it matches
    public String extended(String name) {
        return alias(name).map(a -> String.format("%s (%s)", a, name)).orElse(name);
    }

    private boolean matches(String name, String match) {
        return name.toLowerCase().contains(match.toLowerCase());
    }

    boolean verify() {
        // Matches must be uniq
        Set<String> matches = keySet().stream().map(String::toLowerCase).collect(Collectors.toSet());
        if (matches.size() != keySet().size()) {
            logger.error("Matches are not unique");
            return false;
        }

        // Aliases must be uniq
        Set<String> aliases = values().stream().map(String::toLowerCase).collect(Collectors.toSet());
        if (values().size() != aliases.size()) {
            logger.error("Aliases are not unique");
            return false;
        }
        return true;
    }

    public static ReaderAliases load(Path p) throws IOException {
        ReaderAliases aliases = new ReaderAliases();
        if (!Files.exists(p))
            return aliases;

        try (InputStream in = Files.newInputStream(p)) {
            ArrayList<Map<String, String>> content = new Yaml().load(in);
            for (Map<String, String> e : content) {
                aliases.put(e.get("match"), e.get("alias"));
            }
        } catch (IOException e) {
            logger.error("Could not parse reader name aliases: " + e.getMessage(), e);
        }
        if (!aliases.verify())
            throw new IOException("Matches or aliases are not uniq!");
        logger.info("Loaded aliases: {}", aliases);
        return aliases;
    }


    public static ReaderAliases getDefault() {
        if (INSTANCE == null) {
            try {
                if (System.getenv().containsKey("APDU4J_ALIASES")) {
                    INSTANCE = load(Paths.get(System.getenv("APDU4J_ALIASES")));
                } else {
                    INSTANCE = load(Paths.get(System.getProperty("user.home"), ".apdu4j", "aliases.yaml"));
                }
            } catch (IOException e) {
                logger.error("Could not load reader aliases: " + e.getMessage(), e);
                INSTANCE = new ReaderAliases();
            }
        }
        return INSTANCE;
    }
}
