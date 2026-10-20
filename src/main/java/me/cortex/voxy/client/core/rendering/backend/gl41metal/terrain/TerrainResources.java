package me.cortex.voxy.client.core.rendering.backend.gl41metal.terrain;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.Arrays;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.backend.BackendContext;
import me.cortex.voxy.client.core.rendering.backend.RenderFrameContext;
import me.cortex.voxy.client.core.rendering.backend.gl41metal.jni.NativeBindings;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierarchical.CpuNodeSyncHost;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.VoxyFlags;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;

public final class TerrainResources implements AutoCloseable {
  private static final int MAX_RESIDENT_SECTIONS =
      readInt("voxy.gl41metal.residencyMaxSections", 1 << 18, 1024, 1 << 20);
  private static final long REQUESTED_GEOMETRY_CAPACITY_BYTES =
      (long) readInt("voxy.gl41metal.geometryCapacityMb", 256, 16, 2048) * 1024L * 1024L;
  private static final int MAX_NODES =
      readInt("voxy.gl41metal.maxNodes", 1 << 21, 1024, (1 << 24) - 1);
  private static final int MAX_TRAVERSAL_QUEUE =
      readInt("voxy.gl41metal.maxTraversalQueue", 2_000_000, 1024, 8_000_000);
  private static final int MAX_TRAVERSAL_REQUESTS =
      readInt("voxy.gl41metal.maxTraversalRequests", 16_384, 1, 200_000);
  private static final int MAX_WORKLIST_ITEMS =
      readInt("voxy.gl41metal.maxWorklistItems", 400_000, 1024, 2_000_000);
  private static final int MAX_OPAQUE_RANGE_COMMANDS =
      readInt("voxy.gl41metal.drawlistMaxRanges", 1_000_000, 1024, 4_000_000);
  // Render-thread budget for draining queued block model bakes each frame. The old 100ms value
  // effectively meant "bake everything immediately", which stalls whole frames while new chunks
  // stream in. ModelBakerySubsystem.tick still guarantees a minimum of 5 bakes per frame, so a
  // small budget only spreads a large burst across a few frames instead of one giant hitch.
  private static final long MODEL_BAKE_BUDGET_NANOS =
      readInt("voxy.gl41metal.modelBakeBudgetMs", 4, 1, 100) * 1_000_000L;

  private final long geometryCapacityBytes;
  private final WorldEngine world;
  private final MaterialStore materialStore;
  private final ModelBakerySubsystem modelService;
  private final RenderGenerationService renderGen;
  private final CpuNodeSyncHost nodeSyncHost;
  private final RenderDistanceTracker renderDistanceTracker;
  private final CpuNodeSyncHost.Sink nativeSink = new NativeSyncSink();
  private DrawlistMirror drawlistMirror;
  private long nativeHandle;
  private boolean nativeResourcesCreated;
  private boolean closed;
  private TerrainStats lastStats = TerrainStats.fromNative(new long[0]);
  private Object2IntMap<BlockState> irisBlockStateMapping;

