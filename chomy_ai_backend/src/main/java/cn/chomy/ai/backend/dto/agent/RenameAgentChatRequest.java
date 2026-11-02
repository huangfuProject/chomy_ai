package cn.chomy.ai.backend.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 对话重命名表单，不允许通过重命名修改所属用户、项目、类型或归档状态。
 * @param chatName 必填对话名称，最多 128 个字符，去除首尾空白后保存，允许重名
 * @author chomy
 */
public record RenameAgentChatRequest(
        @NotBlank(message = "对话名不能为空") @Size(max = 128, message = "对话名不能超过128个字符") String chatName) {
}
