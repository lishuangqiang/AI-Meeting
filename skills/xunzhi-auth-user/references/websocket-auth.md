# WebSocket 鉴权

## 鉴权入口

`SaTokenWebSocketAuthService.isAuthorized(session, pathUserId)` 的判断顺序是：

1. 解析 token。
2. 校验 token 是否有效。
3. 解析 token 对应的 username。
4. 根据 username 查询服务端用户 ID。
5. 仅当该用户 ID 的规范十进制字符串与 path `userId` 完全相等时放行；查询失败则拒绝连接。

## token 提取优先级

WebSocket 请求参数目前按下面顺序取 token：

- `token`
- `Authorization`
- `authorization`
- `access_token`
- `satoken`

## 规范化规则

- 如果 token 以 `Bearer ` 开头，会自动裁掉前缀。
- path 必须使用 Token 对应用户的数字 ID，不接受 username，也不接受其他用户的 ID。数字用户名不能作为用户 ID 使用。

## 为什么这里容易踩坑

- token 有效不代表 path `userId` 合法，二者必须匹配。
- WebSocket 不是从普通 HTTP 拦截器里自动拿登录态，所以不能假设 `@CurrentUser` 那套会自动生效。
