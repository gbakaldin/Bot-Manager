package com.vingame.bot.infrastructure.plugin;

import java.util.Objects;

/**
 * One jar of a plugin bundle, as the {@code plugin runtime:} boot line names it
 * (PLUGIN_HOT_RELOAD_3_4 D-15): the file name and the full lowercase hex SHA-256 of its
 * bytes. The boot line renders the first 12 hex characters.
 * <p>
 * A classpath-mode bundle has no jars of its own (its classes come from the application
 * classpath), so it reports none and the boot line reads {@code jars=[]}.
 *
 * @param name   the jar's file name, e.g. {@code bot-messages-1.0.jar}.
 * @param sha256 the jar's SHA-256, lowercase hex.
 */
public record PluginJar(String name, String sha256) {

    /** How many hex characters of the digest the boot line prints. */
    public static final int RENDERED_DIGEST_LENGTH = 12;

    public PluginJar {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sha256, "sha256");
    }

    /** {@code <name> sha256=<first 12 hex>}, the boot-line form. */
    public String render() {
        String prefix = sha256.length() <= RENDERED_DIGEST_LENGTH
                ? sha256
                : sha256.substring(0, RENDERED_DIGEST_LENGTH);
        return name + " sha256=" + prefix;
    }
}
