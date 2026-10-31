-- 连接 PostgreSQL 的 postgres 数据库后单独执行本脚本。
-- 执行用户需具备 CREATEDB 权限；请勿在事务中执行 CREATE DATABASE。
CREATE DATABASE chomy_ai
    ENCODING 'UTF8'
    TEMPLATE template0;
