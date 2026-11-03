package cn.chomy.ai.backend.dto.agent;

import jakarta.validation.constraints.Positive;

/**
 * 聊天移动表单，不接收客户端指定的类型、所有者或归档状态。
 * @param projectId 目标本人项目 ID，提供时必须为正数；省略或为 null 时移出为无项目聊天
 * @author chomy
 */
public record MoveAgentChatRequest(@Positive(message = "项目ID必须大于零") Long projectId) {
}
