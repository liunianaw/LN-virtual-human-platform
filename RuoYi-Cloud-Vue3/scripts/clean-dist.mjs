import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const project = fileURLToPath(new URL('../', import.meta.url))
const output = path.resolve(project, 'dist')
if (path.dirname(output) !== path.resolve(project)) throw new Error('Unexpected build output directory')

// On this Windows/Node24 Unicode path rmSync(recursive) silently leaves old files.
// Remove entries individually, and refuse links rather than following them.
function clean(directory) {
  if (fs.lstatSync(directory).isSymbolicLink()) throw new Error('Build output must not be a link')
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name)
    if (entry.isSymbolicLink()) throw new Error('Unexpected link in build output')
    if (entry.isDirectory()) clean(file)
    else fs.unlinkSync(file)
  }
  fs.rmdirSync(directory)
}
if (fs.existsSync(output)) clean(output)
