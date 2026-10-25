"""从环境变量和本地 .env 文件加载连接 Nacos 所需的启动参数。"""

from pydantic_settings import BaseSettings, SettingsConfigDict


class NacosSettings(BaseSettings):
    """
    Nacos 客户端启动配置，不包含从 Nacos 拉取的业务配置。

    当前使用默认设置来源：构造参数优先于系统环境变量，系统环境变量优先于
    .env 文件，未提供的字段使用下方默认值。BaseSettings 会按字段类型校验值，
    例如将 NACOS_GRPC_TIMEOUT_MS 的字符串转换为整数。
    """

    model_config = SettingsConfigDict(
        # 字段名自动加此前缀：server_address 对应 NACOS_SERVER_ADDRESS。
        env_prefix="NACOS_",
        # 相对路径以进程的当前工作目录为基准，应在项目目录启动 Uvicorn。
        env_file=".env",
        env_file_encoding="utf-8",
        # 忽略 .env 中不属于本配置类的其他字段，便于多个配置类共用文件。
        extra="ignore",
    )

    # Nacos 服务地址；本地默认使用 8848 端口。
    server_address: str = "127.0.0.1:8848"
    # 命名空间 ID（不是显示名称）；空值表示使用默认命名空间。
    namespace_id: str = ""
    # 用户名为空时不向 SDK 配置账号；设置用户名后，客户端要求同时提供密码。
    username: str = ""
    password: str = ""

    # 目标配置的 Data ID 和 Group，应与 Nacos 控制台中的配置标识一致。
    # 环境变量分别为 NACOS_CONFIG_DATA_ID 和 NACOS_CONFIG_GROUP。
    config_data_id: str = "chomy_ai_agent"
    config_group: str = "DEFAULT_GROUP"

    # 以下日志和缓存参数传给 Nacos SDK，不负责配置 Uvicorn 的访问日志。
    log_level: str = "INFO"
    # 相对目录同样以进程的当前工作目录为基准。
    cache_dir: str = "./var/nacos/cache"
    log_dir: str = "./var/nacos/log"
    # SDK 的 gRPC 超时时间，单位为毫秒；5000 表示 5 秒。
    grpc_timeout_ms: int = 5000


# 导入模块时读取并校验设置；后续代码复用该实例，不会在每次请求时读取 .env。
nacos_settings = NacosSettings()
