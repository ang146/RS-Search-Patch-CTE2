package rssearchpatch.client;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachedTooltipDataTest {
    @Test
    void normalizesOnceAndMatchesWithinIndividualTooltipLines() {
        final CachedTooltipData data = CachedTooltipData.fromComponents(List.of(
            Component.literal("Fire Resistance Sword"),
            Component.literal("+8% FIRE RESISTANCE"),
            Component.literal("+2% Maximum Fire Resistance")
        ));

        assertTrue(data.matches("fire resistance"));
        assertTrue(data.matches("maximum fire"));
        assertFalse(data.matches("resistance +2%"));
    }

    @Test
    void preservesRsBehaviorByExcludingTheNameLine() {
        final CachedTooltipData data = CachedTooltipData.fromComponents(List.of(
            Component.literal("Unique Name Only"),
            Component.literal("Ordinary tooltip text")
        ));

        assertFalse(data.matches("unique name"));
    }
}
