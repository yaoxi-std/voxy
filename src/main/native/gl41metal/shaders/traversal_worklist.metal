#include <metal_stdlib>
using namespace metal;
struct SceneUniform {
  float4x4 traversalMvp;
  float4x4 drawMvp;
  int4 baseSectionFrame;
  float4 cameraSubPos;
  float4 renderParams;
  uint4 queueSizes;
  uint4 viewport;
  uint4 rasterLimits;
};
struct Node {
  uint4 raw;
};
struct SectionMeta {
  uint4 a;
  uint4 b;
};
struct WorkItem {
  uint meshId;
  uint quadBase;
  uint reserved;
  uint lodAndQuadCount;
};
struct UnpackedNode {
  uint nodeId;
  uint2 rawPos;
  int3 pos;
  uint lodLevel;
  uint flags;
  uint meshPtr;
  uint childPtr;
};
constant uint NULL_NODE = 0x00ffffffu;
constant uint EMPTY_QUEUE_ID = 0x00fffffeu;
constant uint EMPTY_QUEUE_ENTRY = 0xffffffffu;
constant uint NULL_MESH = 0x00ffffffu;
constant uint EMPTY_MESH = 0x00fffffeu;
constant uint LOCAL_SIZE_BITS = 5u;
constant uint LOCAL_SIZE = 1u << LOCAL_SIZE_BITS;
constant uint MAX_QUADS_PER_RANGE = 16380u;
static inline UnpackedNode unpackNode(device const Node* nodes, uint nodeId) {
  uint4 c = nodes[nodeId].raw;
  UnpackedNode n;
  n.nodeId = nodeId;
  n.lodLevel = c.x >> 28;
  n.rawPos = uint2(c.x, c.y);
  int y = (int(c.x) << 4) >> 24;
  int x = (int(c.y) << 4) >> 8;
  int z = int((int(c.x) & ((1 << 20) - 1)) << 4);
  z |= int(c.y >> 28);
  z <<= 8;
  z >>= 8;
  n.pos = int3(x, y, z);
  n.meshPtr = c.z & 0x00ffffffu;
  n.childPtr = c.w & 0x00ffffffu;
  n.flags = ((c.z >> 24) & 0xffu) | (((c.w >> 24) & 0xffu) << 8);
  return n;
}
static inline int3 extract_section_pos(SectionMeta section) {
  int y = (int(section.a.x) << 4) >> 24;
  int x = (int(section.a.y) << 4) >> 8;
  int z = int((section.a.x & ((1u << 20u) - 1u)) << 4u);
  z |= int(section.a.y >> 28u);
  z <<= 8;
  z >>= 8;
  return int3(x, y, z);
}
static inline uint extract_detail(SectionMeta section) {
  return section.a.x >> 28u;
}
static inline uint group_count(SectionMeta meta, uint group) {
  switch (group) {
    case 0u:
      return meta.b.x & 0xffffu;
    case 1u:
      return meta.b.x >> 16u;
    case 2u:
      return meta.b.y & 0xffffu;
    case 3u:
      return meta.b.y >> 16u;
    case 4u:
      return meta.b.z & 0xffffu;
    case 5u:
      return meta.b.z >> 16u;
    case 6u:
      return meta.b.w & 0xffffu;
    default:
      return meta.b.w >> 16u;
  }
}
static inline bool group_visible_from_camera(uint group, SectionMeta meta,
                                             constant SceneUniform& scene) {
  if (group == 1u) return true;
  if (group == 0u) return false;
  uint detail = extract_detail(meta);
  int3 relative =
      extract_section_pos(meta) - int3(scene.baseSectionFrame.x >> detail,
                                       scene.baseSectionFrame.y >> detail,
                                       scene.baseSectionFrame.z >> detail);
  switch (group) {
    case 2u:
      return relative.y > -1;
    case 3u:
      return relative.y < 1;
    case 4u:
      return relative.z > -1;
    case 5u:
      return relative.z < 1;
    case 6u:
      return relative.x > -1;
    case 7u:
      return relative.x < 1;
    default:
      return false;
  }
}
static inline uint visible_opaque_group_mask(SectionMeta meta,
                                             constant SceneUniform& scene) {
  uint mask = 0u;
  for (uint group = 1u; group < 8u; group++) {
    if (group_visible_from_camera(group, meta, scene)) mask |= 1u << group;
  }
  return mask;
}
static inline uint visible_opaque_quad_count(SectionMeta meta, uint groupMask) {
  uint count = 0u;
  for (uint group = 1u; group < 8u; group++) {
    if ((groupMask & (1u << group)) != 0u) {
      count += group_count(meta, group);
    }
  }
  return count;
}
static inline void emit_opaque_ranges(SectionMeta section, uint groupMask,
                                      constant SceneUniform& scene,
                                      device atomic_uint* rangeCounter,
                                      device uint* rangeCounts,
                                      device uint* rangeBaseVertices) {
  uint2 spans[7];
  uint spanCount = 0u;
  uint sourceOffset = section.a.w;
  for (uint group = 0u; group < 8u; group++) {
    uint count = group_count(section, group);
    if (count != 0u && (groupMask & (1u << group)) != 0u) {
      if (spanCount != 0u && spans[spanCount - 1u].y == sourceOffset) {
        spans[spanCount - 1u].y += count;
      } else {
        spans[spanCount++] = uint2(sourceOffset, sourceOffset + count);
      }
    }
    sourceOffset += count;
  }
  if (spanCount == 0u) return;

  uint commandCount = 0u;
  for (uint i = 0u; i < spanCount; i++) {
    uint spanQuads = spans[i].y - spans[i].x;
    commandCount +=
        (spanQuads + MAX_QUADS_PER_RANGE - 1u) / MAX_QUADS_PER_RANGE;
  }

  uint commandBase = atomic_fetch_add_explicit(&rangeCounter[0], commandCount,
                                               memory_order_relaxed);

  uint commandOffset = 0u;
  uint capacity = scene.rasterLimits.x;
  for (uint i = 0u; i < spanCount; i++) {
    for (uint chunkStart = spans[i].x; chunkStart < spans[i].y;
         chunkStart += MAX_QUADS_PER_RANGE) {
      uint outputIndex = commandBase + commandOffset++;
      if (outputIndex >= capacity) continue;
      uint quadCount = min(MAX_QUADS_PER_RANGE, spans[i].y - chunkStart);
      rangeCounts[outputIndex] = quadCount * 6u;
      rangeBaseVertices[outputIndex] = chunkStart * 4u;
    }
  }
}
static inline uint section_fingerprint(SectionMeta meta) {
  return (meta.a.x ^ (meta.a.y * 0x9e3779b9u)) & 0x00ffffffu;
}
static inline bool hasMesh(UnpackedNode n) { return n.meshPtr != NULL_MESH; }
static inline bool isEmptyMesh(UnpackedNode n) {
  return n.meshPtr == EMPTY_MESH;
}
static inline bool hasChildren(UnpackedNode n) {
  return n.childPtr != NULL_NODE && n.childPtr != EMPTY_QUEUE_ID;
}
static inline bool hasRequested(UnpackedNode n) { return (n.flags & 1u) != 0u; }
static inline float crossMag(float2 a, float2 b) {
  return fabs(a.x * b.y - b.x * a.y);
}
static inline bool reserveQueue(device atomic_uint* cursor, uint count,
                                uint capacity, thread uint& index) {
  uint current = atomic_load_explicit(cursor, memory_order_relaxed);
  while (true) {
    if (current > capacity || count > capacity - current) {
      return false;
    }
    uint desired = current + count;
    uint expected = current;
    if (atomic_compare_exchange_weak_explicit(cursor, &expected, desired,
                                              memory_order_relaxed,
                                              memory_order_relaxed)) {
      index = current;
      return true;
    }
    current = expected;
  }
}
static inline float axisDistanceToAabb(float minValue, float maxValue) {
  if (0.0f < minValue) return minValue;
  if (0.0f > maxValue) return -maxValue;
  return 0.0f;
}
static inline bool insideNearExclusion(float3 base, float size, float radius) {
  float3 maxPoint = base + float3(size);
  float dx = max(abs(base.x), abs(maxPoint.x));
  float dz = max(abs(base.z), abs(maxPoint.z));
  float dy = max(abs(base.y), abs(maxPoint.y));
  return length(float2(dx, dz)) <= radius && dy <= radius;
}
static inline bool intersectsNearExclusion(float3 base, float size,
                                           float radius) {
  float3 maxPoint = base + float3(size);
  float dx = axisDistanceToAabb(base.x, maxPoint.x);
  float dz = axisDistanceToAabb(base.z, maxPoint.z);
  float dy = axisDistanceToAabb(base.y, maxPoint.y);
  return length(float2(dx, dz)) <= radius && dy <= radius;
}
// Squared XZ distance from the camera (origin) to the node AABB's
// closest/furthest corners, matching GL46 traversal_dev.comp
// closestPointToCamera/furthestPointToCamera (base == nPos, size == scale =
// 32<<lodLevel). The render-distance test is an XZ cylinder (Y is unbounded),
// exactly as GL46 isWithinRenderDistance / shouldRenderSelf use.
static inline float closestPointXZSq(float3 base, float size) {
  float cx = (base.x > 0.0f)
                 ? base.x
                 : ((base.x + size < 0.0f) ? (base.x + size) : 0.0f);
  float cz = (base.z > 0.0f)
                 ? base.z
                 : ((base.z + size < 0.0f) ? (base.z + size) : 0.0f);
  return cx * cx + cz * cz;
}
static inline float furthestPointXZSq(float3 base, float size) {
  float fx = (base.x + size < 0.0f) ? base.x : (base.x + size);
  float fz = (base.z + size < 0.0f) ? base.z : (base.z + size);
  return fx * fx + fz * fz;
}
static inline uint classifyNode(UnpackedNode n, constant SceneUniform& scene) {
  int scale = int(1u << n.lodLevel);
  float size = float(32 * scale);
  float3 base = float3((n.pos * scale - scene.baseSectionFrame.xyz) * 32) -
                scene.cameraSubPos.xyz;
  // GL46 isWithinRenderDistance: cull any node whose closest XZ distance
  // exceeds the configured render distance (an XZ cylinder, blocks^2). GL46
  // applies this in main() before traversing, so out-of-range nodes never
  // descend or render. renderParams.w <= 0 means "unbounded".
  float renderDistanceSq = scene.renderParams.w;
  if (renderDistanceSq > 0.0f &&
      closestPointXZSq(base, size) > renderDistanceSq) {
    return 0u;
  }
  float nearExclusionRadius = scene.renderParams.z;
  if (nearExclusionRadius > 0.0f &&
      intersectsNearExclusion(base, size, nearExclusionRadius)) {
    if (insideNearExclusion(base, size, nearExclusionRadius)) return 3u;
    if (n.lodLevel != 0u) return 2u;
  }
  float4 p[8];
  p[0] = scene.traversalMvp * float4(base, 1.0);
  p[1] = scene.traversalMvp * float4(base + float3(size, 0, 0), 1.0);
  p[2] = scene.traversalMvp * float4(base + float3(0, size, 0), 1.0);
  p[3] = scene.traversalMvp * float4(base + float3(0, 0, size), 1.0);
  p[4] = scene.traversalMvp * float4(base + float3(size, size, 0), 1.0);
  p[5] = scene.traversalMvp * float4(base + float3(size, 0, size), 1.0);
  p[6] = scene.traversalMvp * float4(base + float3(0, size, size), 1.0);
  p[7] = scene.traversalMvp * float4(base + float3(size, size, size), 1.0);
  // World-space frustum cull, matching GL46 lod/frustum.glsl (AABB "p-vertex"
  // vs 5 planes, skipping far). The previous clip-space 8-corner test (q.x <
  // -q.w, q.z < 0, ...) was mathematically UNSOUND for any corner with w <= 0:
  // the homogeneous inequalities only hold for w > 0, so when a node straddled
  // or sat behind the camera near plane the per-corner signs flipped and the
  // "all corners outside" reduction never fired. Those nodes leaked through the
  // cull and the rasterizer near-clipped their geometry into a depth-ramping
  // smear that filled the sky (vxDepthTexOpaque reconstructed to <32 blocks
  // straight overhead). Additionally `q.z < 0.0` assumed Metal [0,w] depth, but
  // traversalMvp = computeProjectionMat
  // * modelView keeps MC's GL [-w,w] depth convention (only m22/m32 are
  // overridden), so the near plane is z = -w, not z = 0. Extracting the planes
  // from traversalMvp (Gribb-Hartmann) and testing the AABB in the same
  // camera-relative world space as `base` is exact for every w sign and
  // reproduces the GL46 traverser's culling decisions exactly.
  //
  // traversalMvp is column-major here (see MetalDistantRenderer.writeMatrix),
  // so clip.row_i is gathered across the columns: row_i = (m[0][i], m[1][i],
  // m[2][i], m[3][i]).
  float4 row0 = float4(scene.traversalMvp[0][0], scene.traversalMvp[1][0],
                       scene.traversalMvp[2][0], scene.traversalMvp[3][0]);
  float4 row1 = float4(scene.traversalMvp[0][1], scene.traversalMvp[1][1],
                       scene.traversalMvp[2][1], scene.traversalMvp[3][1]);
  float4 row2 = float4(scene.traversalMvp[0][2], scene.traversalMvp[1][2],
                       scene.traversalMvp[2][2], scene.traversalMvp[3][2]);
  float4 row3 = float4(scene.traversalMvp[0][3], scene.traversalMvp[1][3],
                       scene.traversalMvp[2][3], scene.traversalMvp[3][3]);
  // Inside half-space for each plane is dot(plane.xyz, p) >= -plane.w.
  float4 planes[5];
  planes[0] = row3 + row0;  // left   (x >= -w)
  planes[1] = row3 - row0;  // right  (x <=  w)
  planes[2] = row3 + row1;  // bottom (y >= -w)
  planes[3] = row3 - row1;  // top    (y <=  w)
  planes[4] = row3 + row2;  // near   (z >= -w, GL convention)
  for (uint i = 0u; i < 5u; i++) {
    float3 nrm = planes[i].xyz;
    // p-vertex: the AABB corner furthest along the plane normal. If even it is
    // outside (behind the plane), the whole box is outside -> cull.
    float3 pv = base + select(float3(0.0f), float3(size), nrm > float3(0.0f));
    if (dot(nrm, pv) < -planes[i].w) {
      return 0u;
    }
  }
  // LOD descend metric: accurate projected surface area, matching GL46
  // screenspace.glsl (_screenSize). We sum the three visible face
  // parallelograms seen from each of two opposite corners and halve the total.
  // Corner index map (see p[] above):
  //   p[0]=000 p[1]=100(+x) p[2]=010(+y) p[3]=001(+z) p[4]=110 p[5]=101
  //   p[6]=011 p[7]=111.
  // IMPORTANT: use the raw perspective-divided NDC->[0,1] screen positions
  // WITHOUT clamping. The previous metric used the clamped screen-space AABB
  // box area ((mx-mn).x*(mx-mn).y). That proxy is non-monotonic (the bounding
  // box jumps as the extremal projected corner switches) and the [0,1] clamp
  // shrinks it abruptly as a node crosses a screen edge. Under pure translation
  // a node's size then dithered across the descend threshold, flipping it
  // coarse<->fine every frame; the resulting LOD/coverage flicker fed the
  // shader pack's high-history, edge-clamp-disabled distant TAA (lodChunk) and
  // showed up as boundary jitter/"layering". Pure rotation keeps node distance
  // (hence true area) constant, which is why rotation never jittered. The exact
  // projected area is a smooth, monotonic-under-approach function, so nodes now
  // cross the threshold once (a clean pop TAA absorbs) instead of dithering.
  // renderParams.x is a normalized (pixels^2 / (W*H)) area threshold,
  // consistent with these normalized [0,1] screen coordinates.
  float2 s000 = p[0].xy / p[0].w * 0.5 + 0.5;
  float2 s100 = p[1].xy / p[1].w * 0.5 + 0.5;
  float2 s010 = p[2].xy / p[2].w * 0.5 + 0.5;
  float2 s001 = p[3].xy / p[3].w * 0.5 + 0.5;
  float2 s110 = p[4].xy / p[4].w * 0.5 + 0.5;
  float2 s101 = p[5].xy / p[5].w * 0.5 + 0.5;
  float2 s011 = p[6].xy / p[6].w * 0.5 + 0.5;
  float2 s111 = p[7].xy / p[7].w * 0.5 + 0.5;
  float area = 0.0;
  {
    float2 A = s100 - s000, B = s010 - s000, C = s001 - s000;
    area += crossMag(A, B) + crossMag(A, C) + crossMag(C, B);
  }
  {
    float2 A = s011 - s111, B = s101 - s111, C = s110 - s111;
    area += crossMag(A, B) + crossMag(A, C) + crossMag(C, B);
  }
  area *= 0.5;
  return area > scene.renderParams.x ? 2u : 1u;
}
kernel void traverse(device Node* nodes [[buffer(0)]],
                     device const SectionMeta* sections [[buffer(1)]],
                     device const uint* sourceQueue [[buffer(2)]],
                     device uint* sinkQueue [[buffer(3)]],
                     device atomic_uint* queueMeta [[buffer(4)]],
                     device atomic_uint* requestCounter [[buffer(5)]],
                     device atomic_uint* worklistCounter [[buffer(6)]],
                     device WorkItem* worklist [[buffer(7)]],
                     constant SceneUniform& scene [[buffer(9)]],
                     constant uint& queueIdx [[buffer(10)]],
                     device uint* requestData [[buffer(11)]],
                     device atomic_uint* translucentWorklistCounter
                     [[buffer(12)]],
                     device WorkItem* translucentWorklist [[buffer(13)]],
                     device atomic_uint* opaqueRangeCounter [[buffer(14)]],
                     device uint* opaqueRangeCounts [[buffer(15)]],
                     device uint* opaqueRangeBaseVertices [[buffer(16)]],
                     device atomic_uint* lastDescended [[buffer(17)]],
                     uint gid [[thread_position_in_grid]]) {
  uint queueCount = queueIdx == 0u
                        ? scene.queueSizes.w
                        : atomic_load_explicit(&queueMeta[queueIdx * 4u + 3u],
                                               memory_order_relaxed);
  queueCount = min(queueCount, scene.queueSizes.z);
  if (gid >= queueCount) return;
  uint nodeId = sourceQueue[gid];
  if (nodeId == EMPTY_QUEUE_ENTRY) return;
  UnpackedNode n = unpackNode(nodes, nodeId);
  uint visibility = classifyNode(n, scene);
  if (visibility == 0u) return;
  if (visibility == 3u) return;
  bool shouldDescend =
      n.lodLevel != 0u && visibility == 2u && (n.flags & (1u << 6u)) != 0u;
  if (shouldDescend)
    atomic_store_explicit(&lastDescended[nodeId],
                          uint(scene.baseSectionFrame.w), memory_order_relaxed);
  bool childQueueOverflow = false;
  if (shouldDescend) {
    if (hasChildren(n)) {
      uint childCount = ((n.flags >> 2) & 7u) + 1u;
      uint index = 0u;
      if (reserveQueue(&queueMeta[(queueIdx + 1u) * 4u + 3u], childCount,
                       scene.queueSizes.z, index)) {
        uint inc = ((index + childCount + LOCAL_SIZE - 1u) >> LOCAL_SIZE_BITS) -
                   (index >> LOCAL_SIZE_BITS);
        atomic_fetch_add_explicit(&queueMeta[(queueIdx + 1u) * 4u], inc,
                                  memory_order_relaxed);
        for (uint i = 0; i < childCount; i++)
          sinkQueue[index + i] = n.childPtr + i;
      } else {
        childQueueOverflow = true;
      }
    } else if (!hasRequested(n)) {
      uint r = atomic_fetch_add_explicit(&requestCounter[0], 1u,
                                         memory_order_relaxed);
      if (r < scene.queueSizes.y) {
        requestData[r * 2u] = n.rawPos.x;
        requestData[r * 2u + 1u] = n.rawPos.y;
        nodes[n.nodeId].raw.z |= 1u << 24;
      }
    }
  }
  if (!shouldDescend || !hasChildren(n) || childQueueOverflow) {
    // GL46 shouldRenderSelf gate (traversal_dev.comp): when a node WANTS to
    // descend (shouldDescend) but its children have not streamed in yet (or the
    // bounded Metal queue cannot accept all of them), GL46 only rasterises the
    // coarse self-mesh if the node's FURTHEST XZ corner (+16^3 buffer) is still
    // within render distance. A giant coarse near-root node fails this (its far
    // corner spans thousands of blocks), so GL46 simply waits for children
    // instead of drawing it. We previously rendered that coarse self-mesh
    // unconditionally, and its huge vertical extent
    // near-clipped/rasterised a depth-ramping smear across the whole sky
    // (vxDepthTexOpaque reconstructed to impossible heights straight overhead).
    // Only the descend-wanted fallback case is gated; the !shouldDescend case
    // is normal LOD and renders its mesh as GL46 does. renderParams.w <= 0
    // means "unbounded" (GL46 renderDistance < 0).
    int selfScale = int(1u << n.lodLevel);
    float selfSize = float(32 * selfScale);
    float3 selfBase =
        float3((n.pos * selfScale - scene.baseSectionFrame.xyz) * 32) -
        scene.cameraSubPos.xyz;
    bool blockSelfRender = false;
    if (shouldDescend) {
      float renderDistanceSq = scene.renderParams.w;
      if (renderDistanceSq > 0.0f) {
        blockSelfRender = (furthestPointXZSq(selfBase, selfSize) + 4096.0f) >
                          renderDistanceSq;
      }
    }
    // Near-clip smear suppression: a node whose AABB ENCLOSES the camera
    // near-clips its own mesh into a depth-ramping smear that fills the sky
    // (part of the mesh falls behind the near plane; the zenith depth
    // reconstructs to <32 blocks straight overhead). This only ever happens for
    // a node that contains the camera position (camera-to-node distance 0), so
    // the test is camera enclosure, NOT "intersects the whole near-scene
    // radius".
    //
    // The previous test blocked every node intersecting the Sodium-sized
    // near-exclusion cylinder (renderParams.z). That also suppressed the COARSE
    // boundary self-mesh: a coarse node straddling the near/far interface that
    // wants to descend but whose finer children have not streamed in yet was
    // blocked AND had no children, so the portion of it past the Sodium edge
    // rendered nothing and left a one-coarse-node-wide ring of holes. GL46
    // shouldRenderSelf instead rasterises the coarse self-mesh while children
    // stream (gated only by render distance, handled above), which fills that
    // ring. renderParams.z still drives the classify-time skip/descend near the
    // camera; only the far-boundary self-mesh block is removed here. A small
    // margin guards against near-plane epsilon/jitter.
    const float SMEAR_MARGIN = 4.0f;
    float3 selfMax = selfBase + float3(selfSize);
    bool cameraEnclosed =
        selfBase.x - SMEAR_MARGIN <= 0.0f && 0.0f <= selfMax.x + SMEAR_MARGIN &&
        selfBase.y - SMEAR_MARGIN <= 0.0f && 0.0f <= selfMax.y + SMEAR_MARGIN &&
        selfBase.z - SMEAR_MARGIN <= 0.0f && 0.0f <= selfMax.z + SMEAR_MARGIN;
    if (cameraEnclosed) {
      blockSelfRender = true;
    }
    if (hasMesh(n) && !isEmptyMesh(n)) {
      // When self-render is blocked the node does nothing further (its child
      // request was already issued in the descend branch); we must NOT fall
      // through to the request else-if below, which would double-request. qc==0
      // skips the worklist append while staying in the hasMesh branch.
      SectionMeta section = sections[n.meshPtr];
      uint opaqueGroupMask = visible_opaque_group_mask(section, scene);
      uint qc = blockSelfRender
                    ? 0u
                    : visible_opaque_quad_count(section, opaqueGroupMask);
      if (qc != 0u) {
        uint w = atomic_fetch_add_explicit(&worklistCounter[0], 1u,
                                           memory_order_relaxed);
        if (w < scene.queueSizes.x) {
          WorkItem item;
          item.meshId = n.meshPtr;
          // Preserve the traversal snapshot so the CPU correctness fallback can
          // detect a section mutation between traversal and direct GL sampling.
          item.quadBase = section.a.w;
          item.reserved =
              opaqueGroupMask | (section_fingerprint(section) << 8u);
          item.lodAndQuadCount = (n.lodLevel << 24) | min(qc, 0x00ffffffu);
          worklist[w] = item;
          emit_opaque_ranges(section, opaqueGroupMask, scene,
                             opaqueRangeCounter, opaqueRangeCounts,
                             opaqueRangeBaseVertices);
        }
      }
      // Translucent (group 0) emission. Independent of the opaque worklist: the
      // The CPU range builder sorts these items by section distance. Group 0 is
      // never face-culled (water and glass are viewed from both sides).
      uint tqc = blockSelfRender ? 0u : group_count(sections[n.meshPtr], 0u);
      if (tqc != 0u) {
        uint tw = atomic_fetch_add_explicit(&translucentWorklistCounter[0], 1u,
                                            memory_order_relaxed);
        if (tw < scene.queueSizes.x) {
          WorkItem titem;
          titem.meshId = n.meshPtr;
          titem.quadBase = 0u;  // assigned by scatter_translucent_draw
          titem.reserved =
              0u;  // distance bucket cached by prepare_translucent_sort
          titem.lodAndQuadCount = (n.lodLevel << 24) | min(tqc, 0x00ffffffu);
          translucentWorklist[tw] = titem;
        }
      }
    } else if (!hasRequested(n) && n.lodLevel != 0u) {
      uint r = atomic_fetch_add_explicit(&requestCounter[0], 1u,
                                         memory_order_relaxed);
      if (r < scene.queueSizes.y) {
        requestData[r * 2u] = n.rawPos.x;
        requestData[r * 2u + 1u] = n.rawPos.y;
        nodes[n.nodeId].raw.z |= 1u << 24;
      }
    }
  }
}

