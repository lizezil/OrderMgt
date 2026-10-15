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

/**
 * The e2e customers. Identity is deployment config, not a header (ADR-0017: there is no user
 * table), so a test cannot invent a customer the way it used to -- it must sign in as somebody the
 * config knows. This pool is that "somebody", and it is small and fixed on purpose: the schema is
 * wiped below, so every run starts from the same empty customers and the suite needs no per-run
 * naming scheme.
 *
 * Kept in step with `e2e/support/viewer.ts`, which allocates the same ids for the tests
 * (POOL_BASE / POOL_SIZE / POOL_PIN; the schema reset is what makes the two agreeing enough).
 */
const POOL_BASE = 700000
const POOL_SIZE = 40
const POOL_PIN = '4242'
const AUTH_PINS = Array.from(
  { length: POOL_SIZE },
  (_, index) => `${POOL_BASE + index + 1}:${POOL_PIN}`,
).join(',')

/**
 * Empties the run's own schema before boot, so a customer id means the same thing every time: an
 * account nobody has ordered or chatted with yet. Flyway then migrates the empty database from
 * scratch. Only ever the dedicated e2e schema -- the name has to say so.
 */
function resetSchema() {
  if (!/e2e/.test(DATABASE)) {
    fail(`refusing to reset the schema '${DATABASE}': the e2e database name must contain "e2e"`)
  }
  const sql =
    `DROP DATABASE IF EXISTS \`${DATABASE}\`; ` +
    `CREATE DATABASE \`${DATABASE}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`
  // The resident MySQL is the Compose container (`make db-up`), and its own client already has the
  // credentials -- the same route the story walkthrough takes to move server state by hand.
  const viaDocker = spawnSync(
    'docker',
    ['exec', '-i', 'ordermgt-mysql', 'sh', '-c', 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"'],
    { input: sql, stdio: ['pipe', 'inherit', 'inherit'] },
  )
  if (!viaDocker.error && viaDocker.status === 0) {
    return
  }
  // No Docker (or no such container): a local mysql client pointed at the same server will do.
  const viaClient = spawnSync(
    'mysql',
    [
      '-h',
      MYSQL_HOST,
      '-P',
      String(MYSQL_PORT),
      '-u',
      process.env.MYSQL_ROOT_USER ?? 'root',
      `-p${process.env.MYSQL_ROOT_PASSWORD ?? 'root'}`,
    ],
    { input: sql, stdio: ['pipe', 'inherit', 'inherit'] },
  )
  if (viaClient.error || viaClient.status !== 0) {
    fail(
      `could not reset the e2e schema '${DATABASE}'. Start the resident MySQL with \`make db-up\` ` +
        '(or make a mysql client reachable on it) before running the suite.',
    )
  }
}

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

// Empty schema + the pool of customers the tests sign in as: identity and isolation both come from
// this one step (see POOL_* above).
resetSchema()

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
    // The only identities this run knows: the test pool above. Anything else cannot sign in, which
    // is what keeps a test from drifting onto an account an earlier run left data under.
    ASSISTANT_AUTH_PINS: AUTH_PINS,
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
