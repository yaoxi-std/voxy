package me.cortex.voxy.client.core.rendering.building;

import java.util.LinkedHashMap;
import java.util.function.Predicate;

/** A byte-bounded handoff. The producer owns a result until put succeeds; close wakes producers. */
public final class GeometryResultQueue implements AutoCloseable {
  private final long capacity;
  private final LinkedHashMap<Long, BuiltSection> results = new LinkedHashMap<>();
  private long bytes;
  private boolean closed;

  public GeometryResultQueue(long capacity) {
    if (capacity <= 0) throw new IllegalArgumentException("Non-positive queue capacity");
    this.capacity = capacity;
  }

  private static long size(BuiltSection result) {
    return (result.geometryBuffer == null ? 0 : result.geometryBuffer.size)
        + (result.occupancy == null ? 0 : result.occupancy.size)
        + 128;
  }

  public synchronized boolean canBuild() {
    // RenderDataFactory's scratch geometry is 4 MiB. Leave room for a completed mesh plus
    // occupancy.
    return !this.closed && this.bytes <= this.capacity - (8L << 20);
  }

  public synchronized void put(BuiltSection result) {
    long required = size(result);
    try {
      while (!this.closed) {
        BuiltSection previous = this.results.get(result.position);
        long replaced = previous == null ? 0 : size(previous);
        if (this.bytes - replaced + required <= this.capacity) {
          this.results.put(result.position, result);
          this.bytes += required - replaced;
          if (previous != null) previous.free();
          return;
        }
        if (required > this.capacity) {
          result.free();
          throw new IllegalArgumentException("Mesh exceeds geometry result queue capacity");
        }
        this.wait();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    result.free();
  }

  /** Processes under ownership lock, letting removals pass an allocation that cannot yet fit. */
  public synchronized BuiltSection process(Predicate<BuiltSection> consumer) {
    var iterator = this.results.values().iterator();
    while (iterator.hasNext()) {
      BuiltSection result = iterator.next();
      long size = size(result);
      if (!consumer.test(result)) continue;
      iterator.remove();
      this.bytes -= size;
      this.notifyAll();
      return result;
    }
    return null;
  }

  public synchronized boolean isEmpty() {
    return this.results.isEmpty();
  }

  public synchronized BuiltSection poll() {
    if (this.results.isEmpty()) return null;
    var iterator = this.results.values().iterator();
    BuiltSection result = iterator.next();
    iterator.remove();
    this.bytes -= size(result);
    this.notifyAll();
    return result;
  }

  public synchronized long bytes() {
    return this.bytes;
  }

  @Override
  public synchronized void close() {
    this.closed = true;
    this.results.values().forEach(BuiltSection::free);
    this.results.clear();
    this.bytes = 0;
    this.notifyAll();
  }
}
