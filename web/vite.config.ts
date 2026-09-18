import { defineConfig } from 'vite';

export default defineConfig({
  server: {
    port: 5173,
    proxy: {
      // Backend (Lane A) serves the C2 contract at http://localhost:3000/api/v1/...
      '/api': {
        target: 'http://localhost:3000',
        changeOrigin: true,
      },
    },
  },
  build: {
    // Keep the two heavy libraries in their own stable, long-cacheable chunks.
    // Both are reached only through dynamic import() (day.ts → leaflet,
    // workbench.ts → xlsx), so these stay OFF the entry bundle — the login →
    // overview path downloads neither.
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/leaflet')) return 'leaflet';
          if (id.includes('node_modules/xlsx')) return 'xlsx';
          return undefined;
        },
      },
    },
  },
});
