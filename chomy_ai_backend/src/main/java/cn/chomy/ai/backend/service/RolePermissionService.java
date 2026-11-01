package cn.chomy.ai.backend.service;

import cn.chomy.ai.backend.entity.RolePermission;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * 角色权限关联基础服务接口，继承 MyBatis-Plus IService 的查询、分页及批量增删改能力。
 * 当前仅提供基础持久化操作，不包含鉴权、数据合法性校验或关联关系维护。
 * 后续业务方法应在此声明，由实现类组织业务规则，并在涉及多表写入时显式启用事务。
 *
 * @author chomy
 */
public interface RolePermissionService extends IService<RolePermission> {
}
