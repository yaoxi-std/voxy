#pragma once

#include <jni.h>

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "gl41metal_abi.h"

namespace gl41metal {

enum class SlotState {
  Free,
  MetalSubmitted,
  MetalReady,
  GlSampling,
  Retiring,
};

struct FrameResources {
  id<MTLBuffer> queueMeta = nil;
  id<MTLBuffer> scratchQueueA = nil;
  id<MTLBuffer> scratchQueueB = nil;
  id<MTLBuffer> requestQueue = nil;
  id<MTLBuffer> worklistCounter = nil;
  id<MTLBuffer> worklist = nil;
  id<MTLBuffer> sceneUniform = nil;
  id<MTLBuffer> translucentWorklistCounter = nil;
  id<MTLBuffer> translucentWorklist = nil;
  id<MTLBuffer> opaqueRangeCounter = nil;
  id<MTLBuffer> opaqueRangeCounts = nil;
  id<MTLBuffer> opaqueRangeBaseVertices = nil;
  id<MTLBuffer> evictionQueue = nil;
  uint64_t sectionGeneration = 0;
};

struct Slot {
  SlotState state = SlotState::Free;
  int64_t frameId = -1;
  bool translucentValid = false;
  std::unique_ptr<FrameResources> frame;
};

struct TerrainSectionSlot {
  bool resident = false;
  uint64_t geometryBytes = 0;
  uint32_t translucentQuads = 0;
};

struct TerrainResources {
  int maxSections = 0;
  int maxNodes = 0;
  int maxTraversalQueue = 0;
  int maxTraversalRequests = 0;
  int maxWorklistItems = 0;
  int maxOpaqueRangeCommands = 0;
  uint64_t topNodeCount = 0;
  uint64_t residentSections = 0;
  uint64_t translucentQuadsResident = 0;
  uint64_t geometryBytes = 0;
  uint64_t uploadedSections = 0;
  uint64_t removedSections = 0;
  uint64_t uploadedNodes = 0;
  uint64_t uploadedTopNodes = 0;
  uint64_t removedTopNodes = 0;
  std::atomic<uint64_t> sectionGeneration{1};
  std::vector<TerrainSectionSlot> sections;
  std::vector<uint32_t> topNodes;
  std::vector<uint64_t> pendingRequests;
  std::vector<uint64_t> pendingEvictions;
  id<MTLBuffer> lastDescended = nil;
  id<MTLComputePipelineState> evictionPipeline = nil;
  uint32_t nodeSpan = 0;
  uint32_t currentFrame = 0;
  int requestBudget = 64;
  bool geometryPressure = false;
  double geometryFill = 0;
  float lodScale = 1;
  uint32_t lastLodAdjustment = 0;
  id<MTLBuffer> sectionMetadata = nil;
  id<MTLBuffer> nodeBuffer = nil;
  id<MTLBuffer> topNodeBuffer = nil;
  id<MTLComputePipelineState> traversalPipeline = nil;
};

struct NativeContext {
  id<MTLDevice> device = nil;
  id<MTLCommandQueue> queue = nil;
  id<MTLLibrary> shaderLibrary = nil;
  std::string deviceName;
  std::vector<Slot> slots;
  std::mutex mutex;
  std::condition_variable condition;
  int pendingCommandBuffers = 0;
  std::string asyncFailure;
  std::unique_ptr<TerrainResources> terrain;
};

void throwJava(JNIEnv* env, const std::string& message);
NativeContext* requireContext(JNIEnv* env, jlong handle);
id<MTLComputePipelineState> createComputePipeline(JNIEnv* env, NativeContext* context,
                                                  NSString* functionName, const char* label);
bool createSlotFrameResources(Slot* slot, NativeContext* context, TerrainResources* terrain,
                              std::string* error);
void clearSlotFrameResources(FrameResources* frame, TerrainResources* terrain);
void resetSubmittedSlot(NativeContext* context, int slotIndex);
void clearTerrainCounters(TerrainResources* terrain);
void clearTraversalScratch(FrameResources* frame, TerrainResources* terrain);
id<MTLComputePipelineState> createTraversalPipeline(JNIEnv* env, NativeContext* context);

}  // namespace gl41metal
