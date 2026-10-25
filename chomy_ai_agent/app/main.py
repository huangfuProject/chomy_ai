"""FastAPI 入口：协调组件生命周期，并提供配置测试接口。"""

from contextlib import AsyncExitStack, asynccontextmanager

from fastapi import FastAPI, HTTPException, Query, Request
from langchain_core.messages import AIMessage

from app.core.infrastructure.nacos.bootstrap import initialize_nacos
from app.core.model.model_factory import create_default_agent


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


@app.get("/chat")
async def chat(
    request: Request,
    model_id: str = Query(
        ..., min_length=1, pattern=r"\S",
        description="预留模型 ID，当前统一使用启动时创建的默认 Agent",
    ),
    user_message: str = Query(
        ..., min_length=1, pattern=r"\S", description="发送给默认 Agent 的用户消息",
    ),
) -> dict[str, str]:
    """调用默认 Agent，等待执行完成后返回最终文本回复。

    GET 请求通过查询参数传值，例如 /chat?model_id=demo&user_message=你好。
    model_id 暂不参与模型选择；每次调用只传入当前消息，不附带历史对话。
    """
    agent = getattr(request.app.state, "agent", None)
    if agent is None:
        raise HTTPException(status_code=503, detail="默认 Agent 尚未初始化")

    # ainvoke 等待整个 Agent 流程完成；messages 可能包含工具调用等中间消息。
    result = await agent.ainvoke(
        {"messages": [{"role": "user", "content": user_message}]}
    )
    messages = result.get("messages", [])
    if not messages or not isinstance(messages[-1], AIMessage):
        raise HTTPException(status_code=502, detail="Agent 未返回最终模型回复")

    # text 同时兼容字符串内容和文本内容块，仅返回最终消息的文本部分。
    return {"reply": messages[-1].text}
