// claude 2026-10-20: created: the Kyo | ZIO choice for the session (#52)
export type Runtime = 'kyo' | 'zio'

export const runtimes: { value: Runtime, label: string }[] = [
  { value: 'kyo', label: 'Kyo' },
  { value: 'zio', label: 'ZIO' },
]

/**
 * The runtime that the code panels show. Nuxt state keeps it while the
 * reader moves between pages; a reload starts again with Kyo.
 */
export function useRuntimeChoice() {
  return useState<Runtime>('h34s-runtime', () => 'kyo')
}

// claude 2026-10-20: created: the changed lines in words (#52)
/** "Lines 1, 6 and 8 differ from the ZIO version." or an empty string. */
export function describeChanged(lines: number[], other: Runtime): string {
  if (lines.length === 0)
    return ''
  const label = runtimes.find(item => item.value === other)?.label ?? other
  const numbers = lines.length === 1
    ? `Line ${lines[0]}`
    : `Lines ${lines.slice(0, -1).join(', ')} and ${lines.at(-1)}`
  const verb = lines.length === 1 ? 'differs' : 'differ'
  return `${numbers} ${verb} from the ${label} version. `
    + 'A bar at the left edge marks them.'
}
