package me.cortex.voxy.validation;

import java.util.ArrayList;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.VoxyRenderSystemAccess;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional development-only client acceptance driver; never included in release builds. */
@Mixin(Minecraft.class)
public class GeometryValidation {
  @Unique private long voxy$started;
  @Unique private int voxy$step = -1;

  @Inject(method = "tick", at = @At("TAIL"))
  private void voxy$validate(CallbackInfo ci) {
    Minecraft mc = (Minecraft) (Object) this;
    if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
    if (this.voxy$started == 0) this.voxy$started = System.nanoTime();
    int step = (int) ((System.nanoTime() - this.voxy$started) / 15_000_000_000L);
    if (step == this.voxy$step) return;
    this.voxy$step = step;
    var renderer = VoxyRenderSystemAccess.getNullable();
    var debug = new ArrayList<String>();
    if (renderer != null) renderer.addDebugInfo(debug);
    Logger.info("GEOMETRY_VALIDATION step=" + step + " fps=" + mc.getFps() + " " + debug);
    if (step == 0) {
      this.voxy$command(mc, "gamemode spectator @a");
      this.voxy$command(mc, "tp @a 0 180 0 0 25");
    } else if (step == 1) {
      this.voxy$command(mc, "tp @a 0 180 0 180 25");
    } else if (step == 2) {
      this.voxy$command(mc, "tp @a 1024 180 0 90 25");
    } else if (step == 3) {
      this.voxy$command(mc, "tp @a -1024 180 -1024 270 25");
    } else if (step == 4 || step == 5) {
      mc.options.renderDistance().set(step == 4 ? 8 : 16);
      mc.levelRenderer.allChanged();
      VoxyConfig.CONFIG.subDivisionSize = step == 4 ? 256 : 64;
    } else if (step == 6 || step == 7) {
      float distance = step == 6 ? 16 : 64;
      VoxyConfig.CONFIG.sectionRenderDistance = distance;
      if (renderer != null) renderer.setRenderDistance(distance);
    } else if (step == 8) {
      var access = (VoxyRenderSystemAccess) mc.levelRenderer;
      access.voxy$shutdownRenderer();
      access.voxy$createRenderer();
    } else if (step == 9) {
      this.voxy$command(mc, "tp @a 0 180 0 0 25");
    } else if (step
        >= Math.max(12, Integer.getInteger("voxy.validation.durationSeconds", 180) / 15)) {
      Logger.info("GEOMETRY_VALIDATION completed");
      mc.stop();
      return;
    }
    if (step == 1 || step == 5 || step == 10) {
      Screenshot.grab(
          mc.gameDirectory,
          mc.getMainRenderTarget(),
          message -> Logger.info("GEOMETRY_VALIDATION screenshot: " + message.getString()));
    }
  }

  @Unique
  private void voxy$command(Minecraft mc, String command) {
    var server = mc.getSingleplayerServer();
    server.execute(
        () ->
            server
                .getCommands()
                .performPrefixedCommand(server.createCommandSourceStack(), command));
  }
}
