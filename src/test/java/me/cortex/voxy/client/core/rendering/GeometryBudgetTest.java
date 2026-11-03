package me.cortex.voxy.client.core.rendering;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.GeometryResultQueue;
import me.cortex.voxy.client.core.rendering.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.client.core.rendering.hierarchical.NodeManager;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;

/**
 * Runs against the real allocator, node lifecycle and native-memory queue, without a GL context.
 */
public final class GeometryBudgetTest {
  public static void main(String[] args) throws Exception {
    allocationPressure();
    replacementAtCapacity();
    nodeRecovery();
    admissionRecovery();
    boundedHandoff();
    System.out.println("Geometry budget contracts passed");
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static BuiltSection mesh(long position, int bytes, byte children) {
    return new BuiltSection(
        position,
        children,
        0,
        new MemoryBuffer(bytes).zero(),
        new int[] {0, 0, bytes / 8, bytes / 8, bytes / 8, bytes / 8, bytes / 8, bytes / 8},
        null);
  }

  private static void allocationPressure() {
    AllocationArena arena = new AllocationArena();
    arena.setLimit(1024);
    long a = arena.alloc(256);
    long b = arena.alloc(512);
    arena.alloc(256);
    require(!arena.canAllocate(128), "full arena must reject admission");
    arena.free(b);
    require(arena.canAllocate(512), "interior free range must be usable even at high water");
    require(!arena.canAllocate(640), "fragmentation must be included in admission");
    require(arena.alloc(512) == b, "free range must be reused");
    arena.free(a);
  }

  private static void replacementAtCapacity() {
    var geometry = new BasicAsyncGeometryManager(8, 1024);
    int id = geometry.uploadSection(mesh(1, 1024, (byte) 0));
    BuiltSection bigger = mesh(1, 2048, (byte) 0);
    require(!geometry.canUpload(id, bigger), "growing full allocation must defer");
    try {
      geometry.uploadReplaceSection(id, bigger);
      throw new AssertionError("oversized replacement must fail before mutation");
    } catch (IllegalStateException expected) {
      require(
          geometry.getGeometryUsedBytes() == 1024 && geometry.getSectionCount() == 1,
          "failed replacement must preserve allocation and metadata");
    } finally {
      bigger.free();
    }
    BuiltSection smaller = mesh(1, 512, (byte) 0);
    require(geometry.canUpload(id, smaller), "in-place update must work without free space");
    require(geometry.uploadReplaceSection(id, smaller) == id, "replacement must retain usable id");
    geometry.removeSection(id);
    require(geometry.getGeometryUsedBytes() == 0, "replacement allocation must remain reclaimable");
  }

  private static void nodeRecovery() {
    var geometry = new BasicAsyncGeometryManager(1024, 16 * 1024);
    var watches = new HashMap<Long, Integer>();
    SectionWatcher watcher =
        new SectionWatcher() {
          public boolean watch(long p, int flags) {
            return watches.merge(p, flags, (a, b) -> a | b) != 0;
          }

          public boolean unwatch(long p, int flags) {
            int remaining = watches.get(p) & ~flags;
            if (remaining == 0) watches.remove(p);
            else watches.put(p, remaining);
            return remaining == 0;
          }

          public int get(long p) {
            return watches.getOrDefault(p, 0);
          }
        };
    var nodes = new NodeManager(1024, geometry, watcher);
    long root = WorldEngine.getWorldSectionId(4, 0, 0, 0);
    nodes.insertTopLevelNode(root);
    nodes.processGeometryResult(mesh(root, 1024, (byte) 255));
    nodes.processRequest(root);
    for (int i = 0; i < 8; i++) {
      long child = WorldEngine.getWorldSectionId(3, (i >> 2) & 1, i & 1, (i >> 1) & 1);
      nodes.processGeometryResult(mesh(child, 1024, (byte) 255));
    }
    require(geometry.getSectionCount() == 9, "expansion must install every child");
    BuiltSection replacement = mesh(root, 16384, (byte) 255);
    require(!nodes.tryProcessGeometryResult(replacement), "oversized replacement must defer");
    require(geometry.getSectionCount() == 9, "failed replacement must preserve old mesh and ids");
    replacement.free();
    require(nodes.collapseNode(root), "coarse mesh must permit subtree eviction");
    require(geometry.getSectionCount() == 1, "collapse must free children and retain coarse mesh");
    nodes.processRequest(root);
    long child = WorldEngine.getWorldSectionId(3, 0, 0, 0);
    nodes.processGeometryResult(mesh(child, 1024, (byte) 255));
    require(nodes.collapseNode(root), "collapse must cancel partial expansion");
    nodes.processGeometryResult(mesh(child, 1024, (byte) 255));
    require(geometry.getSectionCount() == 1, "late cancelled result must not resurrect geometry");
    require(!nodes.collapseNode(child), "stale eviction candidate must be harmless");
    nodes.processRequest(root);
    for (int i = 0; i < 8; i++) {
      long p = WorldEngine.getWorldSectionId(3, (i >> 2) & 1, i & 1, (i >> 1) & 1);
      nodes.processGeometryResult(mesh(p, 1024, (byte) 255));
    }
    require(geometry.getSectionCount() == 9, "evicted subtree must be requestable again");
    nodes.removeTopLevelNode(root);
    require(
        geometry.getGeometryUsedBytes() == 0 && watches.isEmpty(), "unload must free hierarchy");
    require(geometry.getUploads().isEmpty(), "cancelled uploads must release their native buffers");
  }

  private static void admissionRecovery() {
    var geometry = new BasicAsyncGeometryManager(1, 1024);
    int old = geometry.uploadSection(mesh(1, 1024, (byte) 0));
    BuiltSection next = mesh(2, 1024, (byte) 0);
    require(!geometry.canUpload(-1, next), "section-id exhaustion must defer new geometry");
    var queue = new GeometryResultQueue(4096);
    queue.put(next);
    queue.put(BuiltSection.empty(1));
    java.util.function.Predicate<BuiltSection> consume =
        result -> {
          if (result.isEmpty()) {
            geometry.removeSection(old);
            result.free();
            return true;
          }
          if (!geometry.canUpload(-1, result)) return false;
          geometry.uploadSection(result);
          return true;
        };
    require(queue.process(consume).position == 1, "removal must pass a blocked allocation");
    require(queue.process(consume).position == 2, "released space must allow deferred upload");
    require(queue.isEmpty(), "successful recovery must drain the handoff");
    geometry.removeSection(0);
    queue.close();
  }

  private static void boundedHandoff() throws Exception {
    var queue = new GeometryResultQueue(1152);
    queue.put(mesh(1, 1024, (byte) 0));
    queue.put(mesh(1, 512, (byte) 0));
    require(queue.bytes() == 640, "same-position result must coalesce and release previous buffer");
    queue.poll().free();
    queue.put(mesh(2, 1024, (byte) 0));
    CountDownLatch started = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var producer =
          executor.submit(
              () -> {
                started.countDown();
                queue.put(mesh(3, 1024, (byte) 0));
              });
      require(started.await(5, TimeUnit.SECONDS), "producer must start");
      try {
        producer.get(100, TimeUnit.MILLISECONDS);
        throw new AssertionError("producer must respect byte capacity");
      } catch (TimeoutException expected) {
        require(queue.bytes() == 1152, "queue must remain within byte capacity");
      }
      queue.close();
      producer.get(5, TimeUnit.SECONDS);
      require(queue.bytes() == 0, "shutdown must wake producers and release queued buffers");
    } finally {
      queue.close();
    }
  }
}
