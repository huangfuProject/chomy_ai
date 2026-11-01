import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";

// 开发代理只在本机生效：浏览器统一访问 /api，转发时移除该前缀，避免修改 Java 接口。
// JAVA_API_TARGET 不带 VITE_ 前缀，因此不会作为浏览器运行时变量泄漏到前端包。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  return {
    plugins: [vue()],
    server: {
      port: 5173,
      proxy: {
        "/api": {
          target: env.JAVA_API_TARGET || "http://127.0.0.1:8001",
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/api/, ""),
        },
      },
    },
  };
});
