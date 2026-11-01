package cn.chomy.ai.backend.common.exception;

/**
 * 跨业务模块复用的基础错误枚举；模块专属错误应另建枚举并实现 ErrorCode。
 * 10000 至 10003 为当前公共业务码，不用于决定 HTTP 状态，BizException 默认返回 HTTP 400。
 *
 * @author chomy
 */
public enum CommonErrorCode implements ErrorCode {

    /** 未进一步分类的可预期业务失败。 */
    BUSINESS_ERROR(10000, "业务处理失败"),

    /** 参数类型正确但不满足业务规则，例如金额或日期范围不合法。 */
    INVALID_PARAMETER(10001, "请求参数不正确"),

    /** 请求引用的业务数据不存在，不等同于 HTTP 路由不存在。 */
    DATA_NOT_FOUND(10002, "业务数据不存在"),

    /** 当前业务状态不允许执行操作，例如重复提交已经完成的任务。 */
    OPERATION_NOT_ALLOWED(10003, "当前状态不允许执行此操作");

    /** 稳定的业务错误码，不以枚举序号作为错误码，避免调整顺序影响接口。 */
    private final int code;

    /** 可直接返回给调用方的默认中文提示。 */
    private final String message;

    /**
     * 保存枚举项对应的错误码与默认提示。
     *
     * @param code 稳定的业务错误码
     * @param message 可公开展示的中文提示
     */
    CommonErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 获取当前枚举项的业务错误码。
     *
     * @return 定义枚举项时指定的错误码
     */
    @Override
    public int getCode() {
        return code;
    }

    /**
     * 获取当前枚举项的默认中文提示。
     *
     * @return 可向调用方公开的错误提示
     */
    @Override
    public String getMessage() {
        return message;
    }
}
