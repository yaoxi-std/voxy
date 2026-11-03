package me.cortex.voxy.client.core.rendering.hierarchical;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.concurrent.ConcurrentHashMap;
import me.cortex.voxy.client.core.rendering.SectionUpdateRouter;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.GeometryResultQueue;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import org.lwjgl.system.MemoryUtil;

/**
 * CPU-side GL46 hierarchy/readiness producer for non-GL consumers.
 *
 * <p>This class deliberately reuses {@link NodeManager} and {@link BasicAsyncGeometryManager} so
 * Metal receives the same packed node, section metadata, and geometry ABI as GL46 traversal, while
 * keeping GL compute/SSBO upload machinery out of GL41Metal.
 */
public final class CpuNodeSyncHost implements AutoCloseable {
  public interface Sink {
    void uploadNode(int nodeId, long nodeAddress);

    void uploadSectionMetadata(int sectionId, long metadataAddress);

    void uploadGeometry(int geometryElementOffset, long geometryAddress, long geometryBytes);

    void removeSection(int sectionId);

    void addTopNode(int nodeId);

    void removeTopNode(int nodeId);
  }

  private static final int MAX_TOP_LEVEL_UPDATES_PER_FRAME = 512;
  private static final int MAX_CHILD_UPDATES_PER_FRAME = 512;
  private static final int MAX_GEOMETRY_UPDATES_PER_FRAME = 384;
  private final SectionUpdateRouter router = new SectionUpdateRouter();
  private final BasicAsyncGeometryManager geometryManager;
  private final NodeManager manager;
  private final LongLinkedOpenHashSet topLevelAdds = new LongLinkedOpenHashSet();
  private final LongLinkedOpenHashSet topLevelRemoves = new LongLinkedOpenHashSet();
  private final ConcurrentHashMap<Long, Byte> childUpdateQueue = new ConcurrentHashMap<>();
  private final GeometryResultQueue geometryUpdateQueue;
  private final RenderGenerationService renderService;
  private final long geometryCapacity;
  private boolean geometryStalled;
  private long lastBudgetLog;
  private long capacityDeferrals;
  private long collapsedSubtrees;
  private final IntOpenHashSet topNodeAdds = new IntOpenHashSet();
  private final IntOpenHashSet topNodeRemoves = new IntOpenHashSet();
  private volatile boolean closed;
  private long nativeRequestsProcessed;
  private long nodeUploads;
  private long metadataUploads;
  private long geometryUploads;
  private long topNodeAddCount;
  private long topNodeRemoveCount;

  public CpuNodeSyncHost(
      int maxNodeCount,
      int maxSectionCount,
      long geometryCapacityBytes,
      RenderGenerationService renderService) {
    this.geometryManager = new BasicAsyncGeometryManager(maxSectionCount, geometryCapacityBytes);
    this.geometryCapacity = geometryCapacityBytes;
    this.renderService = renderService;
    this.geometryUpdateQueue =
        new GeometryResultQueue(
            Math.max(16L << 20, Math.min(64L << 20, geometryCapacityBytes / 4)));
    renderService.setWorkLimiter(this.geometryUpdateQueue::canBuild);
    renderService.setTaskValidity(
        p -> !this.closed && (this.router.get(p) & WorldEngine.UPDATE_TYPE_BLOCK_BIT) != 0);
    this.router.setCallbacks(
        renderService::enqueueTask, renderService::enqueueTask, this::submitChildChange);
    renderService.setResultConsumer(this::submitGeometryResult);
    this.manager = new NodeManager(maxNodeCount, this.geometryManager, this.router);
    this.manager.setTLNCallbacks(
        nodeId -> this.topNodeAdds.add(nodeId),
        nodeId -> {
          this.topNodeAdds.remove(nodeId);
          this.topNodeRemoves.add(nodeId);
        });
  }

  public void addTopLevel(long sectionPos) {
    this.topLevelRemoves.remove(sectionPos);
    if (!this.manager.hasTopLevelNode(sectionPos)) this.topLevelAdds.add(sectionPos);
  }

  public void removeTopLevel(long sectionPos) {
    this.topLevelAdds.remove(sectionPos);
    if (this.manager.hasTopLevelNode(sectionPos)) this.topLevelRemoves.add(sectionPos);
  }

