package rssearchpatch.mixin;

import rssearchpatch.search.QuotedSearchParser;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.api.util.IFilter;
import com.refinedmods.refinedstorage.screen.grid.filtering.GridFilterParser;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;

@Mixin(value = GridFilterParser.class, remap = false)
public abstract class GridFilterParserMixin {
    @Inject(
        method = "getFilters(Lcom/refinedmods/refinedstorage/api/network/grid/IGrid;Ljava/lang/String;Ljava/util/List;)Ljava/util/function/Predicate;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private static void rsSearchPatch$parseQuotedOrTooltipTerms(
        final IGrid grid,
        final String query,
        final List<IFilter> filters,
        final CallbackInfoReturnable<Predicate<IGridStack>> callback
    ) {
        if (QuotedSearchParser.requiresPatchedParser(query)) {
            callback.setReturnValue(QuotedSearchParser.createPredicate(grid, query, filters));
        }
    }
}
