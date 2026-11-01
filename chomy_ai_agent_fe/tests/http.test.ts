import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { request } from "../src/api/http";
import {
  clearSession,
  session,
  setSession,
  restoreSession,
} from "../src/stores/session";
import type { LoginResponse } from "../src/types/api";

const storage = new Map<string, string>();
const dispatchEvent = vi.fn();
const data = {
  accessToken: "test-only-token",
  expiresIn: 3600,
  tokenType: "Bearer",
  user: { id: "970000000000000001" },
  permissions: [],
  roles: [],
} as unknown as LoginResponse;
beforeEach(() => {
  storage.clear();
  vi.stubGlobal("sessionStorage", {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
    removeItem: (key: string) => storage.delete(key),
  });
  vi.stubGlobal("window", { dispatchEvent });
  dispatchEvent.mockClear();
  setSession(data);
});
afterEach(() => {
  clearSession();
  vi.unstubAllGlobals();
});

describe("会话与统一请求", () => {
  it("认证头携带 Bearer，禁用 Cookie，完整保留字符串 ID", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(
        Response.json({ code: 200, data: { id: data.user.id } }),
      );
    vi.stubGlobal("fetch", fetch);
    expect(await request("/user/info")).toEqual({ id: data.user.id });
    const options = fetch.mock.calls[0]![1];
    expect(options.headers.get("Authorization")).toBe("Bearer test-only-token");
    expect(options.credentials).toBe("omit");
  });
  it("401 撤销本地权限，403 仅返回错误并保留会话", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          Response.json({ code: 403, message: "禁止访问" }, { status: 403 }),
        ),
    );
    await expect(request("/admin/users")).rejects.toMatchObject({
      status: 403,
    });
    expect(session.data).not.toBeNull();
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          Response.json({ code: 401, message: "登录失效" }, { status: 401 }),
        ),
    );
    await expect(request("/user/info")).rejects.toMatchObject({ status: 401 });
    expect(session.data).toBeNull();
    expect(dispatchEvent).toHaveBeenCalledOnce();
  });
  it("过期会话在发请求前清理；登录请求不携带旧认证头", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(Response.json({ code: 200, data: {} }));
    vi.stubGlobal("fetch", fetch);
    session.expiresAt = Date.now() - 1;
    await expect(request("/user/info")).rejects.toMatchObject({ status: 401 });
    expect(fetch).not.toHaveBeenCalled();
    await request("/user/login", { method: "POST" }, false);
    expect(fetch.mock.calls[0]![1].headers.has("Authorization")).toBe(false);
  });
  it("HTTP 200 中的失败业务码不会作为成功处理", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          Response.json({ code: 503, message: "服务不可用", data: null }),
        ),
    );
    await expect(request("/user/info")).rejects.toMatchObject({ status: 503 });
  });
  it("恢复的会话必须重新验证，损坏存储安全清理", () => {
    session.data = null;
    session.verified = false;
    restoreSession();
    expect((session.data as LoginResponse | null)?.user.id).toBe(data.user.id);
    expect(session.verified).toBe(false);
    storage.set("chomy.session.v1", "invalid json");
    restoreSession();
    expect(session.data).toBeNull();
  });
});
