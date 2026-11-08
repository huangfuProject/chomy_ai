package cn.chomy.ai.backend.service;

import cn.chomy.ai.backend.entity.AgentChatMessage;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * 聊天消息表级基础服务，复用 MyBatis-Plus 通用 CRUD，仅供可信业务内部调用。
 * 后续业务必须校验聊天所有权、消息与任务同属一聊天及合法状态流转，再调用本服务持久化。
 * 不直接映射为 HTTP 接口，不接收客户端实体；任务执行、取消和恢复编排由专用业务服务实现。
 * @author chomy
 */
public interface AgentChatMessageService extends IService<AgentChatMessage> {
}
