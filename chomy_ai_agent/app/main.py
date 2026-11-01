"""FastAPI 入口：协调组件生命周期，并提供配置测试接口。"""

import json
import logging
from contextlib import AsyncExitStack, aclosing, asynccontextmanager

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import StreamingResponse
from langchain_core.messages import AIMessage

from app.core.agent.middleware.model_routing import AgentRunContext
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
    model_id: str = Query(...,min_length=1,pattern=r"^\S+$",description="模型 ID",),
    user_message: str = Query(...,min_length=1,pattern=r"\S",description="用户消息",),
) -> StreamingResponse:
    """
    调用 Agent，以 SSE 事件逐步返回模型回复。
    model_id: 指定本次 Agent 执行使用的模型。
    user_message: 用户消息。
    message 事件返回增量文本。
    done 表示执行完成。
    error 表示执行失败。
    """
    # 获取 Agent
    agent = getattr(request.app.state, "agent", None)
    if agent is None:
        raise HTTPException(status_code=503,detail="Agent 尚未初始化",)
    # 校验模型是否存在
    chat_models = getattr(request.app.state,"chat_models",None,)
    if chat_models is None:
        raise HTTPException(status_code=503,detail="模型列表尚未初始化",)
    model_id = model_id.strip()
    if model_id not in chat_models:
        raise HTTPException(status_code=400,detail=f"不支持的模型 ID: {model_id}",)

    def event(name: str, data: dict) -> str:
        return f"event: {name}\n"f"data: {json.dumps(data, ensure_ascii=False)}\n\n"

    async def stream_reply():
        yield ": connected\n\n"
        try:
            # 创建本次执行的运行时上下文
            run_context = AgentRunContext(model_id=model_id)
            # 执行 Agent
            async with aclosing(
                agent.astream(
                    {
                        "messages": [
                            {
                                "role": "user",
                                "content": user_message,
                            }
                        ]
                    },
                    context=run_context,
                    stream_mode="messages",
                )
            ) as stream:
                async for message, _metadata in stream:
                    if isinstance(message, AIMessage) and message.text:
                        yield event(
                            "message",
                            {"delta": message.text},
                        )
            yield event("done", {"finished": True})
        except Exception:
            logger.exception(
                "Agent 流式调用失败，model_id=%s",
                model_id,
            )

            yield event(
                "error",
                {"message": "模型调用失败，请稍后重试"},
            )

    return StreamingResponse(
        stream_reply(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )
