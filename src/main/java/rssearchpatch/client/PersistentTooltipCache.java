package rssearchpatch.client;

import rssearchpatch.RSSearchPatch;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Session RAM cache backed by an asynchronously loaded and atomically saved gzip JSON file. */
public final class PersistentTooltipCache {
    public static final Path CACHE_PATH = FMLPaths.CONFIGDIR.get()
        .resolve(RSSearchPatch.MOD_ID)
        .resolve("tooltip-search-cache.json.gz");

    private static final int SCHEMA_VERSION = 1;
    private static final long MAX_UNUSED_MILLIS = Duration.ofDays(30).toMillis();
    private static final long TOUCH_INTERVAL_MILLIS = Duration.ofDays(1).toMillis();
    private static final long SAVE_DEBOUNCE_NS = Duration.ofMillis(7_500).toNanos();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Logger LOGGER = LoggerFactory.getLogger(RSSearchPatch.MOD_ID + ".tooltip_cache");

    private final Map<String, CacheEntry> entries = new ConcurrentHashMap<>();
    private final AtomicLong mutationVersion = new AtomicLong();
    private final AtomicLong generatedThisSession = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(task -> {
        final Thread thread = new Thread(task, "RS Search Patch cache I/O");
        thread.setDaemon(true);
        return thread;
    });

    private CacheMetadata activeMetadata;
    private long loadGeneration;
    private volatile boolean ready;
    private volatile String loadStatus = "not-started";
    private volatile boolean dirty;
    private volatile boolean saveInProgress;
    private volatile long nextSaveNs;

    public synchronized void ensureLoaded(final CacheMetadata metadata) {
        if (Objects.equals(activeMetadata, metadata) && !"not-started".equals(loadStatus)) {
            return;
        }

        activeMetadata = metadata;
        final long generation = ++loadGeneration;
        entries.clear();
        mutationVersion.incrementAndGet();
        ready = false;
        dirty = false;
        nextSaveNs = 0L;
        loadStatus = "loading";
        ioExecutor.execute(() -> load(generation, metadata));
    }

    public boolean isReady() {
        return ready;
    }

    public String getLoadStatus() {
        return loadStatus;
    }

    public int size() {
        return entries.size();
    }

    public long getGeneratedThisSession() {
        return generatedThisSession.get();
    }

    public long getFailureCount() {
        return failures.get();
    }

    public CachedTooltipData lookup(final String fingerprint, final long nowMillis) {
        final CacheEntry entry = entries.get(fingerprint);
        if (entry == null) {
            return null;
        }

        if (nowMillis - entry.lastSeenMillis() >= TOUCH_INTERVAL_MILLIS) {
            entries.replace(fingerprint, entry, new CacheEntry(entry.data(), nowMillis));
            markDirty();
        }
        return entry.data();
    }

    public void put(final String fingerprint, final CachedTooltipData data, final long nowMillis) {
        entries.put(fingerprint, new CacheEntry(data, nowMillis));
        generatedThisSession.incrementAndGet();
        markDirty();
    }

    public void maybeSave(final long nowNs) {
        if (dirty && !saveInProgress && nowNs >= nextSaveNs) {
            scheduleSave();
        }
    }

    public void requestImmediateSave() {
        if (dirty && !saveInProgress) {
            scheduleSave();
        }
    }

    public void saveAndWaitAtShutdown() {
        if (!ready || (!dirty && !saveInProgress)) {
            return;
        }

        try {
            final CacheMetadata metadata = activeMetadata;
            final Future<?> future = ioExecutor.submit(() -> {
                try {
                    writeSnapshot(metadata, new HashMap<>(entries));
                } catch (IOException exception) {
                    failures.incrementAndGet();
                    LOGGER.warn("Unable to save RS tooltip search cache during shutdown", exception);
                }
            });
            future.get(10, TimeUnit.SECONDS);
            dirty = false;
        } catch (Exception exception) {
            failures.incrementAndGet();
            LOGGER.warn("Timed out saving RS tooltip search cache during shutdown", exception);
        } finally {
            ioExecutor.shutdown();
        }
    }

    private void load(final long generation, final CacheMetadata expectedMetadata) {
        final Map<String, CacheEntry> loadedEntries = new HashMap<>();
        String status = "empty";
        boolean pruned = false;

        if (Files.isRegularFile(CACHE_PATH)) {
            try (Reader reader = new InputStreamReader(
                new GZIPInputStream(new BufferedInputStream(Files.newInputStream(CACHE_PATH))),
                StandardCharsets.UTF_8)) {
                final CacheDocument document = GSON.fromJson(reader, CacheDocument.class);
                if (document == null || document.schemaVersion != SCHEMA_VERSION
                    || !expectedMetadata.matches(document)) {
                    status = "incompatible-rebuilt";
                } else {
                    final long cutoff = System.currentTimeMillis() - MAX_UNUSED_MILLIS;
                    if (document.entries != null) {
                        for (Map.Entry<String, DiskEntry> diskEntry : document.entries.entrySet()) {
                            final DiskEntry value = diskEntry.getValue();
                            if (value != null && value.lines != null && value.lastSeenMillis >= cutoff) {
                                loadedEntries.put(
                                    diskEntry.getKey(),
                                    new CacheEntry(new CachedTooltipData(value.lines), value.lastSeenMillis)
                                );
                            } else {
                                pruned = true;
                            }
                        }
                    }
                    status = pruned ? "loaded-pruned" : "loaded";
                }
            } catch (IOException | RuntimeException exception) {
                failures.incrementAndGet();
                status = "corrupt-rebuilt";
                LOGGER.warn("Unable to load RS tooltip search cache {}; rebuilding it", CACHE_PATH, exception);
            }
        }

        synchronized (this) {
            if (generation != loadGeneration || !Objects.equals(expectedMetadata, activeMetadata)) {
                return;
            }
            entries.clear();
            entries.putAll(loadedEntries);
            mutationVersion.incrementAndGet();
            ready = true;
            loadStatus = status;
            if (pruned) {
                markDirty();
            }
        }
    }

