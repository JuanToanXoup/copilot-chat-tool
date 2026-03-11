import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/webview',
    emptyOutDir: true,
    // Use relative paths so the custom resource handler can serve assets
    // from http://copilot-webview/assets/*
    assetsDir: 'assets',
  },
  // Use relative base so asset URLs are relative to index.html
  base: './',
})
