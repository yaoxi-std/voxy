package me.cortex.voxy.client.core.rendering.geometry;

import java.util.function.Consumer;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;

public interface GeometryManager {
  boolean canUpload(int oldId, BuiltSection section);

  int uploadSection(BuiltSection section);

  int uploadReplaceSection(int oldId, BuiltSection section);

  void removeSection(int id);

  void downloadAndRemove(int id, Consumer<BuiltSection> callback);
}
