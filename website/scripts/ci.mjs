import { spawnSync } from 'node:child_process'
import process from 'node:process'

for (const stage of ['check', 'build']) {
  const result = spawnSync('pnpm', ['run', stage], { stdio: 'inherit' })
  if (result.error || result.signal || result.status !== 0) {
    const message = `Documentation ${stage} failed: `
      + `${result.error || result.signal || `exit ${result.status}`}`
    console.error(message)
    if (process.env.GITHUB_ACTIONS === 'true')
      console.error(`::error title=Documentation CI::${message}`)
    process.exit(result.status || 1)
  }
  if (process.env.GITHUB_ACTIONS === 'true')
    console.log(`::notice title=Documentation CI::${stage} passed`)
}
