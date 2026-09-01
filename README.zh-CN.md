# Voxy 1.21.1 多加载器分支

![voxy-on-m4-max](assets/voxy-on-m4-max.png)

[English](README.md)

这是 [MCRcortex/Voxy](https://github.com/MCRcortex/voxy) 的非官方 Minecraft
1.21.1 实验性分支，主要提供原生 Fabric/NeoForge 构建，以及兼容 macOS 的
OpenGL 4.1 + Metal 渲染路径。

> [!WARNING]
> 这是非官方开发分支。使用本仓库构建时遇到的问题，请在本仓库反馈，不要提交到上游的支持渠道。

## 分支目标

| 组件 | 目标 |
| --- | --- |
| Minecraft | 1.21.1 |
| Java | 21 |
| 加载器 | Fabric、NeoForge |
| Sodium | 0.8.12 |
| Iris | 1.8.x；具体光影包兼容性可能不同 |
| 渲染后端 | GL46、实验性 GL41Metal |

本分支已经与当前上游开发分支产生较大差异，不会自动包含上游的最新修复。

## 渲染流水线

### GL46

GL46 后端沿用 Voxy 的 GPU 驱动渲染方式：OpenGL compute shader 执行层级遍历、
LOD 选择和剔除，再通过 GL46 indirect draw 路径提交选中的远景地形。

### GL41Metal

实验性的 macOS 后端将工作拆分给 Metal 和 OpenGL 4.1：

```text
Voxy 世界更新
  -> 镜像地形与模型缓冲区
  -> Metal 遍历、LOD 选择和剔除
  -> 统一内存中的逐帧紧凑工作列表
  -> OpenGL 4.1 远景地形光栅化
  -> Iris/Sodium 深度与半透明合成
```

Metal 负责远景地形选择；OpenGL 和 Iris 继续负责光栅化、近景、光影包 pass 与最终呈现。
该后端目前支持不透明与半透明地形、远景水体、雾、SSAO 和近景深度遮挡。

后端会自动选择：能力足够的系统使用 GL46；GL46 不可用时，受支持的 macOS
系统会回退到 GL41Metal。

## 光影包支持

本分支通过 Iris bridge 向远景地形 pass 提供 Voxy 深度、sampler、矩阵和兼容的
光影包 uniform。不透明地形会在光影包复制手部深度前接入，远景半透明地形则在
Sodium 绘制近景半透明场景前合成。

该功能受 Apple OpenGL 4.1 实现限制。Apple Silicon 会报告 16 个 fragment texture
unit，但使用全部 16 个可能导致驱动在链接 program 时崩溃。因此 GL41Metal 采用
15 个 texture unit 的安全上限：其中 1 个保留给 Voxy 方块图集，光影包最多剩余约
14 个 sampler。需要更多 sampler 的光影包无法使用直接 bridge 路径。这是 sampler
数量限制，并不是“最多 15 个普通 GLSL uniform”。

## 其他改动

- 共享渲染核心，并提供原生 Fabric 与 NeoForge 子项目。
- 通过 `VoxyRenderSystem` facade 管理明确的后端生命周期。
- 针对两个渲染后端调整 Iris 和 Sodium 集成。
- 从 M4G4MED 的 `mc_1211-aero` 分支移植并适配 Sable/Aero 载具兼容。
- 统一代码格式和源码结构，便于后续维护。

## 已知限制

- GL41Metal 仍是实验性功能，需要 macOS，以及打包进模组的原生库和 Metal shader。
- 支持 Iris 不代表兼容所有光影包。
- 超出 GL41Metal 安全 sampler 上限的光影包无法使用其直接 shader bridge。
- GL46 与 GL41Metal 的画面和性能尚未完全一致。
- GL41Metal 的性能仍会受到 Apple 旧版 OpenGL 4.1 驱动限制，尤其是 shader 编译、
  draw 提交、同步以及 fragment 阶段资源上限。Metal 加速了遍历和剔除，但没有取代
  OpenGL 光栅化路径。
- Fabric 与 NeoForge 使用同一套核心代码，但仍需分别进行实际客户端渲染测试。
- 这是开发分支，不保证与 Voxy 官方版本或其他 fork 兼容，也不提供相应的上游支持保证。

## 构建

需要 Java 21。

```shell
./gradlew spotlessCheck
./gradlew release
```

`release` 会明确构建可分发的 Fabric 和 NeoForge 产物。目前 `./gradlew build` 也会
选中两个加载器的 `build` 任务并生成相同 jar；`release` 是语义更清楚的仓库级命令，
将来也可以继续加入发布专用步骤。

仍可分别构建加载器版本：

```shell
./gradlew :fabric:build
./gradlew :neoforge:build
```

GL41Metal 原生代码与 Metal shader 会在 macOS 上构建并打包。

## 上游与致谢

Voxy 由 [MCRcortex](https://github.com/MCRcortex/voxy) 创建。本分支还包含来自
[m3t4f1v3/voxy](https://github.com/m3t4f1v3/voxy) 和
[M4G4MED/voxy](https://github.com/M4G4MED/voxy) 的工作，包括 Minecraft 1.21.1、
多加载器、兼容性和 Aero/Sable 相关改动。各项改动的具体归属请参阅 Git 历史。

## 许可证

参见 [LICENSE.md](LICENSE.md)。
