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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
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

    // Make a classloader of .jar files in a given folder.
    // FIXME: false positive?
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    static ClassLoader pluginFolderClassLoader(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            logger.trace("Can't load plugins from " + folder + " defaulting to current classloader");
            return Thread.currentThread().getContextClassLoader();
        }

        logger.debug("Plugins loaded from {}", folder);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            ArrayList<URL> plugins = new ArrayList<>();
            for (Path p : entries) {
                if (Files.isRegularFile(p)
                        && p.getFileName() != null
                        && p.getFileName().toString().endsWith(".jar")) {
                    plugins.add(p.toUri().toURL());
                    logger.trace("Loading: " + p);
                } else {
                    logger.trace("Ignoring: " + p);
                }
            }
            return AccessController.doPrivileged(
                    (PrivilegedAction<URLClassLoader>) () -> new URLClassLoader(plugins.toArray(new URL[plugins.size()]))
            );
        }
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

            // FIXME: make it so that files would not be downloaded twice.
            final URLClassLoader ucl = AccessController.doPrivileged(
                    (PrivilegedAction<URLClassLoader>) () -> new URLClassLoader(plugin)
            );
            ServiceLoader<T> sl = ServiceLoader.load(t, ucl);
            List<T> list = new ArrayList<>();
            sl.iterator().forEachRemaining(list::add);

            if (list.size() != 1) {
                logger.error("Could not load plugin, found " + list.size() + " services for " + t.getCanonicalName() + " in plugin:\n" + list.stream().map(e -> e.getClass().getCanonicalName()).collect(Collectors.joining("\n")));
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

    // Returns true if the spec matches a class in classloader for a service
    static <T> boolean identifiesPlugin(String spec, Class<T> service) {
        return pluginStream(service).anyMatch(e -> identifies(spec, e.getClass()));
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
            logger.debug("Using plugins from ${}}: {}", env, plugins);
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

    static <T> Stream<T> pluginStream(Class<T> t, ClassLoader loader) {
        return plugins(t, loader).stream();
    }

    static <T> Stream<T> pluginStream(Class<T> t) {
        return plugins(t, bundledLoader).stream();
    }


    // This is used for fetching apps
    static <T> Optional<T> getRemotePluginIfNotLocal(String spec, Class<T> c) {
        // prefer builtin plugins
        if (identifiesPlugin(spec, c)) {
            return loadPlugin(bundledLoader, spec, c);
        }

        // Then local paths
        if (Files.isRegularFile(Paths.get(spec))) {
            return loadPlugin(Paths.get(spec), c);
        }
        // then remote ones
        if (spec.startsWith("https://")) {
            try {
                return loadPlugin(URI.create(spec).toURL(), c);
            } catch (MalformedURLException e) {
                logger.error("Invalid URL: " + spec);
                return Optional.empty();
            }
        }
        logger.warn("Could not get plugin for {} via {}", c.getCanonicalName(), spec);
        return Optional.empty();
    }
}
