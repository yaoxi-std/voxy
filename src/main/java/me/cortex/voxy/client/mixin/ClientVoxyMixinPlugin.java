package me.cortex.voxy.client.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import me.cortex.voxy.common.platform.PlatformAccess;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public class ClientVoxyMixinPlugin implements IMixinConfigPlugin {
  private static boolean valkyrienSkiesInstalled;
  private static boolean nvidiumInstalled;
  private static boolean sableInstalled = false;

  @Override
  public void onLoad(String mixinPackage) {
    // PlatformUtilImpl is early-load-safe on both loaders (FabricLoader / LoadingModList).
    var platform = PlatformAccess.get();
    valkyrienSkiesInstalled = platform.isModLoaded("valkyrienskies");
    nvidiumInstalled = platform.isModLoaded("nvidium");
    sableInstalled = platform.isModLoaded("sable");
  }

  @Override
  public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
    return true;
  }

  @Override
  public List<String> getMixins() {
    List<String> mixins = new ArrayList<>();
    if (valkyrienSkiesInstalled && !nvidiumInstalled) {
      mixins.add("sodium.MixinSodiumWorldRendererVS");
    } else {
      mixins.add("sodium.MixinDefaultChunkRenderer");
    }

    if (sableInstalled) {
      mixins.add("minecraft.MixinGameRendererSableRenderDistance");
      mixins.add("sable.MixinSableReacharoundCulling");
      mixins.add("sable.MixinSableDepthShim");
    }

    return mixins;
  }

  @Override
  public String getRefMapperConfig() {
    return null;
  }

  @Override
  public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

  @Override
  public void preApply(
      String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

  @Override
  public void postApply(
      String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
