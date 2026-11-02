package cn.chomy.ai.backend.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 项目修改表单，仅允许修改名称和介绍，不接收所有者、创建时间等持久化字段。
 * @param projectName 必填项目名称，保存时去除首尾空白，允许重名
 * @param description 可空项目介绍；未提供或空字符串表示清空介绍
 * @author chomy
 */
public record UpdateAgentProjectRequest(
        @NotBlank(message = "项目名不能为空") @Size(max = 128, message = "项目名不能超过128个字符") String projectName,
        @Size(max = 2000, message = "项目介绍不能超过2000个字符") String description) {
}
