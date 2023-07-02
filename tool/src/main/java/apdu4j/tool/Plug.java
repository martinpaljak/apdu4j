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
package apdu4j.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Plug {
    private static final Logger logger = LoggerFactory.getLogger(Plug.class);

    static final ClassLoader pluginClassLoader;
    static final ClassLoader systemClassLoader;

    static {
        try {
            pluginClassLoader = getPluginsClassLoader("APDU4J_PLUGINS", Paths.get(System.getProperty("user.home"), ".apdu4j", "plugins"));
            systemClassLoader = ClassLoader.getSystemClassLoader();
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize plugins!");
        }
    }

    /**
     * Returns a list of all readable and accessible .jar files in a folder
     *
     * @param folder
     * @return List of Path-s with jar-files in that folder
     */
    static List<Path> jars(Path folder) {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries
                    .filter(Files::isReadable)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jar"))
                    .collect(Collectors.toList());
        } catch (NoSuchFileException e) {
            logger.debug("No such directory: " + folder);
            return Collections.emptyList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Make a classloader of .jar files in a given folder.
    static ClassLoader pluginFolderClassLoader(Path folder) {
        if (!Files.isDirectory(folder)) {
            logger.trace("Can't load plugins from " + folder + " defaulting to current classloader");
            return Thread.currentThread().getContextClassLoader();
        }

        List<URL> plugins = jars(folder).stream()
                .map(p -> {
                    try {
                        return p.toUri().toURL();
                    } catch (MalformedURLException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .collect(Collectors.toList());
        logger.debug("Plugins from {}: {}", folder, plugins);
        return  new URLClassLoader(plugins.toArray(new URL[plugins.size()]));
    }

    // Load all plugins of type t from specified JAR file
    static <T> List<T> loadPlugins(Path p, Class<T> t) {
        try {
            return loadPlugins(p.toUri().toURL(), t);
        } catch (MalformedURLException e) {
            logger.error("Malformed URL from Path? ", e);
            return new ArrayList<>();
        }
    }

    // Load list of plugins from a file
    static <T> List<T> loadPlugins(URL u, Class<T> t) {
        ClassLoader parent = ClassLoader.getSystemClassLoader();
        URL[] plugin = new URL[]{u};
        final URLClassLoader ucl = new URLClassLoader(plugin, parent);

        ServiceLoader<T> sl = ServiceLoader.load(t, ucl);
        List<T> list = new ArrayList<>();
        // We skip bad plugins
        Iterator<T> it = sl.iterator();
        while (it.hasNext()) {
            try {
                T i = it.next();
                if (i.getClass().getClassLoader() != ucl) {
                    logger.debug("Ignoring {} as not from classloader", i.getClass().getCanonicalName());
                    continue;
                }
                list.add(i);
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

    // Gets the jarfile of the plugin for display purposes
    static String pluginfile(Object c) {
        if (c.getClass().getClassLoader() == systemClassLoader)
            return "builtin";
        CodeSource src = c.getClass().getProtectionDomain().getCodeSource();
        if (src == null)
            return "builtin";
        URL l = src.getLocation();
        if (c.getClass().getClassLoader() == pluginClassLoader) {
            if (l.getProtocol().equals("file"))
                return "plugin " + l.getFile();
        }
        if (l.getProtocol().equals("file"))
            return l.getFile();
        return l.toExternalForm();
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

    // Returns a list of existing plugins for a service, masking unloadable ones
    static <T> List<T> plugins(Class<T> t, ClassLoader loader) {
        if (loader == null)
            loader = Thread.currentThread().getContextClassLoader();
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
}
