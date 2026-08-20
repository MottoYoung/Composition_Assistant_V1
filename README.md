# Composition Assistant V1

Assistant_v1 是一个面向“辅助拍摄 / 构图引导”的实验项目：用户先拍一张当前场景图，后端给出多个可行构图方案与参考图，Android 客户端再根据这些目标构图做实时闭环引导，帮助用户把手机取景调整到更接近目标效果。

项目内部的应用名仍以 PhotoFramer 为主，当前仓库主要由两部分组成：

- `android_frontend`：Android 前端，负责相机预览、拍照、候选展示、实时引导与最终拍摄
- `backend`：三阶段后端主干，负责拍摄方向/构图候选筛选、构图分析与参考图生成

## 分支定位（重要）

截至 2026-08-20，项目代码主要看两个长期分支，对应稳定基线和仍在验证的 ARCore 路线：

| 分支 | 定位 | 已实现 | 尚未证明 |
| --- | --- | --- | --- |
| `main` | 稳定集成基线 | CameraX 预览/拍照；OpenCV、ML Kit、特征匹配与单应分析驱动的视觉闭环；三阶段 AI 后端默认通过 Gemini Enterprise Agent Platform v1 调用 | ARCore 未接入正式相机管线，不能把 `main` 描述为基于 6DoF 位姿的引导系统 |
| `codex/feat-arcore-semantic-guidance` | 实验性 ARCore 语义引导 | Camera2 + ARCore SharedCamera；相机时间轴同步的 6DoF 与深度；参考图定位、稀疏地图、目标锚点、局部 BA；人像/风景分流的主体约束；由最终参考图驱动的三阶段闭环 | 尚未合并 `main`；仍需在更多真机和场景中验证目标定位成功率、尺度与旋转误差、长期漂移、弱纹理/大透视鲁棒性及失效恢复 |

原 `codex/feat-camera2-arcore-shared-pipeline` 的 Camera2/SharedCamera 工作已经包含在 `codex/feat-arcore-semantic-guidance` 中，因此不再单独保留。`mty`、`haorenyu` 也已从本地删除；远端若仍显示同名分支，只代表尚未清理的历史引用，不是当前开发线。

因此，`main` 仍代表当前可稳定集成的视觉基线；ARCore 分支已经形成实验性的目标位姿闭环，但不代表它已达到可交付精度。

ARCore 基础准确性验证由独立仓库 `ARCore_Validation_App` 承担。该工具通过 `ArCoreValidationActivity` 提供受控动作流程、实时状态提示和逐帧 CSV 日志，用来单独检查方向、尺度、漂移、跳变和前后台恢复。现有日志只能说明部分方向与内部连续性；在加入标定板、动捕等独立参照并完成正式实验前，不应宣称 ARCore 位姿准确可靠。

## 仓库里有什么

```text
Composition_Assistant_V1
├── android_frontend       # Android 客户端
├── backend                # 三阶段后端主干
├── API_access_tests       # 接口侧实验/验证脚本
└── test_images            # 测试图片
```

`Gemini_prompt_tests` 中包含早期提示词实验、生成图片和测试结果，体积较大，已移出当前仓库并单独归档。它不是 Android 客户端或后端的运行依赖，对外交付项目时无需包含；只有在追溯早期 Prompt 实验时才需要从独立归档中恢复。

## 核心能力

- AI 构图分析：当前默认的摄影眼模式会先发现可拍摄方向，再生成候选方案；保留固定构图 technique 模式兼容
- 多候选方案输出：每个候选返回步骤化建议与参考图
- 流式结果返回：后端支持 SSE，将候选逐个推送给前端
- 实时本地验证：main 前端以 OpenCV、ML Kit 和图像几何分析做闭环引导
- 实验性 ARCore 引导：功能分支融合 6DoF、深度、语义约束与参考图定位，估计目标机位并输出移动提示
- 画面内构图：支持基于裁切推荐的“画面内重构图”模式
- Provider 组合：Stage 0 / Stage 1 可在 Qwen 与 Gemini 之间切换，Stage 2 还支持 OpenRouter

## 系统结构

```mermaid
flowchart LR
    A["Android main：CameraX 取景"] --> B["拍照并预处理"]
    B --> C["后端 composition_analyze / composition_analyze_stream"]
    C --> D["Stage 0 软预筛 / 摄影眼方向选择"]
    D --> E["Stage 1 构图分析"]
    E --> F["Stage 2 参考图生成"]
    F --> G["候选 compositions 返回前端"]
    G --> H["用户选择一个方案"]
    H --> I["前端 StepValidator 实时验证"]
    I --> J["引导完成后拍摄"]
```

从职责上看：

- 后端负责“生成目标构图”
- 前端负责“把当前画面拉向目标构图”

## 技术栈概览

### Android 端

- Kotlin + Jetpack Compose
- CameraX（`main`）；Camera2 + ARCore SharedCamera（`codex/feat-arcore-semantic-guidance`）
- Lifecycle + ViewModel + StateFlow
- Retrofit + OkHttp
- OpenCV
- ML Kit Object Detection
- ARCore（仅实验分支接入正式相机管线与目标位姿闭环，准确性尚未充分验证）

