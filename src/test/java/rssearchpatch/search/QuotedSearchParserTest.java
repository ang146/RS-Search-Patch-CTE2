package rssearchpatch.search;

import rssearchpatch.search.QuotedSearchParser.FilterType;
import rssearchpatch.search.QuotedSearchParser.SearchTerm;
import com.refinedmods.refinedstorage.api.storage.tracker.StorageTrackerEntry;
import com.refinedmods.refinedstorage.screen.BaseScreen;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotedSearchParserTest {
    @Test
    void tokenizesRequiredSearchCases() {
        assertQuery("stone", group(name("stone")));
        assertQuery("stone sword", group(name("stone"), name("sword")));
        assertQuery("\"stone sword\"", group(name("stone sword")));
        assertQuery("#fire", group(tooltip("fire")));
        assertQuery("#\"fire resistance\"", group(tooltip("fire resistance")));
        assertQuery("sword #\"fire resistance\"", group(name("sword"), tooltip("fire resistance")));
        assertQuery("#\"fire resistance\" #\"max health\"",
            group(tooltip("fire resistance"), tooltip("max health")));
        assertQuery("#\"fire resistance\" | #\"cold resistance\"",
            group(tooltip("fire resistance")), group(tooltip("cold resistance")));
        assertQuery("\"foo|bar\"", group(name("foo|bar")));
        assertQuery("@\"refined storage\"", group(mod("refined storage")));
        assertQuery("$\"forge tools\"", group(tag("forge tools")));
        assertQuery("\"unterminated quote", group(name("unterminated quote")));
        assertQuery("\"some \\\"quoted\\\" name\"", group(name("some \"quoted\" name")));
    }

    @Test
    void keepsOriginalUnquotedPrefixSemantics() {
        assertQuery("fire resistance", group(name("fire"), name("resistance")));
        assertQuery("#fire resistance", group(tooltip("fire"), name("resistance")));
    }

    @Test
    void lowercasesWithLocaleIndependentRules() {
        assertQuery("@\"REFINED STORAGE\"", group(mod("refined storage")));
    }

    @Test
    void mixinCanLeaveQueriesWithoutQuotesUntouched() {
        assertFalse(QuotedSearchParser.hasQuotedSyntax("stone sword | #fire"));
        assertTrue(QuotedSearchParser.hasQuotedSyntax("#\"fire resistance\""));
        assertFalse(QuotedSearchParser.requiresPatchedParser("stone sword | @refinedstorage"));
        assertTrue(QuotedSearchParser.requiresPatchedParser("#fire resistance"));
    }

    @Test
    void buildsWorkingRsPredicatesForNonTooltipTermsAndOrGroups() {
        final IGridStack stack = new StubGridStack(
            "Polished Stone Sword",
            "refinedstorage",
            "Refined Storage",
            Set.of("forge tools", "minecraft:swords"),
            List.of(Component.literal("Polished Stone Sword"), Component.literal("Fire Resistance +20%"))
        );

        assertTrue(predicate("\"stone sword\"").test(stack));
        assertTrue(predicate("@\"refined storage\"").test(stack));
        assertTrue(predicate("$\"forge tools\"").test(stack));
        assertFalse(predicate("\"foo|bar\"").test(stack));
    }

    @Test
    void unresolvedTooltipPredicateReturnsImmediatelyWithoutGeneratingTooltip() {
        final StubGridStack stack = new StubGridStack(
            "Polished Stone Sword",
            "example",
            "Example",
            Set.of(),
            List.of(Component.literal("Name"), Component.literal("Fire Resistance"))
        );

        assertFalse(predicate("#fire").test(stack));
        assertEquals(0, stack.tooltipCalls);
    }

    private static java.util.function.Predicate<IGridStack> predicate(final String query) {
        return QuotedSearchParser.createPredicate(null, query, List.of());
    }

    @SafeVarargs
    private static void assertQuery(final String query, final List<SearchTerm>... expectedGroups) {
        assertEquals(List.of(expectedGroups), QuotedSearchParser.tokenize(query));
    }

    private static List<SearchTerm> group(final SearchTerm... terms) {
        return List.of(terms);
    }

    private static SearchTerm name(final String text) {
        return new SearchTerm(FilterType.NAME, text);
    }

    private static SearchTerm tooltip(final String text) {
        return new SearchTerm(FilterType.TOOLTIP, text);
    }

    private static SearchTerm mod(final String text) {
        return new SearchTerm(FilterType.MOD, text);
    }

    private static SearchTerm tag(final String text) {
        return new SearchTerm(FilterType.TAG, text);
    }

    private static final class StubGridStack implements IGridStack {
        private final String name;
        private final String modId;
        private final String modName;
        private final Set<String> tags;
        private final List<Component> tooltip;
        private int tooltipCalls;

        private StubGridStack(
            final String name,
            final String modId,
            final String modName,
            final Set<String> tags,
            final List<Component> tooltip
        ) {
            this.name = name;
            this.modId = modId;
            this.modName = modName;
            this.tags = tags;
            this.tooltip = tooltip;
        }

        @Override
        public UUID getId() {
            return null;
        }

        @Override
        public UUID getOtherId() {
            return null;
        }

        @Override
        public void updateOtherId(final UUID id) {
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getModId() {
            return modId;
        }

        @Override
        public String getModName() {
            return modName;
        }

        @Override
        public Set<String> getTags() {
            return tags;
        }

        @Override
        public List<Component> getTooltip(final boolean force) {
            tooltipCalls++;
            return tooltip;
        }

        @Override
        public int getQuantity() {
            return 1;
        }

        @Override
        public void setQuantity(final int quantity) {
        }

        @Override
        public String getFormattedFullQuantity() {
            return "1";
        }

        @Override
        public void draw(final GuiGraphics graphics, final BaseScreen<?> screen, final int x, final int y) {
        }

        @Override
        public Object getIngredient() {
            return null;
        }

        @Override
        public StorageTrackerEntry getTrackerEntry() {
            return null;
        }

        @Override
        public void setTrackerEntry(final StorageTrackerEntry entry) {
        }

        @Override
        public boolean isCraftable() {
            return false;
        }
    }
}
