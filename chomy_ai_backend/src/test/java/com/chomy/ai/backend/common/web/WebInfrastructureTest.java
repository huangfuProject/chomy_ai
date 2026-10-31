package com.chomy.ai.backend.common.web;

import java.util.Map;

import com.chomy.ai.backend.common.api.ApiResult;
import com.chomy.ai.backend.common.exception.BusinessException;
import com.chomy.ai.backend.common.exception.BizException;
import com.chomy.ai.backend.common.exception.CommonErrorCode;
import com.chomy.ai.backend.common.exception.ErrorCode;
import com.chomy.ai.backend.config.CorsConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 对全局 Web 基础设施执行协议级回归测试，覆盖序列化、错误状态、耗时和跨域预检。
 * 使用独立 MockMvc，不启动完整应用，不连接 Nacos、PostgreSQL 或 Redis。
 *
 * @author chomy
 */
class WebInfrastructureTest {

    /** 模拟真实过滤器、控制器与响应拦截器组成的请求处理链。 */
    private MockMvc mvc;

    /** 每次测试重建请求处理链，避免计时属性或其他请求状态在测试间共享。 */
    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FixtureController())
                .setControllerAdvice(new GlobalExceptionHandler(), new GlobalResponseAdvice(new ObjectMapper()))
                .addFilters(new RequestTimingFilter(), new CorsConfig().corsFilterRegistration().getFilter())
                .build();
    }

    /** 字符串响应须序列化为 JSON 对象，避免转换器强转异常或响应双重 JSON 编码。 */
    @Test
    void wrapsStringResponseAsJson() throws Exception {
        mvc.perform(get("/fixture/string"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data").value("ok"))
                .andExpect(jsonPath("$.durationMs").isNumber());
    }

    /** 普通对象自动包装，已经包装的结果只补充耗时，不能产生 data.data 嵌套。 */
    @Test
    void wrapsObjectsWithoutDoubleWrapping() throws Exception {
        for (String path : new String[]{"/fixture/object", "/fixture/wrapped"}) {
            mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.name").value("测试"))
                    .andExpect(jsonPath("$.data.data").doesNotExist());
        }
    }

    /** 显式等待使耗时具备可观测下限，避免只检查字段存在而没有真正验证计时。 */
    @Test
    void measuresSuccessfulAndFailedRequests() throws Exception {
        mvc.perform(get("/fixture/slow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationMs", greaterThanOrEqualTo(20)));
        mvc.perform(get("/fixture/business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("业务条件不满足"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.durationMs", greaterThanOrEqualTo(20)));
    }

    /** 未预期异常只向调用方返回通用中文提示，不泄露异常消息或堆栈。 */
    @Test
    void hidesInternalExceptionDetails() throws Exception {
        mvc.perform(get("/fixture/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("服务器内部错误，请稍后重试"))
                .andExpect(jsonPath("$.durationMs").isNumber())
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    /** 公共错误枚举能够驱动业务异常响应，底层 cause 不会成为公开提示或响应字段。 */
    @Test
    void handlesBizExceptionWithCommonErrorCode() throws Exception {
        mvc.perform(get("/fixture/biz-enum"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10002))
                .andExpect(jsonPath("$.message").value("业务数据不存在"))
                .andExpect(jsonPath("$.durationMs").isNumber())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.cause").doesNotExist());
    }

    /** 模块可以实现自己的错误枚举并继承业务异常基类，仍由同一个全局处理器返回统一结构。 */
    @Test
    void handlesBizExceptionSubclassAndModuleErrorCode() throws Exception {
        mvc.perform(get("/fixture/biz-subclass"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(20001))
                .andExpect(jsonPath("$.message").value("测试模块操作失败"));
        mvc.perform(get("/fixture/biz-message"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("开始日期不能晚于结束日期"));
    }

    /** 校验请求体约束，验证 @Valid 提示最终进入统一错误结构。 */
    @Test
    void handlesBodyValidation() throws Exception {
        mvc.perform(post("/fixture/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("名称不能为空"))
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 请求体格式和请求参数类型错误均属于客户端错误，不能被兜底转换成 HTTP 500。 */
    @Test
    void handlesMalformedJsonAndParameterTypes() throws Exception {
        mvc.perform(post("/fixture/validate").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/fixture/typed").param("count", "invalid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    /** 404、405、415 使用真实 HTTP 状态；405 的 Allow 响应头由框架保留。 */
    @Test
    void preservesProtocolErrorsAndHeaders() throws Exception {
        mvc.perform(get("/fixture/missing"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        mvc.perform(post("/fixture/string"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.code").value(405));
        mvc.perform(post("/fixture/validate").contentType(MediaType.TEXT_PLAIN).content("test"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value(415));
    }

    /** 二进制下载和 HTTP 204 必须保持原始协议，不能被追加 JSON 响应体。 */
    @Test
    void preservesBinaryAndNoContentResponses() throws Exception {
        mvc.perform(get("/fixture/binary"))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1, 2, 3}));
        mvc.perform(get("/fixture/empty"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    /** 控制器主动返回的错误状态须同步到结果码；成功创建资源的状态和响应头保持不变。 */
    @Test
    void preservesExplicitResponseEntityStatus() throws Exception {
        mvc.perform(get("/fixture/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data.reason").value("无权操作"));
        mvc.perform(get("/fixture/created"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/fixture/created/1"))
                .andExpect(jsonPath("$.code").value(200));
    }

    /** 检查任意来源、方法及自定义请求头的预检放行，不为通配来源开启 Cookie 凭据。 */
    @Test
    void allowsCorsPreflight() throws Exception {
        mvc.perform(options("/fixture/string")
                        .header("Origin", "https://frontend.example")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "Authorization, X-Custom-Header"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                .andExpect(header().string("Access-Control-Allow-Methods", "PATCH"))
                .andExpect(header().exists("Access-Control-Allow-Headers"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    /** 异常响应也必须带跨域响应头，使前端能够读取错误结果。 */
    @Test
    void includesCorsHeadersOnFailure() throws Exception {
        mvc.perform(get("/fixture/error").header("Origin", "https://frontend.example"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                .andExpect(jsonPath("$.code").value(500));
    }

    /**
     * 校验专用请求对象，错误提示使用中文，且不包含提交的原始数据。
     *
     * @author chomy
     */
    record Payload(@NotBlank(message = "名称不能为空") String name) { }

    /**
     * 模拟模块自定义错误枚举，验证全局基础设施不依赖某个固定枚举实现。
     *
     * @author chomy
     */
    enum ModuleErrorCode implements ErrorCode {
        /** 模块专属失败，使用独立业务错误码。 */
        OPERATION_FAILED;

        /** 获取模块错误码，验证统一异常处理保留模块定义的值。 */
        @Override
        public int getCode() { return 20001; }

        /** 获取可公开展示的模块错误提示。 */
        @Override
        public String getMessage() { return "测试模块操作失败"; }
    }

    /**
     * 模拟模块专属业务异常，用于验证基类处理器覆盖所有子类。
     *
     * @author chomy
     */
    static class ModuleBizException extends BizException {
        /** 使用模块专属枚举初始化异常，复用基类的错误码与提示存储。 */
        ModuleBizException() { super(ModuleErrorCode.OPERATION_FAILED); }
    }

    /**
     * 只用于模拟各种协议边界的测试控制器，不会注册到正式应用中。
     *
     * @author chomy
     */
    @RestController
    static class FixtureController {
        /** 返回字符串，触发 StringHttpMessageConverter 分支。 */
        @GetMapping("/fixture/string")
        public String string() { return "ok"; }

        /** 返回普通对象，触发 JSON 自动包装。 */
        @GetMapping("/fixture/object")
        public Map<String, String> object() { return Map.of("name", "测试"); }

        /** 返回主动包装的结果，用于验证不会重复包装。 */
        @GetMapping("/fixture/wrapped")
        public ApiResult<?> wrapped() { return ApiResult.success(object()); }

        /** 延迟成功响应，用于测量过滤器记录的实际处理耗时。 */
        @GetMapping("/fixture/slow")
        public String slow() throws InterruptedException { Thread.sleep(30); return "ok"; }

        /** 延迟后抛出自定义业务码异常，验证异常响应也有耗时。 */
        @GetMapping("/fixture/business")
        public String business() throws InterruptedException {
            Thread.sleep(30);
            throw new BusinessException(10001, "业务条件不满足");
        }

        /** 模拟仅允许写入服务端日志的内部错误。 */
        @GetMapping("/fixture/error")
        public String error() { throw new IllegalStateException("仅限日志查看的内部信息"); }

        /** 使用公共错误枚举保留内部原因，只向调用方公开枚举定义的提示。 */
        @GetMapping("/fixture/biz-enum")
        public Object bizEnum() {
            throw new BizException(CommonErrorCode.DATA_NOT_FOUND, new IllegalStateException("仅限服务端的底层原因"));
        }

        /** 使用继承自 BizException 的模块异常，验证多态异常捕获。 */
        @GetMapping("/fixture/biz-subclass")
        public Object bizSubclass() { throw new ModuleBizException(); }

        /** 使用枚举错误码并补充面向当前业务场景的具体提示。 */
        @GetMapping("/fixture/biz-message")
        public Object bizMessage() {
            throw new BizException(CommonErrorCode.INVALID_PARAMETER, "开始日期不能晚于结束日期");
        }

        /** 模拟 JSON 请求体校验入口。 */
        @PostMapping("/fixture/validate")
        public Payload validate(@Valid @RequestBody Payload payload) { return payload; }

        /** 模拟整数参数的类型转换错误。 */
        @GetMapping("/fixture/typed")
        public int typed(@RequestParam("count") int count) { return count; }

        /** 模拟静态资源处理器抛出的标准 404 异常。 */
        @GetMapping("/fixture/missing")
        public Object missing() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "missing");
        }

        /** 模拟二进制下载，其内容不应被 JSON 包装。 */
        @GetMapping("/fixture/binary")
        public byte[] binary() { return new byte[]{1, 2, 3}; }

        /** 模拟没有响应体的 HTTP 204 结果。 */
        @GetMapping("/fixture/empty")
        public ResponseEntity<Void> empty() { return ResponseEntity.noContent().build(); }

        /** 模拟控制器直接返回 HTTP 403，验证结果码不会被误标记为成功。 */
        @GetMapping("/fixture/forbidden")
        public ResponseEntity<?> forbidden() {
            return ResponseEntity.status(403).body(Map.of("reason", "无权操作"));
        }

        /** 模拟创建资源的 HTTP 201 及 Location 响应头，验证包装不会改写协议。 */
        @GetMapping("/fixture/created")
        public ResponseEntity<?> created() {
            return ResponseEntity.status(201).header("Location", "/fixture/created/1").body(object());
        }
    }
}
