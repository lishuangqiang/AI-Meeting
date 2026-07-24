# 线程池

当前线程池在 `xunzhi-agent.thread-pool` 下配置，并在启动时登记到进程内 `ThreadPoolRegistry`。

## 池划分

- `general`：`50/200/1000`，通用异步任务。
- `ai-io`（注册名 `interview-ai-io`）：`24/64/400`，偏 IO 的 AI 调用与等待。
- `cpu`（注册名 `cpu-compute`）：`8/16/256`，偏计算型任务。
- `query`：`16/48/600`，查询与聚合型任务。
- `scheduled`（配置项 `scheduled-pool-size: 8`）：定时任务与调度。

## 设计意图

- 把 AI 调用和普通异步任务拆开，避免慢模型请求拖垮其他任务。
- 把调度线程单列，避免修复任务、清理任务和普通业务抢资源。
- 不同队列容量意味着不同类型的背压策略。

## 运行时观测与动态调参

Actuator 端点：`/actuator/threadpools`（需在 `management.endpoints.web.exposure.include` 中包含 `threadpools`）。

- 查询全部：`GET /actuator/threadpools`
- 查询单个：`GET /actuator/threadpools/{poolName}`
- 动态调整：`POST /actuator/threadpools/{poolName}`，可按需传 `corePoolSize` / `maxPoolSize` / `keepAliveSeconds`（未传字段保持原值）

说明：

- 只支持热更新 core / max / keepAlive；`queueCapacity` 创建后不可热改（需重建线程池）。
- Bean 注入方式不变；调参作用于注册表中的底层 `ThreadPoolExecutor`。
- `/actuator/**` 当前无登录鉴权，生产环境请限制访问面。

## 调参前先问自己

- 是线程不够，还是下游太慢？
- 是线程池满了，还是 singleflight 没命中导致请求放大？
- 是 query 池堆积，还是某个恢复接口在做重建？
