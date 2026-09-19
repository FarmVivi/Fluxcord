package com.example.fixture;

/**
 * A class whose bytecode gets copied into temporary plugin jars by {@code PluginClassLoaderTest}.
 * It lives outside the core/api packages on purpose (those are parent-first). It exists both on the test classpath (parent loader) and inside the jar (child loader), which is
 * exactly the situation child-first delegation must resolve in favour of the jar.
 */
public class SamplePluginClass {
    public static final String MARKER = "sample";
}
