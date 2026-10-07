package com.vingame.bot.plugin.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLUGIN_HOT_RELOAD_3_4 <b>L-2</b> (as amended at compliance-3ab): every <em>file</em> entry
 * of a shipped plugin jar is a {@code com/vingame/bot/domain/bot/{message,strategy}/**}
 * class, the manifest, or that module's own Maven descriptor
 * {@code META-INF/maven/com.mercury/<artifactId>/pom.{xml,properties}}. Directory entries
 * are ignored. Any other {@code META-INF/maven/**} path is the fingerprint of shaded code.
 * <p>
 * Plus the shape of what ships: exactly the two plugin jars, one version directory whose
 * name is the version both manifests carry (D-7).
 */
@DisplayName("L-2: the shipped plugin jars carry plugin classes and nothing else")
class PluginJarContentsIT {

    private static final Pattern PLUGIN_CLASS =
            Pattern.compile("com/vingame/bot/domain/bot/(message|strategy)/.+\\.class");

    @Test
    @DisplayName("plugins-dist holds exactly bot-messages-1.0.jar and bot-strategies-1.0.jar")
    void exactlyTheTwoPluginJars() {
        assertThat(ShippedBundle.jars()).extracting(p -> p.getFileName().toString())
                .containsExactly("bot-messages-1.0.jar", "bot-strategies-1.0.jar");
    }

    @Test
    @DisplayName("both manifests carry the directory's name as Bot-Plugin-Version, and their own Bot-Plugin-Name")
    void manifestsAgreeWithTheDirectory() throws IOException {
        String dirName = ShippedBundle.versionDir().getFileName().toString();
        for (Path jar : ShippedBundle.jars()) {
            try (JarFile file = new JarFile(jar.toFile())) {
                var attributes = file.getManifest().getMainAttributes();
                assertThat(attributes.getValue("Bot-Plugin-Version"))
                        .as("%s: a manifest that disagrees with its directory name means the jars came "
                                + "from another build session - typically `-pl bot-plugin-dist` on its own, "
                                + "which resolves them from ~/.m2. Build the reactor (mvn clean install).",
                                jar.getFileName())
                        .isEqualTo(dirName)
                        .matches("[0-9]{8}\\.[0-9]{6}");
                assertThat(jar.getFileName().toString())
                        .startsWith(attributes.getValue("Bot-Plugin-Name") + "-");
            }
        }
    }

    @Test
    @DisplayName("every file entry is a plugin class, the manifest, or the module's own Maven descriptor")
    void noForeignEntries() throws IOException {
        List<String> foreign = new ArrayList<>();
        int classes = 0;
        for (Path jar : ShippedBundle.jars()) {
            try (JarFile file = new JarFile(jar.toFile())) {
                String artifactId = file.getManifest().getMainAttributes().getValue("Bot-Plugin-Name");
                Pattern ownDescriptor = Pattern.compile(
                        "META-INF/maven/com\\.mercury/" + Pattern.quote(artifactId) + "/pom\\.(xml|properties)");
                Enumeration<JarEntry> entries = file.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    if (PLUGIN_CLASS.matcher(name).matches()) {
                        classes++;
                    } else if (!name.equals("META-INF/MANIFEST.MF")
                            && !ownDescriptor.matcher(name).matches()) {
                        foreign.add(jar.getFileName() + "!/" + name);
                    }
                }
            }
        }
        assertThat(foreign)
                .as("anything else - another META-INF/maven/** descriptor above all - is shaded "
                        + "third-party code, which would be loaded by the plugin loader instead of "
                        + "the parent's copy")
                .isEmpty();
        assertThat(classes).as("the jars are not empty").isGreaterThan(100);
    }
}
