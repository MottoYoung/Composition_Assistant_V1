# 摄影眼模式路径兼容说明

当 `PROMPT_SET = "photographer_eye"` 时，主流程不调用固定构图技巧提示词。

本文件仅用于兼容统一的 `TECHNIQUE_CONFIGS` 路径加载，保证后端启动时能够完成配置初始化。实际 Stage 0 会根据画面生成摄影眼拍摄方向，Stage 1 使用对应的运行时方向任务提示词。
