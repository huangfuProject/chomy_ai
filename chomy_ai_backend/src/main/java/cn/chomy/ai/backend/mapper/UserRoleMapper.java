package cn.chomy.ai.backend.mapper;

import cn.chomy.ai.backend.entity.UserRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户角色关联数据访问接口，采用 MyBatis-Plus BaseMapper 提供单表增删改查。
 * 自定义条件优先使用 LambdaQueryWrapper、LambdaUpdateWrapper 或 Service 链式调用。
 * 不在 Mapper 内隐式处理关联关系、权限校验或级联删除，复杂业务由 Service 层编排。
 *
 * @author chomy
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {
}
