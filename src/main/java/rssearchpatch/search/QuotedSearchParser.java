package rssearchpatch.search;

import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.api.util.IFilter;
import com.refinedmods.refinedstorage.screen.grid.filtering.AndGridFilter;
import com.refinedmods.refinedstorage.screen.grid.filtering.CraftableGridFilter;
import com.refinedmods.refinedstorage.screen.grid.filtering.FilterGridFilter;
import com.refinedmods.refinedstorage.screen.grid.filtering.ModGridFilter;
import com.refinedmods.refinedstorage.screen.grid.filtering.NameGridFilter;
import com.refinedmods.refinedstorage.screen.grid.filtering.OrGridFilter;
import com.refinedmods.refinedstorage.screen.grid.filtering.TagGridFilter;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Quote-aware counterpart to Refined Storage 1.12.4's small grid-filter parser. */
public final class QuotedSearchParser {
    private QuotedSearchParser() {
    }

    /**
     * Quoted queries use this parser. Unquoted queries without tooltip terms stay entirely in
     * Refined Storage's own parser.
     */
    public static boolean hasQuotedSyntax(final String query) {
        return query.indexOf('"') >= 0;
    }

    /** True when parsing must be replaced for quotes or a non-blocking tooltip term. */
    public static boolean requiresPatchedParser(final String query) {
        return hasQuotedSyntax(query) || containsTooltipTerm(query);
    }

    public static boolean containsTooltipTerm(final String query) {
        for (List<SearchTerm> group : tokenize(query)) {
            for (SearchTerm term : group) {
                if (term.type() == FilterType.TOOLTIP) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Recreates only RS's parser orchestration, using RS's original predicate classes. */
    public static Predicate<IGridStack> createPredicate(
        final IGrid grid,
        final String query,
        final List<IFilter> externalFilters
    ) {
        final List<List<SearchTerm>> groups = tokenize(query);
        final List<Predicate<IGridStack>> filters;

        if (groups.size() == 1) {
            filters = createTermFilters(groups.get(0));
        } else {
            final List<Predicate<IGridStack>> orFilters = new ArrayList<>(groups.size());
            for (List<SearchTerm> group : groups) {
                orFilters.add(AndGridFilter.of(createTermFilters(group)));
            }
            filters = new ArrayList<>(1);
            filters.add(OrGridFilter.of(orFilters));
        }

        if (grid != null) {
            if (grid.getViewType() == IGrid.VIEW_TYPE_NON_CRAFTABLES) {
                filters.add(new CraftableGridFilter(false));
            } else if (grid.getViewType() == IGrid.VIEW_TYPE_CRAFTABLES) {
                filters.add(new CraftableGridFilter(true));
            }
        }

        if (!externalFilters.isEmpty()) {
            filters.add(new FilterGridFilter(externalFilters));
        }

        return AndGridFilter.of(filters);
    }

    /**
     * Splits on spaces and vertical bars only while outside quotes. Backslash-escaped quotes are
     * retained as literal quote characters. An unmatched quote simply groups through end-of-input.
     */
    public static List<List<SearchTerm>> tokenize(final String query) {
        final List<String> rawGroups = splitOrGroups(query);
        final List<List<SearchTerm>> groups = new ArrayList<>(rawGroups.size());
        for (String rawGroup : rawGroups) {
            groups.add(tokenizeGroup(rawGroup));
        }
        return groups;
    }

    private static List<String> splitOrGroups(final String query) {
        final List<String> groups = new ArrayList<>();
        final StringBuilder group = new StringBuilder();
        boolean inQuotes = false;
        boolean sawOrSeparator = false;

        for (int i = 0; i < query.length(); i++) {
            final char character = query.charAt(i);
            if (character == '\\' && i + 1 < query.length() && query.charAt(i + 1) == '"') {
                // Preserve the escape for the term pass, but do not let it toggle quote state.
                group.append(character).append('"');
                i++;
            } else if (character == '"') {
                inQuotes = !inQuotes;
                group.append(character);
            } else if (!inQuotes && character == '|') {
                groups.add(group.toString());
                group.setLength(0);
                sawOrSeparator = true;
            } else {
                group.append(character);
            }
        }
        groups.add(group.toString());

        // This is the default-limit behavior of String.split("\\|"): only raw, trailing empty
        // branches disappear. A trailing branch containing spaces remains and becomes an empty
        // NameGridFilter after trim, just as it does in Refined Storage.
        if (sawOrSeparator) {
            while (!groups.isEmpty() && groups.get(groups.size() - 1).isEmpty()) {
                groups.remove(groups.size() - 1);
            }
        }
        return groups;
    }

    private static List<SearchTerm> tokenizeGroup(final String rawGroup) {
        final String normalized = rawGroup.toLowerCase(Locale.ROOT).trim();
        final List<SearchTerm> terms = new ArrayList<>();
        final StringBuilder token = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < normalized.length(); i++) {
            final char character = normalized.charAt(i);
            if (character == '\\' && i + 1 < normalized.length() && normalized.charAt(i + 1) == '"') {
                token.append('"');
                i++;
            } else if (character == '"') {
                inQuotes = !inQuotes;
            } else if (!inQuotes && character == ' ') {
                terms.add(SearchTerm.fromToken(token.toString()));
                token.setLength(0);
            } else {
                token.append(character);
            }
        }

        // RS's split(" ") yields one empty token for an empty/trimmed-empty group.
        terms.add(SearchTerm.fromToken(token.toString()));
        return terms;
    }

    private static List<Predicate<IGridStack>> createTermFilters(final List<SearchTerm> terms) {
        final List<Predicate<IGridStack>> filters = new ArrayList<>(terms.size());
        for (SearchTerm term : terms) {
            filters.add(switch (term.type()) {
                case MOD -> new ModGridFilter(term.text());
                case TOOLTIP -> new ProgressiveTooltipGridFilter(term.text());
                case TAG -> new TagGridFilter(term.text());
                case NAME -> new NameGridFilter(term.text());
            });
        }
        return filters;
    }

    public enum FilterType {
        NAME,
        MOD,
        TOOLTIP,
        TAG
    }

    /** A normalized term with its RS search prefix removed. */
    public record SearchTerm(FilterType type, String text) {
        private static SearchTerm fromToken(final String token) {
            if (token.startsWith("@")) {
                return new SearchTerm(FilterType.MOD, token.substring(1));
            }
            if (token.startsWith("#")) {
                return new SearchTerm(FilterType.TOOLTIP, token.substring(1));
            }
            if (token.startsWith("$")) {
                return new SearchTerm(FilterType.TAG, token.substring(1));
            }
            return new SearchTerm(FilterType.NAME, token);
        }
    }
}
