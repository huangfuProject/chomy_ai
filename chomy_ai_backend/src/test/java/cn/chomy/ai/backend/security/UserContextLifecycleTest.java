package cn.chomy.ai.backend.security;

import cn.chomy.ai.backend.config.SecurityConfig;
import cn.chomy.ai.backend.dto.auth.UserProfile;
import cn.chomy.ai.backend.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 验证容器线程及异步工作线程复用时的身份隔离，覆盖未进入 MVC 的失败分支。
 * 所有测试均使用内存请求，不访问数据库或 Redis。
 * @author chomy
 */
class UserContextLifecycleTest {
    /** 测试退出时删除主线程上的模拟身份，不能污染其他安全测试。 */
    @AfterEach
    void clean() {
        UserContext.clear();
        SecurityContextHolder.clearContext();
    }

    /** 安全链提前拒绝或抛出异常时，入口清空历史值，finally 清理新产生的值。 */
    @Test
    void filterCleansEarlyReturnAndException() throws Exception {
        var filter = new UserContextCleanupFilter();
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        UserContext.set(user(1L));
        filter.doFilter(request, response, (req, res) -> {
            assertThat(UserContext.get()).isNull();
            UserContext.set(user(2L));
            // 模拟下游安全过滤器直接返回，不调用 DispatcherServlet。
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(403);
        });
        assertThat(UserContext.get()).isNull();
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            UserContext.set(user(1L));
            throw new ServletException("测试过滤器失败");
        })).isInstanceOf(ServletException.class);
        assertThat(UserContext.get()).isNull();
        assertThatThrownBy(UserContext::require)
                .isInstanceOf(org.springframework.security.authentication.InsufficientAuthenticationException.class);
    }

    /**
     * 使用同一工作线程依次执行两个用户的 Callable，验证任务读到各自身份且结束后无残留。
     * 第二个任务抛出异常，仍需清理；结果重新分派时 MVC 再绑定并清理容器线程。
     */
    @Test
    void callableRebindsAndCleansReusedWorkerOnSuccessAndFailure() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var interceptor = new LoginInterceptor(new SecurityConfig().publicEndpointMatcher(),
                    new SecurityResponseHandler(new ObjectMapper()));
            var mvc = MockMvcBuilders.standaloneSetup(new AsyncController(pool))
                    .addFilters(new UserContextCleanupFilter()).addInterceptors(interceptor).build();
            for (long id : List.of(1L, 2L)) {
                authenticate(id);
                var result = mvc.perform(get(id == 1 ? "/async" : "/async-failure"))
                        .andExpect(request().asyncStarted()).andReturn();
                // 异步启动后，原请求线程已释放，不应继续持有登录用户。
                assertThat(UserContext.get()).isNull();
                Object value = result.getAsyncResult(3000);
                if (id == 1) {
                    assertThat(value).isEqualTo(1L);
                    mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(content().string("1"));
                } else {
                    assertThat(value).isInstanceOf(IllegalStateException.class).hasToString("java.lang.IllegalStateException: 用户2测试异常");
                    assertThatThrownBy(() -> mvc.perform(asyncDispatch(result))).hasCauseInstanceOf(IllegalStateException.class);
                }
                assertThat(UserContext.get()).isNull();
                // 同一单线程池中的后续普通任务不得继承 Callable 的用户身份。
                assertThat(pool.submit(UserContext::get).get(3, TimeUnit.SECONDS)).isNull();
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    /**
     * 两个线程同时绑定不同用户，普通子线程不可继承父线程身份。
     */
    @Test
    void isolatesConcurrentThreadsWithoutImplicitInheritance() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        UserContext.set(user(9L));
        try {
            var tasks = List.<java.util.concurrent.Callable<Long>>of(() -> isolatedUser(1L, barrier),
                    () -> isolatedUser(2L, barrier));
            var results = pool.invokeAll(tasks);
            assertThat(results.get(0).get()).isEqualTo(1L);
            assertThat(results.get(1).get()).isEqualTo(2L);
            assertThat(UserContext.userId()).isEqualTo(9L);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    /** 在并发栅栏两侧读取独立身份，并在当前工作线程退出前清除映射。 */
    private Long isolatedUser(Long id, java.util.concurrent.CyclicBarrier barrier) throws Exception {
        assertThat(UserContext.get()).isNull();
        UserContext.set(user(id));
        try {
            barrier.await(3, TimeUnit.SECONDS);
            return UserContext.userId();
        } finally {
            UserContext.clear();
        }
    }

    /** 构造仅用于拦截器生命周期测试的已认证主体及资料，模拟前置安全链产物。 */
    private void authenticate(Long id) {
        Jwt token = Jwt.withTokenValue("test-only").header("alg", "HS256").subject(id.toString())
                .jti("session-" + id).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        var authentication = new JwtAuthenticationToken(token, List.of());
        authentication.setDetails(user(id));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /** 构造不携带密码或 Token 的不可变用户资料。 */
    private CurrentUser user(Long id) {
        var account = new User();
        account.setId(id);
        account.setUsername("user_" + id);
        return new CurrentUser("session-" + id, UserProfile.from(account), List.of(), List.of());
    }

    /**
     * 在固定单线程池中执行 MVC 管理的异步任务，便于确定性验证线程复用。
     * @param pool 测试专用工作池
     * @author chomy
     */
    @RestController
    private record AsyncController(ExecutorService pool) {
        /** 正常任务直接从工作线程上下文读取用户 ID。 */
        @GetMapping("/async")
        public WebAsyncTask<Long> success() {
            return new WebAsyncTask<>(3000L, new ConcurrentTaskExecutor(pool), UserContext::userId);
        }

        /** 异常任务先读取身份再抛出异常，用于验证 postProcess 的清理。 */
        @GetMapping("/async-failure")
        public WebAsyncTask<Long> failure() {
            return new WebAsyncTask<>(3000L, new ConcurrentTaskExecutor(pool), () -> {
                throw new IllegalStateException("用户" + UserContext.userId() + "测试异常");
            });
        }
    }
}
