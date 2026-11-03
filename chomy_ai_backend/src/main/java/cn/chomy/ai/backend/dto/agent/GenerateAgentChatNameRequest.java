package cn.chomy.ai.backend.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 聊天名称生成表单，仅接收前端输入的一句话，不接收聊天 ID 或所属用户。
 * @param message 用于命名的原始文本，必填且最多 2000 个字符
 * @author chomy
 */
public record GenerateAgentChatNameRequest(
        @NotBlank(message = "命名文本不能为空") @Size(max = 2000, message = "命名文本不能超过2000个字符") String message) {
}
