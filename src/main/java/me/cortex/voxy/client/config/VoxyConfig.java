package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import me.cortex.voxy.client.core.rendering.post.SSAO;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.platform.PlatformAccess;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.impl.VoxyCommon;
import me.cortex.voxy.impl.compat.sable.SableContraptionRenderDistance;

public class VoxyConfig {
  private static final Gson GSON =
      new GsonBuilder()
          .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
          .setPrettyPrinting()
          .excludeFieldsWithModifiers(Modifier.PRIVATE)
          .create();

  public static VoxyConfig CONFIG = loadOrCreate();

  public boolean enabled = true;
  public boolean enableRendering = true;
  public boolean ingestEnabled = true;
  public float sectionRenderDistance = 16;

  // Sable compat: % interpolation between the vanilla and voxy render distance used as the
  // simulated contraption render distance. Only persisted when Sable is installed.
  public int simulatedContraptionRenderDistancePercent = 50;
  public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount() / 1.5, 1);
  public float subDivisionSize = 64;
  public boolean renderVoxyFog = true;
  public int skyFogDistance = 96;
  public float fogIntensity = 1.0f;
  public float fogDensity = 0.0f;
  public boolean adaptCloudDistance = true;
  public int cloudDistance = 0;
  public boolean dontUseSodiumBuilderThreads = false;

  // LOD boundary buffer (gl41metal): safety margin between vanilla chunks and LOD rendering.
  // Higher values = more overlap, prevents pop-in at chunk boundaries when flying.
  // Range: 0-4 blocks, default 1 (original Voxy behavior)
  public int lodBoundaryBuffer = 1;

  // World curvature (gl41metal): simulates standing on a spherical planet.
  // 0 = disabled (flat world), 1 = real Earth curvature (6371km radius),
  // higher values = more extreme curvature. Range: 0, or 50-5000.
  public int earthCurveRatio = 0;

  public String ssaoMode;

  public SSAO.SSAOMode getSSAOMode() {
    if (this.ssaoMode == null) return SSAO.SSAOMode.AUTO;
    if ("none".equalsIgnoreCase(this.ssaoMode) || "off".equalsIgnoreCase(this.ssaoMode)) {
      return SSAO.SSAOMode.AUTO;
    }
    try {
      return SSAO.SSAOMode.valueOf(this.ssaoMode.toUpperCase(Locale.ROOT));
    } catch (Exception e) {
      return SSAO.SSAOMode.AUTO;
    }
  }

  public void setSSAOMode(SSAO.SSAOMode mode) {
    this.ssaoMode = mode.name().toLowerCase(Locale.ROOT);
  }

  private static VoxyConfig loadOrCreate() {
    if (VoxyCommon.isAvailable()) {
      var path = getConfigPath();
      if (Files.exists(path)) {
        try (FileReader reader = new FileReader(path.toFile())) {
          var conf = GSON.fromJson(reader, VoxyConfig.class);
          if (conf != null) {
            conf.save();
            return conf;
          } else {
            Logger.error("Failed to load voxy config, resetting");
          }
        } catch (IOException e) {
          Logger.error("Could not parse config", e);
        }
      }
      Logger.info("Config doesnt exist, creating new");
      var config = new VoxyConfig();
      config.save();
      return config;
    } else {
      var config = new VoxyConfig();
      config.enabled = false;
      config.enableRendering = false;
      return config;
    }
  }

  public void save() {
    if (!VoxyCommon.isAvailable()) {
      Logger.info("Not saving config since voxy is unavalible");
      this.syncSableContraptionRenderDistance();
      return;
    }

    try {
      JsonObject json = GSON.toJsonTree(this).getAsJsonObject();
      if (!PlatformAccess.get().isModLoaded("sable")) {
        json.remove("simulated_contraption_render_distance_percent");
      }
      Files.writeString(getConfigPath(), GSON.toJson(json));
    } catch (IOException e) {
      Logger.error("Failed to write config file", e);
    }

    this.syncSableContraptionRenderDistance();
  }

  private static Path getConfigPath() {
    return PlatformAccess.get().getConfigDir().resolve("voxy-config.json");
  }

  public boolean isRenderingEnabled() {
    return VoxyCommon.isAvailable() && this.enabled && this.enableRendering;
  }

  public void syncSableContraptionRenderDistance() {
    SableContraptionRenderDistance.updateClientConfig(
        this.isRenderingEnabled(),
        this.sectionRenderDistance,
        this.simulatedContraptionRenderDistancePercent);
  }
}
