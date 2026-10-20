// Pure text extraction for the code that the site shows. This module does
// not read files: the callers pass the text, so the tests use plain strings.

const REGION = /^\s*(?:\/\/|#|<!--)\s*#(region|endregion)\s+(\S+)/
// claude 2026-10-20: created: a whole-line change annotation (#52)
const ANNOTATION = /^\s*(?:\/\/|#|<!--)\s*claude \d{4}-\d{2}-\d{2}: (?:created|changed):/

// claude 2026-10-20: created: the error for a missing source range (#52)
export class SnippetError extends Error {
  constructor(file, message) {
    super(`${file}: ${message}`)
    this.name = 'SnippetError'
  }
}

// claude 2026-10-20: created: removes the common indentation (#52)
export function dedent(lines) {
  const indents = lines
    .filter(line => line.trim() !== '')
    .map(line => /^[ \t]*/.exec(line)[0].length)
  const cut = indents.length === 0 ? 0 : Math.min(...indents)
  return lines.map(line => line.slice(cut))
}

// claude 2026-10-20: changed: the lines of one named region, without annotations (#52)
/**
 * Gives the text between `// #region <name>` and `// #endregion <name>`,
 * without the marker lines of nested regions, without whole-line
 * `// claude <date>: created|changed:` annotations, and without the common
 * indentation. Throws a `SnippetError` that names the file and the region
 * when a marker is missing or repeated.
 */
export function extractRegion(text, name, file) {
  const lines = text.split('\n')
  const starts = []
  const ends = []
  lines.forEach((line, index) => {
    const marker = REGION.exec(line)
    if (marker && marker[2] === name)
      (marker[1] === 'region' ? starts : ends).push(index)
  })
  const fail = message => new SnippetError(file, `region "${name}" ${message}`)
  if (starts.length === 0)
    throw fail(`not found (add "// #region ${name}")`)
  if (ends.length === 0)
    throw fail(`has no "#endregion ${name}" line`)
  if (starts.length > 1 || ends.length > 1)
    throw fail('is marked more than once')
  if (ends[0] < starts[0])
    throw fail('ends before it starts')
  const body = lines
    .slice(starts[0] + 1, ends[0])
    .filter(line => !REGION.test(line) && !ANNOTATION.test(line))
  if (body.every(line => line.trim() === ''))
    throw fail('is empty')
  return dedent(body).join('\n')
}

// claude 2026-10-20: created: the fence after a `<!-- tour: … -->` line (#52)
/**
 * Gives the body of the first code fence after `<!-- tour: <name> -->` in
 * a Markdown file, without the fence's indentation. Only blank lines may
 * stand between the marker and the fence (the Markdown formatter puts one
 * there). Throws a `SnippetError` when the marker is missing, repeated, or
 * not followed by a fence.
 */
export function extractMarkedFence(markdown, name, file) {
  const lines = markdown.split('\n')
  const marker = `<!-- tour: ${name} -->`
  const fail = message =>
    new SnippetError(file, `marker "${marker}" ${message}`)
  const found = lines
    .map((line, index) => (line.trim() === marker ? index : -1))
    .filter(index => index !== -1)
  if (found.length === 0)
    throw fail('not found')
  if (found.length > 1)
    throw fail('is used more than once')
  let start = found[0] + 1
  while (start < lines.length && lines[start].trim() === '')
    start++
  const open = /^([ \t]*)(`{3,}|~{3,})/.exec(lines[start] ?? '')
  if (!open)
    throw fail('is not followed by a code fence')
  const [, indent, fence] = open
  const body = []
  for (const line of lines.slice(start + 1)) {
    const close = /^[ \t]*(`{3,}|~{3,})\s*$/.exec(line)
    const closes = close
      && close[1][0] === fence[0]
      && close[1].length >= fence.length
    if (closes) {
      return body
        .map(item => item.startsWith(indent)
          ? item.slice(indent.length)
          : item.trimStart())
        .join('\n')
    }
    body.push(line)
  }
  throw fail('starts a code fence that is not closed')
}

// claude 2026-10-20: created: the adoption guide's rule for ZIO (#52)
/** The guide's rule: "For ZIO, replace `kyo-h3-ext` with `zio-h3-ext`". */
export function zioBuild(kyoBuild, file) {
  const count = kyoBuild.split('kyo-h3-ext').length - 1
  if (count !== 1) {
    throw new SnippetError(
      file,
      `the Kyo build.sbt must name kyo-h3-ext exactly once (found ${count})`,
    )
  }
  return kyoBuild.replace('kyo-h3-ext', 'zio-h3-ext')
}

// claude 2026-10-20: created: two source ranges shown as one text (#52)
/** Joins source ranges with a `// ...` line, indented like the next range. */
export function joinRanges(ranges) {
  return ranges.reduce((joined, range) => {
    const indent = /^[ \t]*/.exec(range)[0]
    return `${joined}\n${indent}// ...\n${range}`
  })
}

// claude 2026-10-20: created: the lines that differ between two texts (#52)
/**
 * Compares two texts line by line (longest common subsequence). Gives, for
 * each text, the 1-based numbers of its lines outside that subsequence.
 */
export function lineDiff(left, right) {
  const a = left.split('\n')
  const b = right.split('\n')
  const table = Array.from(
    { length: a.length + 1 },
    () => Array.from({ length: b.length + 1 }).fill(0),
  )
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      table[i][j] = a[i] === b[j]
        ? table[i + 1][j + 1] + 1
        : Math.max(table[i + 1][j], table[i][j + 1])
    }
  }
  const keptA = new Set()
  const keptB = new Set()
  let i = 0
  let j = 0
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      keptA.add(i++)
      keptB.add(j++)
    }
    else if (table[i + 1][j] >= table[i][j + 1]) {
      i++
    }
    else {
      j++
    }
  }
  const changed = (lines, kept) =>
    lines.flatMap((_, index) => (kept.has(index) ? [] : [index + 1]))
  return { left: changed(a, keptA), right: changed(b, keptB) }
}

// claude 2026-10-20: created: the marker names of a file, for the orphan check (#52)
/** Gives the names of every `#region <name>` line in a source file. */
export function regionNames(text) {
  return text.split('\n').flatMap((line) => {
    const marker = REGION.exec(line)
    return marker && marker[1] === 'region' ? [marker[2]] : []
  })
}

// claude 2026-10-20: created: the tour marker names of a guide (#52)
/** Gives the names of every `<!-- tour: <name> -->` line in a Markdown file. */
export function tourMarkers(markdown) {
  return markdown.split('\n').flatMap((line) => {
    const marker = /^\s*<!-- tour: (.+?) -->\s*$/.exec(line)
    return marker ? [marker[1]] : []
  })
}
