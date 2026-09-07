package rssearchpatch;

import net.minecraftforge.fml.common.Mod;

/**
 * Forge entry point. All behavior is client-side and registered by the client event subscriber
 * or the client-only Mixin configuration.
 */
@Mod(RSSearchPatch.MOD_ID)
public final class RSSearchPatch {
    public static final String MOD_ID = "rs_search_patch";

    public RSSearchPatch() {
        // No registries, networking, configuration, or server-side hooks are needed.
    }
}
