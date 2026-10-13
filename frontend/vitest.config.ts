import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

// Unit tests live beside the code they cover, but keep their own config: `vite.config.ts`
// describes the artifact that ships, and it should not have to know a test runner exists.
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts'],
    setupFiles: ['./vitest.setup.ts'],
    restoreMocks: true,
    unstubGlobals: true,
    // jsdom is expensive to build, and the default pool rebuilds it for every file. This pool
    // keeps per-file isolation while creating the environment once per worker.
    pool: 'vmThreads',
  },
})
