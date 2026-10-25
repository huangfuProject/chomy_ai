"""FastAPI 入口：协调组件生命周期，并提供配置测试接口。"""

from contextlib import AsyncExitStack, asynccontextmanager

from fastapi import FastAPI, HTTPException, Request

from app.core.infrastructure.nacos.bootstrap import initialize_nacos


@asynccontextmanager
async def lifespan(app: FastAPI):
    """统一协调组件初始化与资源释放。

    各组件的启动细节由其初始化函数封装；成功后立即注册关闭操作。
    后续可在 yield 前按依赖顺序初始化其他组件，并注册对应的关闭操作。
    正常关闭、后续组件启动失败或任务被取消时，退出栈会按注册的逆序清理资源。
    """
    async with AsyncExitStack() as stack:
        nacos_client = await initialize_nacos(app)
        stack.push_async_callback(nacos_client.close)
        # 所有组件初始化完成后才开始处理请求。
        yield


# 注册生命周期函数，Uvicorn 启动和关闭应用时会调用它。
app = FastAPI(lifespan=lifespan)


@app.get("/config/test")
async def get_test_config(request: Request):
    """返回启动时加载的 test 值，用于验证配置加载链路。

    成功响应示例：{"test": "hello"}。每次请求直接读取内存中的配置快照，
    不会重新访问 Nacos；远端修改配置后，当前实现需要重启应用才会重新加载。
    """
    config: dict[str, str] = request.app.state.nacos_config

    value = config.get("test")

    # 仅在键缺失时返回 404，保留 test= 这样的空字符串配置。
    # 正常启动已经校验过该键，这里也防御运行期间配置字典被修改的情况。
    if value is None:
        raise HTTPException(
            status_code=404,
            detail="配置项 test 不存在",
        )

    return {"test": value}
