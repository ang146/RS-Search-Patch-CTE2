package rssearchpatch.client;

import rssearchpatch.RSSearchPatch;
import rssearchpatch.search.QuotedSearchParser;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import com.refinedmods.refinedstorage.screen.grid.view.IGridView;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/** Client render-frame orchestration for the persistent progressive tooltip search index. */
@Mod.EventBusSubscriber(modid = RSSearchPatch.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class TooltipPrewarmer {
    private static final long DISCOVERY_INTERVAL_NS = 250_000_000L;
    private static final long SUMMARY_INTERVAL_NS = 1_000_000_000L;
    private static final Logger LOGGER = LoggerFactory.getLogger(RSSearchPatch.MOD_ID + ".tooltip_index");
    private static CurrentGridIndex currentIndex;
    private static long openedAtNs;
    private static long nextDiscoveryNs;
    private static long nextSummaryNs;

    private TooltipPrewarmer() {
    }

    /** Runs once, after rendering the current screen; all Minecraft object access stays here. */
    @SubscribeEvent
    public static void onScreenRenderPost(final ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof GridScreen gridScreen)) {
            return;
        }

        final long now = System.nanoTime();
        ensureCurrentIndex(gridScreen, now);
        if (currentIndex == null) {
            return;
        }

        if (now >= nextDiscoveryNs) {
            currentIndex.discoverStacks();
            nextDiscoveryNs = now + DISCOVERY_INTERVAL_NS;
        }

        currentIndex.processFrame(cache());
        final boolean tooltipSearchActive = QuotedSearchParser.containsTooltipTerm(
            gridScreen.getSearchFieldText()
        );
        currentIndex.refreshIfNeeded(tooltipSearchActive, System.nanoTime());
        cache().maybeSave(System.nanoTime());
        logSummaryIfDue(System.nanoTime());
    }

    /** Called only by ProgressiveTooltipGridFilter; this path never fingerprints or generates. */
    public static boolean matchesTooltip(final IGridStack stack, final String normalizedQuery) {
        final CurrentGridIndex index = currentIndex;
        return index != null && index.matches(stack, normalizedQuery);
    }

    @SubscribeEvent
    public static void onScreenClosing(final ScreenEvent.Closing event) {
        if (currentIndex != null && event.getScreen() == currentIndex.screen()) {
            logSummary(System.nanoTime(), "closed");
            currentIndex = null;
            cache().requestImmediateSave();
        }
    }

    @SubscribeEvent
    public static void onClientLogout(final ClientPlayerNetworkEvent.LoggingOut event) {
        currentIndex = null;
        cache().requestImmediateSave();
    }

    @SubscribeEvent
    public static void onGameShuttingDown(final GameShuttingDownEvent event) {
        cache().saveAndWaitAtShutdown();
    }

    private static void ensureCurrentIndex(final GridScreen screen, final long now) {
        if (currentIndex == null || currentIndex.screen() != screen) {
            cache().ensureLoaded(PersistentTooltipCache.captureMetadata());
            currentIndex = new CurrentGridIndex(screen, screen.getView());
            openedAtNs = now;
            nextDiscoveryNs = now + DISCOVERY_INTERVAL_NS;
            nextSummaryNs = now + SUMMARY_INTERVAL_NS;
            return;
        }

        final IGridView view = screen.getView();
        if (currentIndex.view() != view) {
            currentIndex.replaceView(view);
            nextDiscoveryNs = now + DISCOVERY_INTERVAL_NS;
        }
    }

    private static void logSummaryIfDue(final long now) {
        if (now < nextSummaryNs) {
            return;
        }
        logSummary(now, "active");
        do {
            nextSummaryNs += SUMMARY_INTERVAL_NS;
        } while (nextSummaryNs <= now);
    }

    private static void logSummary(final long now, final String state) {
        final CurrentGridIndex index = currentIndex;
        if (index == null) {
            return;
        }

        final CurrentGridIndex.Stats stats = index.stats();
        final String elapsed = String.format(
            Locale.ROOT,
            "%.1f",
            (now - openedAtNs) / 1_000_000_000.0D
        );
        LOGGER.debug(
            "[RS Search Patch] index: state={} total={} resolved={} persistentHits={} misses={} "
                + "generated={} resolvePending={} tooltipPending={} cacheEntries={} refreshes={} "
                + "cacheLoad={} failures={} elapsed={}s screen={} view={}",
            state,
            stats.total(),
            stats.resolved(),
            stats.persistentHits(),
            stats.persistentMisses(),
            cache().getGeneratedThisSession(),
            stats.resolvePending(),
            stats.tooltipPending(),
            cache().size(),
            stats.refreshes(),
            cache().getLoadStatus(),
            stats.failures() + cache().getFailureCount(),
            elapsed,
            identity(index.screen()),
            identity(index.view())
        );
    }

    private static String identity(final Object object) {
        return object == null ? "null" : Integer.toHexString(System.identityHashCode(object));
    }

    private static PersistentTooltipCache cache() {
        return CacheHolder.INSTANCE;
    }

    private static final class CacheHolder {
        private static final PersistentTooltipCache INSTANCE = new PersistentTooltipCache();
    }
}
