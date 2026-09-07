package rssearchpatch.client;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Immutable, query-independent tooltip text used by the search hot path. */
public record CachedTooltipData(List<String> lines) {
    public CachedTooltipData {
        lines = List.copyOf(lines);
    }

    public static CachedTooltipData fromComponents(final List<Component> tooltip) {
        final List<String> searchableLines = new ArrayList<>(Math.max(0, tooltip.size() - 1));
        // Refined Storage 1.12.4's TooltipGridFilter starts at index 1, excluding the name line.
        for (int i = 1; i < tooltip.size(); i++) {
            searchableLines.add(tooltip.get(i).getString().toLowerCase(Locale.ROOT));
        }
        return new CachedTooltipData(searchableLines);
    }

    public boolean matches(final String normalizedQuery) {
        for (String line : lines) {
            if (line.contains(normalizedQuery)) {
                return true;
            }
        }
        return false;
    }
}
