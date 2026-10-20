#include "gl41metal_internal.h"

using namespace gl41metal;

namespace gl41metal {

void clearTerrainCounters(TerrainResources* terrain) {
  terrain->residentSections = 0;
  terrain->translucentQuadsResident = 0;
  terrain->geometryBytes = 0;
  terrain->uploadedSections = 0;
  terrain->removedSections = 0;
  terrain->uploadedNodes = 0;
  terrain->uploadedTopNodes = 0;
  terrain->removedTopNodes = 0;
  terrain->topNodeCount = 0;
  terrain->pendingRequests.clear();
  terrain->pendingEvictions.clear();
}

bool createSlotFrameResources(Slot* slot, NativeContext* context, TerrainResources* terrain,
                              std::string* error) {
  auto frame = std::make_unique<FrameResources>();
  frame->queueMeta = [context->device newBufferWithLength:MAX_LOD_ITERATIONS * 4 * sizeof(uint32_t)
                                                  options:MTLResourceStorageModeShared];
  frame->scratchQueueA = [context->device
      newBufferWithLength:static_cast<NSUInteger>(terrain->maxTraversalQueue) * sizeof(uint32_t)
                  options:MTLResourceStorageModeShared];
  frame->scratchQueueB = [context->device
      newBufferWithLength:static_cast<NSUInteger>(terrain->maxTraversalQueue) * sizeof(uint32_t)
                  options:MTLResourceStorageModeShared];
  frame->requestQueue = [context->device
      newBufferWithLength:(1 + static_cast<NSUInteger>(terrain->maxTraversalRequests) * 2) *
                          sizeof(uint32_t)
                  options:MTLResourceStorageModeShared];
  frame->worklistCounter = [context->device newBufferWithLength:sizeof(uint32_t)
                                                        options:MTLResourceStorageModeShared];
  frame->worklist = [context->device
      newBufferWithLength:static_cast<NSUInteger>(terrain->maxWorklistItems) * WORKLIST_ITEM_BYTES
                  options:MTLResourceStorageModeShared];
  frame->evictionQueue = [context->device newBufferWithLength:(1 + 256 * 2) * sizeof(uint32_t)
                                                      options:MTLResourceStorageModeShared];
  frame->sceneUniform = [context->device newBufferWithLength:SCENE_UNIFORM_BYTES
                                                     options:MTLResourceStorageModeShared];
  frame->translucentWorklistCounter =
      [context->device newBufferWithLength:sizeof(uint32_t) options:MTLResourceStorageModeShared];
  frame->translucentWorklist = [context->device
      newBufferWithLength:static_cast<NSUInteger>(terrain->maxWorklistItems) * WORKLIST_ITEM_BYTES
                  options:MTLResourceStorageModeShared];
  frame->opaqueRangeCounter = [context->device newBufferWithLength:sizeof(uint32_t)
                                                           options:MTLResourceStorageModeShared];
  frame->opaqueRangeCounts = [context->device
      newBufferWithLength:static_cast<NSUInteger>(terrain->maxOpaqueRangeCommands) *
                          sizeof(uint32_t)
                  options:MTLResourceStorageModeShared];
  frame->opaqueRangeBaseVertices = [context->device
      newBufferWithLength:static_cast<NSUInteger>(terrain->maxOpaqueRangeCommands) *
                          sizeof(uint32_t)
                  options:MTLResourceStorageModeShared];
  if (frame->evictionQueue == nil || frame->queueMeta == nil || frame->scratchQueueA == nil ||
      frame->scratchQueueB == nil || frame->requestQueue == nil || frame->worklistCounter == nil ||
      frame->worklist == nil || frame->sceneUniform == nil ||
      frame->translucentWorklistCounter == nil || frame->translucentWorklist == nil ||
      frame->opaqueRangeCounter == nil || frame->opaqueRangeCounts == nil ||
      frame->opaqueRangeBaseVertices == nil) {
    *error = "GL41Metal could not allocate per-slot traversal buffers";
    return false;
  }
  clearSlotFrameResources(frame.get(), terrain);
  slot->frame = std::move(frame);
  return true;
}

void clearSlotFrameResources(FrameResources* frame, TerrainResources* terrain) {
  if (frame == nullptr) return;
  std::memset([frame->evictionQueue contents], 0, sizeof(uint32_t));
  std::memset([frame->queueMeta contents], 0, MAX_LOD_ITERATIONS * 4 * sizeof(uint32_t));
  std::memset([frame->scratchQueueA contents], 0xff,
              static_cast<size_t>(terrain->maxTraversalQueue) * sizeof(uint32_t));
  std::memset([frame->scratchQueueB contents], 0xff,
              static_cast<size_t>(terrain->maxTraversalQueue) * sizeof(uint32_t));
  std::memset([frame->requestQueue contents], 0,
              (1 + static_cast<size_t>(terrain->maxTraversalRequests) * 2) * sizeof(uint32_t));
  std::memset([frame->worklistCounter contents], 0, sizeof(uint32_t));
  std::memset([frame->worklist contents], 0,
              static_cast<size_t>(terrain->maxWorklistItems) * WORKLIST_ITEM_BYTES);
  std::memset([frame->sceneUniform contents], 0, SCENE_UNIFORM_BYTES);
  std::memset([frame->translucentWorklistCounter contents], 0, sizeof(uint32_t));
  std::memset([frame->translucentWorklist contents], 0,
              static_cast<size_t>(terrain->maxWorklistItems) * WORKLIST_ITEM_BYTES);
  std::memset([frame->opaqueRangeCounter contents], 0, sizeof(uint32_t));
  frame->sectionGeneration = 0;
}

void clearTraversalScratch(FrameResources* frame, TerrainResources*) {
  if (frame == nullptr) return;
  std::memset([frame->evictionQueue contents], 0, sizeof(uint32_t));
  std::memset([frame->queueMeta contents], 0, MAX_LOD_ITERATIONS * 4 * sizeof(uint32_t));
  std::memset([frame->requestQueue contents], 0, sizeof(uint32_t));
  std::memset([frame->worklistCounter contents], 0, sizeof(uint32_t));
  std::memset([frame->translucentWorklistCounter contents], 0, sizeof(uint32_t));
  std::memset([frame->opaqueRangeCounter contents], 0, sizeof(uint32_t));
}

static void refreshTopNodeBuffer(TerrainResources* terrain) {
  uint32_t* buffer = reinterpret_cast<uint32_t*>([terrain->topNodeBuffer contents]);
  std::memset(buffer, 0xff, static_cast<size_t>(terrain->maxTraversalQueue) * sizeof(uint32_t));
  size_t count =
      std::min(terrain->topNodes.size(), static_cast<size_t>(terrain->maxTraversalQueue));
  if (count != 0) std::memcpy(buffer, terrain->topNodes.data(), count * sizeof(uint32_t));
  terrain->topNodeCount = count;
}

static uint32_t sectionQuadCount(const uint32_t* meta) {
  return (meta[4] & 0xffffu) + (meta[4] >> 16) + (meta[5] & 0xffffu) + (meta[5] >> 16) +
         (meta[6] & 0xffffu) + (meta[6] >> 16) + (meta[7] & 0xffffu) + (meta[7] >> 16);
}

static void updateSectionResidency(TerrainResources* terrain, int sectionId, const uint32_t* meta) {
  uint32_t quadCount = sectionQuadCount(meta);
  TerrainSectionSlot& slot = terrain->sections[sectionId];
  if (slot.resident) {
    terrain->geometryBytes -= slot.geometryBytes;
    terrain->translucentQuadsResident -= slot.translucentQuads;
  }
  if (quadCount == 0) {
    if (slot.resident) {
      terrain->residentSections--;
      terrain->removedSections++;
    }
    slot = TerrainSectionSlot{};
    return;
  }
  if (!slot.resident) terrain->residentSections++;
  slot.resident = true;
  slot.geometryBytes = static_cast<uint64_t>(quadCount) * 8;
  slot.translucentQuads = meta[4] & 0xffffu;
  terrain->geometryBytes += slot.geometryBytes;
  terrain->translucentQuadsResident += slot.translucentQuads;
  terrain->uploadedSections++;
}

}  // namespace gl41metal

