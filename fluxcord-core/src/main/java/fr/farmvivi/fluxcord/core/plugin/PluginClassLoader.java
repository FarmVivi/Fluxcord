package fr.farmvivi.fluxcord.core.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom class loader for loading plugin classes with proper isolation.
 */
public class PluginClassLoader extends URLClassLoader {
    // Core packages that should always be loaded from the parent class loader
    private static final Set<String> CORE_PACKAGES = Set.of(
            "fr.farmvivi.fluxcord.api",   // the contract must be shared: a bundled copy would break Plugin/PluginContext casts
            "fr.farmvivi.fluxcord.core",
            "org.slf4j",
            "org.yaml.snakeyaml",
            "com.google.gson",
            "net.dv8tion.jda.api",
            "com.zaxxer.hikari",
            "software.amazon.awssdk"
    );

    // Packages we've seen in this plugin
    private final Set<String> seenPackages = ConcurrentHashMap.newKeySet();

    // The plugin descriptor for this class loader
    private final PluginDescriptor descriptor;

    /**
     * Creates a new plugin class loader.
     *
     * @param urls       the URLs from which to load classes and resources
     * @param parent     the parent class loader for delegation
     * @param descriptor the plugin descriptor
     */
    public PluginClassLoader(URL[] urls, ClassLoader parent, PluginDescriptor descriptor) {
        super(urls, parent);
        this.descriptor = descriptor;
    }

    /**
     * Gets the plugin descriptor associated with this class loader.
     *
     * @return the plugin descriptor
     */
    public PluginDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Check if the class has already been loaded
        Class<?> loadedClass = findLoadedClass(name);
        if (loadedClass != null) {
            return loadedClass;
        }

        // Get the package name
        String packageName = getPackageName(name);

        // Core packages are always loaded by the parent class loader
        if (isCorePackage(packageName)) {
            return super.loadClass(name, resolve);
        }

        try {
            // Try to load the class from this class loader first
            loadedClass = findClass(name);
            seenPackages.add(packageName);
        } catch (ClassNotFoundException ignored) {
            // If this class loader doesn't have the class, try the parent
            // if we've seen this package before
            if (seenPackages.contains(packageName)) {
                try {
                    loadedClass = super.loadClass(name, false);
                } catch (ClassNotFoundException ignored2) {
                    // Ignore
                }
            }

            // If we still haven't found the class, try the parent
            if (loadedClass == null) {
                loadedClass = super.loadClass(name, false);
            }
        }

        if (resolve) {
            resolveClass(loadedClass);
        }

        return loadedClass;
    }

    private String getPackageName(String className) {
        return className.contains(".") ? className.substring(0, className.lastIndexOf('.')) : "";
    }

    private boolean isCorePackage(String packageName) {
        for (String corePackage : CORE_PACKAGES) {
            if (packageName.startsWith(corePackage)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        super.close();
        seenPackages.clear();
    }

    /*
     * Resources: prefer plugin JAR over parent (child-first) to avoid
     * accidentally picking core resources like "/config.yml".
     */
    @Override
    public URL getResource(String name) {
        // Try to find in this classloader first
        URL url = findResource(name);
        if (url != null) {
            return url;
        }
        // Then delegate to parent
        ClassLoader parent = getParent();
        return parent != null ? parent.getResource(name) : super.getResource(name);
    }

    @Override
    public java.util.Enumeration<URL> getResources(String name) throws IOException {
        // Collect child resources first
        java.util.LinkedHashSet<URL> all = new java.util.LinkedHashSet<>();
        java.util.Enumeration<URL> child = findResources(name);
        while (child.hasMoreElements()) {
            all.add(child.nextElement());
        }
        // Then parent resources
        ClassLoader parent = getParent();
        java.util.Enumeration<URL> parentEnum = parent != null ? parent.getResources(name) : super.getResources(name);
        while (parentEnum.hasMoreElements()) {
            all.add(parentEnum.nextElement());
        }
        return java.util.Collections.enumeration(all);
    }

    @Override
    public java.io.InputStream getResourceAsStream(String name) {
        URL url = getResource(name);
        if (url == null) {
            return null;
        }
        try {
            // No JarURLConnection cache: a cached JarFile outlives close() and keeps the plugin jar locked
            // (Windows), which prevents replacing the jar on reload.
            java.net.URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            return connection.getInputStream();
        } catch (IOException e) {
            return null;
        }
    }
}