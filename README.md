# Voxy 1.21.1 Multi-loader Fork

![voxy-on-m4-max](assets/voxy-on-m4-max.png)

[中文](README.zh-CN.md)

An experimental Minecraft 1.21.1 fork of
[MCRcortex/Voxy](https://github.com/MCRcortex/voxy), focused on native Fabric and
NeoForge builds and a macOS-compatible GL 4.1 + Metal rendering path.

> [!WARNING]
> This is an unofficial development fork. Please report issues with builds from
> this repository here rather than to upstream support channels.

## Branch targets

| Component | Target |
| --- | --- |
| Minecraft | 1.21.1 |
| Java | 21 |
| Loaders | Fabric and NeoForge |
| Sodium | 0.8.12 |
| Iris | 1.8.x; shader-pack compatibility varies |
| Render backends | GL46 and experimental GL41Metal |

This branch has diverged from the current upstream development branches and
does not automatically include their latest fixes.

## Rendering pipeline

### GL46

The GL46 backend follows Voxy's conventional GPU-driven renderer: OpenGL
compute shaders perform hierarchical traversal, LOD selection, and culling,
then the selected terrain is submitted through the GL46 indirect draw path.

### GL41Metal

The experimental macOS backend splits the work between Metal and OpenGL 4.1:

```text
Voxy world updates
  -> mirrored terrain and model buffers
  -> Metal traversal, LOD selection, and culling
  -> compact per-frame worklists in unified memory
  -> OpenGL 4.1 distant-terrain rasterization
  -> Iris/Sodium depth and translucent composition
```

Metal owns distant-terrain selection; OpenGL and Iris remain responsible for
rasterization, the near scene, shader-pack passes, and final presentation. The
backend supports opaque and translucent terrain, distant water, fog, SSAO, and
near-scene depth occlusion.

Backend selection is automatic: capable systems use GL46, while supported
macOS systems fall back to GL41Metal when GL46 is unavailable.

## Shader-pack support

Iris shader packs are supported through a bridge that exposes Voxy depth,
samplers, matrices, and compatible pack uniforms to the distant-terrain pass.
Opaque terrain is integrated before the pack's hand-depth copy, while distant
translucent terrain is composed before Sodium draws the near translucent scene.

Support is limited by Apple's OpenGL 4.1 implementation. Apple Silicon reports
16 fragment texture units, but using all 16 can crash the driver while linking
a program. GL41Metal therefore uses a safe budget of 15 texture units: one is
reserved for Voxy's block atlas, leaving at most 14 shader-pack samplers. Packs
that require more samplers cannot use the direct bridge path. This is a sampler
limit, not a limit of 15 ordinary GLSL uniforms.

## Other changes

- Shared renderer core with native Fabric and NeoForge subprojects.
- Explicit backend lifecycle through the `VoxyRenderSystem` facade.
- Iris and Sodium integration adapted for both rendering backends.
- Sable/Aero contraption compatibility ported and adapted from M4G4MED's
  `mc_1211-aero` branch.
- Formatting and source layout standardized for ongoing maintenance.

## Known limitations

- GL41Metal is experimental and requires macOS plus bundled native and Metal
  shader assets.
- Iris support does not guarantee compatibility with every shader pack.
- Shader packs exceeding the safe GL41Metal sampler budget cannot use its
  direct shader bridge.
- GL46 and GL41Metal are not yet visually or performance-identical.
- GL41Metal performance is partly limited by Apple's legacy OpenGL 4.1 driver,
  especially shader compilation, draw submission, synchronization, and
  fragment-stage resource limits. Metal accelerates traversal and culling but
  does not replace the OpenGL rasterization path.
- Fabric and NeoForge both build from the shared core, but renderer behavior
  still requires in-game validation on each loader.
- This is a development fork; no compatibility or support guarantee is made for
  official Voxy releases or other forks.

## Building

Java 21 is required.

```shell
./gradlew spotlessCheck
./gradlew release
```

`release` explicitly builds the distributable Fabric and NeoForge artifacts.
At present, `./gradlew build` also selects both loader `build` tasks and produces
the same jars; `release` is the clearer repository-level command and may gain
additional release-only steps later.

Loader-specific builds remain available:

```shell
./gradlew :fabric:build
./gradlew :neoforge:build
```

GL41Metal native code and Metal shaders are built and packaged on macOS.

## Upstream and credits

Voxy was created by [MCRcortex](https://github.com/MCRcortex/voxy). This branch
also incorporates work from
[m3t4f1v3/voxy](https://github.com/m3t4f1v3/voxy) and
[M4G4MED/voxy](https://github.com/M4G4MED/voxy), including Minecraft 1.21.1,
multi-loader, compatibility, and Aero/Sable work. See the Git history for
individual attribution.

## License

See [LICENSE.md](LICENSE.md).
