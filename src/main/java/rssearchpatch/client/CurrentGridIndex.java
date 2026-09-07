package rssearchpatch.client;

import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import com.refinedmods.refinedstorage.screen.grid.view.IGridView;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Identity-indexed state for one open Refined Storage grid screen. */
public final class CurrentGridIndex {
    public static final long FINGERPRINT_RESOLVE_BUDGET_NS = 2_000_000L;
    public static final long TOOLTIP_GENERATE_BUDGET_NS = 4_000_000L;
    public static final long REFRESH_THROTTLE_NS = 150_000_000L;

    private final GridScreen screen;
    private final Map<IGridStack, CurrentGridEntry> entries = new IdentityHashMap<>();
    private final List<CurrentGridEntry> entriesInOrder = new ArrayList<>();
    private final ArrayDeque<CurrentGridEntry> resolveQueue = new ArrayDeque<>();
    private final ArrayDeque<CurrentGridEntry> tooltipQueue = new ArrayDeque<>();

    private IGridView view;
    private int verificationCursor;
    private long persistentHits;
    private long persistentMisses;
    private long failures;
    private long refreshes;
    private boolean resultsDirty;
    private long lastRefreshNs;

    public CurrentGridIndex(final GridScreen screen, final IGridView view) {
        this.screen = screen;
        replaceView(view);
    }

    public GridScreen screen() {
        return screen;
    }

    public IGridView view() {
        return view;
    }

    public void replaceView(final IGridView replacement) {
        view = replacement;
        entries.clear();
        entriesInOrder.clear();
        resolveQueue.clear();
        tooltipQueue.clear();
        verificationCursor = 0;
        resultsDirty = false;
        discoverStacks();
    }

    public void discoverStacks() {
        if (view == null) {
            return;
        }
        final Collection<IGridStack> allStacks = view.getAllStacks();
        if (allStacks == null) {
            return;
        }

        final Set<IGridStack> current = Collections.newSetFromMap(new IdentityHashMap<>());
        for (IGridStack stack : allStacks) {
            if (stack != null) {
                current.add(stack);
            }
        }

        final boolean removed = entries.entrySet().removeIf(entry -> !current.contains(entry.getKey()));
        if (removed) {
            entriesInOrder.removeIf(entry -> !current.contains(entry.stack));
            resolveQueue.removeIf(entry -> !current.contains(entry.stack));
            tooltipQueue.removeIf(entry -> !current.contains(entry.stack));
            verificationCursor = Math.min(verificationCursor, entriesInOrder.size());
            resultsDirty = true;
        }

        for (IGridStack stack : current) {
            if (!entries.containsKey(stack)) {
                final CurrentGridEntry entry = new CurrentGridEntry(stack);
                entries.put(stack, entry);
                entriesInOrder.add(entry);
                enqueueResolution(entry);
            }
        }
    }

    /** The tooltip-search hot path: identity lookup, queue request, and lowercase String scanning only. */
    public boolean matches(final IGridStack stack, final String normalizedQuery) {
        CurrentGridEntry entry = entries.get(stack);
        if (entry == null) {
            entry = new CurrentGridEntry(stack);
            entries.put(stack, entry);
            entriesInOrder.add(entry);
            enqueueResolution(entry);
            return false;
        }

        if (entry.state != State.READY || entry.tooltipData == null) {
            requestResolution(entry);
            return false;
        }
        return entry.tooltipData.matches(normalizedQuery);
    }

    public void processFrame(final PersistentTooltipCache cache) {
        if (!cache.isReady()) {
            return;
        }
        processFingerprints(cache);
        processTooltips(cache);
    }

    public void refreshIfNeeded(final boolean tooltipSearchActive, final long nowNs) {
        if (!tooltipSearchActive || !resultsDirty || view == null
            || nowNs - lastRefreshNs < REFRESH_THROTTLE_NS) {
            return;
        }

        // Clear first so a failure cannot produce a forceSort storm every frame.
        resultsDirty = false;
        lastRefreshNs = nowNs;
        try {
            view.forceSort();
            refreshes++;
        } catch (RuntimeException exception) {
            failures++;
        }
    }

    public Stats stats() {
        int resolved = 0;
        int unresolved = 0;
        for (CurrentGridEntry entry : entriesInOrder) {
            if (entry.state == State.READY || entry.state == State.NEEDS_TOOLTIP) {
                resolved++;
            } else if (entry.state == State.UNRESOLVED || entry.state == State.RESOLVING) {
                unresolved++;
            }
        }
        return new Stats(
            entries.size(),
            resolved,
            persistentHits,
            persistentMisses,
            unresolved,
            tooltipQueue.size(),
            refreshes,
            failures
        );
    }

    private void processFingerprints(final PersistentTooltipCache cache) {
        final long deadline = System.nanoTime() + FINGERPRINT_RESOLVE_BUDGET_NS;
        boolean processedAny = false;

        while (!processedAny || System.nanoTime() < deadline) {
            CurrentGridEntry entry = resolveQueue.pollFirst();
            if (entry != null) {
                entry.resolutionQueued = false;
                if (entries.get(entry.stack) != entry || entry.state != State.UNRESOLVED) {
                    continue;
                }
            } else {
                entry = nextVerificationEntry();
                if (entry == null) {
                    break;
                }
            }

            processedAny = true;
            resolveFingerprint(entry, cache);
        }
    }

