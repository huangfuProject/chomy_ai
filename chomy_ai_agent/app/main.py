"""FastAPI 入口：协调组件生命周期，并提供配置测试接口。"""

from contextlib import AsyncExitStack, asynccontextmanager

from fastapi import FastAPI, HTTPException, Request

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