// Only the Metal view/LOD classification decides whether finer children are
// dispensable. Candidates carry world positions, so node-id reuse cannot evict
// a different subtree.
kernel void collect_evictions(device const Node* nodes [[buffer(0)]],
                              constant SceneUniform& scene [[buffer(9)]],
                              device atomic_uint* lastDescended [[buffer(17)]],
                              device atomic_uint* output [[buffer(18)]],
                              uint gid [[thread_position_in_grid]]) {
  if (gid >= scene.viewport.z) return;
  UnpackedNode n = unpackNode(nodes, gid);
  if (n.lodLevel == 0u || !hasMesh(n) ||
      (!hasChildren(n) && (!hasRequested(n) || (n.flags & (1u << 6u)) == 0u)))
    return;
  uint age = uint(scene.baseSectionFrame.w) -
             atomic_load_explicit(&lastDescended[gid], memory_order_relaxed);
  if (age < 30u || classifyNode(n, scene) == 2u) return;
  uint index = atomic_fetch_add_explicit(&output[0], 1u, memory_order_relaxed);
  if (index >= 256u) return;
  atomic_store_explicit(&output[1u + index * 2u], n.rawPos.x,
                        memory_order_relaxed);
  atomic_store_explicit(&output[2u + index * 2u], n.rawPos.y,
                        memory_order_relaxed);
}
