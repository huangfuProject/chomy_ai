package cn.chomy.ai.backend.security;

import org.springframework.security.authentication.InsufficientAuthenticationException;

/**
 * 当前请求线程的登录用户上下文，只允许认证拦截器写入，业务代码通过 require 获取。
 * 使用普通 ThreadLocal，不向子线程自动传播；请求退出及异步切换必须调用 remove。
 *
 * @author chomy
 */
public final class UserContext {
    /** 不使用 InheritableThreadLocal，避免线程池任务继承其他请求的身份。 */
    private static final ThreadLocal<CurrentUser> CURRENT = new ThreadLocal<>();

    /** 工具类不允许实例化。 */
    private UserContext() { }

    /**
     * 绑定已经过服务端认证的用户，仅供同包认证组件及受控异步回调调用。
     * @param user 已校验的当前用户
     */
    static void set(CurrentUser user) { CURRENT.set(user); }

    /** @return 当前用户；公开接口或未绑定的线程返回 null */
    public static CurrentUser get() { return CURRENT.get(); }

    /**
     * 获取受保护业务所需的用户，缺少上下文时拒绝继续执行。
     * @return 已认证用户及其登录资料、角色权限、会话标识
     * @throws InsufficientAuthenticationException 当前线程没有登录身份
     */
    public static CurrentUser require() {
        CurrentUser user = CURRENT.get();
        // 禁止使用默认用户或客户端参数补充缺失身份，避免横向越权。
        if (user == null) { throw new InsufficientAuthenticationException("请先登录"); }
        return user;
    }

    /** @return 当前登录用户 ID，不再要求 Controller 接收 JWT 参数 */
    public static Long userId() { return require().userId(); }

    /** 清除线程映射本身，而非设置 null，避免容器线程长期保留请求对象。 */
    public static void clear() { CURRENT.remove(); }
}
