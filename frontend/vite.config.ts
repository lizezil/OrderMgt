import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'

const outDir = fileURLToPath(new URL('../backend/src/main/resources/static', import.meta.url))

/**
 * `emptyOutDir` wipes the static directory, which also takes the tracked `.gitkeep` that keeps the
 * directory in git -- so every build would leave a spurious "deleted file" in the working tree.
 * Put it back after the bundle is written, so building is not a destructive act.
 */
function keepStaticDirectory(): Plugin {
  return {
    name: 'keep-static-gitkeep',
    closeBundle() {
      writeFileSync(join(outDir, '.gitkeep'), '')
    },
  }
}

// The SPA is not a Maven module: it builds straight into the backend's static resources,
// so `mvn package` produces one jar that serves both the API and the UI.
export default defineConfig({
  plugins: [vue(), keepStaticDirectory()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  build: {
    outDir,
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8081', changeOrigin: true },
    },
  },
})
