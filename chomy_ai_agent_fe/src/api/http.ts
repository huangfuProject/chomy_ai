import { clearSession, session } from "../stores/session";
import type { ApiResult } from "../types/api";

/** 将 HTTP 状态和业务异常统一提供给界面，不输出请求密码或认证头。 */
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
/**
 * 默认请求超时 15 秒；所有认证请求只使用 Bearer 头，不携带 Cookie。
 * 401 清理会话并触发路由重新登录，403 保留登录状态展示无权限提示。
 */
export async function request<T>(
  path: string,
  options: RequestInit = {},
  authenticated = true,
): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.body) headers.set("Content-Type", "application/json");
  if (authenticated) {
    if (!session.data || session.expiresAt <= Date.now()) {
      clearSession();
      window.dispatchEvent(new Event("session-expired"));
      throw new ApiError("登录已过期，请重新登录", 401);
    }
    headers.set("Authorization", `Bearer ${session.data.accessToken}`);
  }
  let response: Response;
  try {
    response = await fetch(
      `${import.meta.env.VITE_API_BASE_URL || "/api"}${path}`,
      {
        ...options,
        headers,
        credentials: "omit",
        signal: AbortSignal.timeout(15_000),
      },
    );
  } catch {
    throw new ApiError("暂时无法连接服务，请稍后重试", 0);
  }
  let body: ApiResult<T>;
  try {
    body = await response.json();
  } catch {
    throw new ApiError("服务返回异常，请稍后重试", response.status);
  }
  if (!response.ok || body.code !== 200) {
    const status = response.ok ? body.code : response.status;
    if (status === 401 && authenticated) {
      clearSession();
      window.dispatchEvent(new Event("session-expired"));
    }
    throw new ApiError(
      body.message ||
        (status === 403
          ? "无此操作权限，请联系管理员"
          : "请求失败，请稍后重试"),
      status,
    );
  }
  return body.data;
}