  public TerrainResources(BackendContext context) {
    long textureLimit =
        Integer.toUnsignedLong(
                org.lwjgl.opengl.GL11C.glGetInteger(
                    org.lwjgl.opengl.GL31C.GL_MAX_TEXTURE_BUFFER_SIZE))
            * Long.BYTES;
    this.geometryCapacityBytes = Math.min(REQUESTED_GEOMETRY_CAPACITY_BYTES, textureLimit);
    if (this.geometryCapacityBytes <= 0)
      throw new IllegalStateException("GL41Metal has no addressable geometry texture buffer");
    if (this.geometryCapacityBytes < REQUESTED_GEOMETRY_CAPACITY_BYTES) {
      Logger.warn(
          "GL41Metal geometry pool clamped to OpenGL texture-buffer limit: "
              + this.geometryCapacityBytes
              + " bytes");
    }
    this.world = context.world();
    this.materialStore = new MaterialStore();
    this.modelService = new ModelBakerySubsystem(this.world.getMapper(), this.materialStore);
    this.renderGen =
        new RenderGenerationService(
            this.world,
            this.modelService,
            context.serviceManager(),
            false,
            RenderGenerationService.TaskPriorityMode.COARSE_LOD_FIRST);
    this.nodeSyncHost =
        new CpuNodeSyncHost(
            MAX_NODES, MAX_RESIDENT_SECTIONS, this.geometryCapacityBytes, this.renderGen);
    Arrays.stream(this.world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
    this.world.getMapper().setBiomeCallback(this.modelService::addBiome);
    this.world.setDirtyCallback(this::onWorldChanged);

    int minSec = -4;
    int maxSec = 19;
    var level = Minecraft.getInstance().level;
    if (level != null) {
      minSec = level.getMinSection() >> 5;
      maxSec = (level.getMaxSection() - 1) >> 5;
    }
    if (VoxyFlags.IS_MINE_IN_ABYSS) {
      minSec = -8;
      maxSec = 7;
    }
    this.renderDistanceTracker =
        new RenderDistanceTracker(
            20, minSec, maxSec, this.nodeSyncHost::addTopLevel, this.nodeSyncHost::removeTopLevel);
    this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
    this.syncIrisBlockStateMapping();
    Logger.info("Created GL41Metal Metal LOD/culling worklist resources");
  }

  public void tick(RenderFrameContext frameContext, long nativeHandle) {
    this.syncIrisBlockStateMapping();
    this.ensureNativeResources(nativeHandle);
    this.renderDistanceTracker.setCenterAndProcess(frameContext.cameraX(), frameContext.cameraZ());
    this.modelService.tick(MODEL_BAKE_BUDGET_NANOS);
    this.materialStore.drainUploads();
    this.nodeSyncHost.drain(
        NativeBindings.pollTraversalRequests(nativeHandle),
        NativeBindings.pollEvictionCandidates(nativeHandle),
        this.nativeSink);
    NativeBindings.setGeometryBudget(
        nativeHandle,
        this.nodeSyncHost.geometryUsedBytes(),
        this.geometryCapacityBytes,
        this.nodeSyncHost.capacityPressure(),
        this.nodeSyncHost.requestBudget());
  }

  public void onChunkTrackerReset() {
    // Sodium rebuilds its RenderSectionManager (firing this hook) on world load AND on every
    // vanilla render-distance change. GL41Metal distant residency is keyed by Voxy 32-block
    // WorldSection ids and driven entirely by WorldEngine dirty events plus the Metal request
    // queue; it is independent of the vanilla/Sodium near-scene render distance. Clearing native
    // terrain (and the Java residency tracker) here wiped all distant LOD whenever the player
    // raised vanilla render distance, and the Java side then believed everything was still
    // resident so nothing was re-streamed. Per AGENTS.md ("Do not clear Metal traversal or
    // residency state from Sodium section add/remove callbacks"), this is a no-op. World reloads
    // recreate the whole backend, which provides a genuine fresh start.
  }

  public void onSectionRenderStateChanged(long sectionPos, boolean present) {
    // Sodium reports vanilla 16-block render-section lifecycle here. GL41Metal
    // terrain residency is keyed by Voxy 32-block WorldSection ids and is driven
    // by WorldEngine dirty events plus the Metal request queue.
  }

  public void addDebugInfo(java.util.List<String> debug) {
    if (this.nativeResourcesCreated) {
      this.lastStats = TerrainStats.fromNative(NativeBindings.getTerrainStats(this.nativeHandle));
    }
    debug.add("Voxy GL41Metal terrain residency: " + this.lastStats.compact());
    debug.add(
        "Voxy GL41Metal terrain queues: models="
            + this.materialStore.pendingModelUploads()
            + ", biomes="
            + this.materialStore.pendingBiomeUploads()
            + ", bakedModels="
            + this.materialStore.uploadedModelCount()
            + ", biomeBatches="
            + this.materialStore.uploadedBiomeBatchCount()
            + ", customIdModels="
            + this.materialStore.uploadedCustomIdModelCount()
            + ", zeroCustomIdModels="
            + this.materialStore.uploadedZeroCustomIdModelCount());
    this.nodeSyncHost.addDebug(debug);
  }

  public void setAtlasMirror(MaterialStore.AtlasMirror atlasMirror) {
    this.materialStore.setAtlasMirror(atlasMirror);
  }

  public void setDrawlistMirror(DrawlistMirror drawlistMirror) {
    this.drawlistMirror = drawlistMirror;
  }

  public static int maxResidentSections() {
    return MAX_RESIDENT_SECTIONS;
  }

  public long geometryCapacityBytes() {
    return this.geometryCapacityBytes;
  }

  public static int maxOpaqueRangeCommands() {
    return MAX_OPAQUE_RANGE_COMMANDS;
  }

  private void ensureNativeResources(long handle) {
    if (this.nativeResourcesCreated && this.nativeHandle == handle) {
      return;
    }
    if (this.nativeResourcesCreated) {
      // The native handle is stable for the backend lifetime. A replacement loses traversal
      // residency, so reset Java-side counters as a defensive fallback.
      Logger.warn(
          "GL41Metal terrain native context changed unexpectedly; resetting residency stats");
      this.resetJavaResidencyState();
    }
    this.nativeHandle = handle;
    NativeBindings.createTerrainResources(
        handle,
        MAX_RESIDENT_SECTIONS,
        MAX_NODES,
        MAX_TRAVERSAL_QUEUE,
        MAX_TRAVERSAL_REQUESTS,
        MAX_WORKLIST_ITEMS,
        MAX_OPAQUE_RANGE_COMMANDS);
    this.nativeResourcesCreated = true;
    Logger.info(
        "GL41Metal terrain native resources initialized: maxSections="
            + MAX_RESIDENT_SECTIONS
            + ", geometryCapacity="
            + this.geometryCapacityBytes
            + ", maxNodes="
            + MAX_NODES
            + ", traversalQueue="
            + MAX_TRAVERSAL_QUEUE
            + ", traversalRequests="
            + MAX_TRAVERSAL_REQUESTS
            + ", worklistItems="
            + MAX_WORKLIST_ITEMS
            + ", opaqueRangeCommands="
            + MAX_OPAQUE_RANGE_COMMANDS);
  }

  private void resetJavaResidencyState() {
    this.lastStats = TerrainStats.fromNative(new long[0]);
  }

  private void onWorldChanged(WorldSection section, int updateFlags, int neighborMask) {
    this.nodeSyncHost.worldEvent(section, updateFlags, neighborMask);
  }

  private void syncIrisBlockStateMapping() {
    Object2IntMap<BlockState> mapping = IrisUtil.irisBlockStateIds();
    if (mapping == this.irisBlockStateMapping) {
      return;
    }
    this.modelService.factory.setCustomBlockStateMapping(mapping);
    if (mapping == null) {
      if (this.irisBlockStateMapping != null) {
        Logger.info("GL41Metal cleared Iris material block-state mapping");
      }
      this.irisBlockStateMapping = null;
      return;
    }
    if (this.irisBlockStateMapping == null) {
      Logger.info("GL41Metal installed Iris material block-state mapping for model baking");
    } else {
      Logger.warn(
          "GL41Metal Iris material block-state mapping changed; newly baked models will use the "
              + "new custom ids. Reload the world if already-baked distant materials look stale.");
    }
    this.irisBlockStateMapping = mapping;
  }

  public void setRenderDistance(float renderDistance) {
    // Reference passes the raw section render distance (no +1 ring, unlike Gl46RenderBackend);
    // ceil only converts this branch's fractional RD to the tracker's int domain.
    this.renderDistanceTracker.setRenderDistance((int) Math.ceil(renderDistance));
  }

  @Override
  public void close() {
    if (this.closed) {
      return;
    }
    this.closed = true;
    this.world.setDirtyCallback(null);
    this.world.getMapper().setBiomeCallback(null);
    this.nodeSyncHost.close();
    try {
      this.renderGen.shutdown();
    } catch (Exception e) {
      Logger.error("Error shutting down GL41Metal render generation service", e);
    }
    try {
      this.modelService.shutdown();
    } catch (Exception e) {
      Logger.error("Error shutting down GL41Metal model service", e);
    }
    this.materialStore.close();
  }

  private static int readInt(String key, int fallback, int min, int max) {
    String value = System.getProperty(key);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      int parsed = Integer.parseInt(value);
      return Math.max(min, Math.min(max, parsed));
    } catch (NumberFormatException e) {
      Logger.warn("Invalid GL41Metal integer config " + key + "=" + value + ", using " + fallback);
      return fallback;
    }
  }

