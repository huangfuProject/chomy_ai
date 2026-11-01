import { reactive } from "vue";
import type { LoginResponse } from "../types/api";

const STORAGE_KEY = "chomy.session.v1";
/** 会话只在当前标签页保存；密码不进入状态、Web Storage 或日志。 */
export const session = reactive({
  data: null as LoginResponse | null,
  expiresAt: 0,
  verified: false,
});

/** 恢复仅用于启动认证；恢复的快照必须经 /user/info 验证后才能渲染受保护页面。 */
export function restoreSession(): void {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return;
    const saved = JSON.parse(raw);
    if (
      !saved.data?.accessToken ||
      !saved.data?.user?.id ||
      !Array.isArray(saved.data?.permissions) ||
      !(saved.expiresAt > Date.now())
    ) {
      clearSession();
      return;
    }
    session.data = saved.data;
    session.expiresAt = saved.expiresAt;
  } catch {
    clearSession();
  }
}
/** 登录响应保持服务器权限原样；存储不可用时仍允许当前内存会话工作。 */
export function setSession(data: LoginResponse): void {
  session.data = data;
  session.expiresAt = Date.now() + data.expiresIn * 1000;
  session.verified = true;
  try {
    sessionStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ data, expiresAt: session.expiresAt }),
    );
  } catch {
    /* 浏览器禁用存储时刷新需重新登录。 */
  }
}
/** 退出、认证失败和过期统一清理资料与授权快照。 */
export function clearSession(): void {
  session.data = null;
  session.expiresAt = 0;
  session.verified = false;
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    /* 无法访问存储不影响内存清理。 */
  }
}