    private CurrentGridEntry nextVerificationEntry() {
        if (entriesInOrder.isEmpty()) {
            return null;
        }

        final int attempts = entriesInOrder.size();
        for (int i = 0; i < attempts; i++) {
            if (verificationCursor >= entriesInOrder.size()) {
                verificationCursor = 0;
            }
            final CurrentGridEntry candidate = entriesInOrder.get(verificationCursor++);
            if (candidate.state == State.READY) {
                return candidate;
            }
        }
        return null;
    }

    private void resolveFingerprint(
        final CurrentGridEntry entry,
        final PersistentTooltipCache cache
    ) {
        final State previousState = entry.state;
        final String previousFingerprint = entry.fingerprint;
        entry.state = State.RESOLVING;
        try {
            final String fingerprint = ContentFingerprint.create(entry.stack);
            if (Objects.equals(previousFingerprint, fingerprint) && previousState == State.READY) {
                entry.state = State.READY;
                return;
            }

            entry.fingerprint = fingerprint;
            entry.tooltipData = null;
            entry.contentMutatedInPlace = previousFingerprint != null
                && !previousFingerprint.equals(fingerprint);
            bindFingerprint(entry, cache);
            if (previousState == State.READY || entry.state == State.READY) {
                resultsDirty = true;
            }
        } catch (IOException | RuntimeException exception) {
            entry.state = State.FAILED;
            entry.tooltipData = null;
            failures++;
            if (previousState == State.READY) {
                resultsDirty = true;
            }
        }
    }

    private void bindFingerprint(
        final CurrentGridEntry entry,
        final PersistentTooltipCache cache
    ) {
        final CachedTooltipData cached = cache.lookup(entry.fingerprint, System.currentTimeMillis());
        if (cached != null) {
            entry.tooltipData = cached;
            entry.state = State.READY;
            persistentHits++;
        } else {
            entry.state = State.NEEDS_TOOLTIP;
            persistentMisses++;
            enqueueTooltip(entry);
        }
    }

    private void processTooltips(final PersistentTooltipCache cache) {
        final long deadline = System.nanoTime() + TOOLTIP_GENERATE_BUDGET_NS;
        boolean processedAny = false;

        while (!tooltipQueue.isEmpty() && (!processedAny || System.nanoTime() < deadline)) {
            final CurrentGridEntry entry = tooltipQueue.removeFirst();
            entry.tooltipQueued = false;
            if (entries.get(entry.stack) != entry || entry.state != State.NEEDS_TOOLTIP) {
                continue;
            }
            processedAny = true;
            generateTooltip(entry, cache);
        }
    }

    private void generateTooltip(
        final CurrentGridEntry entry,
        final PersistentTooltipCache cache
    ) {
        try {
            // Recheck content immediately before generation; a reroll may occur while queued.
            final String currentFingerprint = ContentFingerprint.create(entry.stack);
            if (!currentFingerprint.equals(entry.fingerprint)) {
                entry.contentMutatedInPlace = entry.fingerprint != null;
                entry.fingerprint = currentFingerprint;
                persistentMisses++;
            }

            // Another object with identical content may have populated this fingerprint while this
            // entry waited in the tooltip queue. Recheck RAM before doing expensive generation.
            final CachedTooltipData cached = cache.lookup(currentFingerprint, System.currentTimeMillis());
            if (cached != null) {
                entry.tooltipData = cached;
                entry.state = State.READY;
                persistentHits++;
                resultsDirty = true;
                return;
            }

            // A changed in-place stack may still hold RS's old cachedTooltip. Force only this search
            // index generation fresh; new/replaced objects use RS's normal cached false path.
            final List<Component> tooltip = entry.stack.getTooltip(entry.contentMutatedInPlace);
            final CachedTooltipData data = CachedTooltipData.fromComponents(tooltip);
            cache.put(entry.fingerprint, data, System.currentTimeMillis());
            entry.tooltipData = data;
            entry.state = State.READY;
            entry.contentMutatedInPlace = false;
            resultsDirty = true;
        } catch (IOException | RuntimeException exception) {
            entry.state = State.FAILED;
            entry.tooltipData = null;
            failures++;
            resultsDirty = true;
        }
    }

    private void requestResolution(final CurrentGridEntry entry) {
        if (entry.state == State.UNRESOLVED) {
            enqueueResolution(entry);
        } else if (entry.state == State.NEEDS_TOOLTIP) {
            enqueueTooltip(entry);
        }
    }

    private void enqueueResolution(final CurrentGridEntry entry) {
        if (!entry.resolutionQueued) {
            entry.resolutionQueued = true;
            resolveQueue.addLast(entry);
        }
    }

    private void enqueueTooltip(final CurrentGridEntry entry) {
        if (!entry.tooltipQueued) {
            entry.tooltipQueued = true;
            tooltipQueue.addLast(entry);
        }
    }

    private enum State {
        UNRESOLVED,
        RESOLVING,
        NEEDS_TOOLTIP,
        READY,
        FAILED
    }

    private static final class CurrentGridEntry {
        private final IGridStack stack;
        private State state = State.UNRESOLVED;
        private String fingerprint;
        private CachedTooltipData tooltipData;
        private boolean contentMutatedInPlace;
        private boolean resolutionQueued;
        private boolean tooltipQueued;

        private CurrentGridEntry(final IGridStack stack) {
            this.stack = stack;
        }
    }

    public record Stats(
        int total,
        int resolved,
        long persistentHits,
        long persistentMisses,
        int resolvePending,
        int tooltipPending,
        long refreshes,
        long failures
    ) {
    }
}
