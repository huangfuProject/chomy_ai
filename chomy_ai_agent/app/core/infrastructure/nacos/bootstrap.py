"""Nacos 组件初始化：连接、配置加载、校验及应用状态注册。"""

import logging

from fastapi import FastAPI
from jproperties import Properties

from app.core.infrastructure.nacos.config_client import NacosConfigClient
from app.core.settings import NacosSettings, nacos_settings


logger = logging.getLogger(__name__)


def parse_properties(content: str) -> dict[str, str]:
    """将 Properties 格式的配置原文转换为字符串键值字典。

    例如 ``test=hello`` 会转换为 ``{"test": "hello"}``。
    使用专门的解析器处理注释、转义和分隔符，避免手动按等号切分。
    """
    properties = Properties()
    properties.load(content, encoding="utf-8")

    # jproperties 的每个值是 PropertyTuple；data 才是实际的配置值。
    return {
        key: properties[key].data
        for key in properties
    }


async def load_nacos_config(
    client: NacosConfigClient,
    settings: NacosSettings,
) -> dict[str, str]:
    """读取、解析并校验 Nacos 配置，返回字符串键值字典。

    调用方需要先启动客户端，并负责最终关闭它。本函数只负责配置加载，
    不修改 app.state，方便在应用启动流程之外复用和单独测试。
    读取或解析异常直接向调用方传播；缺少 test 键时抛出 ValueError。
    """
    # 命名空间已在客户端初始化时设置，使用 Data ID 和 Group 定位配置。
    content = await client.get_config(
        data_id=settings.config_data_id,
        group=settings.config_group,
    )
    config = parse_properties(content)

    # 只检查键是否存在，允许 test= 这样的空字符串值。
    if "test" not in config:
        raise ValueError("Nacos 配置缺少必需的 test 属性")

    return config


async def initialize_nacos(app: FastAPI) -> NacosConfigClient:
    """完成 Nacos 启动准备，返回需要在应用关闭时释放的客户端。

    成功时将客户端和配置快照保存到 app.state，供接口和其他组件使用。
    配置只在启动时读取，尚未注册动态更新监听器。
    初始化失败时在本函数内关闭客户端并继续抛出异常，阻止应用启动；
    初始化成功后，客户端的关闭责任交给调用方。
    """
    client = NacosConfigClient(nacos_settings)
    try:
        await client.start()
        config = await load_nacos_config(client, nacos_settings)

        # 仅记录配置标识和键名，避免把密码等敏感配置值写入日志。
        logger.info(
            "Nacos 配置加载成功: data_id=%s, group=%s, keys=%s",
            nacos_settings.config_data_id,
            nacos_settings.config_group,
            list(config.keys()),
        )

        # 所有初始化步骤成功后再发布状态，供 request.app.state 等入口访问。
        app.state.nacos_config_client = client
        app.state.nacos_config = config
        return client
    except BaseException:
        # 包括启动任务被取消的情况：调用方尚未取得客户端，由本函数负责清理。
        await client.close()
        raise
