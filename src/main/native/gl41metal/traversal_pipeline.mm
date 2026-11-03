#include "gl41metal_internal.h"

using namespace gl41metal;

namespace gl41metal {

id<MTLComputePipelineState> createTraversalPipeline(JNIEnv* env, NativeContext* context) {
  return createComputePipeline(env, context, @"traverse", "traversal");
}

}  // namespace gl41metal

extern "C" {

JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_jni_NativeBindings_submitTraversal(
    JNIEnv* env, jclass, jlong handle, jint slotIndex, jlong frameId, jdouble cameraX,
    jdouble cameraY, jdouble cameraZ, jlong traversalMvpAddress, jlong drawMvpAddress,
    jfloat subDivisionSize, jfloat earthRadius, jfloat nearExclusionRadius,
    jfloat renderDistanceSquared, jint viewportWidth, jint viewportHeight) {
  @autoreleasepool {
    NativeContext* context = requireContext(env, handle);
    if (context == nullptr) return;
    if (slotIndex < 0 || slotIndex >= static_cast<jint>(context->slots.size()) ||
        context->terrain == nullptr) {
      throwJava(env, "GL41Metal traversal submit received invalid state");
      return;
    }
    if (traversalMvpAddress == 0 || drawMvpAddress == 0) {
      throwJava(env, "GL41Metal traversal submit requires explicit matrices");
      return;
    }

    TerrainResources* terrain = context->terrain.get();
    terrain->currentFrame = static_cast<uint32_t>(frameId);
    // Pressure changes the screen-area budget; the Metal traverser still chooses every LOD.
    // Hysteresis lets collapsed subtrees stay coarse instead of being requested immediately again.
    uint32_t elapsed = terrain->currentFrame - terrain->lastLodAdjustment;
    if (terrain->geometryPressure && elapsed >= 60) {
      terrain->lodScale = std::min(4096.0f, terrain->lodScale * 2.25f);
      terrain->lastLodAdjustment = terrain->currentFrame;
    } else if (terrain->geometryFill < 0.4 && elapsed >= 300) {
      terrain->lodScale = std::max(1.0f, terrain->lodScale / 2.25f);
      terrain->lastLodAdjustment = terrain->currentFrame;
    }
    {
      std::lock_guard<std::mutex> lock(context->mutex);
      Slot& slot = context->slots[slotIndex];
      if (slot.state != SlotState::Free) {
        throwJava(env, "GL41Metal traversal submit attempted to write a non-free slot");
        return;
      }
      slot.state = SlotState::MetalSubmitted;
      slot.frameId = frameId;
      slot.translucentValid = false;
      context->pendingCommandBuffers++;
    }

    Slot& slot = context->slots[slotIndex];
    FrameResources* frame = slot.frame.get();
    if (frame == nullptr) {
      resetSubmittedSlot(context, slotIndex);
      throwJava(env, "GL41Metal traversal submit has no per-slot resources");
      return;
    }
    id<MTLCommandBuffer> commandBuffer = [context->queue commandBuffer];
    if (commandBuffer == nil) {
      resetSubmittedSlot(context, slotIndex);
      throwJava(env, "GL41Metal traversal commandBuffer returned nil");
      return;
    }
    commandBuffer.label =
        [NSString stringWithFormat:@"Voxy Traversal F%ld S%d", (long)frameId, (int)slotIndex];

    clearTraversalScratch(frame, terrain);
    frame->sectionGeneration = terrain->sectionGeneration.load(std::memory_order_relaxed);
    uint32_t* queueMeta = reinterpret_cast<uint32_t*>([frame->queueMeta contents]);
    queueMeta[0] = static_cast<uint32_t>((terrain->topNodeCount + 31) >> 5);
    queueMeta[1] = 1;
    queueMeta[2] = 1;
    queueMeta[3] = static_cast<uint32_t>(std::min<uint64_t>(
        terrain->topNodeCount, static_cast<uint64_t>(terrain->maxTraversalQueue)));
    for (int i = 1; i < MAX_LOD_ITERATIONS; i++) {
      queueMeta[i * 4 + 1] = 1;
      queueMeta[i * 4 + 2] = 1;
    }

    auto* scene = reinterpret_cast<SceneUniformHost*>([frame->sceneUniform contents]);
    std::memcpy(scene->traversalMvp, reinterpret_cast<const void*>(traversalMvpAddress), 64);
    std::memcpy(scene->drawMvp, reinterpret_cast<const void*>(drawMvpAddress), 64);
    int camSecX = static_cast<int>(std::floor(cameraX)) >> 5;
    int camSecY = static_cast<int>(std::floor(cameraY)) >> 5;
    int camSecZ = static_cast<int>(std::floor(cameraZ)) >> 5;
    scene->baseSectionFrame[0] = camSecX;
    scene->baseSectionFrame[1] = camSecY;
    scene->baseSectionFrame[2] = camSecZ;
    scene->baseSectionFrame[3] = static_cast<int32_t>(frameId);
    scene->cameraSubPos[0] = static_cast<float>(cameraX - static_cast<double>(camSecX << 5));
    scene->cameraSubPos[1] = static_cast<float>(cameraY - static_cast<double>(camSecY << 5));
    scene->cameraSubPos[2] = static_cast<float>(cameraZ - static_cast<double>(camSecZ << 5));
    scene->cameraSubPos[3] = 0.0f;
    float minScreenSpaceSize = 0.0f;
    if (viewportWidth > 0 && viewportHeight > 0) {
      float threshold = std::max(1.0f, static_cast<float>(subDivisionSize));
      minScreenSpaceSize =
          (threshold * threshold) / static_cast<float>(viewportWidth * viewportHeight);
    }
    scene->renderParams[0] = minScreenSpaceSize * terrain->lodScale;
    scene->renderParams[1] = earthRadius;
    scene->renderParams[2] = std::max(0.0f, nearExclusionRadius);
    scene->renderParams[3] = std::max(0.0f, renderDistanceSquared);
    scene->queueSizes[0] = static_cast<uint32_t>(terrain->maxWorklistItems);
    scene->queueSizes[1] = static_cast<uint32_t>(terrain->requestBudget);
    scene->queueSizes[2] = static_cast<uint32_t>(terrain->maxTraversalQueue);
    scene->queueSizes[3] = static_cast<uint32_t>(terrain->topNodeCount);
    scene->viewport[0] = static_cast<uint32_t>(std::max(0, viewportWidth));
    scene->viewport[1] = static_cast<uint32_t>(std::max(0, viewportHeight));
    scene->viewport[2] = terrain->nodeSpan;
    scene->viewport[3] = terrain->geometryPressure ? 1 : 0;
    scene->rasterLimits[0] = static_cast<uint32_t>(terrain->maxOpaqueRangeCommands);
    scene->rasterLimits[1] = 0;
    scene->rasterLimits[2] = 0;
    // Direct GL consumes face-group ranges and must retain every opaque group.
    scene->rasterLimits[3] = 1;

    if (terrain->topNodeCount > 0) {
      id<MTLComputeCommandEncoder> encoder = [commandBuffer computeCommandEncoder];
      if (encoder == nil) {
        resetSubmittedSlot(context, slotIndex);
        throwJava(env, "GL41Metal traversal compute encoder returned nil");
        return;
      }
      encoder.label = @"Voxy LOD Traversal";
      [encoder setComputePipelineState:terrain->traversalPipeline];
      [encoder setBuffer:terrain->lastDescended offset:0 atIndex:17];
      [encoder setBuffer:terrain->nodeBuffer offset:0 atIndex:0];
      [encoder setBuffer:terrain->sectionMetadata offset:0 atIndex:1];
      [encoder setBuffer:frame->queueMeta offset:0 atIndex:4];
      [encoder setBuffer:frame->requestQueue offset:0 atIndex:5];
      [encoder setBuffer:frame->worklistCounter offset:0 atIndex:6];
      [encoder setBuffer:frame->worklist offset:0 atIndex:7];
      [encoder setBuffer:frame->sceneUniform offset:0 atIndex:9];
      [encoder setBuffer:frame->requestQueue offset:sizeof(uint32_t) atIndex:11];
      [encoder setBuffer:frame->translucentWorklistCounter offset:0 atIndex:12];
      [encoder setBuffer:frame->translucentWorklist offset:0 atIndex:13];
      [encoder setBuffer:frame->opaqueRangeCounter offset:0 atIndex:14];
      [encoder setBuffer:frame->opaqueRangeCounts offset:0 atIndex:15];
      [encoder setBuffer:frame->opaqueRangeBaseVertices offset:0 atIndex:16];
      MTLSize threadsPerGroup = MTLSizeMake(32, 1, 1);
      for (uint32_t queueIdx = 0; queueIdx < MAX_LOD_ITERATIONS; queueIdx++) {
        id<MTLBuffer> source;
        id<MTLBuffer> sink;
        if (queueIdx == 0) {
          source = terrain->topNodeBuffer;
          sink = frame->scratchQueueA;
        } else if ((queueIdx & 1u) != 0) {
          source = frame->scratchQueueA;
          sink = frame->scratchQueueB;
        } else {
          source = frame->scratchQueueB;
          sink = frame->scratchQueueA;
        }
        [encoder setBuffer:source offset:0 atIndex:2];
        [encoder setBuffer:sink offset:0 atIndex:3];
        [encoder setBytes:&queueIdx length:sizeof(queueIdx) atIndex:10];
        if (queueIdx == 0) {
          [encoder dispatchThreadgroups:MTLSizeMake(std::max<uint32_t>(1, queueMeta[0]), 1, 1)
                  threadsPerThreadgroup:threadsPerGroup];
        } else {
          [encoder dispatchThreadgroupsWithIndirectBuffer:frame->queueMeta
                                     indirectBufferOffset:queueIdx * 4 * sizeof(uint32_t)
                                    threadsPerThreadgroup:threadsPerGroup];
        }
        [encoder memoryBarrierWithScope:MTLBarrierScopeBuffers];
      }
      if (terrain->geometryPressure && terrain->nodeSpan != 0) {
        [encoder setComputePipelineState:terrain->evictionPipeline];
        [encoder setBuffer:frame->evictionQueue offset:0 atIndex:18];
        [encoder dispatchThreads:MTLSizeMake(terrain->nodeSpan, 1, 1)
            threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
      }
      [encoder endEncoding];
    }

    NativeContext* capturedContext = context;
    FrameResources* capturedFrame = frame;
    int capturedSlot = slotIndex;
    [commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> completed) {
      {
        std::lock_guard<std::mutex> lock(capturedContext->mutex);
        if (completed.status == MTLCommandBufferStatusError && completed.error != nil) {
          capturedContext->asyncFailure = [[completed.error localizedDescription] UTF8String];
        }
        TerrainResources* completedTerrain = capturedContext->terrain.get();
        if (completedTerrain != nullptr) {
          uint32_t requestCount =
              reinterpret_cast<uint32_t*>([capturedFrame->requestQueue contents])[0];
          requestCount = std::min<uint32_t>(
              requestCount, static_cast<SceneUniformHost*>([capturedFrame->sceneUniform contents])
                                ->queueSizes[1]);
          uint32_t* requestData =
              reinterpret_cast<uint32_t*>([capturedFrame->requestQueue contents]) + 1;
          completedTerrain->pendingRequests.reserve(completedTerrain->pendingRequests.size() +
                                                    requestCount);
          for (uint32_t i = 0; i < requestCount; i++) {
            completedTerrain->pendingRequests.push_back(
                (static_cast<uint64_t>(requestData[i * 2]) << 32) | requestData[i * 2 + 1]);
          }
        }
        if (completedTerrain != nullptr) {
          auto* evictions = static_cast<uint32_t*>([capturedFrame->evictionQueue contents]);
          uint32_t count = std::min<uint32_t>(evictions[0], 256);
          completedTerrain->pendingEvictions.clear();
          for (uint32_t i = 0; i < count; i++) {
            completedTerrain->pendingEvictions.push_back(
                (static_cast<uint64_t>(evictions[1 + i * 2]) << 32) | evictions[2 + i * 2]);
          }
        }
        Slot& completedSlot = capturedContext->slots[capturedSlot];
        uint32_t translucentCount =
            reinterpret_cast<uint32_t*>([capturedFrame->translucentWorklistCounter contents])[0];
        completedSlot.translucentValid = translucentCount != 0;
        if (completedSlot.state == SlotState::MetalSubmitted) {
          completedSlot.state = SlotState::MetalReady;
        } else if (completedSlot.state == SlotState::Retiring) {
          completedSlot.state = SlotState::Free;
          completedSlot.frameId = -1;
        }
        capturedContext->pendingCommandBuffers =
            std::max(0, capturedContext->pendingCommandBuffers - 1);
      }
      capturedContext->condition.notify_all();
    }];
    [commandBuffer commit];
  }
}

}  // extern "C"
