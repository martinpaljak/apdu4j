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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;

public class ReaderAliases extends HashMap<String, String> {
    private static final long serialVersionUID = -2039373752191750500L;
    private static transient final Logger logger = LoggerFactory.getLogger(ReaderAliases.class);
    private static transient final ObjectMapper mapper = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));

    public static class AliasEntry {
        public final String match;
        public final String alias;

        @JsonCreator
        public AliasEntry(@JsonProperty("match") String match, @JsonProperty("alias") String alias) {
            this.match = match;
            this.alias = alias;
        }
    }

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
            logger.error("Matches are not uniq");
            return false;
        }

        // Aliases must be uniq
        Set<String> aliases = values().stream().map(String::toLowerCase).collect(Collectors.toSet());
        if (values().size() != aliases.size()) {
            logger.error("Aliases are not uniq");
            return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public static ReaderAliases load(Path p) throws IOException {
        ReaderAliases aliases = new ReaderAliases();
        if (!Files.exists(p))
            return aliases;
        ArrayList<AliasEntry> entries = new ArrayList<>();
        try (InputStream out = Files.newInputStream(p)) {
            entries = mapper.readValue(out, new TypeReference<ArrayList<AliasEntry>>() {
            });
        } catch (JsonParseException | JsonMappingException e) {
            logger.error("Could not parse reader name aliases: " + e.getMessage(), e);
        }
        entries.stream().forEach(e -> aliases.put(e.match, e.alias));
        if (!aliases.verify())
            throw new IOException("Matches or aliases are not uniq!");
        logger.info("Loaded aliases: {}", aliases);
        return aliases;
    }

    public void save(Path p) throws IOException {
        List<AliasEntry> entries = new ArrayList<>();
        entrySet().stream().forEach(e -> entries.add(new AliasEntry(e.getKey(), e.getValue())));
        try (OutputStream out = Files.newOutputStream(p, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)) {
            mapper.writeValue(out, entries);
        }
    }

    public static ReaderAliases getDefault() {
        try {
            if (System.getenv().containsKey("APDU4J_ALIASES")) {
                return load(Paths.get(System.getenv("APDU4J_ALIASES")));
            } else {
                return load(Paths.get(System.getProperty("user.home"), ".apdu4j", "aliases.yaml"));
            }
        } catch (IOException e) {
            logger.error("Could not load reader aliases: " + e.getMessage(), e);
        }
        return new ReaderAliases();
    }
}
