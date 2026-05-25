# VoiceInputApp

一个面向 Android 输入框场景的语音输入法项目。用户可以在任意输入框中切换到本输入法，通过“按住说话、松手结束”的方式完成语音输入，并将识别结果实时回填到当前编辑框。

## 项目目标

本项目主要解决两个问题：

1. 在 Android 输入法形态下提供较自然的语音输入交互
2. 以相对安全的方式接入云端实时语音识别能力

对应方案是：

- Android 客户端负责录音、输入法交互、识别状态管理和文本回填
- 腾讯云 SCF 云函数负责向阿里云申请短期 Token
- 阿里云智能语音交互负责实时语音识别

## 整体架构

整体调用链路如下：

```text
用户按住语音键
   -> Android 输入法开始录音
   -> Android 客户端请求腾讯云 SCF Token 接口
   -> 腾讯云 SCF 调用阿里云 CreateToken
   -> 云函数返回短期 Token
   -> Android 客户端使用 Token + AppKey 建立阿里云实时识别 WebSocket
   -> PCM 音频流实时发送到阿里云
   -> 阿里云返回中间结果和最终结果
   -> 输入法将文本回填到当前输入框
```

## 项目结构

```text
VoiceInputApp/
├─ app/                                  Android 客户端
│  ├─ src/main/java/com/example/voiceinputapp/
│  └─ src/main/res/
├─ cloud-functions/
│  └─ tencent-scf/
│     └─ aliyun-token/                   腾讯云 SCF Token 云函数
├─ gradle.properties                     本地配置项
└─ README.md
```

## 模块说明

### Android 客户端

主要职责：

- 基于 `InputMethodService` 实现自定义输入法
- 使用 `AudioRecord` 采集 16kHz 单声道 PCM 音频
- 管理按压、录音、处理中等输入状态
- 与阿里云实时语音识别 WebSocket 通信
- 将识别结果作为中间文本或最终文本回填到编辑框
- 提供基础拼音、英文和符号编辑能力

核心文件：

- [VoiceInputMethodService.java](app/src/main/java/com/example/voiceinputapp/VoiceInputMethodService.java)
- [PcmRecorder.java](app/src/main/java/com/example/voiceinputapp/PcmRecorder.java)
- [SpeechRecognitionClient.java](app/src/main/java/com/example/voiceinputapp/SpeechRecognitionClient.java)
- [AliyunRealtimeSpeechClient.java](app/src/main/java/com/example/voiceinputapp/AliyunRealtimeSpeechClient.java)
- [AliyunTokenService.java](app/src/main/java/com/example/voiceinputapp/AliyunTokenService.java)

### Token 云函数

主要职责：

- 保存阿里云长期密钥
- 调用阿里云 `CreateToken` 接口申请短期 Token
- 通过腾讯云 SCF 暴露 HTTPS Token 接口
- 将短期 Token 返回给 Android 客户端

模块目录：

- [cloud-functions/tencent-scf/aliyun-token](cloud-functions/tencent-scf/aliyun-token/)

该模块的技术细节见子目录 README。

## 技术选型

### Android 侧

- `InputMethodService`
  用于实现自定义输入法。

- `AudioRecord`
  用于底层录音，输出原始 PCM 音频流。

- `OkHttp`
  用于 HTTP 请求与 WebSocket 通信。

- 抽象识别接口
  通过 [SpeechRecognitionClient.java](app/src/main/java/com/example/voiceinputapp/SpeechRecognitionClient.java) 解耦输入法业务与语音服务实现，降低后续替换供应商的成本。

### 云端侧

- 腾讯云 SCF
  作为轻量部署环境，对外提供 HTTPS Token 接口。

- 阿里云智能语音交互
  提供 Token 和实时语音识别能力。

- Node.js 18+
  用于运行云函数。

- Node.js 内置 `crypto`
  用于生成阿里云 OpenAPI 所需的 HMAC-SHA1 签名。

## 安全与架构设计

本项目采用“客户端获取短期 Token，服务端保存长期密钥”的模式。

这样设计的原因是：

1. Android 客户端不适合保存阿里云长期密钥
2. 短期 Token 的风险显著低于长期密钥
3. 云函数职责单一，部署和维护成本低
4. 客户端和服务端边界清晰，后续扩展更方便

## 第三方服务与依赖说明

本项目使用到的第三方服务和依赖包括：

1. 阿里云智能语音交互
   提供实时语音识别与短期 Token。

2. 腾讯云 SCF
   提供 Token 中转接口的运行环境。

3. OkHttp
   Android 端的网络和 WebSocket 库。

云函数部分未引入额外 npm 第三方依赖，尽量保持最小实现。

## 配置说明

### Android 客户端配置

```properties
ALIYUN_APP_KEY=你的阿里云语音项目AppKey
ALIYUN_TOKEN_ENDPOINT=https://你的腾讯云函数地址
```

### 云函数配置

```text
ALIYUN_ACCESS_KEY_ID=你的阿里云AccessKey ID
ALIYUN_ACCESS_KEY_SECRET=你的阿里云AccessKey Secret
ALIYUN_APP_KEY=你的阿里云语音项目AppKey
ALIYUN_REGION_ID=cn-shanghai
```

## 运行流程

### Android 端

1. 配置 `ALIYUN_APP_KEY`
2. 配置 `ALIYUN_TOKEN_ENDPOINT`
3. 编译并安装 APK
4. 授予麦克风权限
5. 启用并切换到该输入法

### 云函数端

1. 将 [cloud-functions/tencent-scf/aliyun-token](cloud-functions/tencent-scf/aliyun-token/) 上传到腾讯云 SCF
2. 运行环境选择 `Node.js 18` 或更高版本
3. 执行方法设置为 `index.main_handler`
4. 配置环境变量
5. 开启 HTTP 触发器或函数 URL

## 演示视频

- 【语音输入法功能演示-哔哩哔哩】<https://b23.tv/5pOcRtE>

## 参考文档

- 阿里云智能语音交互开始前准备
  https://help.aliyun.com/zh/isi/getting-started/start-here/
- 阿里云获取 Token
  https://help.aliyun.com/zh/isi/getting-started/obtain-an-access-token
- 阿里云通过 HTTP/HTTPS 获取 Token
  https://help.aliyun.com/zh/isi/getting-started/use-http-or-https-to-obtain-an-access-token
- 阿里云 OpenAPI
  https://api.aliyun.com/api/nls-cloud-meta/2019-02-28
