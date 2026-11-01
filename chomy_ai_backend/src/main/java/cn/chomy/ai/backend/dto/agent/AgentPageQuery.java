package cn.chomy.ai.backend.dto.agent;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户对话分页参数，限制单次查询规模；排序规则固定由服务端指定。
 * @author chomy
 */
@Getter
@Setter
public class AgentPageQuery {
    /** 页码从 1 开始，默认第一页，上限避免偏移量溢出。 */
    @NotNull(message = "页码不能为空") @Min(value = 1, message = "页码必须大于零")
    @Max(value = Integer.MAX_VALUE, message = "页码过大")
    private Long current = 1L;
    /** 每页默认 20 条，最多 100 条。 */
    @NotNull(message = "每页条数不能为空") @Min(value = 1, message = "每页条数必须大于零")
    @Max(value = 100, message = "每页最多100条")
    private Long size = 20L;
}
