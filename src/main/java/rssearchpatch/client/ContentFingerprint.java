package rssearchpatch.client;

import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Builds stable SHA-256 keys from registry identity plus canonical, count-free NBT. */
public final class ContentFingerprint {
    private ContentFingerprint() {
    }

    public static String create(final IGridStack gridStack) throws IOException {
        final Object ingredient = gridStack.getIngredient();
        if (ingredient instanceof ItemStack itemStack) {
            final CompoundTag serialized = itemStack.save(new CompoundTag());
            serialized.remove("id");
            serialized.remove("Count");
            return hashCanonical(
                1,
                String.valueOf(ForgeRegistries.ITEMS.getKey(itemStack.getItem())),
                serialized
            );
        }
        if (ingredient instanceof FluidStack fluidStack) {
            final CompoundTag serialized = fluidStack.writeToNBT(new CompoundTag());
            serialized.remove("FluidName");
            serialized.remove("Amount");
            return hashCanonical(
                2,
                String.valueOf(ForgeRegistries.FLUIDS.getKey(fluidStack.getFluid())),
                serialized
            );
        }

        throw new IOException("Unsupported grid ingredient: "
            + (ingredient == null ? "null" : ingredient.getClass().getName()));
    }

    static String hashCanonical(
        final int ingredientType,
        final String registryId,
        final CompoundTag serialized
    ) throws IOException {
        final MessageDigest digest = sha256();
        try (DataOutputStream output = new DataOutputStream(
            new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            output.writeByte(ingredientType);
            writeString(output, registryId);
            writeTag(output, serialized);
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void writeTag(final DataOutputStream output, final Tag tag) throws IOException {
        output.writeByte(tag.getId());
        switch (tag.getId()) {
            case Tag.TAG_END -> {
            }
            case Tag.TAG_BYTE -> output.writeByte(((NumericTag) tag).getAsByte());
            case Tag.TAG_SHORT -> output.writeShort(((NumericTag) tag).getAsShort());
            case Tag.TAG_INT -> output.writeInt(((NumericTag) tag).getAsInt());
            case Tag.TAG_LONG -> output.writeLong(((NumericTag) tag).getAsLong());
            case Tag.TAG_FLOAT -> output.writeFloat(((NumericTag) tag).getAsFloat());
            case Tag.TAG_DOUBLE -> output.writeDouble(((NumericTag) tag).getAsDouble());
            case Tag.TAG_BYTE_ARRAY -> {
                final byte[] values = ((ByteArrayTag) tag).getAsByteArray();
                output.writeInt(values.length);
                output.write(values);
            }
            case Tag.TAG_STRING -> writeString(output, tag.getAsString());
            case Tag.TAG_LIST -> {
                final ListTag list = (ListTag) tag;
                output.writeInt(list.size());
                for (int i = 0; i < list.size(); i++) {
                    writeTag(output, list.get(i));
                }
            }
            case Tag.TAG_COMPOUND -> {
                final CompoundTag compound = (CompoundTag) tag;
                final List<String> keys = new ArrayList<>(compound.getAllKeys());
                keys.sort(Comparator.naturalOrder());
                output.writeInt(keys.size());
                for (String key : keys) {
                    writeString(output, key);
                    final Tag value = compound.get(key);
                    if (value == null) {
                        throw new IOException("Compound value disappeared for key " + key);
                    }
                    writeTag(output, value);
                }
            }
            case Tag.TAG_INT_ARRAY -> {
                final int[] values = ((IntArrayTag) tag).getAsIntArray();
                output.writeInt(values.length);
                for (int value : values) {
                    output.writeInt(value);
                }
            }
            case Tag.TAG_LONG_ARRAY -> {
                final long[] values = ((LongArrayTag) tag).getAsLongArray();
                output.writeInt(values.length);
                for (long value : values) {
                    output.writeLong(value);
                }
            }
            default -> throw new IOException("Unsupported NBT tag type " + tag.getId());
        }
    }

    private static void writeString(final DataOutputStream output, final String value) throws IOException {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