  private final class NativeSyncSink implements CpuNodeSyncHost.Sink {
    @Override
    public void uploadNode(int nodeId, long nodeAddress) {
      NativeBindings.uploadNode(TerrainResources.this.nativeHandle, nodeId, nodeAddress);
    }

    @Override
    public void uploadSectionMetadata(int sectionId, long metadataAddress) {
      NativeBindings.uploadSectionMetadata(
          TerrainResources.this.nativeHandle, sectionId, metadataAddress);
      if (TerrainResources.this.drawlistMirror != null) {
        TerrainResources.this.drawlistMirror.uploadSectionMetadata(sectionId, metadataAddress);
      }
    }

    @Override
    public void uploadGeometry(
        int geometryElementOffset, long geometryAddress, long geometryBytes) {
      if (TerrainResources.this.drawlistMirror != null) {
        TerrainResources.this.drawlistMirror.uploadGeometry(
            geometryElementOffset, geometryAddress, geometryBytes);
      }
    }

    @Override
    public void removeSection(int sectionId) {
      NativeBindings.removeSection(TerrainResources.this.nativeHandle, sectionId);
    }

    @Override
    public void addTopNode(int nodeId) {
      NativeBindings.addTopNode(TerrainResources.this.nativeHandle, nodeId);
    }

    @Override
    public void removeTopNode(int nodeId) {
      NativeBindings.removeTopNode(TerrainResources.this.nativeHandle, nodeId);
    }
  }

  public interface DrawlistMirror {
    void uploadSectionMetadata(int sectionId, long metadataAddress);

    void uploadGeometry(int geometryElementOffset, long geometryAddress, long geometryBytes);
  }
}
