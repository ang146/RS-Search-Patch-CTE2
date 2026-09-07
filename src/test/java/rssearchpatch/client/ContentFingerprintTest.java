package rssearchpatch.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ContentFingerprintTest {
    @Test
    void compoundInsertionOrderDoesNotAffectCanonicalHash() throws Exception {
        final CompoundTag first = new CompoundTag();
        first.putString("zeta", "last");
        first.putInt("alpha", 42);
        final CompoundTag firstNested = new CompoundTag();
        firstNested.putString("b", "two");
        firstNested.putString("a", "one");
        first.put("nested", firstNested);

        final CompoundTag second = new CompoundTag();
        final CompoundTag secondNested = new CompoundTag();
        secondNested.putString("a", "one");
        secondNested.putString("b", "two");
        second.put("nested", secondNested);
        second.putInt("alpha", 42);
        second.putString("zeta", "last");

        assertEquals(
            ContentFingerprint.hashCanonical(1, "example:item", first),
            ContentFingerprint.hashCanonical(1, "example:item", second)
        );
    }

    @Test
    void listOrderAndContentRemainSignificant() throws Exception {
        final CompoundTag first = new CompoundTag();
        final ListTag firstList = new ListTag();
        firstList.add(StringTag.valueOf("a"));
        firstList.add(StringTag.valueOf("b"));
        first.put("values", firstList);

        final CompoundTag second = new CompoundTag();
        final ListTag secondList = new ListTag();
        secondList.add(StringTag.valueOf("b"));
        secondList.add(StringTag.valueOf("a"));
        second.put("values", secondList);

        assertNotEquals(
            ContentFingerprint.hashCanonical(1, "example:item", first),
            ContentFingerprint.hashCanonical(1, "example:item", second)
        );
    }
}
