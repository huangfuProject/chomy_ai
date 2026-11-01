import { createApp } from "vue";
import App from "./App.vue";
import { router } from "./router";
import { restoreSession } from "./stores/session";
import "./style.css";

// 启动先恢复标签页会话；导航守卫完成服务端验证后才挂载相应业务界面。
restoreSession();
window.addEventListener("session-expired", () => {
  void router.replace({ path: "/login", query: { reason: "expired" } });
});
createApp(App).use(router).mount("#app");
