/**
 * Boots the app the way the container ships it -- the SPA baked into the backend's static
 * resources, served on the same origin as the API -- plus the `offline` profile, so the assistant
 * is a deterministic keyword adapter rather than a paid model call.
 *
 * Playwright starts this as its webServer and waits on /actuator/health.
 */
import { spawn, spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { connect } from 'node:net'
import { fileURLToPath } from 'node:url'
import * as path from 'node:path'

const frontendDir = path.resolve(fileURLToPath(new URL('.', import.meta.url)), '..', '..')
const repoRoot = path.resolve(frontendDir, '..')

/** Must match the `webServer.url` in playwright.config.ts. */
const PORT = process.env.E2E_PORT ?? '8082'
/** Its own schema: an e2e run must never write into the dev/demo database. */
const DATABASE = process.env.E2E_DB ?? 'ordermgt_e2e'
const MYSQL_HOST = process.env.MYSQL_HOST ?? 'localhost'
const MYSQL_PORT = Number(process.env.MYSQL_PORT ?? 3306)

function fail(message) {
  console.error(`[e2e] ${message}`)
  process.exit(1)
}

function assertMysqlReachable() {
  return new Promise((resolve) => {
    const socket = connect({ host: MYSQL_HOST, port: MYSQL_PORT })
    const done = (ok) => {
      socket.destroy()
      resolve(ok)
    }
    socket.once('connect', () => done(true))
    socket.once('error', () => done(false))
    setTimeout(() => done(false), 3000)
  })
}

const mysqlUp = await assertMysqlReachable()
if (!mysqlUp) {
  fail(
    `MySQL is not answering on ${MYSQL_HOST}:${MYSQL_PORT}. Start it with \`make db-up\` ` +
      'before running the e2e suite.',
  )
}

// 1) Bake the SPA into backend/src/main/resources/static. `vite build` directly, not `pnpm build`:
//    the latter rewrites source files with Prettier first, which a test run must not do.
const build = spawnSync('pnpm', ['exec', 'vite', 'build'], {
  cwd: frontendDir,
  stdio: 'inherit',
})
if (build.status !== 0) fail('the frontend build failed')

// 2) Start the app. `spring-boot:run` walks the lifecycle up to test-compile first, which is what
//    copies the freshly built SPA onto the runtime classpath.
const javaHome = process.env.JAVA_HOME ?? `${process.env.HOME}/.jdks/temurin-17/Contents/Home`
const child = spawn(path.join(repoRoot, 'mvnw'), ['-q', '-pl', 'backend', 'spring-boot:run'], {
  cwd: repoRoot,
  stdio: 'inherit',
  // Own process group, so shutdown can take the forked JVM down with the Maven wrapper.
  detached: true,
  env: {
    ...process.env,
    ...(existsSync(javaHome) ? { JAVA_HOME: javaHome } : {}),
    SPRING_PROFILES_ACTIVE: 'mysql,offline',
    SERVER_PORT: PORT,
    SPRING_DATASOURCE_URL:
      `jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${DATABASE}?createDatabaseIfNotExist=true` +
      '&useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai',
    SPRING_DATASOURCE_USERNAME: process.env.MYSQL_ROOT_USER ?? 'root',
    SPRING_DATASOURCE_PASSWORD: process.env.MYSQL_ROOT_PASSWORD ?? 'root',
    // The OpenAI auto-configuration needs a key to exist; the offline adapter means no call is made.
    DASHSCOPE_API_KEY: process.env.DASHSCOPE_API_KEY ?? 'offline-profile-never-calls-the-model',
    // A test run wants failures, not MyBatis' SQL trace.
    LOGGING_LEVEL_COM_EXAMPLE_ORDERMGT: process.env.LOGGING_LEVEL_COM_EXAMPLE_ORDERMGT ?? 'INFO',
  },
})

function shutdown(signal) {
  try {
    process.kill(-child.pid, signal)
  } catch {
    // Already gone.
  }
}

for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => {
    shutdown(signal)
    process.exit(0)
  })
}
process.on('exit', () => shutdown('SIGKILL'))
child.on('exit', (code) => process.exit(code ?? 0))
