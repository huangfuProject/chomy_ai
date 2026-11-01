package cn.chomy.ai.backend.common.exception;

/**
 * 兼容既有 BusinessException 调用的业务异常，继承统一的 BizException 基类。
 * 新增业务代码优先使用 BizException 和 ErrorCode；原有构造方式及 HTTP 400 行为保持兼容。
 *
 * @author chomy
 */
public class BusinessException extends BizException {

    /** 当前兼容异常的序列化版本标识。 */
    private static final long serialVersionUID = 1L;

    /**
     * 使用默认错误码 400 创建业务异常。
     *
     * @param message 面向调用方的中文业务提示
     */
    public BusinessException(String message) {
        this(400, message);
    }

    /**
     * 使用自定义业务码创建异常；HTTP 响应仍为 400，不以业务码替代协议状态。
     *
     * @param code 自定义业务错误码，例如 10001
     * @param message 面向调用方的中文业务提示
     */
    public BusinessException(int code, String message) {
        super(code, message);
    }

    /**
     * 支持既有业务异常逐步迁移为通过错误枚举创建，复用基类的错误码和提示处理。
     *
     * @param errorCode 公共或模块专属错误枚举，不能为 null
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode);
    }
}
