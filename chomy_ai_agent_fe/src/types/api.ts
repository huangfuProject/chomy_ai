/** Java 统一返回结构；成功码为 200，HTTP 成功也必须继续检查业务码。 */
export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
  durationMs: number;
}
/** 雪花 ID 必须作为字符串传递，禁止 Number 转换造成精度丢失。 */
export interface Permission {
  id: string;
  parentId: string | null;
  permissionCode: string;
  permissionName: string;
  permissionType: 0 | 1 | 2 | 3;
  status: 0 | 1;
  sortOrder: number;
  routePath: string | null;
}
/** 个人资料响应不包含密码、状态或角色等可被错误回写的字段。 */
export interface UserProfile {
  id: string;
  username: string;
  nickname: string;
  phone: string;
  gender: 0 | 1 | 2;
  signature: string | null;
  avatarUrl: string | null;
  createdAt: string;
  lastLoginAt: string | null;
}
/** 登录时服务端生成的会话及授权快照，角色本身不作为前端授权依据。 */
export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: UserProfile;
  roles: { id: string; roleCode: string; roleName: string }[];
  permissions: Permission[];
}
export type Area = "frontend" | "backend";
/** 后台分页结果沿用 MyBatis-Plus 协议。 */
export interface Page<T> {
  records: T[];
  total: number;
  current: number;
  size: number;
  pages: number;
}
export interface AdminUser extends UserProfile {
  status: 0 | 1;
  actived: 0 | 1;
  frozen: boolean;
  active: boolean;
}
