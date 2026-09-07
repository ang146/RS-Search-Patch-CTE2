package rssearchpatch.search;

import rssearchpatch.client.TooltipPrewarmer;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;

import java.util.Locale;
import java.util.function.Predicate;

/** Non-blocking replacement for RS's synchronous TooltipGridFilter. */
public final class ProgressiveTooltipGridFilter implements Predicate<IGridStack> {
    private final String normalizedQuery;

    public ProgressiveTooltipGridFilter(final String query) {
        normalizedQuery = query.toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean test(final IGridStack stack) {
        return TooltipPrewarmer.matchesTooltip(stack, normalizedQuery);
    }
}
