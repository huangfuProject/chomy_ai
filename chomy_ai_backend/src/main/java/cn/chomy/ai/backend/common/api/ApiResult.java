package cn.chomy.ai.backend.common.api;

/**
 * 对外接口的统一 JSON 响应；实际 HTTP 状态仍由控制器或异常处理器决定。
 *
 * @param code 业务结果码，成功为 200，普通失败使用 HTTP 状态码，也可使用自定义业务码
 * @param message 面向调用方的中文提示，不应包含内部堆栈或敏感配置
 * @param data 业务数据，失败或无返回内容时可以为 null
 * @param durationMs 服务端处理耗时，单位为毫秒，从请求进入过滤器计算到开始写出响应
 * @param <T> 业务数据类型
 *
 * @author chomy
 */
public record ApiResult<T>(int code, String message, T data, long durationMs) {

    /**
     * 创建成功结果；耗时由响应拦截器在写出前统一补充，调用方无需自行计时。
     *
     * @param data 业务数据
     * @param <T> 数据类型
     * @return 尚未填充最终耗时的成功结果
     */
    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>(200, "操作成功", data, 0);
    }

    /**
     * 创建失败结果，不向外返回异常对象，避免暴露服务端实现和敏感数据。
     *
     * @param code HTTP 状态码或自定义业务错误码
     * @param message 可直接展示给调用方的提示
     * @return 业务数据为空的失败结果
     */
    public static ApiResult<Void> failure(int code, String message) {
        return new ApiResult<>(code, message, null, 0);
    }

    /**
     * 保持业务结果不变，生成包含最新请求耗时的不可变响应，避免重复嵌套包装。
     *
     * @param durationMs 服务端处理耗时，单位为毫秒
     * @return 已填充耗时的响应
     */
    public ApiResult<T> withDurationMs(long durationMs) {
        return new ApiResult<>(code, message, data, durationMs);
    }
}
