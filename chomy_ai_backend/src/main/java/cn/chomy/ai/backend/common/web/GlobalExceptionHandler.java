package cn.chomy.ai.backend.common.web;

import java.util.stream.Collectors;

import cn.chomy.ai.backend.common.api.ApiResult;
import cn.chomy.ai.backend.common.exception.BizException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.access.AccessDeniedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 将控制器业务异常、参数校验失败和 Spring MVC 协议错误转换为统一响应。
 * 保留真实 HTTP 状态和框架生成的 Allow 等响应头，未预期异常的详情仅记录到服务端日志。
 * 日志统一通过 Lombok 的 @Slf4j 生成 SLF4J log 字段，使用占位符传参并保留异常堆栈。
 *
 * @author chomy
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * 处理 BizException 及其全部子类，包括兼容的 BusinessException。
     * 自定义业务码和公开提示放入响应体，HTTP 状态统一为 400，不返回底层 cause。
     *
     * @param exception 业务代码主动抛出的异常
     * @return 包含业务提示的失败结果
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResult<Void>> handleBusinessException(BizException exception) {
        return ResponseEntity.badRequest().body(ApiResult.failure(exception.getCode(), exception.getMessage()));
    }

    /**
     * 处理基于 Jakarta Validation 的参数约束异常，仅返回提示，不包含被拒绝的参数值。
     *
     * @param exception 参数约束校验异常
     * @return HTTP 400 及合并后的校验提示
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResult<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .map(violation -> violation.getMessage()).distinct().sorted().collect(Collectors.joining("；"));
        return ResponseEntity.badRequest().body(ApiResult.failure(400, message.isBlank() ? "请求参数校验失败" : message));
    }

    /**
     * 接管框架已分类的异常，包括参数绑定、请求格式错误、404、405 和不支持的媒体类型。
     * 复用父类处理响应已提交的情况，避免在流式写出失败后再次写入响应。
     *
     * @param exception Spring MVC 已识别的异常
     * @param body 框架原始响应体，此处替换为项目统一结构
     * @param headers 应保留的框架响应头
     * @param status 框架计算出的真实 HTTP 状态码
     * @param request 当前请求上下文
     * @return 统一错误响应，响应已提交时返回 null
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
                                                              HttpStatusCode status, WebRequest request) {
        String message = switch (status.value()) {
            case 400 -> "请求参数或请求体格式不正确";
            case 401 -> "请先登录";
            case 403 -> "无权访问该资源";
            case 404 -> "请求资源不存在";
            case 405 -> "不支持该请求方法";
            case 406 -> "不支持请求的响应格式";
            case 413 -> "请求内容过大";
            case 415 -> "不支持该请求内容类型";
            default -> status.is5xxServerError() ? "服务器内部错误，请稍后重试" : "请求处理失败";
        };
        if (status.is4xxClientError() && exception instanceof BindException binding) {
            message = binding.getBindingResult().getAllErrors().stream()
                    .map(error -> error.getDefaultMessage() == null ? "请求参数校验失败" : error.getDefaultMessage())
                    .distinct().collect(Collectors.joining("；"));
        } else if (status.is4xxClientError() && exception instanceof HandlerMethodValidationException validation) {
            message = validation.getAllErrors().stream()
                    .map(error -> error.getDefaultMessage() == null ? "请求参数校验失败" : error.getDefaultMessage())
                    .distinct().collect(Collectors.joining("；"));
        }
        if (status.is5xxServerError()) {
            log.error("请求处理发生服务端异常：{}", request.getDescription(false), exception);
        }
        return super.handleExceptionInternal(exception, ApiResult.failure(status.value(), message), headers, status, request);
    }

    /**
     * 登录控制器中的认证异常不经过安全过滤器异常翻译，需要显式输出统一状态。
     * @param exception 认证或认证基础设施异常，不直接返回内部消息
     * @return 登录失败 401，认证基础设施故障 503
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResult<Void>> handleAuthenticationException(AuthenticationException exception) {
        if (exception instanceof AuthenticationServiceException) {
            log.error("认证基础设施异常", exception);
            return ResponseEntity.status(503).body(ApiResult.failure(503, "认证服务暂不可用，请稍后重试"));
        }
        return ResponseEntity.status(401).body(ApiResult.failure(401, "用户名或密码错误，或账户不可用"));
    }

    /**
     * 方法级 @PreAuthorize 等鉴权拒绝保留 HTTP 403，避免被通用异常处理错误转换为 500。
     * @param exception 权限拒绝异常
     * @return 不暴露内部权限配置的统一错误
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResult<Void>> handleAccessDeniedException(AccessDeniedException exception) {
        return ResponseEntity.status(403).body(ApiResult.failure(403, "无权访问该资源"));
    }

    /**
     * 兜底处理未分类异常，向前端返回通用提示，完整堆栈仅保留在服务端。
     *
     * @param exception 未预期的运行异常
     * @param request 用于定位请求的上下文，不读取请求体或认证信息
     * @return HTTP 500 和不暴露内部信息的错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleUnexpectedException(Exception exception, WebRequest request) {
        log.error("请求处理发生未预期异常：{}", request.getDescription(false), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.failure(500, "服务器内部错误，请稍后重试"));
    }
}