    private void markDirty() {
        mutationVersion.incrementAndGet();
        if (!dirty) {
            dirty = true;
            nextSaveNs = System.nanoTime() + SAVE_DEBOUNCE_NS;
        }
    }

    private synchronized void scheduleSave() {
        if (!ready || !dirty || saveInProgress || activeMetadata == null) {
            return;
        }

        saveInProgress = true;
        final long generation = loadGeneration;
        final long version = mutationVersion.get();
        final CacheMetadata metadata = activeMetadata;
        ioExecutor.execute(() -> {
            boolean success = false;
            try {
                // The worker copies only immutable strings, timestamps, and tooltip line lists.
                writeSnapshot(metadata, new HashMap<>(entries));
                success = true;
            } catch (IOException exception) {
                failures.incrementAndGet();
                LOGGER.warn("Unable to save RS tooltip search cache {}; continuing in memory", CACHE_PATH, exception);
            }

            synchronized (PersistentTooltipCache.this) {
                saveInProgress = false;
                if (generation != loadGeneration) {
                    return;
                }
                if (success && version == mutationVersion.get()) {
                    dirty = false;
                } else {
                    nextSaveNs = System.nanoTime() + SAVE_DEBOUNCE_NS;
                }
            }
        });
    }

    private static void writeSnapshot(
        final CacheMetadata metadata,
        final Map<String, CacheEntry> snapshot
    ) throws IOException {
        Files.createDirectories(CACHE_PATH.getParent());
        final Path temporary = CACHE_PATH.resolveSibling(CACHE_PATH.getFileName() + ".tmp");
        final CacheDocument document = CacheDocument.from(metadata, snapshot);

        try (Writer writer = new OutputStreamWriter(
            new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE))),
            StandardCharsets.UTF_8)) {
            GSON.toJson(document, writer);
        }

        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
        }

        try {
            Files.move(
                temporary,
                CACHE_PATH,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, CACHE_PATH, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static CacheMetadata captureMetadata() {
        final Minecraft minecraft = Minecraft.getInstance();
        final String refinedStorageVersion = ModList.get().getModContainerById("refinedstorage")
            .map(container -> container.getModInfo().getVersion().toString())
            .orElse("unknown");
        final List<String> mods = new ArrayList<>();
        for (IModInfo mod : ModList.get().getMods()) {
            mods.add(mod.getModId() + "=" + mod.getVersion());
        }
        mods.sort(Comparator.naturalOrder());

        return new CacheMetadata(
            SharedConstants.getCurrentVersion().getName(),
            refinedStorageVersion,
            minecraft.getLanguageManager().getSelected(),
            minecraft.options.advancedItemTooltips,
            hashStrings(mods)
        );
    }

    private static String hashStrings(final List<String> values) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record CacheMetadata(
        String minecraftVersion,
        String refinedStorageVersion,
        String languageCode,
        boolean advancedTooltips,
        String modEnvironmentSignature
    ) {
        private boolean matches(final CacheDocument document) {
            return Objects.equals(minecraftVersion, document.minecraftVersion)
                && Objects.equals(refinedStorageVersion, document.refinedStorageVersion)
                && Objects.equals(languageCode, document.languageCode)
                && advancedTooltips == document.advancedTooltips
                && Objects.equals(modEnvironmentSignature, document.modEnvironmentSignature);
        }
    }

    private record CacheEntry(CachedTooltipData data, long lastSeenMillis) {
    }

    private static final class DiskEntry {
        private List<String> lines;
        private long lastSeenMillis;

        private DiskEntry() {
        }

        private DiskEntry(final List<String> lines, final long lastSeenMillis) {
            this.lines = lines;
            this.lastSeenMillis = lastSeenMillis;
        }
    }

    private static final class CacheDocument {
        private int schemaVersion;
        private String minecraftVersion;
        private String refinedStorageVersion;
        private String languageCode;
        private boolean advancedTooltips;
        private String modEnvironmentSignature;
        private Map<String, DiskEntry> entries;

        private static CacheDocument from(
            final CacheMetadata metadata,
            final Map<String, CacheEntry> snapshot
        ) {
            final CacheDocument document = new CacheDocument();
            document.schemaVersion = SCHEMA_VERSION;
            document.minecraftVersion = metadata.minecraftVersion();
            document.refinedStorageVersion = metadata.refinedStorageVersion();
            document.languageCode = metadata.languageCode();
            document.advancedTooltips = metadata.advancedTooltips();
            document.modEnvironmentSignature = metadata.modEnvironmentSignature();
            document.entries = new HashMap<>();
            for (Map.Entry<String, CacheEntry> entry : snapshot.entrySet()) {
                document.entries.put(
                    entry.getKey(),
                    new DiskEntry(entry.getValue().data().lines(), entry.getValue().lastSeenMillis())
                );
            }
            return document;
        }
    }
}
