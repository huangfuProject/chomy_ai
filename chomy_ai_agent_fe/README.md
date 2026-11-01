# Chomy AI 前端

Vue 3 + TypeScript + Vite + Vue Router 基础框架，连接当前 Java 后端。界面使用暖白侧栏、居中对话输入框和一次提交用户名密码的登录表单。

## 本地运行

需要 Node.js 22.18+ 或 24.12+，当前开发环境使用 Node.js 24。首次运行：

```bash
cd chomy_ai_agent_fe
npm ci
# 可选：复制示例后调整 Java 服务地址，不填写密码和令牌。
cp .env.example .env.local
npm run dev
```

默认前端地址为 http://127.0.0.1:5173，开发代理将 `/api/*` 转发到 `http://127.0.0.1:8001/*`。Java 后端需要按其 README 配置数据库、Redis、Nacos 并启动。本前端不创建默认账户，不自动分配权限，也不连接第三方登录。

```bash
# 同时检查 TypeScript、Vue 模板和生产构建。
npm run build
# 检查权限隔离、会话恢复、业务错误及认证失效边界。
npm test
```

## 接口对照

| 接口                                              | 用途                                     | 权限              |
| ------------------------------------------------- | ---------------------------------------- | ----------------- |
| `POST /user/login`                                | JSON `{username,password}`，一次提交     | 公开              |
| `GET /user/info`                                  | 刷新页面时验证 JWT、Redis 会话及用户状态 | 已登录            |
| `POST /user/logout`                               | 撤销服务端当前会话                       | 已登录            |
| `GET /admin/users?current=1&size=20&username=...` | 真实用户分页及搜索                       | `user:get:page`   |
| `GET /admin/users/{id}`                           | 真实用户详情                             | `user:get:detail` |

所有接口使用 Java 的 `{code,message,data,durationMs}` 包装。令牌只通过 `Authorization: Bearer` 请求头传递；密码只存在于表单和登录请求中。用户 ID、权限 ID 维持字符串，避免雪花 ID 精度损失。请求默认超时 15 秒；401 清理会话并回到登录，403 展示错误，网络及基础设施故障不放行。

已发现但当前阶段未实现页面操作的 Java 接口包括：个人资料更新、个人改密、后台新建/激活/冻结/解冻/重置密码/修改资料/删除用户。它们的详细 DTO 和各自独立权限以 Java 源码及后端 README 为准，后续可继续在 `src/api` 扩展。

## 授权与路由

- 仅正常状态 `status=0` 的目录 `menu:frontend` / `menu:backend` 决定前后台访问资格。无两种目录时显示空白页及“无任何权限，请联系管理员。”，同时允许退出。
- 两种目录同时存在时默认进入前台；仅后台时进入管理首页。前台头像菜单在拥有后台权限时显示“管理后端”；后台在拥有前台权限时显示“跳转到前台”，不为仅后台用户提供无权访问的跳转。
- 导航仅显示当前区域内、已明确授权的类型 1 菜单。目录不隐式授予菜单，菜单不隐式授予接口/按钮；操作使用完整权限码，不做前缀匹配或角色名推断。
- `agent:manage` + `/agents` 映射聊天页面；`user:manage` + `/user-management` 映射后台用户列表。其他明确授权的菜单进入功能占位页，不执行服务端提供的任意 URL。
- 菜单父链需要在登录响应中完整存在；缺失、循环、禁用或跨工作区的资源默认不显示。请明确授权目录和所需菜单。当前 Java 登录只返回已授权资源，没有查询完整菜单树的接口。
- 使用全局路由守卫防止直接 URL 绕过；服务端 Spring Security/RBAC 仍是最终授权边界。前端修改存储不会获得 Java 接口权限。
- 会话保存在当前标签页的 `sessionStorage`，关闭标签页后清除。刷新先调用 `/user/info` 验证会话再进入页面。角色权限改变后需按后端约定撤销 Redis 会话，重新登录取得新快照。

聊天接口按需求暂不接入。建议卡片可填入草稿、Enter 提交/Shift+Enter 换行、新对话清空草稿；发送时明确提示服务暂未开放，保留草稿，不伪造回复或持久化聊天记录。

## 结构与生产部署

`src/api` 管理接口与错误处理，`src/stores` 管理会话，`src/utils/permissions.ts` 管理授权与导航映射，`src/router` 管理守卫，`src/layouts` 管理前后台共用布局，`src/views` 提供登录、聊天、无权限和后台页面。业务流程和边界处理使用中文注释。

生产构建输出 `dist/`。采用 HTML5 history 路由，Web 服务器需将非 `/api` 静态文件路径回退到 `index.html`；`/api` 必须优先反向代理 Java，且去掉前缀。可参考同目录 `nginx.conf.example`。生产使用 HTTPS；Vite 开发代理不会进入构建包。若使用独立 API 域名，可设置 `VITE_API_BASE_URL` 并同步配置 Java CORS。所有 `VITE_` 环境值会公开打包，禁止放入密码和服务端密钥。
