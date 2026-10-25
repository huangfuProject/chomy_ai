"""FastAPI 入口：协调组件生命周期，并提供配置测试接口。"""

import json
import logging
from contextlib import AsyncExitStack, aclosing, asynccontextmanager

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import StreamingResponse
from langchain_core.messages import AIMessage

from app.core.infrastructure.nacos.bootstrap import initialize_nacos
from app.core.model.model_factory import create_default_agent


logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """统一协调组件初始化与资源释放。

    各组件的启动细节由其初始化函数封装；成功后立即注册关闭操作。
    后续可在 yield 前按依赖顺序初始化其他组件，并注册对应的关闭操作。
    正常关闭、后续组件启动失败或任务被取消时，退出栈会按注册的逆序清理资源。
    """
    async with AsyncExitStack() as stack:
        nacos_client = await initialize_nacos(app)
        await create_default_agent(app)
        stack.push_async_callback(nacos_client.close)
        # 所有组件初始化完成后才开始处理请求。
        yield


# 注册生命周期函数，Uvicorn 启动和关闭应用时会调用它。
app = FastAPI(lifespan=lifespan)


@app.get("/config/test")
async def get_test_config(request: Request):
    return request.app.state.nacos_config


@app.get("/chat", response_class=StreamingResponse)
async def chat(
    request: Request,
    model_id: str = Query(
        ..., min_length=1, pattern=r"\S",
        description="预留模型 ID，当前统一使用启动时创建的默认 Agent",
    ),
    user_message: str = Query(
        ..., min_length=1, pattern=r"\S", description="发送给默认 Agent 的用户消息",
    ),
) -> StreamingResponse:
    """调用默认 Agent，以 SSE 事件逐步返回模型回复。

    GET 请求通过查询参数传值，例如 /chat?model_id=demo&user_message=你好。
    model_id 暂不参与模型选择；每次调用只传入当前消息，不附带历史对话。
    message 事件的 delta 字段包含新增文本，done 表示执行完成，error 表示执行失败。
    """
    agent = getattr(request.app.state, "agent", None)
    if agent is None:
        raise HTTPException(status_code=503, detail="默认 Agent 尚未初始化")

    def event(name: str, data: dict) -> str:
        # 使用 JSON 编码文本，确保换行等字符不会破坏 SSE 的事件分隔格式。
        return f"event: {name}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"

    async def stream_reply():
        # SSE 注释用于立即建立响应；浏览器 EventSource 不会将其当作消息事件。
        yield ": connected\n\n"
        try:
            # messages 模式输出 (消息片段, 元数据)，无需等待整个 Agent 执行完成。
            # 客户端断开时 StreamingResponse 会取消生成器；aclosing 确保关闭上游流。
            async with aclosing(agent.astream(
                {"messages": [{"role": "user", "content": user_message}]},
                stream_mode="messages",
            )) as stream:
                async for message, _metadata in stream:
                    # 忽略工具消息、推理块和空片段，仅发送 AI 消息中的文本。
                    if isinstance(message, AIMessage) and message.text:
                        yield event("message", {"delta": message.text})
            yield event("done", {"finished": True})
        except Exception:
            # 响应开始后无法再更改 HTTP 状态码，通过 SSE error 事件通知调用方。
            # 不向客户端暴露 SDK 异常详情；取消任务的异常不在此捕获范围内。
            logger.exception("默认 Agent 流式调用失败")
            yield event("error", {"message": "模型调用失败，请稍后重试"})

    return StreamingResponse(
        stream_reply(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )
