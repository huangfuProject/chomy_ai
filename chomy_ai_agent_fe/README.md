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

聊天消息发送接口暂未提供。建议卡片可填入草稿，Enter 提交、Shift+Enter 换行；发送时明确提示服务暂未开放，不伪造 AI 回复。

## 项目、聊天接口与滚动分页

当前按 `AgentProjectController` 和 `AgentChatController` 对接以下接口，统一使用 Bearer JWT 及 `{code,message,data,durationMs}` 响应：

| 方法与路径                            | 功能                 | 独立权限                |
| ------------------------------------- | -------------------- | ----------------------- |
| `GET /user/agent/projects`            | 项目分页             | `agent:project:page`    |
| `POST /user/agent/projects`           | 创建项目             | `agent:project:create`  |
| `PUT /user/agent/projects/{id}`       | 修改项目名称、介绍   | `agent:project:update`  |
| `DELETE /user/agent/projects/{id}`    | 删除项目及其全部聊天 | `agent:project:delete`  |
| `GET /user/agent/projects/{id}/chats` | 项目聊天分页         | `agent:chat:project`    |
| `GET /user/agent/chats/standalone`    | 独立聊天分页         | `agent:chat:standalone` |
| `GET /user/agent/chats/archived`      | 全部归档聊天分页     | `agent:chat:archived`   |
| `POST /user/agent/chats`              | 创建独立或项目聊天   | `agent:chat:create`     |
| `PUT /user/agent/chats/{id}/archive`  | 归档聊天             | `agent:chat:archive`    |
| `PUT /user/agent/chats/{id}/restore`  | 恢复聊天             | `agent:chat:restore`    |
| `PUT /user/agent/chats/{id}/move`     | 移动聊天或移出项目   | `agent:chat:move`       |
| `PUT /user/agent/chats/{id}/name`     | 重命名聊天           | `agent:chat:rename`     |
| `DELETE /user/agent/chats/{id}`       | 永久删除聊天         | `agent:chat:delete`     |

创建及修改项目发送 `{projectName,description}`；只修改项目名时也传入原介绍，避免 PUT 将介绍清空。名称最多 128 字符，介绍最多 2000 字符。聊天创建发送 `{chatName,projectId}`，无项目时 `projectId=null`；重命名发送 `{chatName}`，名称最多 128 字符。归档、恢复使用不带请求体的 PUT；移动发送 `{projectId}`，为空时移出为独立聊天。项目 ID 以字符串发送并由 Jackson 绑定 Long，不使用 JS Number 转换。服务端 ID 始终为字符串；不向接口发送用户 ID、归档状态或创建时间。

- 所有列表采用 `current`（从 1 开始）和 `size=20`，对接 MyBatis-Plus 的 `records/current/pages/total`。滚动到底部自动请求下一页、按 ID 去重追加；加载中拒绝重复请求，失败不推进页码并提供重试，到达末页停止。保留“加载更多”作为辅助技术及不支持 IntersectionObserver 时的入口，不显示页码按钮。
- 独立聊天和项目分别滚动；每个展开的项目聊天拥有自己的滚动容器与分页状态，避免聊天过多把项目区推到不可达位置。归档弹窗同样滚动分页，显示所属项目名。后端项目聊天及独立聊天分页包含归档记录，前端严格按 `archived=0` 过滤普通导航；归档数字为空或未知时拒绝响应，不默认为正常。
- “项目”标题下拉提供创建及归档列表；头像弹窗显示前台授权菜单，“管理后端”固定在“退出登录”紧上方。每个请求及写入按钮均检查对应完整权限码，菜单不隐式授予接口权限。后端 403/503 不会降级为本地成功。
- 服务端项目创建、修改、聊天重命名和删除均等待请求成功后更新界面。插入/删除后重置受影响分页，避免 OFFSET 变化造成漏项；账户切换、列表重置及写入后的旧响应不会覆盖新数据或恢复已删除记录。
- 新聊天按钮及项目行撰写按钮通过 POST 建立真实聊天，取得服务端 ID 后再导航。归档、恢复、移动分别使用独立权限码并等待服务端成功；移动目标列表也采用滚动分页，移动当前聊天后同步深层链接的项目 ID。分享接口仍未提供，分享入口暂不可用，不创建无效链接。
- 项目置顶与侧栏折叠是当前浏览器偏好，服务端聊天的输入草稿也按账号保存在本地；项目和已持久化聊天列表始终以接口结果为准。旧 `chomy.workspace.v1:*` 模拟数据保留原样但不再作为真实资源载入，也不会自动上传到数据库。兼容读取上个前端版本的 `draft-*` 未发送窗口，用户显式发送时才通过 POST 升级为真实聊天。
- 刷新项目聊天链接携带 `projectId`，路由守卫通过服务端分页定位当前账号资源。当前没有项目/聊天详情 HTTP 接口，未缓存的深层地址可能需要依次读取相应列表页；权限不足或读取失败返回工作区首页并保留列表错误。后续增加详情接口后可优化定位请求。
- 新增权限由后端版本 SQL 持久化授予超级管理员；部署更新后需撤销旧 Redis 会话或重新登录以取得最新权限快照。

## 结构与生产部署

`src/api` 管理接口与错误处理，`src/stores` 管理会话，`src/utils/permissions.ts` 管理授权与导航映射，`src/router` 管理守卫，`src/layouts` 管理前后台共用布局，`src/views` 提供登录、聊天、无权限和后台页面。业务流程和边界处理使用中文注释。

生产构建输出 `dist/`。采用 HTML5 history 路由，Web 服务器需将非 `/api` 静态文件路径回退到 `index.html`；`/api` 必须优先反向代理 Java，且去掉前缀。可参考同目录 `nginx.conf.example`。生产使用 HTTPS；Vite 开发代理不会进入构建包。若使用独立 API 域名，可设置 `VITE_API_BASE_URL` 并同步配置 Java CORS。所有 `VITE_` 环境值会公开打包，禁止放入密码和服务端密钥。