  public void worldEvent(WorldSection section, int flags, int neighborMask) {
    this.router.forwardEvent(section, flags);
    if ((neighborMask & 0b000001) != 0) {
      this.router.triggerRemesh(
          WorldEngine.getWorldSectionId(section.lvl, section.x, section.y - 1, section.z));
    }
    if ((neighborMask & 0b000010) != 0) {
      this.router.triggerRemesh(
          WorldEngine.getWorldSectionId(section.lvl, section.x, section.y + 1, section.z));
    }
    if ((neighborMask & 0b000100) != 0) {
      this.router.triggerRemesh(
          WorldEngine.getWorldSectionId(section.lvl, section.x - 1, section.y, section.z));
    }
    if ((neighborMask & 0b001000) != 0) {
      this.router.triggerRemesh(
          WorldEngine.getWorldSectionId(section.lvl, section.x + 1, section.y, section.z));
    }
    if ((neighborMask & 0b010000) != 0) {
      this.router.triggerRemesh(
          WorldEngine.getWorldSectionId(section.lvl, section.x, section.y, section.z - 1));
    }
    if ((neighborMask & 0b100000) != 0) {
      this.router.triggerRemesh(
          WorldEngine.getWorldSectionId(section.lvl, section.x, section.y, section.z + 1));
    }
  }

  public void drain(long[] metalRequests, long[] evictions, Sink sink) {
    for (long position : evictions) {
      if (this.manager.collapseNode(position)) this.collapsedSubtrees++;
    }
    this.processMetalRequests(metalRequests);
    this.processTopLevelUpdates();
    this.processChildUpdates();
    this.processGeometryUpdates();
    this.flushSync(sink);
    if (Boolean.getBoolean("voxy.gl41metal.logGeometryBudget")
        && System.nanoTime() - this.lastBudgetLog > 5_000_000_000L) {
      var debug = new java.util.ArrayList<String>();
      this.addDebug(debug);
      debug.forEach(me.cortex.voxy.common.Logger::info);
      this.lastBudgetLog = System.nanoTime();
    }
  }

  public void addDebug(java.util.List<String> debug) {
    debug.add(
        "Voxy GL41Metal hierarchy sync: nodes="
            + this.manager.getCurrentMaxNodeId()
            + ", sections="
            + this.geometryManager.getSectionCount()
            + ", geometryMb="
            + (this.geometryManager.getGeometryUsedBytes() >> 20)
            + ", requests="
            + this.nativeRequestsProcessed
            + ", queuedMb="
            + (this.geometryUpdateQueue.bytes() >> 20)
            + ", pendingBuilds="
            + this.renderService.pendingTasks()
            + ", deferrals="
            + this.capacityDeferrals
            + ", collapsed="
            + this.collapsedSubtrees);
    debug.add(
        "Voxy GL41Metal hierarchy uploads: node="
            + this.nodeUploads
            + ", metadata="
            + this.metadataUploads
            + ", geometry="
            + this.geometryUploads
            + ", topAdd="
            + this.topNodeAddCount
            + ", topRemove="
            + this.topNodeRemoveCount);
  }

  private void processMetalRequests(long[] requests) {
    if (requests == null) {
      return;
    }
    for (long request : requests) {
      this.manager.processRequest(request);
      this.nativeRequestsProcessed++;
    }
  }

  private void processTopLevelUpdates() {
    for (int i = 0; i < MAX_TOP_LEVEL_UPDATES_PER_FRAME; i++) {
      if (this.topLevelRemoves.isEmpty()) break;
      long section = this.topLevelRemoves.removeFirstLong();
      this.manager.removeTopLevelNode(section);
    }
    for (int i = 0;
        i < MAX_TOP_LEVEL_UPDATES_PER_FRAME && this.renderService.pendingTasks() < 512;
        i++) {
      if (this.topLevelAdds.isEmpty()) break;
      long section = this.topLevelAdds.removeFirstLong();
      this.manager.insertTopLevelNode(section);
    }
  }

  private void processChildUpdates() {
    var iterator = this.childUpdateQueue.keySet().iterator();
    for (int i = 0; i < MAX_CHILD_UPDATES_PER_FRAME && iterator.hasNext(); i++) {
      long position = iterator.next();
      Byte mask = this.childUpdateQueue.remove(position);
      if (mask != null) this.manager.processChildChange(position, mask);
    }
  }

