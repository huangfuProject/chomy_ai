package cn.chomy.ai.backend.dto.agent;

/**
 * 聊天名称生成结果；当前使用文本截取占位，后续接入模型时保持此响应结构不变。
 * @param chatName 生成的聊天名称，仅供前端预览，不代表已创建或重命名聊天
 * @author chomy
 */
public record AgentChatNameResponse(String chatName) {
}
