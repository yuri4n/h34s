// The region markers of the init templates. A template marks the part that a guide block shows
// with `#region <name>` and `#endregion <name>` lines, in the syntax that the site's snippet
// extractor reads (docs/snippets/extract.mjs). init removes these lines before it writes a file.

// claude 2026-10-20: created: a whole-line region marker in // or # comments (ADR-0009)
const MARKER = /^\s*(?:\/\/|#)\s*#(?:region|endregion)\s+\S+\s*$/

// claude 2026-10-20: created: wraps lines in a named region (ADR-0009)
/** Gives `lines` between `<comment> #region <name>` and `<comment> #endregion <name>`. */
export function region(name, lines, comment = '//') {
  return [`${comment} #region ${name}`, ...lines, `${comment} #endregion ${name}`]
}

// claude 2026-10-20: created: the text that init writes, without region markers (ADR-0009)
/** Removes every region marker line from a rendered template. */
export function stripRegions(text) {
  return text.split('\n').filter(line => !MARKER.test(line)).join('\n')
}

// claude 2026-10-20: created: joins template lines into file text (ADR-0009)
/** Joins lines into a file's text, with one final newline. */
export function file(lines) {
  return `${lines.join('\n')}\n`
}
