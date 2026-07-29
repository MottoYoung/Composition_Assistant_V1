# PhotoFramer Android 前端

`android_frontend` 是 PhotoFramer 的 Android 客户端。它负责相机预览、拍照、把图片发给后端获取候选构图方案，并在用户选定一个方案后利用本地视觉验证链做实时引导，直到用户完成拍摄。

## main 与实验性 AR 分支

请先区分两条路线：

- `main` 是当前基于视觉的应用主线：使用 CameraX 提供相机能力，实时引导主要依赖 OpenCV 特征匹配、单应分析和其他图像信号。main 中的 ARCore 依赖、接口或历史实验代码不构成一次真正的 Camera2/ARCore 联合接入。
- `codex/feat-camera2-arcore-shared-pipeline` 是实验性 AR 分支：它把正式相机管线重构为 Camera2 + ARCore `SharedCamera`，让预览、CPU 分析帧、JPEG 拍照、缩放/闪光灯和 ARCore 6DoF 共用同一相机会话，并在界面显示实时位姿。

该 AR 分支为后续基于位姿的移动引导提供了相机与位姿基础设施，但当前只验证了预览稳定、能够进入 `TRACKING`、位姿连续输出、拍照和生命周期恢复。ARCore 位姿相对物理真值的准确性尚未充分验证，不能仅凭 `TRACKING`、实时数字变化或 ARCore 内部回位结果宣称精度可靠。

准确性实验使用独立仓库 `ARCore_Validation_App`。它的入口为 `ArCoreValidationActivity`，能够强制受控动作顺序、显示跟踪/门控状态并记录逐帧 CSV，专门用于分析方向、米制尺度、漂移、跳变和恢复。该工具仍需接入标定板、动捕等独立参照，才能形成正式准确性结论。

## 这一端负责什么

Android 端的职责不是“只展示后端结果”，而是完整承接下面这条链：

1. CameraX 取景与拍照
2. 图片预处理
3. 请求后端分析当前场景
4. 展示多个候选构图方案
5. 让用户选中某个方案进入引导
6. 结合 OpenCV / ML Kit 和图像几何信号实时判断当前取景是否已经接近目标构图
7. 完成后引导用户拍摄最终照片

## main 当前主要能力

- 全屏相机预览
- AI 构图分析
- 流式候选返回与候选列表展示
- 画面内构图模式
- 实时步骤式引导
- 参考图缩略图展示
- 镜头切换、缩放、闪光灯、网格、倒计时、连拍

## 架构总览

主调用链可以简单理解为：

```text
MainActivity
  -> CameraScreen
     -> CameraViewModel
        -> CompositionRepository
           -> Retrofit / OkHttp
              -> 后端接口
```

实时验证链则是：

```text
CameraX ImageAnalysis
  -> 当前帧 Bitmap
  -> StepValidator
  -> StepValidationResult
  -> GuidanceOverlay / TopGuidanceBar
```

实验性 AR 分支的相机与位姿链是：

```text
Camera2 + ARCore SharedCamera
  -> 同一 CaptureSession
     -> ARCore GPU 相机纹理：实时预览
     -> Frame.acquireCameraImage()：CPU 分析帧
     -> JPEG ImageReader：拍照
     -> displayOrientedPose：屏幕坐标系 6DoF
  -> CameraPoseSample
  -> 位姿显示 / 后续位姿残差引导
```

## UI 状态流

当前 UI 主状态定义在：

- `app/src/main/java/com/photoframer/ui/state/CameraUiState.kt`

主要状态包括：

- `Preview`
- `Analyzing`
- `Candidates`
- `Guiding`
- `Error`

这意味着前端不是“页面乱跳”，而是一个比较清晰的状态机：

```text
Preview -> Analyzing -> Candidates -> Guiding
Preview -> Analyzing -> Guiding
任意阶段 -> Error
Guiding -> Candidates / Preview
```

## 目录结构

```text
android_frontend
├── app
│   ├── build.gradle.kts
│   └── src/main
│       ├── AndroidManifest.xml
│       ├── java/com/photoframer
│       │   ├── MainActivity.kt
│       │   ├── data
│       │   │   ├── api
│       │   │   └── repository
│       │   ├── ui
│       │   │   ├── screens
│       │   │   ├── components
│       │   │   ├── state
│       │   │   └── theme
│       │   ├── viewmodel
│       │   ├── vision
│       │   ├── inframe
│       │   ├── guidance
│       │   ├── arcore
│       │   └── utils
│       └── res
├── gradle
│   └── libs.versions.toml
└── README.md
```

## 关键文件建议先看什么

如果你第一次看这部分代码，建议先读：

1. `app/src/main/java/com/photoframer/MainActivity.kt`
2. `app/src/main/java/com/photoframer/ui/state/CameraUiState.kt`
3. `app/src/main/java/com/photoframer/ui/screens/CameraScreen.kt`
4. `app/src/main/java/com/photoframer/viewmodel/CameraViewModel.kt`
5. `app/src/main/java/com/photoframer/data/repository/CompositionRepository.kt`
6. `app/src/main/java/com/photoframer/vision/StepValidator.kt`
7. `app/src/main/java/com/photoframer/vision/FeatureMatcher.kt`
8. `app/src/main/java/com/photoframer/vision/ViewChangeAnalyzer.kt`

## 技术栈

### 平台与基础

- Kotlin 2.0.21
- Android Gradle Plugin 8.7.3
- Compile SDK 36
- Min SDK 26
- JVM Target 11

### UI

- Jetpack Compose
- Material 3
- Lifecycle Compose
- ViewModel Compose

### 相机与实时处理

- CameraX（`main`）
- Camera2 + ARCore SharedCamera（实验性 AR 分支）
- OpenCV
- ML Kit Object Detection
- ARCore（实验性接入，准确性尚未充分验证）

