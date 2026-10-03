import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig({
  base: "/console/",
  plugins: [react()],
  css: {
    postcss: {
      plugins: [
        {
          postcssPlugin: "remove-unused-sap-theme-metadata",
          Declaration(decl) {
            // SAP's JSON metadata is not a visual token and trips CSS minifiers.
            if (decl.prop.startsWith("--sapThemeMetaData-")) decl.remove();
          },
        },
      ],
    },
  },
  server: {
    host: "127.0.0.1",
    proxy: {
      "/api": {
        target: process.env.CASTRELSIGN_API_URL || "https://localhost:8443",
        changeOrigin: false,
        secure: process.env.CASTRELSIGN_DEV_INSECURE_TLS !== "true",
      },
    },
  },
});
