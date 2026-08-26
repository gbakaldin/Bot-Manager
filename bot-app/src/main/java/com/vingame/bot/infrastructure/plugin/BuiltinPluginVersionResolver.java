package com.vingame.bot.infrastructure.plugin;

import com.vingame.bot.common.plugin.PluginVersionResolver;
import com.vingame.bot.common.plugin.PluginVersions;
import org.springframework.stereotype.Component;

/**
 * The Phase 1 implementation of the {@link PluginVersionResolver} seam (PLUGIN_HOT_RELOAD
 * AD-11): a constant.
 * <p>
 * Every product implementation ships inside the application jar and is loaded by the
 * application classloader, so there is exactly one version and it is
 * {@link PluginVersions#BUILTIN}. This class is the whole of what step 4 has to replace —
 * when a child {@code ClassLoader} per version exists, the resolver answers from the
 * loader registry and no call site moves.
 */
@Component
public class BuiltinPluginVersionResolver implements PluginVersionResolver {

    @Override
    public String currentVersion() {
        return PluginVersions.BUILTIN;
    }
}