### 网络

- Retrofit
- OkHttp
- Gson Converter

### 测试

- JUnit4
- `kotlinx-coroutines-test`

## 运行环境要求

- Android Studio 较新版本
- JDK 11
- Android 8.0 及以上设备或模拟器
- 相机权限

如果你要运行实验性 AR 分支或准确性验证 App，必须使用支持 ARCore 的真机，Android 需要在自带应用市场安装 Google Play Services for AR。模拟器不能代表真实 VIO 表现。

## API 配置

接口配置文件在：

- `app/src/main/java/com/photoframer/data/api/ApiConfig.kt`

当前默认值：

```kotlin
const val AI_COMPOSITION_URL = "http://aicrop.312237.xyz/"
const val IN_FRAME_COMPOSITION_URL = "https://crop2.312237.xyz/predict?return_preview=0"
```

说明：

- `AI_COMPOSITION_URL` 指向当前默认部署的主后端
- `IN_FRAME_COMPOSITION_URL` 指向外部裁切/画面内构图服务

如果你要联调本地后端，请把 `AI_COMPOSITION_URL` 改成你的本机地址。

### 模拟器联调本机后端

```kotlin
const val AI_COMPOSITION_URL = "http://10.0.2.2:8100/"
```

### 真机联调本机后端

```kotlin
const val AI_COMPOSITION_URL = "http://192.168.x.x:8100/"
```

注意：

- 当前后端默认端口是 `8100`
- 如果真机无法访问，请检查电脑和手机是否在同一局域网
- 如果只想验证主 AI 构图链，可只改 `AI_COMPOSITION_URL`
- 画面内构图服务当前不在本仓库里

## 快速开始

### 1. 打开项目

1. 打开 Android Studio
2. 选择 `File -> Open`
3. 选择 `android_frontend`
4. 等待 Gradle Sync 完成

### 2. 如需本地后端联调，先启动后端

```bash
cd ../backend
pip install -r requirements.txt
# 当前本地后端默认使用 Gemini Enterprise Agent Platform
export GOOGLE_CLOUD_PROJECT=picgen-503700
export GOOGLE_API_KEY=replace-with-agent-platform-api-key
uvicorn main:app --host 0.0.0.0 --port 8100 --reload
```

### 3. 修改前端 API 地址

按上面说明修改 `ApiConfig.kt`。

### 4. 运行

1. 连接手机或启动模拟器
2. 点击 Run
3. 首次运行授予相机权限

## 当前主流程

### AI 构图模式

```text
相机预览
-> 拍照
-> 图片预处理
-> 请求后端 composition_analyze_stream
-> 展示候选方案
-> 选择一个方案
-> 进入步骤式引导
-> 完成后拍摄最终照片
```

### 画面内构图模式

```text
相机预览
-> 拍照
-> 请求外部裁切服务
-> 本地生成 Shift / Zoom 引导步骤
-> 进入引导
```

## 实时视觉验证链

前端实时验证不是简单比对一张图，而是一个组合链：

- `FeatureMatcher`：默认使用 ORB 做高频实时匹配
- `HomographyAnalyzer`：把匹配结果转成平移 / 缩放 / 旋转信息
- `PerceptualHashMatcher`：在匹配不稳时做兜底相似度判断
- `ViewChangeAnalyzer`：处理视角变化动作并叠加主体锁定等信号

这是 `main` 当前最值得理解的核心部分。实验性 AR 分支提供额外的 `CameraPoseSample`，但尚不能替代准确性验证。

## 关键设计特点

- ViewModel 使用 `StateFlow` 持有业务状态
- `CameraScreen` 负责设备能力和 UI 组装，不承担核心业务编排
- Repository 隔离网络实现细节
- 分析接口优先走流式，失败时再回退到普通接口
- 实时引导默认优先走轻量 ORB，而不是重型离线匹配
- main 不依赖 ARCore 位姿完成视觉引导；实验性 AR 分支才把 ARCore 作为相机与位姿基础设施

## 依赖补充说明

- 项目当前声明了 Coil 依赖，但主干实现的关键图片显示逻辑并不依赖 Coil
- `healthCheck()` 已在网络层实现，但当前主交互链并不依赖它

## 测试

运行单元测试：

```bash
cd android_frontend
./gradlew test
```

当前主要可见的测试位于：

- `app/src/test/java/com/photoframer/viewmodel/CameraViewModelTest.kt`

## 常见问题

### 1. 为什么本地后端启动了，App 还是连不上

优先检查：

- 端口是不是 `8100`
- `AI_COMPOSITION_URL` 是否带结尾 `/`
- 模拟器是否用了 `10.0.2.2`
- 真机是否和电脑同网段

### 2. 为什么 ARCore 没生效

如果运行的是 `main`，这不是开关或设备问题：main 的正式相机与引导管线本来就没有真正接入 ARCore SharedCamera。

如果运行的是 `codex/feat-camera2-arcore-shared-pipeline` 或独立验证 App，再检查：

- 设备不支持 ARCore
- 没安装 Google Play Services for AR
- 相机权限未授予
- 当前环境光线、纹理或运动速度导致 ARCore 无法进入 `TRACKING`

即使已经进入 `TRACKING`，也只说明 ARCore 正在输出位姿，不等于位姿精度已经通过验证。

### 3. 为什么画面内构图模式不工作

因为 `IN_FRAME_COMPOSITION_URL` 对应的是外部服务，不在这个仓库内。主仓库当前只包含 Android 客户端和新的三阶段主后端。

## 进一步阅读

- 仓库总说明：[../README.md](../README.md)
- 后端说明：[../backend/README.md](../backend/README.md)
