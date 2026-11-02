import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The console is served by Vite in development and by any static host in production; the API is
// proxied so the browser sees one origin and no CORS configuration is needed.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        // Override with BIZSIM_API when the backend is not on the default port.
        target: "http://localhost:8080",
        changeOrigin: false,
        // Server-sent events must not be buffered by the proxy.
        configure: (proxy) => {
          proxy.on("proxyRes", (res) => {
            if ((res.headers["content-type"] ?? "").includes("text/event-stream")) {
              res.headers["cache-control"] = "no-cache";
            }
          });
        },
      },
    },
  },
});
