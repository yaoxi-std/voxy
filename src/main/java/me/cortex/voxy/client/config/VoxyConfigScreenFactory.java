package me.cortex.voxy.client.config;

import me.cortex.voxy.impl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * Shared config-screen construction used by the ModMenu (fabric) and IConfigScreenFactory
 * (neoforge) hooks.
 */
public class VoxyConfigScreenFactory {
  public static Screen create(Screen parent) {
    if (!VoxyCommon.isAvailable()) {
      return null;
    }
    OptionPage firstVoxyPage = null;
    if (ConfigManager.CONFIG != null) {
      firstVoxyPage =
          ConfigManager.CONFIG.getModOptions().stream()
              .filter(options -> options.configId().equals("voxy"))
              .flatMap(options -> options.pages().stream())
              .filter(OptionPage.class::isInstance)
              .map(OptionPage.class::cast)
              .findFirst()
              .orElse(null);
    }
    return VideoSettingsScreen.createScreen(parent, firstVoxyPage);
  }
}
