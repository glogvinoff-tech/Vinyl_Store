import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

function backendProxy() {
  return {
    target: "http://127.0.0.1:8080",
    configure(proxy) {
      // Only a same-origin request through the local dev server is treated as
      // same-origin by Spring. Foreign Origin headers still reach its CORS check.
      proxy.on("proxyReq", (outgoing, incoming) => {
        if (incoming.headers.origin === `http://${incoming.headers.host}`) outgoing.removeHeader("origin");
      });
    },
  };
}
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "../backend/src/main/resources/static",
    assetsDir: "frontend-assets",
    emptyOutDir: false,
  },
  server: {
    host: "127.0.0.1",
    proxy: Object.fromEntries(["/api", "/assets", "/admin.html", "/admin.js", "/admin.css", "/swagger-ui", "/v3/api-docs"].map(path => [path, backendProxy()])),
  },
});
