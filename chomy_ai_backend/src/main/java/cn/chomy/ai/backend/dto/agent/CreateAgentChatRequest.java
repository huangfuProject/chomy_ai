package cn.chomy.ai.backend.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 聊天创建表单，归属类型由服务端按项目 ID 确定，初始状态固定为未归档。
 * @param chatName 必填名称，最多 128 个字符，去除首尾空白后保存，允许重名
 * @param projectId 可空的本人项目 ID；为空时创建无项目聊天，提供时必须为正数
 * @author chomy
 */
public record CreateAgentChatRequest(
        @NotBlank(message = "对话名不能为空") @Size(max = 128, message = "对话名不能超过128个字符") String chatName,
        @Positive(message = "项目ID必须大于零") Long projectId) {
}
