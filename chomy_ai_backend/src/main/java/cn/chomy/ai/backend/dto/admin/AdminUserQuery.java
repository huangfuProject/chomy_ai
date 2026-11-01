package cn.chomy.ai.backend.dto.admin;

import java.time.OffsetDateTime;
import cn.chomy.ai.backend.enums.ActivationStatus;
import cn.chomy.ai.backend.enums.Gender;
import cn.chomy.ai.backend.enums.UserStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 管理端用户分页查询条件，按创建时间倒序及 ID 倒序稳定排序。
 * 查询参数中的枚举采用数字编码，名称、手机号支持转义后的字面量模糊匹配。
 *
 * @author chomy
 */
@Getter
@Setter
public class AdminUserQuery {
    /** 页码从 1 开始，缺省第一页，不允许空值或非正数。 */
    @NotNull(message = "页码不能为空") @Min(value = 1, message = "页码必须大于零") @Max(value = Integer.MAX_VALUE, message = "页码过大")
    private Long current = 1L;
    /** 每页条数默认为 20，最多 100，避免不受限制读取全部用户。 */
    @NotNull(message = "每页条数不能为空") @Min(value = 1, message = "每页条数必须大于零") @Max(value = 100, message = "每页最多100条")
    private Long size = 20L;
    /** 用户名字面量模糊查询，可为空。 */
    @Size(max = 64, message = "用户名查询条件过长")
    private String username;
    /** 昵称字面量模糊查询，可为空。 */
    @Size(max = 64, message = "昵称查询条件过长")
    private String nickname;
    /** 手机号字面量模糊查询，可为空。 */
    @Size(max = 32, message = "手机号查询条件过长")
    private String phone;
    /** 性别过滤：0 未知、1 男、2 女，省略时不过滤。 */
    private Gender gender;
    /** 冻结状态过滤：0 正常、1 冻结，省略时不过滤。 */
    private UserStatus status;
    /** 激活状态过滤：0 未激活、1 激活，省略时不过滤。 */
    private ActivationStatus actived;
    /** 创建时间下限，包含边界，采用 ISO-8601 带时区格式。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime createdFrom;
    /** 创建时间上限，包含边界，不能早于下限。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime createdTo;
}
