package net.easecation.ecclientlight.light;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import net.easecation.ecclientlight.ECClientLight;
import net.easecation.ecclientlight.light.FrozenRegionalRelightScheduler.CenterRequest;
import net.easecation.ecclientlight.light.FrozenRegionalRelightScheduler.FreezeContext;
import net.easecation.ecclientlight.light.FrozenRegionalRelightScheduler.FrozenBatch;
import net.easecation.ecclientlight.light.FrozenRegionalRelightScheduler.Limits;
import net.easecation.ecclientlight.light.staging.StagingCompletion;
import net.easecation.ecclientlight.light.staging.StagingJob;
import net.easecation.ecclientlight.light.staging.StagingJobHandle;
import net.easecation.ecclientlight.light.staging.StagingRevisionSnapshot;
import net.easecation.ecclientlight.light.staging.StagingWorker;
import net.easecation.ecclientlight.mixin.LayerLightSectionStorageInvoker;
import net.easecation.ecclientlight.mixin.LightEngineStorageAccessor;
import net.easecation.ecclientlight.network.ClientLightSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;

/** Coordinates snapshot ingestion, worker-owned relighting and atomic light publication. */
public final class ClientLightManager {

    private static final int MAX_QUEUED_LIGHT_TASKS_PER_FRAME = Math.clamp(
        Integer.getInteger("ecclientlight.maxQueuedLightTasksPerFrame", 32),
        4,
        128
    );
    private static final long SNAPSHOT_BUDGET_NANOS = TimeUnit.MICROSECONDS.toNanos(Math.clamp(
        Long.getLong("ecclientlight.snapshotMicrosPerFrame", 500L),
        100L,
        2_000L
    ));
    private static final long PUBLISH_BUDGET_NANOS = TimeUnit.MICROSECONDS.toNanos(Math.clamp(
        Long.getLong("ecclientlight.publishMicrosPerFrame", 500L),
        100L,
        2_000L
    ));
    private static final int MAX_PUBLISHED_LAYERS_PER_FRAME = Math.clamp(
        Integer.getInteger("ecclientlight.maxPublishedLayersPerFrame", 512),
        64,
        2_048
    );
    private static final long BATCH_AGING_NANOS = TimeUnit.MILLISECONDS.toNanos(Math.clamp(
        Long.getLong("ecclientlight.batchAgingMillis", 250L),
        50L,
        2_000L
    ));
    private static final int MAX_RETAINED_LIGHT_CHUNKS = RetainedLightCache.resolveMaxChunks(
        Integer.getInteger("ecclientlight.maxRetainedLightChunks")
    );
    private static final long MAX_RETAINED_LIGHT_BYTES = RetainedLightCache.resolveMaxBytes(
        Long.getLong("ecclientlight.maxRetainedLightBytes")
    );
    private static final long RETAINED_LIGHT_TTL_NANOS = RetainedLightCache.resolveTtlNanos(
        Long.getLong("ecclientlight.retainedLightSeconds")
    );
    private static final long MAX_OVERLAY_BYTES = PublishedLightOverlay.resolveMaxBytes(
        Long.getLong("ecclientlight.maxPublishedOverlayBytes")
    );
    private static final int MAX_SNAPSHOT_CACHE_CHUNKS = Math.clamp(
        Integer.getInteger("ecclientlight.maxSnapshotCacheChunks", 96),
        0,
        256
    );
    private static final long MAX_SNAPSHOT_CACHE_BYTES = Math.clamp(
        Long.getLong("ecclientlight.maxSnapshotCacheBytes", 24L * 1024L * 1024L),
        0L,
        64L * 1024L * 1024L
    );
    private static final long CACHE_STATS_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30L);
    private static final int MAX_DEFERRED_OFFERS = 8192;
    private static final int MAX_PENDING_PROVISIONAL_COLUMNS = 8192;
    private static final int MAX_PROVISIONAL_OPENS_PER_FRAME = 256;
    private static final Limits BATCH_LIMITS = new Limits(
        Math.clamp(Integer.getInteger("ecclientlight.maxBatchCenters", 16), 1, 64),
        Math.clamp(Integer.getInteger("ecclientlight.maxQueuedCenters", 2048), 64, 8192),
        Math.clamp(Integer.getInteger("ecclientlight.maxBatchAffectedColumns", 36), 9, 256),
        Math.clamp(Integer.getInteger("ecclientlight.maxBatchSourceColumns", 64), 25, 400)
    );

    private static final Map<ClientLevel, LevelState> LEVEL_STATES = new WeakHashMap<>();
    private static final ConcurrentHashMap<LevelLightEngine, LevelState> LIVE_ENGINES = new ConcurrentHashMap<>();
    private static final Map<Long, LevelState> JOB_OWNERS = new HashMap<>();
    private static final ConcurrentHashMap<LightEngine<?, ?>, OverlayBinding> OVERLAY_BINDINGS = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_LEVEL_EPOCH = new AtomicLong();
    private static final AtomicLong NEXT_JOB_ID = new AtomicLong();
    private static long observedDroppedCompletions;

    private ClientLightManager() {
    }

    public static boolean canNegotiateLocalLight() {
        return MAX_OVERLAY_BYTES > 0L && WorkerHolder.WORKER.isWorkerAlive();
    }

    public static int maxQueuedLightTasksPerFrame() {
        return MAX_QUEUED_LIGHT_TASKS_PER_FRAME;
    }

    public static long snapshotMicrosPerFrame() {
        return TimeUnit.NANOSECONDS.toMicros(SNAPSHOT_BUDGET_NANOS);
    }

    public static long publishMicrosPerFrame() {
        return TimeUnit.NANOSECONDS.toMicros(PUBLISH_BUDGET_NANOS);
    }

    public static int maxBatchCenters() {
        return BATCH_LIMITS.maxCentersPerBatch();
    }

    public static long maxOverlayBytes() {
        return MAX_OVERLAY_BYTES;
    }

    public static int maxSnapshotCacheChunks() {
        return MAX_SNAPSHOT_CACHE_CHUNKS;
    }

    public static long maxSnapshotCacheBytes() {
        return MAX_SNAPSHOT_CACHE_BYTES;
    }

    public static int maxRetainedLightChunks() {
        return MAX_RETAINED_LIGHT_CHUNKS;
    }

    public static long maxRetainedLightBytes() {
        return MAX_RETAINED_LIGHT_BYTES;
    }

    public static long retainedLightSeconds() {
        return TimeUnit.NANOSECONDS.toSeconds(RETAINED_LIGHT_TTL_NANOS);
    }

    /** Establishes negotiated level state before the packet-light callback queue is drained. */
    public static void prepareLightUpdatePoll(ClientLevel level) {
        drainWorkerCompletions();
        if (!LEVEL_STATES.containsKey(level) && isNegotiatedCurrentLevel(level)) {
            createLevelState(level);
        }
        LevelState state = LEVEL_STATES.get(level);
        if (state != null) {
            checkWorkerHealth(level, state);
        }
    }

    public static int limitQueuedLightTasks(ClientLevel level, int vanillaLimit) {
        return LEVEL_STATES.containsKey(level)
            ? Math.min(vanillaLimit, MAX_QUEUED_LIGHT_TASKS_PER_FRAME)
            : vanillaLimit;
    }

    /** Advances only bounded client-thread ingestion/publication work after packet callbacks. */
    public static void afterLightUpdatePoll(ClientLevel level) {
        LevelState state = LEVEL_STATES.get(level);
        if (state == null || state.failed) {
            return;
        }
        long sliceStart = System.nanoTime();
        try {
            long now = sliceStart;
            if (!flushPendingProvisionals(level, state)) {
                return;
            }
            releaseExpiredRetainedLight(level, state, now);
            maybeLogStats(state, now);
            retryDeferredOffers(state, 64);

            if (state.pendingCommit != null) {
                PendingCommit commit = state.pendingCommit;
                PendingCommit.QueueStatus status = commit.queueBounded(level, state);
                if (status == PendingCommit.QueueStatus.STALE) {
                    supersedePendingCommit(level, state, commit);
                }
                return;
            }
            if (state.commitPreparation != null) {
                advanceCommitPreparation(level, state);
                return;
            }
            if (state.capture != null) {
                advanceCapture(level, state);
                return;
            }
            if (state.activeJobId != 0L) {
                return;
            }
            startNextBatch(level, state, now);
            if (state.capture != null) {
                advanceCapture(level, state);
            }
        } finally {
            state.maxCoordinatorSliceNanos = Math.max(
                state.maxCoordinatorSliceNanos,
                Math.max(0L, System.nanoTime() - sliceStart)
            );
        }
    }

    public static void beforeLightEngineRun(ClientLevel level) {
        LevelState state = LEVEL_STATES.get(level);
        if (state != null) {
            state.lightRunStartedNanos = System.nanoTime();
        }
    }

    /** Completes publication only after Vanilla has installed every queued final layer. */
    public static void afterLightEngineRun(ClientLevel level) {
        LevelState state = LEVEL_STATES.get(level);
        if (state == null) {
            return;
        }
        long now = System.nanoTime();
        if (state.lightRunStartedNanos != 0L) {
            state.maxLightRunNanos = Math.max(
                state.maxLightRunNanos,
                Math.max(0L, now - state.lightRunStartedNanos)
            );
            state.lightRunStartedNanos = 0L;
        }
        LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
        if (!lightEngine.hasLightWork()) {
            state.retainedLight.promotePending();
        }
        PendingCommit commit = state.pendingCommit;
        if (commit == null || !commit.queuedAll || lightEngine.hasLightWork()) {
            return;
        }
        if (!commit.revisions.validate(state).isCurrent()) {
            supersedePendingCommit(level, state, commit);
            return;
        }

        LinkedHashSet<Long> releaseEpochs = new LinkedHashSet<>(commit.openedOverlayEpochs.values());
        List<Long> guardColumns = new ArrayList<>();
        List<Long> provisionalColumns = new ArrayList<>();
        LinkedHashSet<Long> dirtyColumns = new LinkedHashSet<>(commit.dirtyColumns);
        LinkedHashMap<Long, Long> committedRevisions = new LinkedHashMap<>();
        for (long chunkKey : commit.outputColumns) {
            long expectedRevision = commit.outputRevisions.getOrDefault(
                chunkKey,
                StagingRevisionSnapshot.MISSING_REVISION
            );
            if (state.chunkRevision(chunkKey) != expectedRevision || !isLoaded(level, chunkKey)) {
                supersedePendingCommit(level, state, commit);
                return;
            }
            PublicationGuardSet.Guard guard = state.publicationGuards.get(chunkKey);
            if (guard != null) {
                releaseEpochs.add(guard.overlayEpoch());
                guardColumns.add(chunkKey);
                dirtyColumns.addAll(guard.dirtyColumns());
            }
            ProvisionalLight provisional = state.provisionalColumns.get(chunkKey);
            if (provisional != null) {
                if (provisional.revision != expectedRevision) {
                    failClosed(level, state, new IllegalStateException("Provisional light revision drifted"));
                    return;
                }
                provisionalColumns.add(chunkKey);
                releaseEpochs.add(provisional.overlayEpoch);
            }
            state.publishedColumns.add(chunkKey);
            committedRevisions.put(chunkKey, expectedRevision);
        }
        if (releaseEpochs.isEmpty()) {
            failClosed(level, state, new IllegalStateException("Current light commit has no publication guard"));
            return;
        }
        PublishedLightOverlay.ReleaseResult release = state.overlay.releaseAll(
            releaseEpochs.stream().mapToLong(Long::longValue).toArray()
        );
        if (!release.released()) {
            failClosed(level, state, new IllegalStateException("Published light overlay epoch disappeared before commit"));
            return;
        }
        for (long chunkKey : guardColumns) {
            if (state.publicationGuards.remove(chunkKey) == null) {
                failClosed(level, state, new IllegalStateException("Publication guard disappeared after atomic release"));
                return;
            }
        }
        for (long chunkKey : provisionalColumns) {
            if (state.provisionalColumns.remove(chunkKey) == null) {
                failClosed(level, state, new IllegalStateException("Provisional guard disappeared after atomic release"));
                return;
            }
        }
        state.pendingCommit = null;
        publishDirtyColumns(level, dirtyColumns);
        state.centersSatisfiedByCoverage += state.scheduler.satisfyCovered(
            committedRevisions,
            commit.revisions.chunkRevisions().keySet()
        );
        if (!state.scheduler.completeActive(commit.batchId)) {
            failClosed(level, state, new IllegalStateException("Frozen relight batch changed before publication"));
            return;
        }
        state.batchesPublished++;
        state.totalPacketToPublishNanos += Math.max(0L, System.nanoTime() - commit.oldestQueuedAt);
        state.maxPacketToPublishNanos = Math.max(
            state.maxPacketToPublishNanos,
            Math.max(0L, System.nanoTime() - commit.oldestQueuedAt)
        );
        state.activeBatch = null;
    }

    /** Combined chunk packets call this after Vanilla has installed the safe placeholder layers. */
    public static void onFullChunkDataIngress(ClientLevel level, int chunkX, int chunkZ) {
        LevelState state = LEVEL_STATES.get(level);
        if (state == null) {
            state = createLevelState(level);
        }
        long now = System.nanoTime();
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        long generation = state.bumpRevision(chunkKey);
        LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
        boolean retainedReady = state.retainedLight.shouldPreserveIncomingLight(
            chunkKey,
            lightEngine.getMinLightSection(),
            lightEngine.getLightSectionCount(),
            now
        );
        if (
            !retainedReady
                && !state.publishedColumns.contains(chunkKey)
                && !ensureProvisional(level, state, chunkKey, generation)
        ) {
            return;
        }
        offerCenter(state, chunkKey, generation, now);
    }

    /** Combined chunk packets call this after Vanilla has installed the safe placeholder layers. */
    public static void onFullChunkData(ClientLevel level, LevelChunk chunk) {
        LevelState state = LEVEL_STATES.get(level);
        if (state == null) {
            state = createLevelState(level);
        }
        LevelChunk current = level.getChunkSource().getChunk(chunk.getPos().x, chunk.getPos().z, false);
        if (current != chunk) {
            return;
        }
        long now = System.nanoTime();
        long chunkKey = chunk.getPos().toLong();
        long generation = state.ensureRevision(chunkKey);
        LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();

        boolean retainedReady = state.retainedLight.shouldPreserveIncomingLight(
            chunkKey,
            lightEngine.getMinLightSection(),
            lightEngine.getLightSectionCount(),
            now
        );
        RetainedLightCache.Entry retained = retainedReady
            ? state.retainedLight.claimReady(chunkKey)
            : null;
        if (!retainedReady) {
            RetainedLightCache.Entry discarded = state.retainedLight.discard(chunkKey);
            if (discarded != null) {
                releaseRetainedColumn(lightEngine, discarded, false);
            }
        }
        if (retained != null) {
            lightEngine.retainData(chunk.getPos(), false);
            releaseProvisional(level, state, chunkKey);
            state.publishedColumns.add(chunkKey);
            state.cachedRestores++;
        } else if (!state.publishedColumns.contains(chunkKey)) {
            if (!ensureProvisional(level, state, chunkKey, generation)) {
                return;
            }
            state.placeholderPublications++;
        }
        offerCenter(state, chunkKey, generation, now);
    }

    /** Keeps retained or already-published light across a combined full-chunk resend. */
    public static boolean shouldPreserveRetainedLight(ClientLevel level, int chunkX, int chunkZ) {
        LevelState state = LEVEL_STATES.get(level);
        if (state == null) {
            return false;
        }
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        if (state.publishedColumns.contains(chunkKey)) {
            return true;
        }
        LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
        return state.retainedLight.shouldPreserveIncomingLight(
            chunkKey,
            lightEngine.getMinLightSection(),
            lightEngine.getLightSectionCount(),
            System.nanoTime()
        );
    }

    /** Redirects dynamic block changes into the snapshot pipeline instead of the live engine. */
    public static boolean interceptBlockChange(LevelLightEngine lightEngine, BlockPos pos) {
        LevelState state = LIVE_ENGINES.get(lightEngine);
        if (state == null || state.cleared || state.failed) {
            return false;
        }
        long chunkKey = ChunkPos.asLong(
            SectionPos.blockToSectionCoord(pos.getX()),
            SectionPos.blockToSectionCoord(pos.getZ())
        );
        long generation = state.bumpRevision(chunkKey);
        offerCenter(state, chunkKey, generation, System.nanoTime());
        state.blockChanges++;
        return true;
    }

    /** Defers destructive live section-status changes until the matching final layers are committed. */
    public static boolean interceptSectionStatus(LevelLightEngine lightEngine, SectionPos pos) {
        LevelState state = LIVE_ENGINES.get(lightEngine);
        if (
            state == null
                || state.cleared
                || state.failed
                || state.installingFinalSectionStatus
        ) {
            return false;
        }
        long chunkKey = ChunkPos.asLong(pos.x(), pos.z());
        if (!state.publishedColumns.contains(chunkKey)) {
            return false;
        }
        long generation = state.bumpRevision(chunkKey);
        offerCenter(state, chunkKey, generation, System.nanoTime());
        state.sectionStatusChanges++;
        return true;
    }

    public static void onChunkUnloaded(ClientLevel level, ChunkPos pos) {
        LevelState state = LEVEL_STATES.get(level);
        if (state == null) {
            return;
        }
        LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
        long chunkKey = pos.toLong();
        boolean publishing = state.isColumnPublishing(chunkKey);
        releaseProvisional(level, state, chunkKey);
        RetainedLightCache.Entry previous = state.retainedLight.discard(chunkKey);
        if (previous != null) {
            releaseRetainedColumn(lightEngine, previous, true);
        }
        if (
            isNegotiatedCurrentLevel(level)
                && state.publishedColumns.contains(chunkKey)
                && !publishing
        ) {
            RetainedLightCache.Reservation reservation = state.retainedLight.reserve(
                chunkKey,
                lightEngine.getMinLightSection(),
                lightEngine.getLightSectionCount(),
                System.nanoTime()
            );
            for (RetainedLightCache.Entry evicted : reservation.evicted()) {
                releaseRetainedColumn(lightEngine, evicted, true);
            }
            if (reservation.accepted()) {
                lightEngine.retainData(pos, true);
            }
        }
        releasePublicationGuard(level, state, chunkKey);

        long oldGeneration = state.revisions.getOrDefault(chunkKey, 0L);
        state.forgetRevision(chunkKey);
        state.publishedColumns.remove(chunkKey);
        state.scheduler.cancel(chunkKey, Math.max(oldGeneration, 1L));
    }

    /** Returns the immutable protected section for renderer queries, or null when unprotected. */
    @Nullable
    public static PublishedLightOverlay.SectionSnapshot overlaySection(LightEngine<?, ?> engine, long sectionKey) {
        OverlayBinding binding = OVERLAY_BINDINGS.get(engine);
        return binding == null ? null : binding.overlay.publishedView().section(sectionKey);
    }

    @Nullable
    public static LightLayer overlayLayer(LightEngine<?, ?> engine) {
        OverlayBinding binding = OVERLAY_BINDINGS.get(engine);
        return binding == null ? null : binding.layer;
    }

    public static int overlayLightValue(LightEngine<?, ?> engine, long packedBlockPos) {
        OverlayBinding binding = OVERLAY_BINDINGS.get(engine);
        return binding == null
            ? PublishedLightOverlay.View.NOT_PROTECTED
            : binding.overlay.publishedView().lightValue(binding.layer, packedBlockPos);
    }

    public static boolean isOverlayColumnProtected(LevelLightEngine engine, long zeroNode) {
        LevelState state = LIVE_ENGINES.get(engine);
        return state != null && state.overlay.publishedView().protectsColumn(zeroNode);
    }

    /** Suppresses intermediate renderer invalidations while final layers are installed in slices. */
    public static boolean isRenderPending(ClientLevel level, int sectionX, int sectionZ) {
        LevelState state = LEVEL_STATES.get(level);
        if (state == null) {
            return false;
        }
        long chunkKey = ChunkPos.asLong(sectionX, sectionZ);
        return state.publicationGuards.protectsDirtyColumn(chunkKey)
            || (state.pendingCommit != null && state.pendingCommit.dirtyColumns.contains(chunkKey));
    }

    public static void clearLevel(ClientLevel level) {
        LevelState state = LEVEL_STATES.remove(level);
        if (state == null) {
            return;
        }
        unregisterLiveEngine(level.getChunkSource().getLightEngine(), state);
        if (ECClientLight.LOGGER.isDebugEnabled()) {
            FrozenRegionalRelightScheduler.Stats stats = state.scheduler.stats();
            ECClientLight.LOGGER.debug(
                "[ECClientLight] Cleared staging light state: {} published, {} queued, {} batches submitted/{} published/{} stale, {} placeholders, {} cache restores, {} overlay bytes, {} source replays saved, max latency={} ms",
                state.publishedColumns.size(),
                state.scheduler.nextCenterCount(),
                state.batchesSubmitted,
                state.batchesPublished,
                state.staleResults,
                state.placeholderPublications,
                state.cachedRestores,
                state.overlay.retainedBytes(),
                stats.sourceReplaysSaved(),
                TimeUnit.NANOSECONDS.toMillis(state.maxPacketToPublishNanos)
            );
        }
        state.clear();
    }

    public static void clearAll() {
        for (Map.Entry<ClientLevel, LevelState> entry : List.copyOf(LEVEL_STATES.entrySet())) {
            unregisterLiveEngine(entry.getKey().getChunkSource().getLightEngine(), entry.getValue());
            entry.getValue().clear();
        }
        LEVEL_STATES.clear();
        LIVE_ENGINES.clear();
        OVERLAY_BINDINGS.clear();
        JOB_OWNERS.clear();
    }

    private static LevelState createLevelState(ClientLevel level) {
        LevelState existing = LEVEL_STATES.get(level);
        if (existing != null) {
            return existing;
        }
        LevelState state = new LevelState(level);
        LEVEL_STATES.put(level, state);
        registerLiveEngine(level.getChunkSource().getLightEngine(), state);
        return state;
    }

    private static void registerLiveEngine(LevelLightEngine lightEngine, LevelState state) {
        LIVE_ENGINES.put(lightEngine, state);
        registerLayer(lightEngine, LightLayer.BLOCK, state.overlay);
        if (state.hasSkyLight) {
            registerLayer(lightEngine, LightLayer.SKY, state.overlay);
        }
    }

    private static boolean ensureProvisional(
        ClientLevel level,
        LevelState state,
        long chunkKey,
        long revision
    ) {
        ProvisionalLight existing = state.provisionalColumns.get(chunkKey);
        if (existing != null) {
            state.provisionalColumns.put(chunkKey, new ProvisionalLight(existing.overlayEpoch, revision));
            return true;
        }
        PendingProvisionalLight pending = state.pendingProvisionals.get(chunkKey);
        if (pending != null) {
            state.pendingProvisionals.put(chunkKey, new PendingProvisionalLight(pending.capture, revision));
            return true;
        }
        if (state.pendingProvisionals.size() >= MAX_PENDING_PROVISIONAL_COLUMNS) {
            failClosed(level, state, new IllegalStateException("Pending provisional overlay queue exceeded its hard limit"));
            return false;
        }

        LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
        ChunkPos pos = new ChunkPos(chunkKey);
        PublishedLightOverlay.Capture capture = PublishedLightOverlay.newCapture(lightEngine.getLightSectionCount());
        DataLayer skyPlaceholder = state.hasSkyLight ? new DataLayer(15) : null;
        for (int offset = 0; offset < lightEngine.getLightSectionCount(); offset++) {
            SectionPos sectionPos = SectionPos.of(pos, lightEngine.getMinLightSection() + offset);
            capture.put(sectionPos.asLong(), null, skyPlaceholder);
        }
        state.pendingProvisionals.put(chunkKey, new PendingProvisionalLight(capture, revision));
        return true;
    }

    private static boolean flushPendingProvisionals(ClientLevel level, LevelState state) {
        if (state.pendingProvisionals.isEmpty()) {
            return true;
        }
        List<Long> chunkKeys = new ArrayList<>();
        List<PublishedLightOverlay.Capture> captures = new ArrayList<>();
        var iterator = state.pendingProvisionals.entrySet().iterator();
        while (iterator.hasNext() && captures.size() < MAX_PROVISIONAL_OPENS_PER_FRAME) {
            Map.Entry<Long, PendingProvisionalLight> entry = iterator.next();
            long chunkKey = entry.getKey();
            if (!isLoaded(level, chunkKey) || state.publishedColumns.contains(chunkKey)) {
                iterator.remove();
                continue;
            }
            chunkKeys.add(chunkKey);
            captures.add(entry.getValue().capture);
        }
        if (captures.isEmpty()) {
            return state.pendingProvisionals.isEmpty();
        }

        long firstOverlayEpoch = state.nextOverlayEpoch;
        long nextOverlayEpoch;
        try {
            nextOverlayEpoch = Math.addExact(firstOverlayEpoch, captures.size());
        } catch (ArithmeticException failure) {
            failClosed(level, state, new IllegalStateException("Provisional overlay epoch overflow", failure));
            return false;
        }
        PublishedLightOverlay.OpenResult opened = state.overlay.tryOpenBatch(firstOverlayEpoch, captures);
        if (!opened.accepted()) {
            failClosed(
                level,
                state,
                new IllegalStateException(
                    "Published light overlay rejected provisional batch at epoch "
                        + firstOverlayEpoch
                        + ": "
                        + opened.rejection()
                )
            );
            return false;
        }
        for (int index = 0; index < chunkKeys.size(); index++) {
            long chunkKey = chunkKeys.get(index);
            PendingProvisionalLight pending = state.pendingProvisionals.remove(chunkKey);
            if (pending == null) {
                failClosed(level, state, new IllegalStateException("Pending provisional disappeared during flush"));
                return false;
            }
            state.provisionalColumns.put(
                chunkKey,
                new ProvisionalLight(firstOverlayEpoch + index, pending.revision)
            );
        }
        state.nextOverlayEpoch = nextOverlayEpoch;
        return state.pendingProvisionals.isEmpty();
    }

    private static void releaseProvisional(ClientLevel level, LevelState state, long chunkKey) {
        if (state.pendingProvisionals.remove(chunkKey) != null) {
            return;
        }
        ProvisionalLight provisional = state.provisionalColumns.remove(chunkKey);
        if (provisional == null) {
            return;
        }
        if (!state.overlay.release(provisional.overlayEpoch).released()) {
            failClosed(level, state, new IllegalStateException("Provisional light overlay epoch disappeared"));
        }
    }

    private static void releasePublicationGuard(ClientLevel level, LevelState state, long chunkKey) {
        PublicationGuardSet.Guard guard = state.publicationGuards.get(chunkKey);
        if (guard == null) {
            return;
        }
        if (!state.overlay.release(guard.overlayEpoch()).released()) {
            failClosed(level, state, new IllegalStateException("Publication guard overlay epoch disappeared"));
            return;
        }
        if (state.publicationGuards.remove(chunkKey) == null) {
            failClosed(level, state, new IllegalStateException("Publication guard disappeared after release"));
        }
    }

    private static void registerLayer(
        LevelLightEngine lightEngine,
        LightLayer layer,
        PublishedLightOverlay overlay
    ) {
        if (lightEngine.getLayerListener(layer) instanceof LightEngine<?, ?> engine) {
            OVERLAY_BINDINGS.put(engine, new OverlayBinding(overlay, layer));
        }
    }

    private static void unregisterLiveEngine(LevelLightEngine lightEngine, LevelState state) {
        LIVE_ENGINES.remove(lightEngine, state);
        if (lightEngine.getLayerListener(LightLayer.BLOCK) instanceof LightEngine<?, ?> engine) {
            OVERLAY_BINDINGS.remove(engine);
        }
        if (state.hasSkyLight && lightEngine.getLayerListener(LightLayer.SKY) instanceof LightEngine<?, ?> engine) {
            OVERLAY_BINDINGS.remove(engine);
        }
    }

    private static void offerCenter(LevelState state, long chunkKey, long generation, long now) {
        long queuedAt = Math.max(0L, now);
        FrozenRegionalRelightScheduler.OfferResult result = state.scheduler.offer(
            chunkKey,
            generation,
            queuedAt
        );
        if (result == FrozenRegionalRelightScheduler.OfferResult.BACKPRESSURED) {
            state.backpressureEvents++;
            DeferredOffer existing = state.deferredOffers.get(chunkKey);
            if (existing != null) {
                if (generation > existing.generation) {
                    state.deferredOffers.put(
                        chunkKey,
                        new DeferredOffer(generation, existing.firstQueuedAt)
                    );
                }
                return;
            }
            if (state.deferredOffers.size() >= MAX_DEFERRED_OFFERS) {
                failClosed(
                    state.level,
                    state,
                    new IllegalStateException("Relight scheduler backpressure queue exceeded its hard limit")
                );
                return;
            }
            state.deferredOffers.put(chunkKey, new DeferredOffer(generation, queuedAt));
        }
    }

    private static void retryDeferredOffers(LevelState state, int limit) {
        var iterator = state.deferredOffers.entrySet().iterator();
        int retried = 0;
        while (iterator.hasNext() && retried < limit) {
            Map.Entry<Long, DeferredOffer> entry = iterator.next();
            DeferredOffer offer = entry.getValue();
            FrozenRegionalRelightScheduler.OfferResult result = state.scheduler.offer(
                entry.getKey(),
                offer.generation,
                offer.firstQueuedAt
            );
            if (result == FrozenRegionalRelightScheduler.OfferResult.BACKPRESSURED) {
                break;
            }
            iterator.remove();
            retried++;
        }
    }

    private static void startNextBatch(ClientLevel level, LevelState state, long now) {
        if (state.scheduler.hasActiveBatch() || state.scheduler.nextCenterCount() == 0) {
            return;
        }
        ChunkPos anchor = playerChunk(level);
        FreezeContext context = new FreezeContext(
            anchor.x,
            anchor.z,
            Math.max(0L, now),
            BATCH_AGING_NANOS,
            chunkKey -> isLoadedWithHalo(level, chunkKey, 1),
            chunkKey -> isLoaded(level, chunkKey)
        );
        Optional<FrozenBatch> frozen = state.scheduler.freezeNext(context);
        if (frozen.isEmpty()) {
            return;
        }
        FrozenBatch batch = frozen.get();
        state.activeBatch = batch;
        if (batch.affectedColumns().isEmpty()) {
            state.scheduler.completeActive(batch.id());
            state.activeBatch = null;
            return;
        }

        LinkedHashMap<Long, Long> revisions = new LinkedHashMap<>();
        for (long sourceKey : batch.sourceColumns()) {
            revisions.put(sourceKey, state.ensureRevision(sourceKey));
        }
        if (revisions.isEmpty()) {
            state.scheduler.completeActive(batch.id());
            state.activeBatch = null;
            return;
        }
        state.capture = new CaptureState(
            batch,
            new StagingRevisionSnapshot(state.levelEpoch, revisions),
            batch.affectedColumns(),
            new ArrayList<>(revisions.keySet()),
            oldestQueuedAt(batch)
        );
    }

    private static void advanceCapture(ClientLevel level, LevelState state) {
        CaptureState capture = Objects.requireNonNull(state.capture);
        StagingRevisionSnapshot.Validation validation = capture.revisions.validate(state);
        if (!validation.isCurrent()) {
            state.staleCaptures++;
            abortAndRequeue(state, capture.batch);
            state.capture = null;
            return;
        }

        long deadline = System.nanoTime() + SNAPSHOT_BUDGET_NANOS;
        while (capture.nextSource < capture.sourceColumns.size()) {
            long chunkKey = capture.sourceColumns.get(capture.nextSource);
            ChunkPos pos = new ChunkPos(chunkKey);
            LevelChunk chunk = level.getChunkSource().getChunk(pos.x, pos.z, false);
            if (chunk == null) {
                state.staleCaptures++;
                abortAndRequeue(state, capture.batch);
                state.capture = null;
                return;
            }
            long revision = capture.revisions.chunkRevisions().get(chunkKey);
            if (capture.activeChunkCapture == null) {
                StagingLightEngine.ChunkSnapshot cached = state.snapshotCache.get(chunkKey, revision);
                if (cached != null) {
                    capture.snapshots.add(cached);
                    capture.nextSource++;
                    if (System.nanoTime() >= deadline) {
                        break;
                    }
                    continue;
                }
                capture.activeChunkCapture = StagingLightEngine.beginChunkCapture(level, chunk);
            }
            boolean complete = false;
            do {
                complete = capture.activeChunkCapture.advance();
            } while (!complete && System.nanoTime() < deadline);
            if (!complete) {
                return;
            }
            StagingLightEngine.ChunkSnapshot snapshot = capture.activeChunkCapture.finish();
            capture.activeChunkCapture = null;
            state.snapshotCache.put(chunkKey, revision, snapshot, snapshot.estimatedBytes());
            capture.snapshots.add(snapshot);
            capture.nextSource++;
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        if (capture.nextSource < capture.sourceColumns.size()) {
            return;
        }
        if (!capture.revisions.validate(state).isCurrent()) {
            state.staleCaptures++;
            abortAndRequeue(state, capture.batch);
            state.capture = null;
            return;
        }

        LevelLightEngine liveEngine = level.getChunkSource().getLightEngine();
        StagingLightEngine.Input input = new StagingLightEngine.Input(
            level.getMinY(),
            level.getHeight(),
            state.hasSkyLight,
            liveEngine.getMinLightSection(),
            liveEngine.getLightSectionCount(),
            capture.outputColumns,
            capture.snapshots
        );
        long jobId = NEXT_JOB_ID.incrementAndGet();
        StagingJob<StagingLightEngine.Input, StagingLightEngine.Result> job = new StagingJob<>(
            jobId,
            capture.revisions,
            input,
            StagingLightEngine::createComputation
        );
        StagingJobHandle handle = WorkerHolder.WORKER.submit(job);
        if (handle.state() == StagingJobHandle.State.REJECTED_CLOSED) {
            state.capture = null;
            failClosed(level, state, new IllegalStateException("Staging light worker is closed"));
            return;
        }
        if (handle.state() == StagingJobHandle.State.REJECTED_FULL) {
            state.workerBackpressure++;
            return;
        }
        state.capture = null;
        state.activeJobId = jobId;
        state.activeJob = handle;
        state.batchesSubmitted++;
        state.jobOldestQueuedAt = capture.oldestQueuedAt;
        JOB_OWNERS.put(jobId, state);
    }

    private static void drainWorkerCompletions() {
        WorkerHolder.WORKER.drainCompletions(16, completion -> {
            LevelState owner = JOB_OWNERS.remove(completion.jobId());
            if (owner != null && !owner.cleared) {
                acceptCompletion(owner, completion);
            }
        });
        long dropped = WorkerHolder.WORKER.droppedCompletions();
        if (dropped > observedDroppedCompletions) {
            observedDroppedCompletions = dropped;
            for (Map.Entry<ClientLevel, LevelState> entry : LEVEL_STATES.entrySet()) {
                if (entry.getValue().activeJobId != 0L) {
                    failClosed(
                        entry.getKey(),
                        entry.getValue(),
                        new IllegalStateException("Staging completion queue overflowed")
                    );
                }
            }
        }
    }

    private static void acceptCompletion(
        LevelState state,
        StagingCompletion<StagingLightEngine.Result> completion
    ) {
        if (state.activeJobId != completion.jobId()) {
            return;
        }
        state.activeJobId = 0L;
        state.activeJob = null;
        FrozenBatch batch = state.activeBatch;
        if (batch == null) {
            return;
        }
        if (completion.kind() == StagingCompletion.Kind.CANCELLED) {
            abortAndRequeue(state, batch);
            return;
        }
        if (completion.kind() == StagingCompletion.Kind.FAILED) {
            failClosed(state.level, state, Objects.requireNonNull(completion.failure()));
            return;
        }
        StagingRevisionSnapshot.Validation validation = completion.validate(state);
        if (!validation.isCurrent()) {
            state.staleResults++;
            abortAndRequeue(state, batch);
            return;
        }
        StagingLightEngine.Result result = Objects.requireNonNull(completion.result());
        state.commitPreparation = CommitPreparation.create(
            state.level,
            state,
            batch,
            completion.revisions(),
            result,
            state.jobOldestQueuedAt
        );
    }

    private static void advanceCommitPreparation(ClientLevel level, LevelState state) {
        CommitPreparation preparation = Objects.requireNonNull(state.commitPreparation);
        if (!preparation.revisions.validate(state).isCurrent()) {
            state.staleResults++;
            state.commitPreparation = null;
            abortAndRequeue(state, preparation.batch);
            return;
        }
        PendingCommit commit = preparation.advance(level, state);
        if (commit != null) {
            state.commitPreparation = null;
            state.pendingCommit = commit;
        }
    }

    private static void abortAndRequeue(LevelState state, FrozenBatch batch) {
        state.scheduler.abortActive(batch.id());
        state.activeBatch = null;
        long now = System.nanoTime();
        for (CenterRequest center : batch.centers()) {
            if (!isLoaded(state.level, center.centerKey())) {
                continue;
            }
            long generation = state.revisions.getOrDefault(center.centerKey(), center.generation());
            offerCenter(state, center.centerKey(), generation, now);
        }
    }

    private static void supersedePendingCommit(ClientLevel level, LevelState state, PendingCommit commit) {
        if (state.pendingCommit != commit || state.activeBatch == null || state.activeBatch.id() != commit.batchId) {
            failClosed(level, state, new IllegalStateException("Stale publication no longer owns the active batch"));
            return;
        }

        LinkedHashSet<Long> releaseEpochs = new LinkedHashSet<>();
        LinkedHashSet<Long> repairColumns = new LinkedHashSet<>();
        for (Map.Entry<Long, Long> entry : commit.openedOverlayEpochs.entrySet()) {
            long chunkKey = entry.getKey();
            long overlayEpoch = entry.getValue();
            if (
                commit.touchedColumns.contains(chunkKey)
                    && isLoaded(level, chunkKey)
                    && !state.isColumnProtected(chunkKey)
            ) {
                state.publicationGuards.add(chunkKey, overlayEpoch);
                state.guardPromotions++;
                repairColumns.add(chunkKey);
            } else {
                releaseEpochs.add(overlayEpoch);
            }
        }
        for (long chunkKey : commit.touchedColumns) {
            if (!isLoaded(level, chunkKey)) {
                continue;
            }
            if (!state.isColumnProtected(chunkKey)) {
                failClosed(level, state, new IllegalStateException("Partially written light column lost its guard"));
                return;
            }
            repairColumns.add(chunkKey);
        }
        if (!releaseEpochs.isEmpty()) {
            PublishedLightOverlay.ReleaseResult release = state.overlay.releaseAll(
                releaseEpochs.stream().mapToLong(Long::longValue).toArray()
            );
            if (!release.released()) {
                failClosed(level, state, new IllegalStateException("Superseded publication overlay epoch disappeared"));
                return;
            }
        }

        FrozenBatch batch = state.activeBatch;
        state.pendingCommit = null;
        state.stalePublications++;
        abortAndRequeue(state, batch);
        long now = System.nanoTime();
        for (long chunkKey : repairColumns) {
            if (isLoaded(level, chunkKey)) {
                offerCenter(state, chunkKey, state.ensureRevision(chunkKey), now);
            }
        }
    }

    private static void publishDirtyColumns(ClientLevel level, Set<Long> dirtyColumns) {
        for (long dirtyKey : dirtyColumns) {
            ChunkPos pos = new ChunkPos(dirtyKey);
            if (level.getChunkSource().getChunk(pos.x, pos.z, false) == null) {
                continue;
            }
            level.setSectionRangeDirty(
                pos.x,
                level.getMinSectionY(),
                pos.z,
                pos.x,
                level.getMaxSectionY(),
                pos.z
            );
        }
    }

    private static boolean isLoaded(ClientLevel level, long chunkKey) {
        ChunkPos pos = new ChunkPos(chunkKey);
        return level.getChunkSource().getChunk(pos.x, pos.z, false) != null;
    }

    private static boolean isLoadedWithHalo(ClientLevel level, long chunkKey, int radius) {
        ChunkPos center = new ChunkPos(chunkKey);
        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                if (level.getChunkSource().getChunk(x, z, false) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static ChunkPos playerChunk(ClientLevel level) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == level && minecraft.player != null) {
            return minecraft.player.chunkPosition();
        }
        return new ChunkPos(0, 0);
    }

    private static long oldestQueuedAt(FrozenBatch batch) {
        long oldest = Long.MAX_VALUE;
        for (CenterRequest center : batch.centers()) {
            oldest = Math.min(oldest, center.firstQueuedAt());
        }
        return oldest == Long.MAX_VALUE ? System.nanoTime() : oldest;
    }

    private static boolean isNegotiatedCurrentLevel(ClientLevel level) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == level
            && minecraft.getConnection() != null
            && ClientLightSession.isConfirmed(minecraft.getConnection().getConnection());
    }

    private static void checkWorkerHealth(ClientLevel level, LevelState state) {
        if (!WorkerHolder.WORKER.isWorkerAlive()) {
            failClosed(level, state, new IllegalStateException("Staging light worker terminated"));
        }
    }

    private static void failClosed(ClientLevel level, LevelState state, Throwable failure) {
        if (state.failed) {
            return;
        }
        state.failed = true;
        ECClientLight.LOGGER.error(
            "[ECClientLight] Local light pipeline failed; disconnecting instead of exposing incomplete lighting",
            failure
        );
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != level || minecraft.getConnection() == null) {
            return;
        }
        Connection connection = minecraft.getConnection().getConnection();
        ClientLightSession.reset(connection, "staging pipeline failure");
        connection.disconnect(Component.literal("ECClientLight staging pipeline failed; reconnect to use proxy fallback"));
    }

    private static void releaseExpiredRetainedLight(ClientLevel level, LevelState state, long nowNanos) {
        RetainedLightCache.Entry expired = state.retainedLight.pollExpired(nowNanos);
        if (expired != null) {
            releaseRetainedColumn(level.getChunkSource().getLightEngine(), expired, true);
        }
    }

    private static void releaseRetainedColumn(
        LevelLightEngine lightEngine,
        RetainedLightCache.Entry entry,
        boolean clearQueuedLayers
    ) {
        ChunkPos pos = new ChunkPos(entry.chunkKey());
        lightEngine.retainData(pos, false);
        if (!clearQueuedLayers) {
            return;
        }
        int maxSection = entry.minLightSection() + entry.lightSectionCount();
        for (int sectionY = entry.minLightSection(); sectionY < maxSection; sectionY++) {
            SectionPos sectionPos = SectionPos.of(pos, sectionY);
            lightEngine.queueSectionData(LightLayer.BLOCK, sectionPos, null);
            lightEngine.queueSectionData(LightLayer.SKY, sectionPos, null);
        }
    }

    private static void maybeLogStats(LevelState state, long nowNanos) {
        if (!ECClientLight.LOGGER.isDebugEnabled() || nowNanos < state.nextStatsNanos) {
            return;
        }
        state.nextStatsNanos = nowNanos + CACHE_STATS_INTERVAL_NANOS;
        FrozenRegionalRelightScheduler.Stats stats = state.scheduler.stats();
        ECClientLight.LOGGER.debug(
            "[ECClientLight] Staging pipeline: queued={}, activeJob={}, batches={}/{}, stale={}/{}/{}, published={}, provisionals={}/{}, guards={} ({} promotions), coveredCenters={}, block/status changes={}/{}, lightCache={}/{} bytes ({}/{} hits, {}/{} evict/reject), snapshotCache={}/{} bytes ({}/{} hits, {} evictions), overlay={}/{} bytes, source replays saved={}, client max coordinator/lightRun={}/{} us, latency avg/max={}/{} ms, backpressure={}/{}",
            state.scheduler.nextCenterCount(),
            state.activeJobId,
            state.batchesSubmitted,
            state.batchesPublished,
            state.staleCaptures,
            state.staleResults,
            state.stalePublications,
            state.publishedColumns.size(),
            state.pendingProvisionals.size(),
            state.provisionalColumns.size(),
            state.publicationGuards.size(),
            state.guardPromotions,
            state.centersSatisfiedByCoverage,
            state.blockChanges,
            state.sectionStatusChanges,
            state.retainedLight.size(),
            state.retainedLight.retainedBytes(),
            state.retainedLight.hits(),
            state.retainedLight.misses(),
            state.retainedLight.evictions(),
            state.retainedLight.rejected(),
            state.snapshotCache.size(),
            state.snapshotCache.retainedBytes(),
            state.snapshotCache.hits(),
            state.snapshotCache.misses(),
            state.snapshotCache.evictions(),
            state.overlay.retainedBytes(),
            state.overlay.maxBytes(),
            stats.sourceReplaysSaved(),
            TimeUnit.NANOSECONDS.toMicros(state.maxCoordinatorSliceNanos),
            TimeUnit.NANOSECONDS.toMicros(state.maxLightRunNanos),
            state.batchesPublished == 0L
                ? 0L
                : TimeUnit.NANOSECONDS.toMillis(state.totalPacketToPublishNanos / state.batchesPublished),
            TimeUnit.NANOSECONDS.toMillis(state.maxPacketToPublishNanos),
            state.backpressureEvents,
            state.workerBackpressure
        );
    }

    private record OverlayBinding(PublishedLightOverlay overlay, LightLayer layer) {
    }

    private record DeferredOffer(long generation, long firstQueuedAt) {
    }

    private record ProvisionalLight(long overlayEpoch, long revision) {
    }

    private record PendingProvisionalLight(PublishedLightOverlay.Capture capture, long revision) {
    }

    private static final class CaptureState {
        private final FrozenBatch batch;
        private final StagingRevisionSnapshot revisions;
        private final List<Long> outputColumns;
        private final List<Long> sourceColumns;
        private final List<StagingLightEngine.ChunkSnapshot> snapshots = new ArrayList<>();
        private final long oldestQueuedAt;
        @Nullable
        private StagingLightEngine.ChunkSnapshotCapture activeChunkCapture;
        private int nextSource;

        private CaptureState(
            FrozenBatch batch,
            StagingRevisionSnapshot revisions,
            List<Long> outputColumns,
            List<Long> sourceColumns,
            long oldestQueuedAt
        ) {
            this.batch = batch;
            this.revisions = revisions;
            this.outputColumns = List.copyOf(outputColumns);
            this.sourceColumns = List.copyOf(sourceColumns);
            this.oldestQueuedAt = oldestQueuedAt;
        }
    }

    private static final class CommitPreparation {
        private final FrozenBatch batch;
        private final long oldestQueuedAt;
        private final StagingRevisionSnapshot revisions;
        private final List<StagingLightEngine.ColumnResult> columns;
        private final Set<Long> outputColumns;
        private final Map<Long, Long> outputRevisions;
        private final Set<Long> dirtyColumns;
        private final List<Long> capturedColumns = new ArrayList<>();
        private final List<PublishedLightOverlay.Capture> captures = new ArrayList<>();
        private int captureIndex;

        private CommitPreparation(
            FrozenBatch batch,
            long oldestQueuedAt,
            StagingRevisionSnapshot revisions,
            List<StagingLightEngine.ColumnResult> columns,
            Set<Long> outputColumns,
            Map<Long, Long> outputRevisions,
            Set<Long> dirtyColumns
        ) {
            this.batch = batch;
            this.oldestQueuedAt = oldestQueuedAt;
            this.revisions = revisions;
            this.columns = List.copyOf(columns);
            this.outputColumns = Set.copyOf(outputColumns);
            this.outputRevisions = Map.copyOf(outputRevisions);
            this.dirtyColumns = Set.copyOf(dirtyColumns);
        }

        @Nullable
        private static CommitPreparation create(
            ClientLevel level,
            LevelState state,
            FrozenBatch batch,
            StagingRevisionSnapshot revisions,
            StagingLightEngine.Result result,
            long oldestQueuedAt
        ) {
            List<StagingLightEngine.ColumnResult> columns = new ArrayList<>();
            LinkedHashSet<Long> output = new LinkedHashSet<>();
            LinkedHashMap<Long, Long> outputRevisions = new LinkedHashMap<>();
            LinkedHashSet<Long> dirty = new LinkedHashSet<>();
            for (StagingLightEngine.ColumnResult column : result.columns()) {
                if (!isLoaded(level, column.chunkKey())) {
                    continue;
                }
                Long revision = revisions.chunkRevisions().get(column.chunkKey());
                if (revision == null) {
                    failClosed(level, state, new IllegalStateException("Staging result lacks an output revision"));
                    return null;
                }
                columns.add(column);
                output.add(column.chunkKey());
                outputRevisions.put(column.chunkKey(), revision);
                ChunkPos pos = new ChunkPos(column.chunkKey());
                for (long dirtyKey : RelightTargets.square(pos.x, pos.z, 1)) {
                    dirty.add(dirtyKey);
                }
            }
            if (columns.isEmpty()) {
                state.scheduler.completeActive(batch.id());
                state.activeBatch = null;
                return null;
            }
            return new CommitPreparation(
                batch,
                oldestQueuedAt,
                revisions,
                columns,
                output,
                outputRevisions,
                dirty
            );
        }

        @Nullable
        private PendingCommit advance(ClientLevel level, LevelState state) {
            LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
            long deadline = System.nanoTime() + PUBLISH_BUDGET_NANOS;
            while (this.captureIndex < this.columns.size()) {
                StagingLightEngine.ColumnResult column = this.columns.get(this.captureIndex++);
                if (!state.isColumnProtected(column.chunkKey())) {
                    PublishedLightOverlay.Capture capture = PublishedLightOverlay.newCapture(
                        lightEngine.getLightSectionCount()
                    );
                    ChunkPos pos = new ChunkPos(column.chunkKey());
                    for (int offset = 0; offset < lightEngine.getLightSectionCount(); offset++) {
                        SectionPos sectionPos = SectionPos.of(pos, lightEngine.getMinLightSection() + offset);
                        DataLayer block = visibleDataLayer(lightEngine, LightLayer.BLOCK, sectionPos.asLong());
                        DataLayer sky = state.hasSkyLight
                            ? visibleDataLayer(lightEngine, LightLayer.SKY, sectionPos.asLong())
                            : null;
                        capture.put(sectionPos.asLong(), block, sky);
                    }
                    this.capturedColumns.add(column.chunkKey());
                    this.captures.add(capture);
                }
                if (System.nanoTime() >= deadline) {
                    return null;
                }
            }

            LinkedHashMap<Long, Long> openedOverlayEpochs = new LinkedHashMap<>();
            if (!this.captures.isEmpty()) {
                long firstOverlayEpoch = state.nextOverlayEpoch;
                long nextOverlayEpoch;
                try {
                    nextOverlayEpoch = Math.addExact(firstOverlayEpoch, this.captures.size());
                } catch (ArithmeticException failure) {
                    failClosed(level, state, new IllegalStateException("Published light overlay epoch overflow", failure));
                    return null;
                }
                PublishedLightOverlay.OpenResult opened = state.overlay.tryOpenBatch(firstOverlayEpoch, this.captures);
                if (!opened.accepted()) {
                    failClosed(
                        level,
                        state,
                        new IllegalStateException(
                            "Published light overlay rejected column batch at epoch "
                                + firstOverlayEpoch
                                + ": "
                                + opened.rejection()
                        )
                    );
                    return null;
                }
                for (int index = 0; index < this.capturedColumns.size(); index++) {
                    openedOverlayEpochs.put(this.capturedColumns.get(index), firstOverlayEpoch + index);
                }
                state.nextOverlayEpoch = nextOverlayEpoch;
            }
            return new PendingCommit(
                this.batch.id(),
                this.oldestQueuedAt,
                this.revisions,
                this.columns,
                this.outputColumns,
                this.outputRevisions,
                openedOverlayEpochs,
                this.dirtyColumns
            );
        }
    }

    private static final class PendingCommit {
        private final long batchId;
        private final long oldestQueuedAt;
        private final StagingRevisionSnapshot revisions;
        private final List<StagingLightEngine.ColumnResult> columns;
        private final Set<Long> outputColumns;
        private final Map<Long, Long> outputRevisions;
        private final Map<Long, Long> openedOverlayEpochs;
        private final Set<Long> dirtyColumns;
        private final Set<Long> touchedColumns = new LinkedHashSet<>();
        private int columnIndex;
        private boolean queuedAll;

        private PendingCommit(
            long batchId,
            long oldestQueuedAt,
            StagingRevisionSnapshot revisions,
            List<StagingLightEngine.ColumnResult> columns,
            Set<Long> outputColumns,
            Map<Long, Long> outputRevisions,
            Map<Long, Long> openedOverlayEpochs,
            Set<Long> dirtyColumns
        ) {
            this.batchId = batchId;
            this.oldestQueuedAt = oldestQueuedAt;
            this.revisions = revisions;
            this.columns = List.copyOf(columns);
            this.outputColumns = Set.copyOf(outputColumns);
            this.outputRevisions = Map.copyOf(outputRevisions);
            this.openedOverlayEpochs = Map.copyOf(openedOverlayEpochs);
            this.dirtyColumns = Set.copyOf(dirtyColumns);
        }

        private QueueStatus queueBounded(ClientLevel level, LevelState state) {
            if (this.queuedAll) {
                return QueueStatus.ACTIVE;
            }
            if (!this.revisions.validate(state).isCurrent()) {
                return QueueStatus.STALE;
            }
            LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
            long deadline = System.nanoTime() + PUBLISH_BUDGET_NANOS;
            int queuedLayers = 0;
            while (this.columnIndex < this.columns.size()) {
                StagingLightEngine.ColumnResult column = this.columns.get(this.columnIndex);
                ChunkPos pos = new ChunkPos(column.chunkKey());
                LevelChunk liveChunk = level.getChunkSource().getChunk(pos.x, pos.z, false);
                long expectedRevision = this.outputRevisions.getOrDefault(
                    column.chunkKey(),
                    StagingRevisionSnapshot.MISSING_REVISION
                );
                if (liveChunk == null || state.chunkRevision(column.chunkKey()) != expectedRevision) {
                    return QueueStatus.STALE;
                }
                int layerCount = column.blockLayers().size();
                int layersForColumn = layerCount * (column.skyLayers() == null ? 1 : 2);
                if (
                    queuedLayers > 0
                        && (
                            queuedLayers + layersForColumn > MAX_PUBLISHED_LAYERS_PER_FRAME
                                || System.nanoTime() >= deadline
                        )
                ) {
                    return QueueStatus.ACTIVE;
                }

                if (column.emptySections().size() != liveChunk.getSectionsCount()) {
                    failClosed(level, state, new IllegalStateException("Staging section topology height changed"));
                    return QueueStatus.FAILED;
                }
                this.touchedColumns.add(column.chunkKey());
                state.installingFinalSectionStatus = true;
                try {
                    for (int sectionIndex = 0; sectionIndex < column.emptySections().size(); sectionIndex++) {
                        lightEngine.updateSectionStatus(
                            SectionPos.of(pos, level.getMinSectionY() + sectionIndex),
                            column.emptySections().get(sectionIndex)
                        );
                    }
                } finally {
                    state.installingFinalSectionStatus = false;
                }

                for (int sectionIndex = 0; sectionIndex < layerCount; sectionIndex++) {
                    SectionPos sectionPos = SectionPos.of(
                        pos,
                        lightEngine.getMinLightSection() + sectionIndex
                    );
                    lightEngine.queueSectionData(
                        LightLayer.BLOCK,
                        sectionPos,
                        column.blockLayers().get(sectionIndex)
                    );
                    if (column.skyLayers() != null) {
                        lightEngine.queueSectionData(
                            LightLayer.SKY,
                            sectionPos,
                            column.skyLayers().get(sectionIndex)
                        );
                    }
                }
                queuedLayers += layersForColumn;
                this.advanceColumn();
                if (queuedLayers >= MAX_PUBLISHED_LAYERS_PER_FRAME || System.nanoTime() >= deadline) {
                    return QueueStatus.ACTIVE;
                }
            }
            this.queuedAll = true;
            return QueueStatus.ACTIVE;
        }

        private void advanceColumn() {
            this.columnIndex++;
        }

        private enum QueueStatus {
            ACTIVE,
            STALE,
            FAILED
        }
    }

    @Nullable
    private static DataLayer visibleDataLayer(LevelLightEngine lightEngine, LightLayer layer, long sectionKey) {
        if (!(lightEngine.getLayerListener(layer) instanceof LightEngine<?, ?> engine)) {
            return null;
        }
        var storage = ((LightEngineStorageAccessor)(Object)engine).ecclientlight$getStorage();
        return ((LayerLightSectionStorageInvoker)(Object)storage).ecclientlight$getDataLayer(sectionKey, false);
    }

    private static final class LevelState implements StagingRevisionSnapshot.CurrentRevisions {
        private final ClientLevel level;
        private final long levelEpoch = NEXT_LEVEL_EPOCH.incrementAndGet();
        private final boolean hasSkyLight;
        private final FrozenRegionalRelightScheduler scheduler = new FrozenRegionalRelightScheduler(BATCH_LIMITS);
        private final PublishedLightOverlay overlay;
        private final RetainedLightCache retainedLight = new RetainedLightCache(
            MAX_RETAINED_LIGHT_CHUNKS,
            MAX_RETAINED_LIGHT_BYTES,
            RETAINED_LIGHT_TTL_NANOS
        );
        private final RevisionedSnapshotCache<StagingLightEngine.ChunkSnapshot> snapshotCache =
            new RevisionedSnapshotCache<>(MAX_SNAPSHOT_CACHE_CHUNKS, MAX_SNAPSHOT_CACHE_BYTES);
        private final Map<Long, Long> revisions = new HashMap<>();
        private final Set<Long> publishedColumns = new HashSet<>();
        private final Map<Long, ProvisionalLight> provisionalColumns = new HashMap<>();
        private final LinkedHashMap<Long, PendingProvisionalLight> pendingProvisionals = new LinkedHashMap<>();
        private final PublicationGuardSet publicationGuards = new PublicationGuardSet();
        private final LinkedHashMap<Long, DeferredOffer> deferredOffers = new LinkedHashMap<>();
        @Nullable
        private FrozenBatch activeBatch;
        @Nullable
        private CaptureState capture;
        @Nullable
        private CommitPreparation commitPreparation;
        @Nullable
        private PendingCommit pendingCommit;
        @Nullable
        private StagingJobHandle activeJob;
        private long activeJobId;
        private long generation;
        private long nextOverlayEpoch = 1L;
        private long jobOldestQueuedAt;
        private long nextStatsNanos = System.nanoTime() + CACHE_STATS_INTERVAL_NANOS;
        private long placeholderPublications;
        private long cachedRestores;
        private long blockChanges;
        private long sectionStatusChanges;
        private long centersSatisfiedByCoverage;
        private long batchesSubmitted;
        private long batchesPublished;
        private long staleCaptures;
        private long staleResults;
        private long stalePublications;
        private long guardPromotions;
        private long backpressureEvents;
        private long workerBackpressure;
        private long totalPacketToPublishNanos;
        private long maxPacketToPublishNanos;
        private long maxCoordinatorSliceNanos;
        private long lightRunStartedNanos;
        private long maxLightRunNanos;
        private boolean failed;
        private boolean cleared;
        private boolean installingFinalSectionStatus;

        private LevelState(ClientLevel level) {
            this.level = level;
            this.hasSkyLight = level.dimensionType().hasSkyLight();
            this.overlay = new PublishedLightOverlay(MAX_OVERLAY_BYTES, this.hasSkyLight);
        }

        private long bumpRevision(long chunkKey) {
            long revision = this.nextGeneration();
            this.revisions.put(chunkKey, revision);
            this.snapshotCache.invalidate(chunkKey);
            ProvisionalLight provisional = this.provisionalColumns.get(chunkKey);
            if (provisional != null) {
                this.provisionalColumns.put(chunkKey, new ProvisionalLight(provisional.overlayEpoch, revision));
            }
            PendingProvisionalLight pending = this.pendingProvisionals.get(chunkKey);
            if (pending != null) {
                this.pendingProvisionals.put(chunkKey, new PendingProvisionalLight(pending.capture, revision));
            }
            return revision;
        }

        private long ensureRevision(long chunkKey) {
            Long revision = this.revisions.get(chunkKey);
            if (revision != null) {
                return revision;
            }
            long created = this.nextGeneration();
            this.revisions.put(chunkKey, created);
            return created;
        }

        private void forgetRevision(long chunkKey) {
            this.revisions.remove(chunkKey);
            this.snapshotCache.invalidate(chunkKey);
        }

        private long nextGeneration() {
            return ++this.generation;
        }

        private boolean isColumnPublishing(long chunkKey) {
            return this.publicationGuards.contains(chunkKey)
                || (this.pendingCommit != null && this.pendingCommit.outputColumns.contains(chunkKey));
        }

        private boolean isColumnProtected(long chunkKey) {
            return this.provisionalColumns.containsKey(chunkKey)
                || this.pendingProvisionals.containsKey(chunkKey)
                || this.publicationGuards.contains(chunkKey);
        }

        @Override
        public long levelEpoch() {
            return this.levelEpoch;
        }

        @Override
        public long chunkRevision(long chunkKey) {
            return this.revisions.getOrDefault(chunkKey, StagingRevisionSnapshot.MISSING_REVISION);
        }

        private void clear() {
            this.cleared = true;
            if (this.activeJob != null) {
                this.activeJob.requestCancellation();
            }
            if (this.activeJobId != 0L) {
                JOB_OWNERS.remove(this.activeJobId);
            }
            this.scheduler.clear();
            this.overlay.clear();
            this.retainedLight.clear();
            this.snapshotCache.clear();
            this.revisions.clear();
            this.publishedColumns.clear();
            this.provisionalColumns.clear();
            this.pendingProvisionals.clear();
            this.publicationGuards.clear();
            this.deferredOffers.clear();
            this.capture = null;
            this.commitPreparation = null;
            this.pendingCommit = null;
            this.activeBatch = null;
            this.activeJob = null;
            this.activeJobId = 0L;
        }
    }

    private static final class WorkerHolder {
        private static final StagingWorker<StagingLightEngine.Input, StagingLightEngine.Result> WORKER =
            new StagingWorker<>(new StagingWorker.Config(
                2,
                4,
                TimeUnit.MILLISECONDS.toNanos(4L),
                TimeUnit.MICROSECONDS.toNanos(250L),
                "ECClientLight Staging",
                Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1)
            ));
    }
}
