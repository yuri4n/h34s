import { spawnSync } from 'node:child_process'
import { rmSync, statSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath, pathToFileURL } from 'node:url'

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const modules = [
  { task: 'nuxtsvr/fullLinkJS', directory: 'server/.scala', outputs: ['test.js', 'notes-get.js', 'notes-post.js'] },
  { task: 'kyoExample/fullLinkJS', directory: 'server/.scala-kyo', outputs: ['kyo-greeting-get.js', 'kyo-greeting-post.js'] },
]

export function verifyScalaOutputs(root = repository) {
  for (const { directory, outputs } of modules) {
    for (const filename of outputs) {
      const output = resolve(root, directory, filename)
      const file = statSync(output, { throwIfNoEntry: false })
      if (!file?.isFile() || file.size === 0) {
        throw new Error(
          `Scala production linker did not produce ${directory}/${filename}`,
        )
      }
    }
  }
}

export function buildScala({ root = repository, run = spawnSync } = {}) {
  // Nitro must never package stale development output after a failed link.
  for (const { directory } of modules)
    rmSync(resolve(root, directory), { recursive: true, force: true })

  const command = process.platform === 'win32' ? 'sbt.bat' : 'sbt'
  const tasks = modules.map(({ task }) => task)
  const result = run(command, tasks, { cwd: root, stdio: 'inherit' })
  if (result.error)
    throw result.error
  if (result.signal || result.status !== 0) {
    const reason = result.signal || `exit ${result.status}`
    throw new Error(`Scala production linking failed (${reason})`)
  }

  verifyScalaOutputs(root)
}

const entry = process.argv[1] && pathToFileURL(resolve(process.argv[1]))
if (entry && import.meta.url === entry.href) {
  try {
    buildScala()
  }
  catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}
