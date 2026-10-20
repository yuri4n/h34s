// claude 2026-10-20: created: reads the countdown routes with the browser's EventSource (ADR-0007)
// The streaming guide shows this region (docs/content/1.guide/09.streaming.md,
// "Read the events in the browser"): docs/test checks that they are equal.
// #region sse-browser
/** One `tick` event: the JSON that `ServerEvent.json(Countdown(n))` writes. */
interface Countdown {
  remaining: number
}

type CountdownState = 'idle' | 'running' | 'done' | 'failed'

/** The countdown of `GET /api/<runtime>/countdown`, read with `EventSource`. */
export function useCountdown(runtime: 'kyo' | 'zio') {
  const remaining = ref<number | null>(null)
  const state = ref<CountdownState>('idle')
  let source: EventSource | undefined

  function stop() {
    source?.close()
    source = undefined
  }

  function start() {
    stop()
    remaining.value = null
    state.value = 'running'
    source = new EventSource(`/api/${runtime}/countdown`)
    source.addEventListener('tick', (message) => {
      const tick: Countdown = JSON.parse(message.data)
      remaining.value = tick.remaining
      if (tick.remaining === 0) {
        state.value = 'done'
        stop()
      }
    })
    // EventSource reconnects after an error. Stop instead: the run failed.
    source.addEventListener('error', () => {
      state.value = 'failed'
      stop()
    })
  }

  onScopeDispose(stop)
  return { remaining, state, start }
}
// #endregion sse-browser
