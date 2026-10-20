// The one text edit that init makes to an existing nuxt.config: it adds the h34s module to
// `modules`. It edits only shapes it can read safely, and otherwise returns null, so that init
// prints the line to add instead (ADR-0009, decision 2).

// claude 2026-10-20: created: the significant tokens of a config file, or null for a shape it does not read (ADR-0009)
/**
 * Splits `text` into the tokens that carry its structure: brackets, `,`, `:`, `...`, identifiers
 * and string literals, each with its offset. Comments and whitespace are skipped. Gives null for
 * text that a plain scan cannot read safely: a template literal with `${`, a `/` that does not
 * start a comment (a regular expression or a division), or an unclosed string or comment.
 */
function tokens(text) {
  const found = []
  let i = 0
  while (i < text.length) {
    const char = text[i]
    if (/\s/.test(char)) {
      i++
    }
    else if (text.startsWith('//', i)) {
      const end = text.indexOf('\n', i)
      i = end === -1 ? text.length : end
    }
    else if (text.startsWith('/*', i)) {
      const end = text.indexOf('*/', i + 2)
      if (end === -1)
        return null
      i = end + 2
    }
    else if (char === '\'' || char === '"' || char === '`') {
      let j = i + 1
      while (j < text.length && text[j] !== char) {
        if (char === '`' && text.startsWith('${', j))
          return null
        if (char !== '`' && text[j] === '\n')
          return null
        j += text[j] === '\\' ? 2 : 1
      }
      if (j >= text.length)
        return null
      found.push({ kind: 'string', quote: char, value: text.slice(i + 1, j), at: i })
      i = j + 1
    }
    else if (text.startsWith('...', i)) {
      found.push({ kind: 'spread', at: i })
      i += 3
    }
    else if ('()[]{},:'.includes(char)) {
      found.push({ kind: char, at: i })
      i++
    }
    else if (char === '/') {
      return null
    }
    else if (/[\w$]/.test(char)) {
      let j = i
      while (j < text.length && /[\w$]/.test(text[j]))
        j++
      found.push({ kind: 'word', value: text.slice(i, j), at: i })
      i = j
    }
    else {
      i++
    }
  }
  return found
}

// claude 2026-10-20: created: the indentation of the line after an opening bracket (ADR-0009)
/** The indentation for a new first entry after the bracket at `at`, which ends its line. */
function entryIndent(text, at, close) {
  const lines = text.slice(at + 1).split(/\r?\n/).slice(1)
  const next = lines.find(line => line.trim() !== '') ?? ''
  const indent = /^[ \t]*/.exec(next)[0]
  if (!next.trimStart().startsWith(close))
    return indent
  return indent + (indent.includes('\t') ? '\t' : '  ')
}

// claude 2026-10-20: created: inserts one entry right after an opening bracket, in the bracket's layout (ADR-0009)
/** Inserts `entry` as the first item after the bracket at `at`, which closes with `close`. */
function insertFirst(text, at, close, entry) {
  const eol = text.includes('\r\n') ? '\r\n' : '\n'
  const after = text.slice(at + 1)
  const empty = new RegExp(`^[ \\t]*\\${close}`).exec(after)
  if (empty) {
    return close === ']'
      ? `${text.slice(0, at + 1)}${entry}${after.slice(empty[0].length - 1)}`
      : `${text.slice(0, at + 1)}${eol}  ${entry},${eol}${after.slice(empty[0].length - 1)}`
  }
  if (/^[ \t]*\r?\n/.test(after))
    return `${text.slice(0, at + 1)}${eol}${entryIndent(text, at, close)}${entry},${after}`
  const leading = /^[ \t]*/.exec(after)[0]
  return `${text.slice(0, at + 1)}${leading}${entry}, ${after.slice(leading.length)}`
}

// claude 2026-10-20: created: the object of the one defineNuxtConfig({ ... }) and its top-level keys, or null (review P2)
/**
 * Reads the object literal of the one `defineNuxtConfig({ ... })` in `list`: the index of its
 * `{` and `}`, its top-level keys with the index of each key token, and whether a spread or a
 * shorthand `modules` hides some of its keys. Gives null when there is not exactly one
 * `defineNuxtConfig({` or the object does not close.
 */
