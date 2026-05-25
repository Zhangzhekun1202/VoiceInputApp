# 阿里云 Token 云函数

本目录提供一个部署在腾讯云 SCF 的轻量 HTTP 云函数，用于向阿里云智能语音交互申请短期 `Token`，再返回给 Android 客户端。

## 作用

该模块只负责一件事：

1. 使用服务端保存的阿里云长期密钥调用 `CreateToken`
2. 返回短期 `Token` 给客户端

这样可以避免将 `AccessKey ID / AccessKey Secret` 放进 Android 客户端。

## 输入与输出

### 环境变量

```text
ALIYUN_ACCESS_KEY_ID=你的阿里云 AccessKey ID
ALIYUN_ACCESS_KEY_SECRET=你的阿里云 AccessKey Secret
ALIYUN_APP_KEY=你的阿里云语音项目 AppKey
ALIYUN_REGION_ID=cn-shanghai
```

### 成功返回

```json
{
  "token": "xxxxx",
  "expire_time": 1735689600,
  "app_key": "your-app-key",
  "region_id": "cn-shanghai"
}
```

### 失败返回

```json
{
  "error": "token_fetch_failed",
  "message": "Detailed error message"
}
```

## 技术说明

当前实现使用：

- 腾讯云 SCF 作为运行环境
- 阿里云智能语音交互 `CreateToken` 接口作为凭证来源
- Node.js 18+ 作为运行时
- Node.js 内置 `crypto` 进行 HMAC-SHA1 签名
- Node.js 内置 `fetch` 发起 HTTPS 请求

当前模块未引入额外 npm 第三方依赖。

## 部署方式

建议在腾讯云 SCF 中使用以下配置：

1. 运行环境：`Node.js 18` 或更高
2. 执行方法：`index.main_handler`
3. 触发方式：`HTTP 触发器` 或 `函数 URL`

## 本地调试

在本目录下执行：

```powershell
$env:ALIYUN_ACCESS_KEY_ID="你的AK"
$env:ALIYUN_ACCESS_KEY_SECRET="你的SK"
$env:ALIYUN_APP_KEY="你的AppKey"
$env:ALIYUN_REGION_ID="cn-shanghai"
node index.js
```

本地默认访问地址：

```text
http://127.0.0.1:3000
```

## 相关说明

本模块只描述云函数本身。

项目整体架构、Android 客户端说明、演示视频链接等内容，请查看[仓库根目录 README](../../../README.md)。
