import json
from typing import Any

from deepagents import create_deep_agent
from fastapi import FastAPI
from langchain_openai import ChatOpenAI
from langchain_openai.chat_models.base import BaseChatOpenAI
from pydantic import ValidationError

from app.core.model.model_config import ModelConfig


def create_chat_model(config: ModelConfig):
    kwargs = {
        "model":config.model_name,
        "base_url": config.base_url,
        "api_key": config.api_key.get_secret_value(),
        "timeout": config.timeout,
        "streaming": True,
    }
    if config.temperature is not None:
        kwargs["temperature"] = config.temperature

    if config.top_p is not None:
        kwargs["top_p"] = config.top_p

    if config.max_tokens is not None:
        kwargs["max_tokens"] = config.max_tokens

    if config.reasoning_effort is not None:
        kwargs["reasoning_effort"] = config.reasoning_effort

    if config.extra_body:
        kwargs["extra_body"] = config.extra_body

    if config.extra_headers:
        kwargs["default_headers"] = config.extra_headers

    return ChatOpenAI(**kwargs)

def build_model_config(model_id: str, config: dict[str, str]) -> ModelConfig:
    """将 Nacos 的 ``模型ID.camelCase`` 配置转换为 ModelConfig。

    只读取指定模型前缀下的字段，忽略其他模型和全局配置。
    必填连接参数不允许为空；可选生成参数缺失时使用 ModelConfig 默认值，
    显式配置为空时转为 None，表示调用模型时不传递该参数。
    能力声明使用 true/false，扩展参数使用 JSON 对象，空值按默认值处理。
    转换失败时只在错误中报告配置键，不包含 API Key 等配置值。
    """
    model_id = model_id.strip()
    if not model_id:
        raise ValueError("模型 ID 不能为空")

    def read(key: str) -> str | None:
        """去除值两端的空白，区分未配置和显式空值。"""
        value = config.get(f"{model_id}.{key}")
        return value.strip() if value is not None else None

    # model_id 参数用于选择前缀；modelId 未配置时直接使用此前缀作为唯一标识。
    configured_id = read("modelId")
    if configured_id is not None and configured_id != model_id:
        raise ValueError(f"{model_id}.modelId 必须与模型 ID 一致")
    values: dict[str, Any] = {"model_id": model_id}

    required_fields = {
        "modelName": "model_name",
        "provider": "provider",
        "baseUrl": "base_url",
        "apiKey": "api_key",
    }
    for key, field in required_fields.items():
        value = read(key)
        if not value:
            raise ValueError(f"缺少必需的模型配置: {model_id}.{key}")
        values[field] = value

    # 数字字符串交给 ModelConfig 转换和校验；空字符串不能直接转换为数字。
    optional_fields = {
        "temperature": "temperature",
        "topP": "top_p",
        "maxTokens": "max_tokens",
        "timeout": "timeout",
        "reasoningEffort": "reasoning_effort",
    }
    for key, field in optional_fields.items():
        value = read(key)
        if value is not None:
            values[field] = value or None

    extra_fields = {"extraBody": "extra_body", "extraHeaders": "extra_headers"}
    for key, field in extra_fields.items():
        value = read(key)
        if not value:
            continue
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError:
            raise ValueError(f"{model_id}.{key} 必须是合法的 JSON 对象") from None
        if not isinstance(parsed, dict):
            raise ValueError(f"{model_id}.{key} 必须是 JSON 对象")
        values[field] = parsed

    try:
        return ModelConfig(**values)
    except ValidationError as exc:
        # Pydantic 默认错误可能携带原始输入，仅提取字段名用于定位配置问题。
        field_to_key = {field: key for key, field in optional_fields.items()}
        keys = ", ".join(
            f"{model_id}.{field_to_key.get(error['loc'][0], error['loc'][0])}"
            for error in exc.errors()
        )
        raise ValueError(f"模型配置类型错误: {keys}") from None

def build_deep_agent(model: BaseChatOpenAI):
    # 创建一个默认的agents
    agents = create_deep_agent(
        model = model,
        system_prompt="你是一个精通各种领域的大师，可以致力于帮用户解决任何问题.",
    )
    return agents

async def create_default_agent(app: FastAPI):
    """
    创建一个全局agent
    :param app: fastapi 上下文
    :return:
    """
    # 获取默认模型id
    config: dict[str, str] = app.state.nacos_config
    # 默认模型必须明确配置，空字符串或纯空白也视为未配置。
    default_model_id = (config.get("default.modelId") or "").strip()
    if not default_model_id:
        raise ValueError("默认模型未配置：default.modelId 不能为空")
    # 获取所有的模型id
    all_model_ids = config.get("modelIds")
    # 格式化为字符串
    model_ids = [
        item.strip()
        for item in (all_model_ids or "").split(",")
        if item.strip()
    ]
    # 创建模型实例前校验，避免最终索引字典时仅得到难以定位的 KeyError。
    if default_model_id not in model_ids:
        raise ValueError(
            f"默认模型不存在：{default_model_id} 未在 modelIds 中配置"
        )

    # 将model_id转换为模型实例
    chat_models = {
        model_id: create_chat_model(build_model_config(model_id=model_id,config=config))
        for model_id in model_ids
    }
    # 将模型字段注册到上下文
    app.state.chat_models = chat_models
    # 获取默认模型
    default_model = chat_models[default_model_id]
    app.state.default_model=default_model
    app.state.agent = build_deep_agent(default_model)