function configObject(list) {
  const calls = list
    .map((token, index) => ({ token, index }))
    .filter(({ token }) => token.kind === 'word' && token.value === 'defineNuxtConfig')
  if (calls.length !== 1)
    return null
  const start = calls[0].index
  if (list[start + 1]?.kind !== '(' || list[start + 2]?.kind !== '{')
    return null
  const open = start + 2
  let depth = 0
  let close = -1
  let hidden = false
  const keys = []
  for (let index = open; index < list.length; index++) {
    const token = list[index]
    if ('([{'.includes(token.kind)) {
      depth++
    }
    else if (')]}'.includes(token.kind)) {
      depth--
      if (depth === 0) {
        close = index
        break
      }
    }
    else if (depth === 1) {
      const before = list[index - 1].kind
      const next = list[index + 1]?.kind
      if (token.kind === 'spread') {
        hidden = true
      }
      else if ((token.kind === 'word' || token.kind === 'string') && (before === '{' || before === ',')) {
        if (next === ':')
          keys.push({ name: token.value, index })
        else if (token.value === 'modules')
          hidden = true
      }
    }
  }
  return close === -1 ? null : { open, close, keys, hidden }
}

// claude 2026-10-20: created: whether the top-level modules array of defineNuxtConfig({...}) lists a module (review P2)
/**
 * Whether the `modules` array literal of `defineNuxtConfig({ ... })` has `name` as an entry: the
 * string itself, or an `[name, options]` array. A comment, Nitro's `modules` or another string
 * that holds the name is not an entry. Gives null when it cannot tell: text that `tokens` cannot
 * read, not exactly one `defineNuxtConfig({`, a `modules` value that is not one array literal, or
 * no `modules` key beside a spread. `addModule` gives null for each of these too.
 */
export function hasModule(text, name) {
  const list = tokens(text)
  const object = list && configObject(list)
  if (!object)
    return null
  const modules = object.keys.filter(key => key.name === 'modules')
  if (modules.length === 0)
    return object.hidden ? null : false
  const open = modules[0].index + 2
  if (modules.length > 1 || list[open]?.kind !== '[')
    return null
  const named = index => list[index]?.kind === 'string' && list[index].value === name && [',', ']'].includes(list[index + 1]?.kind)
  let depth = 0
  for (let index = open; index < list.length; index++) {
    const token = list[index]
    if ('([{'.includes(token.kind))
      depth++
    else if (')]}'.includes(token.kind) && --depth === 0)
      return false
    // An entry starts after the array's `[` and after each `,` between its entries.
    if (depth === 1 && (index === open || token.kind === ',')) {
      const entry = index + 1
      if (named(entry) || (list[entry]?.kind === '[' && named(entry + 1)))
        return true
    }
  }
  return null
}

// claude 2026-10-20: changed: adds the module to the top-level modules of defineNuxtConfig({...}), or gives null; the object is read by configObject (ADR-0009, decision 2, review P2)
/**
 * Adds `name` as the first entry of the `modules` array in `defineNuxtConfig({ ... })`, or adds a
 * `modules` property when the object has none. Gives the new text, or null when the file is not
 * in a shape this edit reads safely: not exactly one `defineNuxtConfig({`, a `modules` value that
 * is not an array literal, two `modules` keys, a spread or a shorthand `modules` in the object, or
 * text that `tokens` cannot read. The quotes follow the file's string literals.
 */
export function addModule(text, name) {
  const list = tokens(text)
  const object = list && configObject(list)
  if (!object || object.hidden)
    return null
  const strings = list.filter(token => token.kind === 'string')
  const quote = strings.filter(token => token.quote === '"').length
    > strings.filter(token => token.quote === '\'').length
    ? '"'
    : '\''
  const entry = `${quote}${name}${quote}`
  const modules = object.keys.filter(key => key.name === 'modules')
  if (modules.length > 1)
    return null
  if (modules.length === 1) {
    const value = list[modules[0].index + 2]
    if (value?.kind !== '[')
      return null
    return insertFirst(text, value.at, ']', entry)
  }
  return insertFirst(text, list[object.open].at, '}', `modules: [${entry}]`)
}
