package cn.chomy.ai.backend.controller.user;

import cn.chomy.ai.backend.dto.auth.LoginRequest;
import cn.chomy.ai.backend.dto.auth.LoginResponse;
import cn.chomy.ai.backend.service.LoginService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 用户端登录与退出接口，仅接收请求、触发参数校验并委托 Service，不编排认证或会话业务。
 * 用户名密码认证、审计时间及 JWT/Redis 读写全部由 LoginService 负责。
 *
 * @author chomy
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class LoginController {
    /** 登录及退出业务入口，控制器不直接依赖 Mapper 或认证基础设施。 */
    private final LoginService loginService;

    /**
     * 登录并取得 Service 返回的 JWT、资料、角色与权限，统一响应由全局通知包装。
     *
     * @param request 完成基础参数校验的登录请求
     * @return 登录业务结果
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        // 控制器只委托业务服务，不自行认证、更新数据库或签发 Token。
        return loginService.login(request);
    }

    /**
     * 由 Service 从当前用户上下文取得会话标识并退出。
     *
     * @return 退出业务结果
     */
    @PostMapping("/logout")
    public String logout() {
        // 由服务读取已认证用户上下文，不接受客户端另行指定退出目标。
        return loginService.logout();
    }
}