  public long geometryUsedBytes() {
    return this.geometryManager.getGeometryUsedBytes();
  }

  public boolean capacityPressure() {
    return this.geometryStalled || this.geometryUsedBytes() > this.geometryCapacity * 4 / 5;
  }

  public int requestBudget() {
    return this.capacityPressure()
        ? 0
        : Math.max(0, Math.min(64, (512 - this.renderService.pendingTasks()) / 8));
  }

  private void processGeometryUpdates() {
    this.geometryStalled = false;
    long uploaded = 0;
    for (int i = 0; i < MAX_GEOMETRY_UPDATES_PER_FRAME && uploaded < (2L << 20); i++) {
      BuiltSection section =
          this.geometryUpdateQueue.process(this.manager::tryProcessGeometryResult);
      if (section == null) {
        this.geometryStalled = !this.geometryUpdateQueue.isEmpty();
        if (this.geometryStalled) this.capacityDeferrals++;
        break;
      }
      if (section.geometryBuffer != null) uploaded += section.geometryBuffer.size;
    }
  }

  private void flushSync(Sink sink) {
    if (!this.topNodeRemoves.isEmpty()) {
      var iter = this.topNodeRemoves.intIterator();
      while (iter.hasNext()) {
        sink.removeTopNode(iter.nextInt());
        this.topNodeRemoveCount++;
      }
      this.topNodeRemoves.clear();
    }
    if (!this.topNodeAdds.isEmpty()) {
      var iter = this.topNodeAdds.intIterator();
      while (iter.hasNext()) {
        sink.addTopNode(iter.nextInt());
        this.topNodeAddCount++;
      }
      this.topNodeAdds.clear();
    }

    var removals = this.geometryManager.getHeapRemovals();
    if (!removals.isEmpty()) {
      // Java already returned these ranges to AllocationArena. GL buffer writes are ordered
      // after previous draws in the same context; metadata invalidation is streamed below.
      removals.clear();
    }

    var uploads = this.geometryManager.getUploads();
    if (!uploads.isEmpty()) {
      var iter = uploads.int2ObjectEntrySet().fastIterator();
      while (iter.hasNext()) {
        var entry = iter.next();
        MemoryBuffer geometry = entry.getValue();
        sink.uploadGeometry(entry.getIntKey(), geometry.address, geometry.size);
        geometry.free();
        this.geometryUploads++;
      }
      uploads.clear();
    }

    var metadataIds = this.geometryManager.getUpdateIds();
    if (!metadataIds.isEmpty()) {
      MemoryBuffer metadata = new MemoryBuffer(BasicAsyncGeometryManager.SECTION_METADATA_SIZE);
      try {
        var iter = metadataIds.intIterator();
        while (iter.hasNext()) {
          int sectionId = iter.nextInt();
          MemoryUtil.memSet(metadata.address, 0, metadata.size);
          this.geometryManager.writeMetadata(sectionId, metadata.address);
          sink.uploadSectionMetadata(sectionId, metadata.address);
          this.metadataUploads++;
        }
      } finally {
        metadata.free();
      }
      metadataIds.clear();
    }

    var nodeIds = this.manager.getNodeUpdates();
    if (!nodeIds.isEmpty()) {
      MemoryBuffer node = new MemoryBuffer(16);
      try {
        var iter = nodeIds.intIterator();
        while (iter.hasNext()) {
          int nodeId = iter.nextInt();
          this.manager.writeNode(nodeId, node.address);
          sink.uploadNode(nodeId, node.address);
          this.nodeUploads++;
        }
      } finally {
        node.free();
      }
      nodeIds.clear();
    }
  }

  private synchronized void submitChildChange(WorldSection section) {
    if (!this.closed) this.childUpdateQueue.put(section.key, section.getNonEmptyChildren());
  }

  private void submitGeometryResult(BuiltSection section) {
    if (this.closed) {
      section.free();
      return;
    }
    this.geometryUpdateQueue.put(section);
  }

  @Override
  public synchronized void close() {
    this.closed = true;
    this.childUpdateQueue.clear();
    this.geometryUpdateQueue.close();
    if (!this.geometryManager.getUploads().isEmpty()) {
      var iter = this.geometryManager.getUploads().values().iterator();
      while (iter.hasNext()) {
        iter.next().free();
      }
      this.geometryManager.getUploads().clear();
    }
  }
}
