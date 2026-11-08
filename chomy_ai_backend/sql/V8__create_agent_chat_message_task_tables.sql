-- 聊天消息及 Agent 执行任务基础表；新库及已有库均须在聊天表已存在后手动执行一次。
-- 新库执行 V1 后可执行本文件；旧库若缺少聊天表须先执行 V3，禁止重复执行全量 V1。
-- 执行前确认这两张表尚不存在，且账号具有 public 模式建表权限；应用不会自动执行本脚本。
-- 仅创建表、字段、注释及索引，不创建外键、CHECK、触发器、函数、存储过程或数据库级联。
-- 消息与任务的 chat_id 必须一致，任务 message_id 指向触发用户消息，消息 task_id 指向关联执行任务。
-- 用户消息可先落库，再在业务事务中创建任务及回填 task_id，避免依赖循环外键。
-- 所有权、状态流转、开始及结束时间、删除聊天或项目后的关联清理由后续业务代码显式维护。
-- 本文件不自动接入执行引擎；检查点 ID 仅保存恢复位置引用，恢复还依赖引擎的实际检查点存储。
-- @author chomy
BEGIN;

-- 一、聊天消息：用户输入和 AI 回复各自保存一条记录；content 使用 JSONB 保留结构化内容。
CREATE TABLE public.sys_agent_chat_message (
    id              BIGINT          NOT NULL,
    chat_id         BIGINT          NOT NULL,
    task_id         BIGINT,
    role            VARCHAR(16)     NOT NULL,
    content         JSONB           NOT NULL,
    status          SMALLINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_sys_agent_chat_message PRIMARY KEY (id)
);
COMMENT ON TABLE public.sys_agent_chat_message IS '聊天消息表；保存用户消息和 AI 回复，支撑记录恢复、内容展示及 JSON 内容检索';
COMMENT ON COLUMN public.sys_agent_chat_message.id IS '消息主键，应用生成的正数雪花 ID';
COMMENT ON COLUMN public.sys_agent_chat_message.chat_id IS '所属聊天 ID，必填；业务层通过聊天验证用户所有权';
COMMENT ON COLUMN public.sys_agent_chat_message.task_id IS '关联执行任务 ID，可空；允许先保存用户消息，再在业务事务中关联新建任务';
COMMENT ON COLUMN public.sys_agent_chat_message.role IS '消息角色：user 用户、assistant AI 回复，使用 AgentChatMessageRole 显式字符串编码';
COMMENT ON COLUMN public.sys_agent_chat_message.content IS '消息结构化内容，JSONB，必填，无默认值；内容结构由后续业务协议定义';
COMMENT ON COLUMN public.sys_agent_chat_message.status IS '消息状态：0 待处理、1 生成中、2 已完成、3 失败、4 已取消，默认 0；映射 AgentChatMessageStatus';
COMMENT ON COLUMN public.sys_agent_chat_message.created_at IS '消息创建时间，带时区，默认当前事务时间；时间相同按消息 ID 稳定排序';

-- 按聊天、创建时间和 ID 恢复完整历史，同时支持按聊天批量清理；不按状态过滤丢失失败或部分回复。
CREATE INDEX idx_sys_agent_chat_message_chat_created ON public.sys_agent_chat_message (chat_id, created_at, id);
-- 查询一次执行关联的消息及部分回复，支持任务详情追踪和后续关联清理。
CREATE INDEX idx_sys_agent_chat_message_task_created ON public.sys_agent_chat_message (task_id, created_at, id);
-- 默认 jsonb_ops 支持 JSON 包含及键存在检索；不等同于正文全文或任意子串搜索，后者须另行设计。
CREATE INDEX idx_sys_agent_chat_message_content ON public.sys_agent_chat_message USING GIN (content);

-- 二、Agent 执行任务：任务生命周期与消息分离，同一用户消息允许重试产生多条任务。
CREATE TABLE public.sys_agent_chat_task (
    id              BIGINT          NOT NULL,
    chat_id         BIGINT          NOT NULL,
    message_id      BIGINT          NOT NULL,
    status          SMALLINT        NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    error_message   TEXT,
    checkpoint_id   VARCHAR(256),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_sys_agent_chat_task PRIMARY KEY (id)
);
COMMENT ON TABLE public.sys_agent_chat_task IS 'Agent 执行任务表；保存任务状态、执行时间、错误摘要及检查点引用，不自动执行或恢复任务';
COMMENT ON COLUMN public.sys_agent_chat_task.id IS '任务主键，应用生成的正数雪花 ID';
COMMENT ON COLUMN public.sys_agent_chat_task.chat_id IS '所属聊天 ID，必填；必须与触发消息和关联消息属于同一聊天';
COMMENT ON COLUMN public.sys_agent_chat_task.message_id IS '触发任务的用户消息 ID，必填，不是 AI 回复 ID；同一消息可重试创建多个任务';
COMMENT ON COLUMN public.sys_agent_chat_task.status IS '任务状态：0 待执行、1 执行中、2 已完成、3 失败、4 取消中、5 已取消，默认 0；映射 AgentChatTaskStatus';
COMMENT ON COLUMN public.sys_agent_chat_task.started_at IS '实际开始执行时间，带时区；待执行时为空，由业务代码设置';
COMMENT ON COLUMN public.sys_agent_chat_task.finished_at IS '进入已完成、失败或已取消终态的时间，带时区；非终态时为空，由业务代码设置';
COMMENT ON COLUMN public.sys_agent_chat_task.error_message IS '脱敏后的错误摘要，可空；禁止保存密码、令牌等敏感信息，不存完整原始请求或堆栈';
COMMENT ON COLUMN public.sys_agent_chat_task.checkpoint_id IS '执行引擎恢复检查点标识，可空，最多 256 个字符；实际检查点数据由执行引擎持久化';
COMMENT ON COLUMN public.sys_agent_chat_task.created_at IS '任务创建时间，带时区，默认当前事务时间';

-- 支持聊天任务列表、状态追踪及按聊天清理；相同时间以任务 ID 排序。
CREATE INDEX idx_sys_agent_chat_task_chat_created ON public.sys_agent_chat_task (chat_id, created_at DESC, id DESC);
-- 查询触发消息的全部任务及重试历史；不使用唯一索引限制同一消息重试。
CREATE INDEX idx_sys_agent_chat_task_message_created ON public.sys_agent_chat_task (message_id, created_at DESC, id DESC);
-- 为后续调度、取消处理及重启后扫描未终结任务提供状态与创建时间索引；不代表已有调度器。
CREATE INDEX idx_sys_agent_chat_task_status_created ON public.sys_agent_chat_task (status, created_at, id);

COMMIT;
