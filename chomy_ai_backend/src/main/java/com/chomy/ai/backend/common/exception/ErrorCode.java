package com.chomy.ai.backend.common.exception;

/**
 * 所有业务错误枚举的公共契约，统一定义错误码及面向调用方的提示。
 * Java 枚举不能继承其他枚举，因此各业务模块通过实现此接口扩展自己的错误枚举。
 * 错误码用于前端业务判断，与 HTTP 状态码独立；提示中不能包含密码、SQL 或内部堆栈。
 *
 * @author chomy
 */
public interface ErrorCode {

    /**
     * 获取稳定的业务错误码；不同含义的错误应使用不同码值，避免前端判断歧义。
     *
     * @return 业务错误码
     */
    int getCode();

    /**
     * 获取可公开展示的默认中文提示，不应拼接未经处理的底层异常信息。
     *
     * @return 面向调用方的默认错误提示
     */
    String getMessage();
}
