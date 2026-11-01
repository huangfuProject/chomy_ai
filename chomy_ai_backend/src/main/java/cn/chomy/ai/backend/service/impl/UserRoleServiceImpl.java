package cn.chomy.ai.backend.service.impl;

import cn.chomy.ai.backend.entity.UserRole;
import cn.chomy.ai.backend.mapper.UserRoleMapper;
import cn.chomy.ai.backend.service.UserRoleService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 用户角色关联基础服务实现，复用 MyBatis-Plus ServiceImpl，不重复实现通用 CRUD。
 * Spring 自动注入对应 Mapper，业务层可通过依赖 UserRoleService 调用基础能力。
 * 当前不执行级联操作或自动修改审计字段；关联关系、业务校验及更新时间由后续业务显式维护。
 *
 * @author chomy
 */
@Service
public class UserRoleServiceImpl extends ServiceImpl<UserRoleMapper, UserRole> implements UserRoleService {
}
