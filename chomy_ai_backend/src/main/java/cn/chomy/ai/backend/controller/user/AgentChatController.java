package cn.chomy.ai.backend.controller.user;

import cn.chomy.ai.backend.dto.agent.*;
import cn.chomy.ai.backend.service.AgentChatService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * 当前用户对话入口，参数中不接受所属用户；所有分页与删除均由服务层实施所有权隔离。
 * @author chomy
 */
// 使用 Spring MVC 原生方法参数校验，不以类级 Validated 切换到额外的 AOP 校验路径。
@RestController
@RequestMapping("/user/agent/chats")
@RequiredArgsConstructor
public class AgentChatController {
    /** 对话分页与删除业务。 */
    private final AgentChatService chatService;

    /**
     * 分页读取本人无项目对话，包含归档记录。
     * @param jwt 已验签的认证主体
     * @param query 有界分页参数
     * @return 创建时间、ID 倒序分页
     */
    @GetMapping("/standalone")
    public IPage<AgentChatView> standalone(@AuthenticationPrincipal Jwt jwt, @Valid @ModelAttribute AgentPageQuery query) {
        // 当前用户 ID 来自认证主体，避免客户端查询他人对话。
        return chatService.standaloneChats(Long.valueOf(jwt.getSubject()), query);
    }

    /**
     * 分页读取本人全部归档对话，项目名称无归属时为空。
     * @param jwt 已验签的认证主体
     * @param query 有界分页参数
     * @return 带所属项目名的归档分页
     */
    @GetMapping("/archived")
    public IPage<AgentChatView> archived(@AuthenticationPrincipal Jwt jwt, @Valid @ModelAttribute AgentPageQuery query) {
        // 项目名称由业务层查询，Controller 不进行额外数据访问。
        return chatService.archivedChats(Long.valueOf(jwt.getSubject()), query);
    }

    /**
     * 删除本人单条对话，不删除所属项目。
     * @param jwt 已验签的认证主体
     * @param chatId 正数对话 ID
     * @return 删除结果
     */
    @DeleteMapping("/{chatId}")
    public String delete(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive(message = "对话ID必须大于零") Long chatId) {
        // 删除条件与所有权在服务层合并执行，防止横向越权。
        return chatService.deleteChat(Long.valueOf(jwt.getSubject()), chatId);
    }
}
