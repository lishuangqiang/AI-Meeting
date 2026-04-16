SET NAMES utf8mb4;

create table admin_permission
(
    id          bigint auto_increment comment 'ID'
        primary key,
    user_id     bigint               not null comment '用户ID',
    username    varchar(256)         not null comment '用户名',
    is_admin    tinyint(1) default 0 null comment '是否管理员 0：普通用户 1：管理员',
    create_time datetime             null comment '创建时间',
    update_time datetime             null comment '修改时间',
    del_flag    tinyint(1) default 0 null comment '删除标识 0：未删除 1：已删除',
    constraint idx_unique_user_id
        unique (user_id)
)
    comment '管理员权限表';

create index idx_is_admin
    on admin_permission (is_admin);

create index idx_username
    on admin_permission (username);

create table agent_file_asset
(
    id              bigint auto_increment comment '主键ID'
        primary key,
    agent_id        bigint                                not null comment '智能体ID',
    session_id      varchar(64)                           null comment '会话ID',
    user_name       varchar(64)                           not null comment '上传用户名',
    biz_type        varchar(32) default 'general'         not null comment '业务类型',
    source_platform varchar(32) default 'xingchen'        not null comment '来源平台',
    file_name       varchar(255)                          not null comment '原始文件名',
    file_ext        varchar(32)                           null comment '文件扩展名',
    content_type    varchar(128)                          null comment 'MIME类型',
    file_size       bigint      default 0                 not null comment '文件大小（字节）',
    file_url        varchar(1024)                         not null comment '平台返回文件URL',
    upload_status   tinyint     default 1                 not null comment '上传状态：1成功 2失败',
    remark          varchar(255)                          null comment '备注',
    create_time     datetime    default CURRENT_TIMESTAMP null comment '创建时间',
    update_time     datetime    default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间',
    del_flag        tinyint     default 0                 not null comment '删除标识：0未删除 1已删除'
)
    comment '星辰上传文件持久化表' collate = utf8mb4_unicode_ci;

create index idx_agent_id
    on agent_file_asset (agent_id);

create index idx_create_time
    on agent_file_asset (create_time);

create index idx_session_id
    on agent_file_asset (session_id);

create index idx_user_name
    on agent_file_asset (user_name);

create table agent_properties
(
    id          bigint auto_increment comment 'ID'
        primary key,
    agent_name  varchar(256) null comment '智能体名称',
    api_secret  varchar(256) null comment '鉴权密钥',
    api_key     varchar(512) null comment '鉴权key',
    api_flow_id varchar(256) null comment '工作流id',
    create_time datetime     null comment '创建时间',
    update_time datetime     null comment '修改时间',
    del_flag    tinyint(1)   null comment '删除标识 0：未删除 1：已删除'
);

create table agent_tag
(
    id          bigint auto_increment comment 'ID'
        primary key,
    tag_name    varchar(256)         not null comment '标签名称',
    agent_id    bigint               not null comment '关联的智能体ID',
    description varchar(512)         null comment '标签描述',
    create_time datetime             null comment '创建时间',
    update_time datetime             null comment '修改时间',
    del_flag    tinyint(1) default 0 null comment '删除标识 0：未删除 1：已删除'
)
    comment '智能体标签表';

create index idx_agent_id
    on agent_tag (agent_id);

create index idx_create_time
    on agent_tag (create_time);

create index idx_tag_name
    on agent_tag (tag_name);

create table ai_message_media
(
    id           bigint auto_increment comment 'ID'
        primary key,
    session_id   varchar(64)                 not null comment '会话ID',
    message_seq  int                         not null comment '消息序号',
    media_type   varchar(32) default 'image' not null comment '媒体类型：image, file, audio',
    media_url    varchar(1024)               not null comment '媒体URL',
    file_name    varchar(256)                null comment '原始文件名',
    file_size    bigint                      null comment '文件大小（字节）',
    content_type varchar(128)                null comment '文件MIME类型',
    create_time  datetime                    null comment '创建时间'
)
    comment 'AI消息媒体附件表';

create index idx_session_seq
    on ai_message_media (session_id, message_seq);

create table ai_properties
(
    id                     bigint auto_increment comment 'ID'
        primary key,
    ai_name                varchar(256)               not null comment 'AI名称',
    ai_type                varchar(64)                not null comment 'AI类型：spark、openai、claude等',
    api_key                varchar(512)               not null comment 'API密钥',
    api_secret             varchar(512)               null comment 'API密钥（部分AI需要）',
    api_url                varchar(512)               null comment 'API地址',
    model_name             varchar(256)               null comment '模型名称',
    max_tokens             int           default 4096 null comment '最大token数',
    temperature            decimal(3, 2) default 0.70 null comment '温度参数',
    system_prompt          text                       null comment '系统提示词',
    is_enabled             tinyint(1)    default 1    null comment '是否启用 0：禁用 1：启用',
    enable_thinking        tinyint(1)    default 0    null comment '是否开启思考模式（DeepSeek专用） 0：关闭 1：开启',
    thinking_budget_tokens int                        null comment '思考模式预算Token数（DeepSeek专用）',
    create_time            datetime                   null comment '创建时间',
    update_time            datetime                   null comment '修改时间',
    del_flag               tinyint(1)    default 0    null comment '删除标识 0：未删除 1：已删除',
    project_id             varchar(255)               null comment '项目ID',
    organization_id        varchar(255)               null comment '组织ID'
)
    comment 'AI配置表';

create index idx_ai_type
    on ai_properties (ai_type);

create index idx_create_time
    on ai_properties (create_time);

create table interview_record
(
    id                    bigint auto_increment comment '主键ID'
        primary key,
    user_id               bigint                             not null comment '用户ID',
    session_id            varchar(64)                        not null comment '会话ID',
    interview_score       int                                null comment '面试得分',
    resume_score          int                                null comment '简历得分',
    interview_status      varchar(32)                        null comment '面试状态: INIT/IN_PROGRESS/FINISHED/EVALUATED',
    question_count        int                                null comment '面试题数量',
    interviewer_agent_id  bigint                             null comment '面试官Agent ID',
    interview_suggestions text                               null comment '面试建议',
    interview_direction   varchar(128)                       null comment '面试方向',
    start_time            datetime                           null comment '开始时间',
    end_time              datetime                           null comment '结束时间',
    duration_seconds      int                                null comment '面试时长(秒)',
    session_snapshot_json longtext                           null comment '会话快照JSON',
    create_time           datetime default CURRENT_TIMESTAMP null comment '创建时间',
    update_time           datetime default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间',
    del_flag              tinyint  default 0                 not null comment '删除标记: 0未删除 1已删除'
)
    comment '面试记录表' collate = utf8mb4_unicode_ci;

create index idx_create_time
    on interview_record (create_time);

create unique index uk_interview_record_user_session_del
    on interview_record (user_id, session_id, del_flag);

create index idx_session_id
    on interview_record (session_id);

create table t_user
(
    id            bigint auto_increment comment 'ID'
        primary key,
    username      varchar(256) null comment '用户名',
    password      varchar(512) null comment '密码',
    real_name     varchar(256) null comment '真实姓名',
    phone         varchar(128) null comment '手机号',
    mail          varchar(512) null comment '邮箱',
    deletion_time bigint       null comment '注销时间戳',
    create_time   datetime     null comment '创建时间',
    update_time   datetime     null comment '修改时间',
    del_flag      tinyint(1)   null comment '删除标识 0：未删除 1：已删除',
    constraint idx_unique_username
        unique (username)
);

