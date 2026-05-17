import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes("node_modules")) {
            return undefined;
          }
          if (id.includes("/react/") || id.includes("/react-dom/") || id.includes("/scheduler/")) {
            return "react-vendor";
          }
          if (id.includes("/antd/") || id.includes("/@ant-design/") || id.includes("/rc-") || id.includes("/@rc-component/")) {
            return "antd-vendor";
          }
          if (id.includes("/@xyflow/") || id.includes("/d3-")) {
            return "graph-vendor";
          }
          return undefined;
        },
      },
      onwarn(warning, warn) {
        if (
          warning.code === "MODULE_LEVEL_DIRECTIVE" &&
          warning.message.includes('"use client"')
        ) {
          return;
        }
        warn(warning);
      },
    },
  },
});
