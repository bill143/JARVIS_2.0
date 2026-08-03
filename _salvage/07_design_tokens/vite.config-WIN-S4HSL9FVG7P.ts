import path from "path";
import { fileURLToPath } from "url";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    proxy: {
      "/proxy/anthropic": {
        target: "https://api.anthropic.com",
        changeOrigin: true,
        secure: true,
        rewrite: (p) => p.replace(/^\/proxy\/anthropic/, ""),
      },
      "/proxy/openai": {
        target: "https://api.openai.com",
        changeOrigin: true,
        secure: true,
        rewrite: (p) => p.replace(/^\/proxy\/openai/, ""),
      },
      "/proxy/mistral": {
        target: "https://api.mistral.ai",
        changeOrigin: true,
        secure: true,
        rewrite: (p) => p.replace(/^\/proxy\/mistral/, ""),
      },
    },
  },
});