extern "C" {

JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_jni_NativeBindings_createTerrainResources(
    JNIEnv* env, jclass, jlong handle, jint maxSections, jint maxNodes, jint maxTraversalQueue,
    jint maxTraversalRequests, jint maxWorklistItems, jint maxOpaqueRangeCommands) {
  @autoreleasepool {
    NativeContext* context = requireContext(env, handle);
    if (context == nullptr) return;
    if (maxSections <= 0 || maxNodes <= 0 || maxTraversalQueue <= 0 || maxTraversalRequests <= 0 ||
        maxWorklistItems <= 0 || maxOpaqueRangeCommands <= 0) {
      throwJava(env, "GL41Metal terrain resources received invalid dimensions");
      return;
    }
    if (context->terrain != nullptr) return;

    auto terrain = std::make_unique<TerrainResources>();
    terrain->maxSections = maxSections;
    terrain->maxNodes = maxNodes;
    terrain->maxTraversalQueue = maxTraversalQueue;
    terrain->maxTraversalRequests = maxTraversalRequests;
    terrain->maxWorklistItems = maxWorklistItems;
    terrain->maxOpaqueRangeCommands = maxOpaqueRangeCommands;
    terrain->lastDescended =
        [context->device newBufferWithLength:static_cast<NSUInteger>(maxNodes) * sizeof(uint32_t)
                                     options:MTLResourceStorageModeShared];
    if (terrain->lastDescended == nil) {
      throwJava(env, "GL41Metal could not allocate bounded node usage buffer");
      return;
    }
    std::memset([terrain->lastDescended contents], 0,
                static_cast<size_t>(maxNodes) * sizeof(uint32_t));
    terrain->evictionPipeline =
        createComputePipeline(env, context, @"collect_evictions", "geometry eviction");
    if (terrain->evictionPipeline == nil) return;
    terrain->sections.resize(static_cast<size_t>(maxSections));
    terrain->topNodes.reserve(static_cast<size_t>(std::min(maxNodes, maxTraversalQueue)));
    terrain->sectionMetadata = [context->device
        newBufferWithLength:static_cast<NSUInteger>(maxSections) * SECTION_METADATA_BYTES
                    options:MTLResourceStorageModeShared];
    terrain->nodeBuffer =
        [context->device newBufferWithLength:static_cast<NSUInteger>(maxNodes) * NODE_BYTES
                                     options:MTLResourceStorageModeShared];
    terrain->topNodeBuffer = [context->device
        newBufferWithLength:static_cast<NSUInteger>(maxTraversalQueue) * sizeof(uint32_t)
                    options:MTLResourceStorageModeShared];
    if (terrain->sectionMetadata == nil || terrain->nodeBuffer == nil ||
        terrain->topNodeBuffer == nil) {
      throwJava(env, "GL41Metal could not allocate traversal terrain buffers");
      return;
    }
    terrain->traversalPipeline = createTraversalPipeline(env, context);
    if (terrain->traversalPipeline == nil) return;

    std::memset([terrain->sectionMetadata contents], 0,
                static_cast<size_t>(maxSections) * SECTION_METADATA_BYTES);
    std::memset([terrain->nodeBuffer contents], 0, static_cast<size_t>(maxNodes) * NODE_BYTES);
    std::memset([terrain->topNodeBuffer contents], 0xff,
                static_cast<size_t>(maxTraversalQueue) * sizeof(uint32_t));
    clearTerrainCounters(terrain.get());
    for (Slot& slot : context->slots) {
      std::string error;
      if (!createSlotFrameResources(&slot, context, terrain.get(), &error)) {
        throwJava(env, error);
        return;
      }
    }
    context->terrain = std::move(terrain);
  }
}

JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_jni_NativeBindings_clearTerrainResources(
    JNIEnv* env, jclass, jlong handle) {
  NativeContext* context = requireContext(env, handle);
  if (context == nullptr || context->terrain == nullptr) return;
  TerrainResources* terrain = context->terrain.get();
  std::memset([terrain->sectionMetadata contents], 0,
              static_cast<size_t>(terrain->maxSections) * SECTION_METADATA_BYTES);
  std::memset([terrain->nodeBuffer contents], 0,
              static_cast<size_t>(terrain->maxNodes) * NODE_BYTES);
  terrain->sections.assign(static_cast<size_t>(terrain->maxSections), TerrainSectionSlot{});
  terrain->topNodes.clear();
  refreshTopNodeBuffer(terrain);
  clearTerrainCounters(terrain);
  terrain->sectionGeneration.fetch_add(1, std::memory_order_relaxed);
  for (Slot& slot : context->slots) clearSlotFrameResources(slot.frame.get(), terrain);
}

JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_jni_NativeBindings_removeSection(
    JNIEnv* env, jclass, jlong handle, jint sectionId) {
  NativeContext* context = requireContext(env, handle);
  if (context == nullptr || context->terrain == nullptr) return;
  TerrainResources* terrain = context->terrain.get();
  if (sectionId < 0 || sectionId >= terrain->maxSections) {
    throwJava(env, "GL41Metal removeSection received an invalid section id");
    return;
  }
  uint32_t empty[8] = {};
  updateSectionResidency(terrain, sectionId, empty);
  std::memset(static_cast<uint8_t*>([terrain->sectionMetadata contents]) +
                  static_cast<uint64_t>(sectionId) * SECTION_METADATA_BYTES,
              0, SECTION_METADATA_BYTES);
  terrain->sectionGeneration.fetch_add(1, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_jni_NativeBindings_uploadNode(
    JNIEnv* env, jclass, jlong handle, jint nodeId, jlong nodeAddress) {
  NativeContext* context = requireContext(env, handle);
  if (context == nullptr || context->terrain == nullptr) return;
  TerrainResources* terrain = context->terrain.get();
  if (nodeId < 0 || nodeId >= terrain->maxNodes || nodeAddress == 0) {
    throwJava(env, "GL41Metal uploadNode received invalid data");
    return;
  }
  auto* destination = static_cast<uint8_t*>([terrain->nodeBuffer contents]) +
                      static_cast<uint64_t>(nodeId) * NODE_BYTES;
  if (std::memcmp(destination, reinterpret_cast<const void*>(nodeAddress), 8) != 0) {
    static_cast<uint32_t*>([terrain->lastDescended contents])[nodeId] = terrain->currentFrame;
  }
  terrain->nodeSpan = std::max(terrain->nodeSpan, static_cast<uint32_t>(nodeId + 1));
  std::memcpy(destination, reinterpret_cast<const void*>(nodeAddress), NODE_BYTES);
  terrain->uploadedNodes++;
}

JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_jni_NativeBindings_uploadSectionMetadata(
    JNIEnv* env, jclass, jlong handle, jint sectionId, jlong metadataAddress) {
  NativeContext* context = requireContext(env, handle);
  if (context == nullptr || context->terrain == nullptr) return;
  TerrainResources* terrain = context->terrain.get();
  if (sectionId < 0 || sectionId >= terrain->maxSections || metadataAddress == 0) {
    throwJava(env, "GL41Metal uploadSectionMetadata received invalid data");
    return;
  }
  auto* source = reinterpret_cast<const uint32_t*>(metadataAddress);
  std::memcpy(static_cast<uint8_t*>([terrain->sectionMetadata contents]) +
                  static_cast<uint64_t>(sectionId) * SECTION_METADATA_BYTES,
              source, SECTION_METADATA_BYTES);
  updateSectionResidency(terrain, sectionId, source);
  terrain->sectionGeneration.fetch_add(1, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_jni_NativeBindings_addTopNode(
    JNIEnv* env, jclass, jlong handle, jint nodeId) {
  NativeContext* context = requireContext(env, handle);
  if (context == nullptr || context->terrain == nullptr) return;
  TerrainResources* terrain = context->terrain.get();
  if (nodeId < 0 || nodeId >= terrain->maxNodes ||
      terrain->topNodes.size() >= static_cast<size_t>(terrain->maxTraversalQueue)) {
    throwJava(env, "GL41Metal addTopNode received invalid data or a full top-node buffer");
    return;
  }
  uint32_t id = static_cast<uint32_t>(nodeId);
  if (std::find(terrain->topNodes.begin(), terrain->topNodes.end(), id) ==
      terrain->topNodes.end()) {
    terrain->topNodes.push_back(id);
    terrain->uploadedTopNodes++;
    refreshTopNodeBuffer(terrain);
  }
}

JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_jni_NativeBindings_removeTopNode(
    JNIEnv* env, jclass, jlong handle, jint nodeId) {
  NativeContext* context = requireContext(env, handle);
  if (context == nullptr || context->terrain == nullptr) return;
  TerrainResources* terrain = context->terrain.get();
  auto iter =
      std::find(terrain->topNodes.begin(), terrain->topNodes.end(), static_cast<uint32_t>(nodeId));
  if (iter != terrain->topNodes.end()) {
    terrain->topNodes.erase(iter);
    terrain->removedTopNodes++;
    refreshTopNodeBuffer(terrain);
  }
}

JNIEXPORT jboolean JNICALL
Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_jni_NativeBindings_canUpdateTerrain(
    JNIEnv* env, jclass, jlong handle) {
  NativeContext* context = requireContext(env, handle);
  if (context == nullptr) return JNI_FALSE;
  std::lock_guard<std::mutex> lock(context->mutex);
  return context->pendingCommandBuffers == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_jni_NativeBindings_setGeometryBudget(
    JNIEnv* env, jclass, jlong handle, jlong usedBytes, jlong capacityBytes, jboolean pressure,
    jint requestBudget) {
  NativeContext* context = requireContext(env, handle);
  if (context == nullptr || context->terrain == nullptr) return;
  TerrainResources* terrain = context->terrain.get();
  terrain->geometryPressure = pressure;
  terrain->geometryFill = static_cast<double>(usedBytes) / std::max<jlong>(1, capacityBytes);
  terrain->requestBudget = std::clamp<int>(requestBudget, 0, terrain->maxTraversalRequests);
}

JNIEXPORT jlongArray JNICALL
Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_jni_NativeBindings_pollEvictionCandidates(
    JNIEnv* env, jclass, jlong handle) {
  NativeContext* context = requireContext(env, handle);
  std::vector<uint64_t> positions;
  if (context != nullptr && context->terrain != nullptr) {
    std::lock_guard<std::mutex> lock(context->mutex);
    positions.swap(context->terrain->pendingEvictions);
  }
  jlongArray result = env->NewLongArray(static_cast<jsize>(positions.size()));
  if (result != nullptr && !positions.empty())
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(positions.size()),
                            reinterpret_cast<const jlong*>(positions.data()));
  return result;
}

JNIEXPORT jlongArray JNICALL
Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_jni_NativeBindings_pollTraversalRequests(
    JNIEnv* env, jclass, jlong handle) {
  NativeContext* context = requireContext(env, handle);
  std::vector<uint64_t> requests;
  if (context != nullptr && context->terrain != nullptr) {
    std::lock_guard<std::mutex> lock(context->mutex);
    requests.swap(context->terrain->pendingRequests);
  }
  jlongArray result = env->NewLongArray(static_cast<jsize>(requests.size()));
  if (result != nullptr && !requests.empty()) {
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(requests.size()),
                            reinterpret_cast<const jlong*>(requests.data()));
  }
  return result;
}

JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_jni_NativeBindings_clearTraversalWorklist(
    JNIEnv* env, jclass, jlong handle) {
  NativeContext* context = requireContext(env, handle);
  if (context == nullptr || context->terrain == nullptr) return;
  std::lock_guard<std::mutex> lock(context->mutex);
  context->terrain->pendingRequests.clear();
  for (Slot& slot : context->slots)
    clearSlotFrameResources(slot.frame.get(), context->terrain.get());
}

JNIEXPORT jlongArray JNICALL
Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_jni_NativeBindings_getTerrainStats(
    JNIEnv* env, jclass, jlong handle) {
  NativeContext* context = requireContext(env, handle);
  jlong values[11] = {};
  if (context != nullptr && context->terrain != nullptr) {
    TerrainResources* terrain = context->terrain.get();
    values[0] = static_cast<jlong>(terrain->residentSections);
    values[1] = static_cast<jlong>(terrain->geometryBytes);
    values[2] = static_cast<jlong>(terrain->uploadedSections);
    values[3] = static_cast<jlong>(terrain->removedSections);
    values[4] = static_cast<jlong>(terrain->uploadedNodes);
    values[5] = static_cast<jlong>(terrain->uploadedTopNodes);
    values[6] = static_cast<jlong>(terrain->removedTopNodes);
    values[7] = static_cast<jlong>(terrain->topNodeCount);
    values[8] = static_cast<jlong>(terrain->pendingRequests.size());
    values[9] = static_cast<jlong>(terrain->lodScale * 1024);
    values[10] = terrain->requestBudget;
  }
  jlongArray result = env->NewLongArray(11);
  if (result != nullptr) env->SetLongArrayRegion(result, 0, 11, values);
  return result;
}

}  // extern "C"
