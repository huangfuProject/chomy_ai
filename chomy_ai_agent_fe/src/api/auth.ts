import { request } from "./http";
import type { LoginResponse, UserProfile } from "../types/api";

/** 用户名密码一次提交；只通过 TLS/本地开发代理发送，不记录认证数据。 */
export const login = (username: string, password: string) =>
  request<LoginResponse>(
    "/user/login",
    {
      method: "POST",
      body: JSON.stringify({ username, password }),
    },
    false,
  );
/** 刷新时验证 Redis 会话和账户状态，认证基础设施故障由上层阻止进入。 */
export const getProfile = () => request<UserProfile>("/user/info");
/** 退出先调用服务端撤销 Redis 会话，失败时由界面明确告知。 */
export const logout = () => request<string>("/user/logout", { method: "POST" });
