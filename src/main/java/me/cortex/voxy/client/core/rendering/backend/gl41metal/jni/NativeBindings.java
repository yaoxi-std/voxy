package me.cortex.voxy.client.core.rendering.backend.gl41metal.jni;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import me.cortex.voxy.common.Logger;

public final class NativeBindings {
  private static final String RESOURCE_PATH = "/natives/macos/libvoxy_gl41metal.dylib";
  private static final String SHADER_RESOURCE_PATH =
      "/natives/macos/gl41metal/voxy_gl41metal.metallib";
  private static final String DEV_PATH = "build/gl41metal-native/libvoxy_gl41metal.dylib";
  private static final String DEV_SHADER_PATH = "build/gl41metal-shaders/voxy_gl41metal.metallib";
  private static final boolean LOADED;
  private static final String LOAD_FAILURE;
  private static final Path SHADER_LIBRARY_PATH;

  static {
    String failure = null;
    boolean loaded = false;
    Path shaderLibraryPath = null;
    try {
      loadLibrary();
      shaderLibraryPath = resolveShaderLibrary();
      loaded = true;
    } catch (Throwable e) {
      failure = e.getMessage();
    }
    LOADED = loaded;
    LOAD_FAILURE = failure;
    SHADER_LIBRARY_PATH = shaderLibraryPath;
  }

  private NativeBindings() {}

  public static boolean isLoaded() {
    return LOADED;
  }

  public static String loadFailure() {
    return LOAD_FAILURE == null ? "none" : LOAD_FAILURE;
  }

  private static void loadLibrary() throws IOException {
    try (InputStream stream = NativeBindings.class.getResourceAsStream(RESOURCE_PATH)) {
      if (stream != null) {
        Path extracted = Files.createTempFile("voxy_gl41metal", ".dylib");
        Files.copy(stream, extracted, StandardCopyOption.REPLACE_EXISTING);
        extracted.toFile().deleteOnExit();
        System.load(extracted.toAbsolutePath().toString());
        Logger.info("Loaded Voxy GL41Metal native library from bundled resource");
        return;
      }
    }

    Path devLibrary = Path.of(DEV_PATH);
    if (Files.exists(devLibrary)) {
      System.load(devLibrary.toAbsolutePath().toString());
      Logger.info("Loaded Voxy GL41Metal native library from " + devLibrary);
      return;
    }

    throw new IOException("missing " + RESOURCE_PATH + " and " + DEV_PATH);
  }

  private static Path resolveShaderLibrary() throws IOException {
    try (InputStream stream = NativeBindings.class.getResourceAsStream(SHADER_RESOURCE_PATH)) {
      if (stream != null) {
        Path extracted = Files.createTempFile("voxy_gl41metal", ".metallib");
        Files.copy(stream, extracted, StandardCopyOption.REPLACE_EXISTING);
        extracted.toFile().deleteOnExit();
        Logger.info("Loaded Voxy GL41Metal shader library from bundled resource");
        return extracted.toAbsolutePath();
      }
    }

    Path devLibrary = Path.of(DEV_SHADER_PATH);
    if (Files.exists(devLibrary)) {
      Logger.info("Loaded Voxy GL41Metal shader library from " + devLibrary);
      return devLibrary.toAbsolutePath();
    }

    throw new IOException("missing " + SHADER_RESOURCE_PATH + " and " + DEV_SHADER_PATH);
  }

  public static native String getUnsupportedReason();

  public static native long createContext(int slotCount, String shaderLibraryPath);

  public static long createContext(int slotCount) {
    if (SHADER_LIBRARY_PATH == null) {
      throw new IllegalStateException("GL41Metal shader library was not loaded");
    }
    return createContext(slotCount, SHADER_LIBRARY_PATH.toString());
  }

  public static native void destroyContext(long handle);

  public static native String getDeviceName(long handle);

  public static native int acquireFreeSlot(long handle);

  public static native void submitTraversal(
      long handle,
      int slot,
      long frameId,
      double cameraX,
      double cameraY,
      double cameraZ,
      long traversalMvpAddress,
      long drawMvpAddress,
      float subDivisionSize,
      float earthRadius,
      float nearExclusionRadius,
      float renderDistanceSquared,
      int viewportWidth,
      int viewportHeight);

  // countersAddress points at two uint64 values:
  // [0]=overflow range count,
  // [1]=work items whose traversal-time section snapshot no longer matches current metadata.
  public static native int buildOpaqueRanges(
      long handle,
      int slot,
      long countsAddress,
      long baseVerticesAddress,
      int capacity,
      long countersAddress);

  // countersAddress points at one uint64 value: [0]=overflow range count.
  public static native int buildTranslucentRanges(
      long handle,
      int slot,
      long countsAddress,
      long baseVerticesAddress,
      int capacity,
      long countersAddress);

  public static native int waitCurrent(long handle, int currentSlot, int timeoutMs);

  // Whether traversal emitted any translucent work for this slot.
  public static native boolean isSlotTranslucentValid(long handle, int slot);

  public static native void discardCurrentSlot(long handle, int slot);

  public static native void releaseSampledSlot(long handle, int slot);

  public static native void createTerrainResources(
      long handle,
      int maxSections,
      int maxNodes,
      int maxTraversalQueue,
      int maxTraversalRequests,
      int maxWorklistItems,
      int maxOpaqueRangeCommands);

  public static native void clearTerrainResources(long handle);

  public static native void removeSection(long handle, int sectionId);

  public static native void uploadNode(long handle, int nodeId, long nodeAddress);

  public static native void uploadSectionMetadata(long handle, int sectionId, long metadataAddress);

  public static native void addTopNode(long handle, int nodeId);

  public static native void removeTopNode(long handle, int nodeId);

  public static native boolean canUpdateTerrain(long handle);

  public static native void setGeometryBudget(
      long handle, long usedBytes, long capacityBytes, boolean pressure, int requestBudget);

  public static native long[] pollEvictionCandidates(long handle);

  public static native long[] pollTraversalRequests(long handle);

  public static native void clearTraversalWorklist(long handle);

  public static native long[] getTerrainStats(long handle);
}
