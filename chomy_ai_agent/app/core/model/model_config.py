from typing import Any

from pydantic import BaseModel, Field, SecretStr


class ModelConfig(BaseModel):
    """
    模型配置参数
    """

    # 基础连接
    # 模型唯一标识
    model_id: str
    # 模型的实际名称
    model_name: str
    # 模型提供商或者适配器类型
    provider: str
    # 模型api的基础地址
    base_url: str
    # 调用模型的凭证
    api_key: SecretStr

    # 通用生成参数
    # 随机倾向
    # 0稳定输出，但是不保证每次结果完全相同
    # 0.2 常见的偏低随机性设置
    # 0.7 可以用于更开放的内容生成，但不是所有的任务都是最佳值
    temperature: float | None = 0.5
    # 控制采样范围的参数 一般不建议调整
    top_p: float | None = None
    # 控制生成，长度上限，但不同API对这个字段的定义可能不同
    max_tokens: int | None = None
    # 超时时间
    timeout: float | None = 120.0

    # 推理参数
    # 推理强度  比如有些模型可以设置 low   medium   high
    reasoning_effort: str | None = None

    # 能力声明，供调用方判断是否允许启用对应功能；未声明时保守地视为不支持。
    supports_tools: bool = False
    supports_structured_output: bool = False
    supports_reasoning: bool = False

    # 模型服务商专属参数
    extra_body: dict[str, Any] | None = Field(default_factory=dict)
    extra_headers: dict[str, Any] | None = Field(default_factory=dict)