### 后端

- Python + FastAPI
- Pydantic
- `google-genai`
- DashScope / OpenAI-compatible client
- Pillow
- SSE（`StreamingResponse`）

## 快速开始

### 1. 启动后端

详细说明见 [backend/README.md](./backend/README.md)。

首次运行前，从 [`.env.example`](./.env.example) 创建本地 `.env` 并注入当前 shell，或直接设置环境变量：

```bash
cp .env.example .env
set -a && source .env && set +a
# 也可以分别 export：
export GOOGLE_CLOUD_PROJECT=picgen-503700
export GOOGLE_API_KEY=replace-with-agent-platform-api-key
```

`GOOGLE_API_KEY` 必须是在该 Google Cloud 项目中创建的标准 API Key；`.env` 已被 Git 忽略。Gemini 官方调用使用 Enterprise Agent Platform v1，项目 ID 仅用于本地校验和日志，绝不传入 SDK Client。

如果要切换到 Qwen、OpenRouter 或混合 provider，请修改 `backend/config.py` 中的：

- `STAGE0_PROVIDER`
- `STAGE1_PROVIDER`
- `STAGE2_PROVIDER`
- `USE_GEMINI_PROXY`
- `USE_GEMINI_DOMESTIC_API`

启动后可访问：

- `http://localhost:8100/docs`
- `http://localhost:8100/health`

### 2. 配置 Android 前端指向后端

详细说明见 [android_frontend/README.md](./android_frontend/README.md)。

默认情况下，前端代码里的：

- `AI_COMPOSITION_URL` 指向公网地址 `http://aicrop.312237.xyz/`
- `IN_FRAME_COMPOSITION_URL` 指向外部裁切服务 `https://crop2.312237.xyz/predict?return_preview=0`

如果你要联调本地后端，请修改：

- `android_frontend/app/src/main/java/com/photoframer/data/api/ApiConfig.kt`

如果你在 Android 模拟器里访问本机后端，可用：

```kotlin
const val AI_COMPOSITION_URL = "http://10.0.2.2:8100/"
```

如果你在真机上访问本机后端，请改成电脑局域网 IP，例如：

```kotlin
const val AI_COMPOSITION_URL = "http://192.168.1.20:8100/"
```

### 3. 运行 Android 客户端

```text
用 Android Studio 打开 android_frontend
-> 等待 Gradle Sync
-> 连接真机或启动模拟器
-> Run
```
## 服务器以及网络配置
项目的两个构图后端都在服务器 mty@10.112.242.164的用户文件夹下。

### 画面内构图后端
运行方式见 /home/mty/RethinkingImageCropping_resnet50_APIVERSION/crop_backend/README.md

### ai构图后端
ai构图后端为该项目后端的复制，运行方式见
/home/mty/RethinkingImageCropping_resnet50_APIVERSION/crop_backend/README.md

### 代理配置
xray代理在服务器 端口为11087，使用gemini、openrouter 模型需要开启xray代理
/home/mty/xray/start.md

### 内网穿透配置
内网穿透使用 cloudeflare tunnel
- 启动脚本：/home/mty/start_cf_tunnel.sh
- 终止脚本：/home/mty/stop_cf_tunnel.sh
- log: /home/mty/tunnel2.log
- log: /home/mty/tunnel1.log


## 推荐阅读顺序

如果你第一次接手这个仓库，推荐按这个顺序看：

1. 本 README
2. [android_frontend/README.md](./android_frontend/README.md)
3. [backend/README.md](./backend/README.md)
4. `android_frontend/app/src/main/java/com/photoframer/ui/screens/CameraScreen.kt`
5. `android_frontend/app/src/main/java/com/photoframer/viewmodel/CameraViewModel.kt`
6. `backend/routers/composition.py`
7. `backend/services/common.py`

## 关键事实和注意事项

- 后端主干现在位于 `backend/`
- 后端实际运行架构是 Stage 0 / Stage 1 / Stage 2 三阶段流水线；当前摄影眼模式默认开启 Stage 0，以生成运行时拍摄方向任务
- Android 前端默认优先走流式接口，流式失败后再回退普通接口
- `main` 的有效实时引导仍以视觉匹配和图像几何分析为基础；其中出现 ARCore 依赖或历史实验代码，不等于 AR 已真正接入正式相机管线
- 当前唯一保留的 ARCore 功能分支是 `codex/feat-arcore-semantic-guidance`；它已经吸收原 SharedCamera 分支的工作，但尚未合并到 `main`
- 画面内构图服务当前是外部 URL，不在本仓库内
- 后端 API key 从环境变量读取；provider、模型、代理、超时等运行配置主要在 `backend/config.py`
- Android 端当前声明了 Coil 依赖，但主干实现的核心图片显示并不依赖它

## 文档入口

- Android 端详细说明：[android_frontend/README.md](./android_frontend/README.md)
- 后端详细说明：[backend/README.md](./backend/README.md)
- 早期 Gemini Prompt 实验已移出仓库并单独归档，不在当前交付范围内
