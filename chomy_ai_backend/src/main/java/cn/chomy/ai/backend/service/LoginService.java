package cn.chomy.ai.backend.service;

import cn.chomy.ai.backend.dto.auth.LoginRequest;
import cn.chomy.ai.backend.dto.auth.LoginResponse;

/**
 * 用户登录与退出业务接口，统一编排账户认证、登录时间维护和 Redis/JWT 会话管理。
 * 不继承 IService，因为登录流程不是某张数据表的通用增删改查。
 *
 * @author chomy
 */
public interface LoginService {

    /**
     * 验证用户名和密码，更新成功登录时间，写入角色权限缓存后返回 JWT 及用户资料。
     *
     * @param request 已完成基础参数校验的登录请求
     * @return 登录成功的令牌、资料及角色权限
     */
    LoginResponse login(LoginRequest request);

    /**
     * 撤销当前 JWT 对应的 Redis 会话，不影响该账户其他设备。
     *
     * @return 公开的退出结果提示
     */
    String logout();
}
