package cn.chomy.ai.backend.controller.user;

import cn.chomy.ai.backend.dto.auth.ChangePasswordRequest;
import cn.chomy.ai.backend.dto.auth.UpdateUserInfoRequest;
import cn.chomy.ai.backend.dto.auth.UserProfile;
import cn.chomy.ai.backend.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * 当前用户个人资料及密码接口，用户 ID 只从已验签的 JWT 取得，防止横向越权。
 * 密码、角色、冻结和激活状态均不属于个人资料修改白名单。
 *
 * @author chomy
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserInfoController {
    /** 个人资料及密码业务。 */
    private final UserService userService;

    /**
     * 查看当前用户资料，不查询或返回密码哈希。
     * @param jwt 当前认证 JWT
     * @return 最新数据库资料
     */
    @GetMapping("/info")
    public UserProfile info(@AuthenticationPrincipal Jwt jwt) {
        // 目标用户固定来自验签后的 JWT，避免通过请求参数读取他人资料。
        return userService.getProfile(Long.valueOf(jwt.getSubject()));
    }

    /**
     * 修改自己的昵称、性别、签名及头像，其他 JSON 字段不会成为更新字段。
     * @param jwt 当前认证 JWT
     * @param request 完整个人资料表单
     * @return 更新后的资料
     */
    @PutMapping("/info")
    public UserProfile update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateUserInfoRequest request) {
        // 将校验后的资料白名单交给业务层，不把客户端 JSON 直接作为用户实体更新。
        return userService.updateProfile(Long.valueOf(jwt.getSubject()), request);
    }

    /**
     * 校验原密码并修改密码，成功后当前及其他设备的旧 JWT 均需重新登录。
     * @param jwt 当前认证 JWT
     * @param request 原密码及新密码
     * @return 公开的重新登录提示
     */
    @PutMapping("/password")
    public String password(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        // 业务层校验原密码并原子替换哈希，下一次认证时旧 JWT 的密码指纹将不再匹配。
        return userService.changePassword(Long.valueOf(jwt.getSubject()), request);
    }
}
