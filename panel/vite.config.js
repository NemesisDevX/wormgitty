import { defineConfig } from "vite";

export default defineConfig({
  server: {
    port: 5173,
    strictPort: true,
    // Real backend mode (VITE_USE_BACKEND=true): /api/* forwards to the
    // Fastify backend which serves the same routes under /api/v1/*.
    proxy: {
      "/api": {
        target: "http://127.0.0.1:3000",
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/api/, "/api/v1"),
      },
    },
  },
  preview: { port: 5173, strictPort: true },
});
