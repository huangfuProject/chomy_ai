from dataclasses import dataclass

from langchain.agents.middleware import AgentMiddleware
from langchain_openai.chat_models.base import BaseChatOpenAI


@dataclass(frozen=True)
class AgentRunContext:
    """Agent 单次执行的运行时上下文。"""
    model_id: str | None = None

class ModelRoutingMiddleware(AgentMiddleware):
    def __init__(self,chat_models: dict[str, BaseChatOpenAI],default_model_id: str,):
        self.chat_models = chat_models
        self.default_model_id = default_model_id

    def _resolve_model(self, request) -> BaseChatOpenAI:
        """根据运行时上下文获取模型实例。"""
        context = request.runtime.context
        model_id = (getattr(context, "model_id", None) or self.default_model_id)
        model = self.chat_models.get(model_id)
        if model is None:
            raise ValueError(f"未注册的模型 ID: {model_id}")
        return model

    def wrap_model_call(self, request, handler):
        """同步模型调用拦截。"""
        model = self._resolve_model(request)
        return handler(
            request.override(model=model)
        )

    async def awrap_model_call(self, request, handler):
        """异步模型调用拦截。"""
        model = self._resolve_model(request)
        return await handler(
            request.override(model=model)
        )
