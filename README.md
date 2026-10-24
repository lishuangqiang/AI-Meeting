<div align="center">

**码上面试平台** - 基于大语言模型的简历分析、模拟面试服务

**[官网 / 商业 SaaS](https://chrionai.icu/) · [服务场景](#服务场景) · [版本与源码学习](#开源版商业-saas-与源码学习)**


[![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.4-green?logo=springboot)](https://spring.io/projects/spring-boot)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-blue?logo=mysql)](https://www.mysql.com/)
[![MongoDB](https://img.shields.io/badge/MongoDB-6.x-green?logo=mongodb)](https://www.mongodb.com/)
[![Redis](https://img.shields.io/badge/Redis-Redisson-red?logo=redis)](https://redis.io/)


</div>

![项目概览](docs/assets/首页登陆.png)

这是一个基于 Spring Boot 3 + Java 17 + Spring AI + MySQL + MongoDB + Redis 构建的 AI 智能助手后端项目，聚焦 AI 对话、智能体会话、模拟面试、实时语音转写和长文本语音合成等场景。项目采用模块化单体架构，支持 HTTP、SSE、WebSocket 多链路交互，兼顾业务完整性、工程规范性和开箱即用性。

演示视频：[Bilibili 项目展示]( https://www.bilibili.com/video/BV1ccR9B9EEm/?share_source=copy_web&vd_source=2147a1677cc5a940112d07c6f03c4bc9)

前端仓库链接：https://github.com/lishuangqiang/AI-Meeting-Frontend

后端仓库链接：https://github.com/lishuangqiang/AI-Meeting/tree/main

## 官网与商业 SaaS

**[访问官网 / 进入商业 SaaS → https://chrionai.icu/](https://chrionai.icu/)**

AI-Meeting（码上面试平台）是我们项目的开源前身。在此基础上，我们持续迭代了 **Chiron Agent 商业 SaaS**，将简历整理、岗位评估、模拟面试和复盘串成完整的求职准备流程。

希望直接使用产品，可以访问官网；希望学习 AI 应用开发，可以从本仓库入手，也可以联系我们了解商业 SaaS 源码学习。

### 服务场景

以下是 Chiron Agent 商业 SaaS 面向的主要场景，具体可用功能以线上版本为准；本开源仓库的能力见下方「功能特性」。

| 场景 | 可以做什么 |
| --- | --- |
| 校招 / 实习准备 | 整理简历，围绕项目经历进行模拟面试，通过逐题反馈发现表达和知识薄弱点。 |
| 社招 / 跳槽准备 | 结合简历与目标岗位 JD 评估匹配情况、能力缺口与风险，明确准备重点。 |
| 面试训练与复盘 | 练习回答和追问，查看评分、反馈与复盘报告，持续调整后续训练方向。 |
| 简历与能力梳理 | 维护简历材料，结合岗位评估和面试记录积累能力画像，为下一次投递做准备。 |

## 开源版、商业 SaaS 与源码学习

### 免费开源版

**本仓库的代码与配套学习文档继续免费开放，代码遵循 [MIT 许可证](LICENSE)。** 适合学习 Spring Boot、Spring AI、模拟面试业务、SSE / WebSocket，以及在理解实现后用于个人项目实践或毕设研究。

项目由我和程序员海豚共同完成，感谢参与建设的朋友。我们保留这份开源项目和学习资料，方便大家从基础设施、核心业务到项目复盘逐步学习。

领取本开源项目的飞书学习文档，可添加微信：**JavaAndGo8888** 或 **hqy2004hw**。

### 商业 SaaS 在线服务（付费）

**服务地址：[https://chrionai.icu/](https://chrionai.icu/)**

面向希望直接进行求职准备的用户，提供在线使用的简历、岗位评估、模拟面试与复盘服务，无需自行部署项目。商业 SaaS 持续独立迭代，其功能范围与本开源仓库不同。

商业 SaaS 为付费在线服务。具体开通方式、价格、使用额度与服务范围，请通过官网了解或添加微信 **hqy2004hw** 咨询。

### 商业 SaaS 源码学习（付费）

面向希望进一步学习完整商业 AI 产品实现的开发者，了解从开源面试项目演进到商业 SaaS 的工程实践，包括前后端协作、Agent 运行、岗位评估与面试业务闭环等。

**商业 SaaS 源码学习 / 费用咨询：微信 `hqy2004hw`**

添加时请备注：**商业 SaaS 源码学习**。

商业版源码的交付内容、学习费用与授权范围以双方确认为准，商业版源码不适用本开源仓库的 MIT 许可。

## 系统架构：
![系统架构图](docs/assets/项目架构图.svg)

## 功能特性

### 模拟面试模块

- **简历驱动出题**：上传 PDF 简历后，由「简历评分面试官」智能体解析简历并生成结构化面试题、简历评分与参考建议，题目与候选人经历深度关联。
- **多 Agent 协同评估**：内置 5 个星云工作流智能体（出题官、提问官、评分官、追问官、表情分析面试官），答题后由评分官同步返回分数与反馈，LiteFlow 规则链驱动追问裁决，模拟真实多轮深挖场景。
- **分布式 Single-flight**：同一次答题请求（评分/追问/抽题/神态分析）在多实例部署下只执行一次 AI 调用，支持结果回放、心跳保活、失败分类与超时接管，避免重复扣费。
- **长会话状态治理**：基于 MongoDB 热冷分层快照 + Redis 懒加载构建可恢复运行态体系，支持中断恢复、CAS 并发保护与异步补偿，解决长时间面试中途断线的状态丢失问题。
- **问答回放与雷达图**：面试结束后生成结构化面试记录，包含逐题回放（问题 → 回答 → 分数 → 反馈）、多维雷达图评分和 AI 综合建议。
- **神态分析**：支持上传摄像头截图，由表情识别智能体评估候选人仪态表现并纳入总分。
- **面试记录管理**：分页查询历史面试记录，支持按会话查看完整报告与评分详情。

### AI 对话模块

- **多模型统一接入**：基于 Spring AI 封装 `UniversalAiChatHandler`，通过 OpenAI 兼容协议统一接入 DeepSeek、星火、豆包等模型，运行时可切换。
- **SSE 流式响应**：基于 WebFlux Flux + SSE 实现打字机式流式输出，支持 DeepSeek `reasoning_content` 思维链展示。
- **会话管理**：支持创建、分页查询、结束、删除对话，消息持久化至 MongoDB，支持历史消息回溯。

### 智能体（Agent）模块

- **智能体配置管理**：支持运行时创建、更新、启停智能体配置（API Key / Secret / FlowId / 系统提示词等），按需绑定不同星云工作流。
- **Agent SSE 对话**：智能体对话走星辰大模型工作流接口，支持上下文多轮记忆与流式输出。
- **文件上传**：支持为智能体会话上传附件，由 `FileUploadUtil` 统一处理文件类型校验与持久化。

### 语音媒体模块

- **实时语音转写（ASR）**：基于 WebSocket + 讯飞大模型 AST 实现端到端实时语音识别，支持分段增量去重（TreeMap + seg_id/pgs 重叠比对）、`committedText / liveText / displayText` 三级文本渲染，解决重复文本与前缀误删问题。
- **长文本语音合成（TTS）**：集成讯飞长文本 TTS 异步接口，支持创建合成任务、轮询状态、同步等待三种模式，可配置音色、语速、音量与音频编码格式。
- **WebSocket 通信管理**：基于 JSR 356 `@ServerEndpoint` 实现会话级 WebSocket 连接，支持心跳保活、鉴权校验、二进制音频帧接收与服务端主动推送。

### 用户与权限模块

- **Sa-Token 认证**：基于 Sa-Token 实现登录 / 注册 / 登出，Token 持久化至 Redis 支持多实例共享登录态。
- **角色权限控制**：支持管理员角色分配，管理员接口通过 `@SaCheckRole` 注解鉴权。
- **WebSocket 鉴权**：WebSocket 连接建立时通过 Token 参数校验用户身份，支持多参数名兼容（token / Authorization / access_token）。

## 简历写法

1. 负责 AI 面试答题主链路设计，串联评分/追问 Agent 协同工作，基于 EnumMap 状态机 + LiteFlow 规则链 实现追问裁决与流程推进；设计题级分布式锁 + 幂等防重 + 答题轮次异步补偿机制，保障答题闭环的数据一致性。
2. 主导 AI 面试长会话状态治理与恢复架构升级，基于 Mongo Snapshot + Redis 懒加载 构建可恢复运行态体系，并引入热冷分层、CAS 并发保护与恢复幂等补偿，解决 Redis 异常导致的会话中断与状态丢失问题。
3. 设计并实现面试 AI 分布式 Single-flight框架，基于 Redis Lua、状态机与 Fencing Token 实现多实例场景下同请求跨节点去重、结果回放、失败分类和超时接管；已接入评分、追问、抽题、神态分析等链路，并结合 心跳机制 和本地降级机制提升 AI 调用稳定性与成本控制能力。
4. 设计并优化基于 WebSocket + Xunfei AST 的实时 ASR 链路，实现分段增量去重，解决重复文本等问题，借鉴 NIO Buffer 的异步缓冲思想设计会话级 TranscriptionSessionContext，实现音频接收与下游推流解耦，并基于 TreeMap + seg_id/pgs/rg/bg/ed 完成分段增量去重与有序重建，解决重复文本、前缀误删和结果抖动问题
5. 设计并推动面向 AI Coding 的 Skill 业务知识体系，将分散、过时、不可持续维护的传统文档升级为可被 Code Agent 直接消费的模块化业务知识单元，解决复杂业务开发中的知识断层与隐性规则丢失问题。

## 文档展示

![文档首页截图](docs/assets/文档首页截图.png)
![文档部分截图](docs/assets/文档截图.png)
![文档部分截图](docs/assets/文档截图2.png)

## 技术栈

### 后端技术

| 技术 | 版本 | 说明 |
| --- | --- | --- |
| Java | 17 | 开发语言 |
| Spring Boot | 3.4.4 | 应用框架 |
| Spring AI | 1.0.0 | AI 集成框架，统一多模型接入（DeepSeek / 星辰） |
| MyBatis-Plus | 3.5.9 | ORM 持久层框架 |
| MySQL | 8.0 | 关系型数据库 |
| MongoDB | 6.x | 文档数据库，存储运行态快照与对话消息 |
| Redis + Redisson | 3.27.2 | 分布式锁、布隆过滤器、限流、缓存、Stream 消息 |
| Sa-Token | 1.39.0 | 权限认证框架，集成 Redis 共享登录态 |
| Resilience4j | 2.2.0 | 熔断、限流、重试、舱壁隔离 |
| LiteFlow | 2.15.3.2 | 规则引擎，驱动追问裁决等业务规则链 |
| 讯飞 WebSDK | 3.0.2 | 语音转写（IAT）、OCR、表情识别、大模型 AST |
| OkHttp | 4.9.3 | HTTP / WebSocket 客户端 |
| WebSocket / SSE | - | 实时 ASR 双向通信、AI 流式响应 |
| Maven | 3.6.3+ | 构建工具 |

1.为什么要用Redis和Mongodb？
   - Redis 用于存储会话状态、分布式锁、缓存等，提供高并发、低延迟的访问。
   - MongoDB 用于存储运行态快照、对话消息等，支持文档存储、查询与聚合。


### 运维与部署

| 技术 | 说明 |
| --- | --- |
| Docker Compose | 容器化一键部署（MySQL + MongoDB + Redis + 应用） |
| GitHub Actions | CI 流水线，自动执行单元测试与构建 |

### 前端技术
| 技术 | 版本 | 说明 |
| --- | --- | --- |
| React | 19.2 | UI 框架 |
| TypeScript | 5.9 | 开发语言 |
| Vite | 7.3 | 构建工具 |
| Tailwind CSS | 3.4 | 样式框架（配合 shadcn/ui CSS 变量体系） |
| React Router DOM | 7.13 | 路由管理 |
| Framer Motion | 12.35 | 动画库 |
| Lucide React | 0.564 | 图标库 |
| Redux Toolkit | 2.11 | 全局状态管理 |
| React Redux | 9.2 | Redux React 绑定 |
| TanStack React Query | 5.90 | 服务端状态 / 数据请求缓存 |
| Axios | 1.13 | HTTP 请求 |
| Zod | 4.3 | 数据校验 / 表单 Schema |
| React Hook Form | 7.71 | 表单管理 |
| React PDF | 10.4 | PDF 简历预览 |
| React Markdown | 10.1 | Markdown 渲染（AI 回复展示） |
| Vitest | 4.0 | 单元测试框架 |
| Radix UI | — | 无障碍原语组件（shadcn/ui 基础） |

## 测试与 CI

统一校验入口：

```bash
./mvnw -B -ntp clean verify
```

当前 CI 覆盖：

- Maven 构建与测试
- JDK 17 基线校验
- 仓库元数据与文档格式检查

工作流文件见 [`.github/workflows/backend-ci.yml`](.github/workflows/backend-ci.yml)。

## 效果展示

### 简历与面试

首页登录：

![首页登录](docs/assets/首页登陆.png)

上传简历：

![上传简历](docs/assets/上传简历.png)

在线解析并出题：

![在线解析并出题](docs/assets/在线解析并出题.png)

面试入口：

![面试入口](docs/assets/面试入口.png)

提问环节：

![提问环节](docs/assets/提问环节.png)

追问环节：

![追问环节](docs/assets/追问环节.png)

结果复盘：

![结果复盘](docs/assets/结果复盘.png)

面试结果分析：

![面试结果分析](docs/assets/面试结果分析.png)


## 开源协作

- 许可证：[MIT](LICENSE)
- 贡献指南：[CONTRIBUTING.md](CONTRIBUTING.md)
- 行为准则：[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
- 安全说明：[SECURITY.md](SECURITY.md)

## 发布说明

当前仓库仍保留开发期配置结构，真实密钥脱敏和最终公开发布前的安全清理将在后续阶段单独处理。本仓库本轮重点是把架构边界、运行链路、文档门面和开源协作面收敛到展示级标准。
