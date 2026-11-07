import { fileURLToPath, URL } from "node:url";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  build: {
    rollupOptions: {
      input: {
        main: fileURLToPath(new URL("./index.html", import.meta.url)),
        aaveRisk: fileURLToPath(new URL("./aave-risk.html", import.meta.url)),
      },
    },
  },
  server: {
    proxy: {
      // CLOB gateway WS only — do not proxy `/` or HTML would 502 without Java up.
      "/ws": {
        target: "ws://localhost:8080",
        ws: true,
      },
    },
  },
});
