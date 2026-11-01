package cn.chomy.ai.backend.common.exception;

import java.util.Objects;

/**
 * 业务异常基类，供业务代码直接抛出或由具体模块继承后扩展。
 * 全局异常处理器捕获此类及其子类，将业务码和中文提示写入统一响应，HTTP 状态为 400。
 * 底层原因通过 cause 保留用于诊断，不会随统一响应返回给调用方。
 *
 * @author chomy
 */
public class BizException extends RuntimeException {

    /** 异常序列化版本标识，显式声明以避免依赖编译器计算结果。 */
    private static final long serialVersionUID = 1L;

    /** 与 HTTP 状态码独立的业务结果码，由错误枚举或构造参数提供。 */
    private final int code;

    /**
     * 只指定中文提示，业务码默认使用 400，兼容现有业务异常的默认行为。
     *
     * @param message 可直接向前端公开的中文提示
     */
    public BizException(String message) {
        this(400, message);
    }

    /**
     * 直接指定业务码与中文提示，适用于尚未定义错误枚举的简单业务场景。
     *
     * @param code 业务错误码
     * @param message 可公开的中文提示，不能直接使用底层异常消息
     */
    public BizException(int code, String message) {
        this(code, message, null);
    }

    /**
     * 从任意实现 ErrorCode 的错误枚举创建异常，保证错误码与默认提示保持一致。
     *
     * @param errorCode 公共或模块专属错误枚举，不能为 null
     */
    public BizException(ErrorCode errorCode) {
        this(errorCode, (Throwable) null);
    }

    /**
     * 使用枚举的错误码并替换展示提示，适用于同一种错误需要补充业务上下文的场景。
     *
     * @param errorCode 提供稳定业务码的错误枚举，不能为 null
     * @param message 替换枚举默认提示的中文业务描述
     */
    public BizException(ErrorCode errorCode, String message) {
        this(Objects.requireNonNull(errorCode, "错误枚举不能为空").getCode(), message);
    }

    /**
     * 保留底层原因并使用枚举的公开提示，避免把底层错误信息直接暴露到前端。
     *
     * @param errorCode 提供错误码和公开提示的错误枚举，不能为 null
     * @param cause 用于服务端诊断的底层异常，可为 null
     */
    public BizException(ErrorCode errorCode, Throwable cause) {
        this(Objects.requireNonNull(errorCode, "错误枚举不能为空").getCode(), errorCode.getMessage(), cause);
    }

    /**
     * 统一初始化业务码、公开提示和异常原因，便于子类在扩展时复用。
     *
     * @param code 业务错误码，不参与 HTTP 状态选择
     * @param message 可向调用方公开的中文提示
     * @param cause 仅保留在服务端的异常原因，可为 null
     */
    public BizException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * 获取写入统一响应 code 字段的业务错误码。
     *
     * @return 构造异常时提供的业务码
     */
    public int getCode() {
        return code;
    }
}
