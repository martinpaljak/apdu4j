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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.CodeSource;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Plug {
    private static final Logger logger = LoggerFactory.getLogger(Plug.class);

    static final ClassLoader bundledLoader;

    static {
        try {
            bundledLoader = getPluginsClassLoader("APDU4J_PLUGINS", Paths.get(System.getProperty("user.home"), ".apdu4j", "plugins"));
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize plugins!");
        }
    }

    static List<Path> jars(Path folder) {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries
                    .filter(Files::isReadable)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jar"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Make a classloader of .jar files in a given folder.
    static ClassLoader pluginFolderClassLoader(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            logger.trace("Can't load plugins from " + folder + " defaulting to current classloader");
            return Thread.currentThread().getContextClassLoader();
        }

        logger.debug("Plugins loaded from {}", folder);
        List<URL> plugins = jars(folder).stream()
                .map(p -> {
                    try {
                        return p.toUri().toURL();
                    } catch (MalformedURLException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .collect(Collectors.toList());

        return AccessController.doPrivileged(
                (PrivilegedAction<URLClassLoader>) () -> new URLClassLoader(plugins.toArray(new URL[plugins.size()]))
        );
    }

    static <T> Optional<T> loadPlugin(String name, Class<T> c) {
        return loadPlugin(bundledLoader, name, c);
    }

    // Loads a plugin by name from classloader
    static <T> Optional<T> loadPlugin(ClassLoader cl, String name, Class<T> t) {
        try {
            ServiceLoader<T> sl = ServiceLoader.load(t, cl);
            List<T> list = new ArrayList<>();
            sl.iterator().forEachRemaining(list::add);
            list.stream().forEach(e -> logger.debug("Found {} from {}", e.getClass().getCanonicalName(), pluginfile(e)));
            return list.stream().filter(e -> identifies(name, e.getClass())).findFirst();
        } catch (ServiceConfigurationError e) {
            throw new RuntimeException("Failed to load plugin: " + e.getCause().getMessage());
        }
    }

    // Loads given plugin from a JAR file
    // Throws runtime exception if can't be loaded
    static <T> Optional<T> loadPlugin(Path p, Class<T> t) {
        try {
            return loadPlugin(p.toUri().toURL(), t);
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid plugin: " + e.getMessage());
        }
    }

    // Load a plugin from a file
    static <T> Optional<T> loadPlugin(URL u, Class<T> t) {
        try {
            // Load plugin
            URL[] plugin = new URL[]{u};
            logger.debug("Loading plugin from " + plugin[0]);

            final URLClassLoader ucl = AccessController.doPrivileged(
                    (PrivilegedAction<URLClassLoader>) () -> new URLClassLoader(plugin)
            );
            ServiceLoader<T> sl = ServiceLoader.load(t, ucl);
            List<T> list = new ArrayList<>();
            sl.iterator().forEachRemaining(list::add);

            if (list.size() != 1) {
                logger.debug("Could not load plugin, found {} services for {}", list.size(), t.getCanonicalName());
                return Optional.empty();
            }
            logger.debug("Loaded " + list.get(0).getClass().getCanonicalName() + " from " + u);
            return Optional.ofNullable(list.get(0));
        } catch (ServiceConfigurationError e) {
            logger.error("Failed to load plugin: " + e.getCause().getMessage());
            return Optional.empty();
        }
    }

    // Gets the jarfile of the plugin for display purposes
    static String pluginfile(Object c) {
        CodeSource src = c.getClass().getProtectionDomain().getCodeSource();
        if (src == null)
            return "BUILTIN";
        URL l = src.getLocation();
        if (l.getProtocol().equals("file")) {
            return l.getFile();
        }
        return l.toExternalForm();
    }

    // Print plugins for service in stdout
    static <T> void listPlugins(Class<T> service) {
        pluginStream(service).forEach(e -> System.out.println(String.format("- %s from %s", e.getClass().getCanonicalName(), pluginfile(e))));
    }

    // Returns the classloader that contains plugin folder jar-s
    static ClassLoader getPluginsClassLoader(String env, Path p) throws IOException {
        // Override from environment
        if (System.getenv().containsKey(env)) {
            Path plugins = Paths.get(System.getenv(env));
            logger.debug("Using plugins from ${}: {}", env, plugins);
            return pluginFolderClassLoader(plugins);
        }
        logger.debug("Using plugins from {}", p);
        return pluginFolderClassLoader(p);
    }

    // Returns true if the name somehow addresses the given class
    static boolean identifies(String name, Class<?> c) {
        logger.trace("{} vs {}", name, c.getCanonicalName());
        return c.getCanonicalName().equalsIgnoreCase(name) || c.getSimpleName().equalsIgnoreCase(name);
    }

    // Returns a list of existing plugins for a service, masking unloadable ones
    static <T> List<T> plugins(Class<T> t, ClassLoader loader) {
        ServiceLoader<T> sl = ServiceLoader.load(t, loader);
        List<T> list = new ArrayList<>();
        // We skip bad plugins
        Iterator<T> it = sl.iterator();
        while (it.hasNext()) {
            try {
                list.add(it.next());
            } catch (ServiceConfigurationError e) {
                if (e.getCause() != null) {
                    logger.warn("Plugin loading failure: " + e.getCause().getMessage());
                } else {
                    logger.warn("Plugin loading failure: " + e.getMessage());
                }
            }
        }
        return list;
    }

    static <T> Stream<T> pluginStream(Class<T> t) {
        return plugins(t, bundledLoader).stream();
    }
}
