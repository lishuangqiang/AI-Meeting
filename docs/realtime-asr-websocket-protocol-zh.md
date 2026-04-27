# 实时 ASR WebSocket 协议

本文档约定前后端在面试答题场景下使用的实时语音转写协议。后端负责适配讯飞 ASR 增量协议，并向前端输出更稳定的产品语义。

## 连接与控制消息

- WebSocket 地址：`/api/xunzhi/v1/xunfei/audio-to-text/{userId}`
- 连接成功后，后端返回 `type = "connected"`
- 前端收到 `connected` 后发送 `{ "type": "start_transcription" }`
- 停止转写时发送 `{ "type": "stop_transcription" }`

## 转写消息结构

`type = "transcription"` 与 `type = "final"` 的消息统一包含以下字段：

```ts
type AsrWsPacket = {
  type: "transcription" | "final" | "error" | "connected" | "heartbeat" | "transcription_stopped";
  message: string;
  timestamp: number;

  data?: string;
  fullText?: string;
  displayText?: string;
  committedText?: string;
  liveText?: string;
  revision?: number;
  resultStatus?: "partial" | "final" | string;

  segmentId?: number;
  sentenceSeq?: number;
  segmentText?: string;
  pgs?: "apd" | "rpl" | string | null;
  rg?: [number, number] | number[] | null;
  bg?: number | null;
  ed?: number | null;
  isFinalPacket?: boolean | null;
  isSnapshot?: boolean;
  updateAction?: string;
};
```

## 前端消费规则

- 主输入框优先使用 `displayText`，其次才回退到 `fullText` / `data`
- `displayText = committedText + liveText`，表示当前应该展示给用户的一整段文本
- `committedText` 表示已经相对稳定的文本
- `liveText` 表示当前仍在滚动修正的临时文本
- `revision` 是后端递增版本号，前端可用于丢弃乱序消息
- `segmentId/sentenceSeq/segmentText/pgs/rg/bg/ed` 是 ASR 元信息，保留给调试、字幕分段或高级编辑，不作为主输入框的展示来源

## Partial 示例

```json
{
  "type": "transcription",
  "message": "Partial snapshot",
  "data": "喂喂喂你好你好我操牛逼",
  "fullText": "喂喂喂你好你好我操牛逼",
  "displayText": "喂喂喂你好你好我操牛逼",
  "committedText": "喂喂喂你好你好",
  "liveText": "我操牛逼",
  "revision": 6,
  "resultStatus": "partial",
  "isSnapshot": true,
  "updateAction": "replace",
  "timestamp": 1777216142367,
  "segmentId": 6,
  "sentenceSeq": 6,
  "segmentText": "我操牛逼",
  "pgs": "apd",
  "rg": null,
  "bg": 3880,
  "ed": 4520,
  "isFinalPacket": false
}
```

## 替换示例

```json
{
  "type": "transcription",
  "message": "Partial snapshot",
  "data": "喂喂喂你好你好。我操你牛逼吧你",
  "fullText": "喂喂喂你好你好。我操你牛逼吧你",
  "displayText": "喂喂喂你好你好。我操你牛逼吧你",
  "committedText": "喂喂喂你好你好",
  "liveText": "。我操你牛逼吧你",
  "revision": 7,
  "resultStatus": "partial",
  "segmentId": 6,
  "sentenceSeq": 6,
  "segmentText": "。我操你牛逼吧你",
  "pgs": "rpl",
  "rg": [5, 6],
  "bg": 3880,
  "ed": 5380,
  "isFinalPacket": false
}
```

## Final 示例

```json
{
  "type": "final",
  "message": "Transcription completed",
  "data": "喂喂喂你好你好。我操你牛逼吧你",
  "fullText": "喂喂喂你好你好。我操你牛逼吧你",
  "displayText": "喂喂喂你好你好。我操你牛逼吧你",
  "committedText": "喂喂喂你好你好。我操你牛逼吧你",
  "liveText": "",
  "revision": 8,
  "resultStatus": "final",
  "updateAction": "archive",
  "isFinalPacket": true
}
```
