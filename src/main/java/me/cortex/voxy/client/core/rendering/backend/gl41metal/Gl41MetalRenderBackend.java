package me.cortex.voxy.client.core.rendering.backend.gl41metal;

import java.util.List;
import me.cortex.voxy.client.core.rendering.backend.BackendContext;
import me.cortex.voxy.client.core.rendering.backend.RenderBackendId;
import me.cortex.voxy.client.core.rendering.backend.RenderFrame;
import me.cortex.voxy.client.core.rendering.backend.RenderFrameContext;
import me.cortex.voxy.client.core.rendering.backend.RenderFrameMatrices;
import me.cortex.voxy.client.core.rendering.backend.RenderStage;
import me.cortex.voxy.client.core.rendering.backend.RenderStageContext;
import me.cortex.voxy.client.core.rendering.backend.ShaderPatchBridgePayload;
import me.cortex.voxy.client.core.rendering.backend.VoxyRenderBackend;
import me.cortex.voxy.client.core.rendering.backend.gl41metal.bridge.DistantChunkBoundRenderer;
import me.cortex.voxy.client.core.rendering.backend.gl41metal.bridge.DistantTerrainBridge;
import me.cortex.voxy.client.core.rendering.backend.gl41metal.bridge.FrameSlotContext;
import me.cortex.voxy.client.core.rendering.backend.gl41metal.bridge.SlotScheduler;
import me.cortex.voxy.client.core.rendering.backend.gl41metal.jni.NativeBindings;
import me.cortex.voxy.client.core.rendering.backend.gl41metal.terrain.LoadedVolumeBound;
import me.cortex.voxy.client.core.rendering.backend.gl41metal.terrain.TerrainResources;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public final class Gl41MetalRenderBackend implements VoxyRenderBackend {
  private final BackendContext context;
  private final Config config;
  private final DistantRenderer metalRenderer = new DistantRenderer();
  private final SlotScheduler slotScheduler;
  private final DistantTerrainBridge bridge = new DistantTerrainBridge();
  private final DrawlistOpaqueRenderer drawlistRenderer;
  private final DistantChunkBoundRenderer boundRenderer = new DistantChunkBoundRenderer();
  private final TerrainResources terrainResources;
  private final FrameSlotContext slots;

  private LoadedVolumeBound currentBound = LoadedVolumeBound.DISABLED;
  private RenderFrameMatrices lastFrameMatrices = RenderFrameMatrices.identity();
  private long nextFrameId;
  private boolean loggedMissingMatrices;
  private String loggedUnavailableReason = "";

  // Opaque and translucent consume the same native worklist slot in one host frame. Retirement is
  // deferred until the translucent stage has finished sampling the mirrored GL terrain buffers.
  private int heldTranslucentSlot = -1;
  private Frame heldTranslucentFrame;
  private RenderFrameContext heldTranslucentContext;

  public Gl41MetalRenderBackend(BackendContext context) {
    this.context = context;
    this.config = Config.fromSystemProperties();
    this.slotScheduler = new SlotScheduler(this.config.waitTimeoutMs());
    this.slots = FrameSlotContext.create(this.config.slotCount());
    this.terrainResources = new TerrainResources(context);
    this.drawlistRenderer =
        new DrawlistOpaqueRenderer(
            TerrainResources.maxResidentSections(), this.terrainResources.geometryCapacityBytes());
    this.terrainResources.setAtlasMirror(this.drawlistRenderer);
    this.terrainResources.setDrawlistMirror(this.drawlistRenderer);
    Logger.info("Created Voxy GL41Metal drawlist backend: " + this.slots.description());
  }

  @Override
  public RenderBackendId id() {
    return RenderBackendId.GL41METAL;
  }

  @Override
  public boolean rendersLodTerrain() {
    return true;
  }

  @Override
  public RenderFrame setupFrame(RenderFrameContext context) {
    return this.submitMetalFrame(context);
  }

  @Override
  public RenderFrame runFrameStage(
      RenderStage stage, RenderStageContext context, RenderFrame frame) {
    return switch (stage) {
      case FRAME_BEGIN ->
          context.shaderPackActive() ? this.submitMetalFrame(context.frameContext()) : frame;
      case VIEWPORT_SETUP, SODIUM_SOLID_SYNC, SODIUM_CUTOUT_SYNC, FRAME_END -> frame;
      case PRE_TRANSLUCENT -> {
        if (context.shaderPackActive()) {
          ShaderPatchBridgePayload payload =
              context.payload() instanceof ShaderPatchBridgePayload p ? p : null;
          if (payload != null && payload.strictBridgeAvailable()) {
            this.sampleDrawlistIrisOpaque(frame, context.frameContext(), payload);
          } else {
            this.logUnavailable(payload);
            this.consumeWithoutDrawing(frame);
          }
        }
        yield frame;
      }
      case TRANSLUCENT -> {
        if (context.shaderPackActive()) {
          this.sampleIrisTranslucent(context);
        } else {
          this.sampleVanillaTranslucent(context);
        }
        yield frame;
      }
      case OPAQUE -> {
        if (context.shaderPackActive()) {
          yield frame;
        }
        RenderFrame renderFrame =
            frame == null ? this.submitMetalFrame(context.frameContext()) : frame;
        this.sampleDrawlistOpaque(renderFrame, context.frameContext());
        yield renderFrame;
      }
    };
  }

  private RenderFrame submitMetalFrame(RenderFrameContext context) {
    this.releaseHeldTranslucentSlot();

    // A timed-out previous Metal submission may still read the shared hierarchy. Skip this
    // frame instead of mutating it or turning the configured bounded wait into a blocking wait.
    if (!NativeBindings.canUpdateTerrain(this.slots.nativeHandle())) {
      return new Frame(context, this.nextFrameId++, -1, new Matrix4f(), new Matrix4f());
    }
    this.terrainResources.tick(context, this.slots.nativeHandle());

    if (context.matrices() == null) {
      this.lastFrameMatrices = RenderFrameMatrices.identity();
      if (!this.loggedMissingMatrices) {
        this.loggedMissingMatrices = true;
        Logger.warn("GL41Metal skipped Metal submit because real frame matrices were unavailable");
      }
      return new Frame(context, this.nextFrameId++, -1, new Matrix4f(), new Matrix4f());
    }

    DistantRenderer.FrameMatrices matrices = DistantRenderer.computeFrameMatrices(context);
    this.lastFrameMatrices =
        new RenderFrameMatrices(
            matrices.traversalMvp(), context.matrices().modelView(), matrices.projection());
    long frameId = this.nextFrameId++;
    int writeSlot = this.slotScheduler.acquireWriteSlot(this.slots);
    if (writeSlot >= 0) {
      this.metalRenderer.submitTraversal(
          this.slots, writeSlot, frameId, context, matrices.traversalMvp(), matrices.drawMvp());
    } else {
      this.slotScheduler.recordNoFreeSlot();
    }
    return new Frame(context, frameId, writeSlot, matrices.drawMvp(), matrices.vanillaDrawMvp());
  }

  @Override
  public void renderOpaque(RenderFrame frame) {
    this.sampleDrawlistOpaque(frame, null);
  }

  private void sampleDrawlistOpaque(RenderFrame frame, RenderFrameContext stageContext) {
    Frame nativeFrame = this.requireFrame(frame);
    if (nativeFrame == null) {
      return;
    }
    RenderFrameContext renderContext = stageContext == null ? nativeFrame.context() : stageContext;
    int sampleSlot = this.waitForSlot(nativeFrame);
    if (sampleSlot < 0) {
      return;
    }
    boolean hasTranslucent =
        NativeBindings.isSlotTranslucentValid(this.slots.nativeHandle(), sampleSlot);
    boolean held = false;
    try {
      DistantRenderer.FrameMatrices matrices = DistantRenderer.computeFrameMatrices(renderContext);
      this.currentBound =
          hasTranslucent
              ? this.renderCurrentBound(matrices.drawMvp(), renderContext)
              : LoadedVolumeBound.DISABLED;
      this.drawlistRenderer.render(
          this.slots.nativeHandle(),
          sampleSlot,
          renderContext,
          matrices.drawMvp(),
          matrices.vanillaDrawMvp(),
          this.config.visibleComposite());
      if (hasTranslucent) {
        this.holdTranslucentSlot(sampleSlot, nativeFrame, renderContext);
        held = true;
      }
    } finally {
      if (!held) {
        this.slotScheduler.queueSampledSlotRetirement(this.slots, sampleSlot);
      }
    }
  }

  private void sampleDrawlistIrisOpaque(
      RenderFrame frame, RenderFrameContext stageContext, ShaderPatchBridgePayload payload) {
    Frame nativeFrame = this.requireFrame(frame);
    if (nativeFrame == null) {
      return;
    }
    RenderFrameContext renderContext = stageContext == null ? nativeFrame.context() : stageContext;
    int sampleSlot = this.waitForSlot(nativeFrame);
    if (sampleSlot < 0) {
      return;
    }
    boolean hasTranslucent =
        NativeBindings.isSlotTranslucentValid(this.slots.nativeHandle(), sampleSlot);
    boolean held = false;
    try {
      DistantRenderer.FrameMatrices matrices = DistantRenderer.computeFrameMatrices(renderContext);
      this.currentBound =
          hasTranslucent
              ? this.renderCurrentBound(matrices.drawMvp(), renderContext)
              : LoadedVolumeBound.DISABLED;
      boolean rendered =
          this.drawlistRenderer.renderIrisOpaque(
              this.slots.nativeHandle(),
              sampleSlot,
              renderContext,
              matrices.drawMvp(),
              matrices.vanillaDrawMvp(),
              payload,
              this.bridge,
              this.config.visibleComposite());
      if (rendered) {
        this.holdTranslucentSlot(sampleSlot, nativeFrame, renderContext);
        held = true;
      }
    } finally {
      if (!held) {
        this.slotScheduler.queueSampledSlotRetirement(this.slots, sampleSlot);
      }
    }
  }

  private void sampleIrisTranslucent(RenderStageContext context) {
    int slot = this.heldTranslucentSlot;
    if (slot < 0 || this.heldTranslucentFrame == null) {
      return;
    }
    ShaderPatchBridgePayload payload =
        context.payload() instanceof ShaderPatchBridgePayload p ? p : null;
    try {
      if (payload == null || !payload.strictBridgeAvailable()) {
        this.logUnavailable(payload);
        return;
      }
      RenderFrameContext renderContext =
          context.frameContext() != null ? context.frameContext() : this.heldTranslucentContext;
      DistantRenderer.FrameMatrices matrices = DistantRenderer.computeFrameMatrices(renderContext);
      this.drawlistRenderer.renderIrisTranslucent(
          this.slots.nativeHandle(),
          slot,
          renderContext,
          matrices.drawMvp(),
          matrices.vanillaDrawMvp(),
          this.currentBound,
          payload,
          this.bridge,
          this.config.visibleComposite());
    } finally {
      this.releaseHeldTranslucentSlot();
    }
  }

  private void sampleVanillaTranslucent(RenderStageContext context) {
    int slot = this.heldTranslucentSlot;
    if (slot < 0 || this.heldTranslucentFrame == null) {
      return;
    }
    try {
      RenderFrameContext renderContext =
          context.frameContext() != null ? context.frameContext() : this.heldTranslucentContext;
      DistantRenderer.FrameMatrices matrices = DistantRenderer.computeFrameMatrices(renderContext);
      this.drawlistRenderer.renderTranslucent(
          this.slots.nativeHandle(),
          slot,
          renderContext,
          matrices.drawMvp(),
          matrices.vanillaDrawMvp(),
          this.currentBound,
          this.config.visibleComposite());
    } finally {
      this.releaseHeldTranslucentSlot();
    }
  }

  private int waitForSlot(Frame frame) {
    return this.slotScheduler.selectSlotForSampling(this.slots, frame.writeSlot());
  }

  private void consumeWithoutDrawing(RenderFrame frame) {
    Frame nativeFrame = this.requireFrame(frame);
    if (nativeFrame == null) {
      return;
    }
    int slot = this.waitForSlot(nativeFrame);
    if (slot >= 0) {
      this.slotScheduler.queueSampledSlotRetirement(this.slots, slot);
    }
  }

  private Frame requireFrame(RenderFrame frame) {
    if (frame == null) {
      return null;
    }
    if (!(frame instanceof Frame nativeFrame)) {
      throw new IllegalArgumentException(
          "Cannot render frame for backend " + frame.backendId() + " with GL41Metal backend");
    }
    return nativeFrame;
  }

  private void holdTranslucentSlot(int slot, Frame frame, RenderFrameContext context) {
    this.heldTranslucentSlot = slot;
    this.heldTranslucentFrame = frame;
    this.heldTranslucentContext = context;
  }

  private LoadedVolumeBound renderCurrentBound(Matrix4fc drawMvp, RenderFrameContext context) {
    int worldMinY = -64;
    int worldMaxY = 320;
    var level = Minecraft.getInstance().level;
    if (level != null) {
      worldMinY = level.getMinSection() << 4;
      worldMaxY = level.getMaxSection() << 4;
    }
    int verticalRadiusBlocks = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
    return this.boundRenderer.render(
        drawMvp,
        context.cameraX(),
        context.cameraY(),
        context.cameraZ(),
        worldMinY,
        worldMaxY,
        verticalRadiusBlocks,
        context.viewportWidth(),
        context.viewportHeight());
  }

  private void releaseHeldTranslucentSlot() {
    if (this.heldTranslucentSlot >= 0) {
      this.slotScheduler.queueSampledSlotRetirement(this.slots, this.heldTranslucentSlot);
    }
    this.dropHeldTranslucentSlot();
  }

  private void dropHeldTranslucentSlot() {
    this.heldTranslucentSlot = -1;
    this.heldTranslucentFrame = null;
    this.heldTranslucentContext = null;
  }

  private void logUnavailable(ShaderPatchBridgePayload payload) {
    String reason = payload == null ? "missing shader-pack payload" : payload.unavailableReason();
    if (!reason.equals(this.loggedUnavailableReason)) {
      this.loggedUnavailableReason = reason;
      Logger.warn("GL41Metal direct Iris output skipped: " + reason);
    }
  }

  @Override
  public void setRenderDistance(float renderDistance) {
    this.terrainResources.setRenderDistance(renderDistance);
  }

  @Override
  public void addDebugInfo(List<String> debug) {
    debug.add("Voxy backend: GL41METAL drawlist");
    debug.add("Voxy GL41Metal selection: " + this.context.selection().reason());
    debug.add("Voxy GL41Metal config: " + this.config);
    debug.add("Voxy GL41Metal native: " + this.slots.description());
    this.slotScheduler.addDebugInfo(debug);
    this.terrainResources.addDebugInfo(debug);
  }

  @Override
  public RenderFrameMatrices getLastFrameMatrices() {
    return this.lastFrameMatrices;
  }

  @Override
  public int voxyDistantOpaqueDepthTextureId() {
    return this.bridge.voxyDistantOpaqueDepthTextureId();
  }

  @Override
  public int voxyDistantTranslucentDepthTextureId() {
    return this.bridge.voxyDistantTranslucentDepthTextureId();
  }

  @Override
  public void onChunkTrackerReset() {
    this.boundRenderer.reset();
    this.terrainResources.onChunkTrackerReset();
  }

  @Override
  public void onSectionRenderStateChanged(long sectionPos, boolean present) {
    if (present) {
      this.boundRenderer.addSection(sectionPos);
    } else {
      this.boundRenderer.removeSection(sectionPos);
    }
    this.terrainResources.onSectionRenderStateChanged(sectionPos, present);
  }

  @Override
  public void close() {
    Logger.info("Shutting down Voxy GL41Metal drawlist backend");
    this.dropHeldTranslucentSlot();
    this.terrainResources.close();
    this.drawlistRenderer.close();
    this.bridge.close();
    this.boundRenderer.close();
    this.slotScheduler.closeRetiringSlots(this.slots);
    this.slots.close();
  }
}
