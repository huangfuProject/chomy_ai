
"""封装 Nacos v2 SDK，统一提供连接初始化、配置读取和关闭操作。"""

from v2.nacos import (
    ClientConfigBuilder,
    NacosConfigService,
    GRPCConfig, ConfigParam,
)

from app.core.settings import NacosSettings


class NacosConfigClient:
    """由应用生命周期管理的配置客户端。

    调用顺序为 start() → get_config() → close()。
    对上层返回配置原文，具体格式解析和业务必填项校验由调用方负责。
    """

    def __init__(self, settings: NacosSettings):
        """保存连接参数；构造时不发起网络连接。"""
        self._settings = settings
        # None 表示尚未成功初始化，或者已执行关闭流程。
        self._client: NacosConfigService | None = None

    async def start(self) -> None:
        """异步创建 SDK 客户端；已有实例时直接返回。

        此判断防止顺序重复调用创建多个客户端，并非并发初始化锁。
        当前由 FastAPI 启动流程调用一次。
        """
        if self._client is not None:
            return

        s = self._settings

        # Builder 收集连接、命名空间、日志、缓存和 gRPC 参数。
        # build() 生成配置对象，实际客户端初始化在下方异步工厂中进行。
        builder = (
            ClientConfigBuilder()
            .server_address(s.server_address)
            .namespace_id(s.namespace_id)
            .log_level(s.log_level)
            .cache_dir(s.cache_dir)
            .log_dir(s.log_dir)
            .grpc_config(
                GRPCConfig(grpc_timeout=s.grpc_timeout_ms)
            )
        )

        # 仅在设置用户名时配置认证参数，提前检查密码可给出清晰的错误信息。
        if s.username:
            if not s.password:
                raise ValueError("Nacos 密码未配置")

            builder.username(s.username)
            builder.password(s.password)

        # 等初始化成功后再赋值；若工厂抛出异常，_client 仍保持 None。
        self._client = await NacosConfigService.create_config_service(
            builder.build()
        )


    async def get_config(
        self,
        data_id: str,
        group: str = "DEFAULT_GROUP",
    ) -> str:
        """按 Data ID 和 Group 获取当前命名空间下的配置原文。

        未初始化、配置不存在或内容为空时抛出 RuntimeError；
        SDK 的连接、鉴权等异常直接向调用方传播，由应用启动流程处理。
        """

        # 明确调用顺序，避免对 None 调用 SDK 方法而产生难以理解的异常。
        if self._client is None:
            raise RuntimeError("Nacos 客户端尚未初始化")

        # ConfigParam 是 SDK 的请求参数对象，命名空间已在 start() 中配置。
        content = await self._client.get_config(
            ConfigParam(
                data_id=data_id,
                group=group,
            )
        )

        # 仅拒绝 None 和空字符串；纯空白内容交给上层解析与业务校验处理。
        if content is None or content == "":
            raise RuntimeError(
                f"Nacos 配置为空或不存在: "
                f"data_id={data_id}, group={group}"
            )

        return content


    async def close(self) -> None:
        """关闭已创建的 SDK 客户端；未初始化或重复关闭时直接返回。"""
        if self._client is not None:
            client = self._client
            # 先清除引用，再等待关闭；即使 shutdown 抛错，也不会重复使用该实例。
            # 此实现不保留失败实例用于关闭重试，也不支持与读取操作并发关闭。
            self._client = None
            # 等待 SDK 释放其管理的连接及后台资源。
            await client.shutdown()

