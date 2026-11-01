package cn.chomy.ai.backend.service;

import cn.chomy.ai.backend.entity.User;
import cn.chomy.ai.backend.dto.auth.ChangePasswordRequest;
import cn.chomy.ai.backend.dto.auth.UpdateUserInfoRequest;
import cn.chomy.ai.backend.dto.auth.UserProfile;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * 用户基础服务接口，继承 MyBatis-Plus IService 的查询、分页及批量增删改能力。
 * 提供当前用户资料查询、白名单资料更新和密码修改，不处理 RBAC 授权关系维护。
 * 后续业务方法应在此声明，由实现类组织业务规则，并在涉及多表写入时显式启用事务。
 *
 * @author chomy
 */
public interface UserService extends IService<User> {

    /**
     * 查询当前认证用户的公开资料。
     * @param userId 从认证主体取得的用户 ID，不能采用请求体中任意 ID
     * @return 不含密码的资料
     */
    UserProfile getProfile(Long userId);

    /**
     * 更新当前用户可自行修改的资料，不能更改角色、账号、手机号及状态。
     * @param userId 当前认证用户 ID
     * @param request 经过校验的资料表单
     * @return 修改后的公开资料
     */
    UserProfile updateProfile(Long userId, UpdateUserInfoRequest request);

    /**
     * 验证原密码并原子替换密码哈希；修改后所有旧 JWT 因密码指纹不同而失效。
     * @param userId 当前认证用户 ID
     * @param request 原密码及新密码
     * @return 修改成功及重新登录提示
     */
    String changePassword(Long userId, ChangePasswordRequest request);
}
