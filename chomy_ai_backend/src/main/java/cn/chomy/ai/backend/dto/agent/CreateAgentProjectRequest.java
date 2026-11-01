package cn.chomy.ai.backend.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建项目表单，不接受客户端指定项目 ID、所属用户或创建时间。
 * @param projectName 必填项目名，去除首尾空白后保存，允许重复
 * @param description 可空项目介绍，最多 2000 个字符
 * @author chomy
 */
public record CreateAgentProjectRequest(
        @NotBlank(message = "项目名不能为空") @Size(max = 128, message = "项目名不能超过128个字符") String projectName,
        @Size(max = 2000, message = "项目介绍不能超过2000个字符") String description) {
}
