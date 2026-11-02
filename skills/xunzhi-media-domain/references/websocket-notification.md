# WebSocket 通知

## 服务端推送接口

以下 REST 管理接口统一要求 `admin` 角色，普通用户不能向自己或其他用户发送系统推送，也不能查询其他用户在线状态。业务代码使用 `WebSocketMessageService` 内部推送，无需调用 REST 管理接口。目标 `userId` 与 WebSocket 连接一致，使用数字用户 ID。

- 在线状态：`GET /api/xunzhi/v1/websocket/user/{userId}/status`
- 发送自定义消息：`POST /api/xunzhi/v1/websocket/send-message`
- 发送系统通知：`POST /api/xunzhi/v1/websocket/notification/{userId}`
- 推送转写结果：`POST /api/xunzhi/v1/websocket/transcription/{userId}`
- 推送错误：`POST /api/xunzhi/v1/websocket/error/{userId}`

## 消息语义

- `type` 决定消息类别。
- `message` 是主文案。
- `data` 是可选扩展数据。
- 转写结果接口还带 `isFinal` 标识是否是终态。

## 典型使用场景

- 页面提示某用户在线/离线。
- 后端把系统通知单独推给某个用户。
- 将转写内容从服务端主动推送到前端页面。
