/*
 * Copyright (c) 2019 Martin Paljak
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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.stream.Collectors;

public class Plug extends SecurityManager {
    private static final Logger logger = LoggerFactory.getLogger(Plug.class);

    Plug() {
        Class<?>[] ctx = getClassContext();
        verifyClassSignature(ctx[ctx.length - 1]);
    }

    @Override
    public void checkPermission(Permission perm) {
        // Do nothing here for now
        //super.checkPermission(perm, context);
    }

    private static final String K = "4814DA41FA7253040E33D4A93F0B89B6317CF8DF76583D5B50BC3EC44B684934".toLowerCase();
    private static final ClassLoader bundledLoader;

    static {
        try {
            bundledLoader = getPluginsClassLoader("APDU4J_PLUGINS", Paths.get(System.getProperty("user.home"), ".apdu4j", "plugins"));
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize plugins!");
        }
    }

    static void verifyClassSignature(Class<?> c) {
        try {
            CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs == null)
                throw new SecurityException("no code source");
            if (cs.getCodeSigners() == null || cs.getCodeSigners().length != 1)
                throw new SecurityException("unsigned code");
            X509Certificate cert = (X509Certificate) cs.getCodeSigners()[0].getSignerCertPath().getCertificates().get(0);
            cert.checkValidity();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            if (!Arrays.equals(HexUtils.hex2bin(K), hash) && System.getProperty(K) == null)
                throw new SecurityException("invalid signature");
        } catch (Exception e) {
            if (!(e instanceof SecurityException))
                e.printStackTrace();
            System.err.printf("Verification failed. Reason: %s, %s%n", e.getClass().getSimpleName(), e.getMessage());
            System.err.println("Please contact martin@martinpaljak.net for support");
            System.exit(666);
        }
    }

    public static void verifyExecutable() {
        try {
            Class<?>[] callers = ((Plug) System.getSecurityManager()).getClassContext();
            verifyClassSignature(callers[callers.length - 1]);
        } catch (Exception e) {
            throw new SecurityException("could not locate code source");
        }
    }

    public static String getExpirationDate() {
        return ((X509Certificate) Plug.class.getProtectionDomain().getCodeSource().getCertificates()[0]).getNotAfter().toString();
    }

    // Make a classloader of .jar files in a given folder.
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    static ClassLoader pluginFolderClassLoader(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            System.err.println("Can't load plugins from " + folder + " defaulting to current classloader");
            return Thread.currentThread().getContextClassLoader();
        }

        logger.debug("Plugin classloader for {}", folder);
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
            list.stream().forEach(e -> verifyClassSignature(e.getClass()));
            return list.stream().filter(e -> identifies(name, e.getClass())).findFirst();
        } catch (ServiceConfigurationError e) {
            throw new RuntimeException("Failed to load plugin: " + e.getCause().getMessage());
        }
    }

    // Loads given plugin from a JAR file
    // Throws runtime exception if can't be loaded
    static <T> T loadPluginFile(Path p, Class<T> t) {
        try {
            // Load plugin
            URL[] plugin = new URL[]{p.toUri().toURL()};
            logger.debug("Loading plugin from " + plugin[0]);
            final URLClassLoader ucl = AccessController.doPrivileged(
                    (PrivilegedAction<URLClassLoader>) () -> new URLClassLoader(plugin)
            );
            ServiceLoader<T> sl = ServiceLoader.load(t, ucl);
            List<T> list = new ArrayList<>();
            sl.iterator().forEachRemaining(list::add);

            if (list.size() != 1) {
                throw new RuntimeException("Could not load plugin, found " + list.size() + " services for " + t.getCanonicalName() + " in plugin:\n" + list.stream().map(e -> e.getClass().getCanonicalName()).collect(Collectors.joining("\n")));
            }
            logger.debug("Loaded " + list.get(0).getClass().getCanonicalName() + " from " + p);
            return list.get(0);
        } catch (MalformedURLException e) {
            throw new RuntimeException("Failed to load plugin: " + e.getMessage());
        } catch (ServiceConfigurationError e) {
            throw new RuntimeException("Failed to load plugin: " + e.getCause().getMessage());
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
    static <T> boolean identifiesPlugin(String spec, ClassLoader loader, Class<T> service) {
        ServiceLoader<T> sl = ServiceLoader.load(service, loader);
        List<T> list = new ArrayList<>();
        sl.iterator().forEachRemaining(list::add);
        logger.debug("Found {} services for {}", list.size(), service.getCanonicalName());
        list.stream().forEach(e -> logger.debug("{} from {}", e.getClass().getCanonicalName(), pluginfile(e)));
        return list.stream().anyMatch(e -> identifies(spec, e.getClass()));
    }

    static <T> void listPlugins(Class<T> service) {
        ServiceLoader<T> sl = ServiceLoader.load(service, bundledLoader);
        List<T> list = new ArrayList<>();
        sl.iterator().forEachRemaining(list::add);
        logger.debug("Found %d services for %s%n", list.size(), service.getCanonicalName());
        list.stream().forEach(e -> System.out.printf("%s from %s%n", e.getClass().getCanonicalName(), pluginfile(e)));
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
        return c.getCanonicalName().equalsIgnoreCase(name) || c.getSimpleName().equalsIgnoreCase(name);
    }

    // Throws SecurityException if the given class does not pass signature check
    static <T> T verifyPlugin(T t) throws SecurityException {
        try {
            X509Certificate cert = (X509Certificate) t.getClass().getProtectionDomain().getCodeSource().getCodeSigners()[0].getSignerCertPath().getCertificates().get(0);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            if (!Arrays.equals(hash, HexUtils.hex2bin(K)))
                throw new SecurityException("invalid plugin signature");
            return t;
        } catch (SecurityException e) {
            // Pass through
            throw e;
        } catch (Exception e) {
            // Catch all other
            throw new SecurityException("plugin verification failed: " + e.getMessage());
        }
    }

    // Given a plugin spec and type, try load it, possibly from the addressed file or URL
    static <T> Optional<T> getPlugin(String spec, Class<T> t) {
        // Try to load the plugin from file
        if (Files.isRegularFile(Paths.get(spec))) {
            logger.trace("Will try to load plugin from file " + spec);
            return Optional.ofNullable(loadPluginFile(Paths.get(spec), t));
        } else if (identifiesPlugin(spec, bundledLoader, t)) {
            // load the plugin from built-in or plugins folder
            logger.trace("Will load built in or global plugin");
            return loadPlugin(bundledLoader, spec, t);
        } else {
            logger.warn("{} does not have a match for {}", spec, t.getCanonicalName());
            return Optional.empty();
        }
    }
}
