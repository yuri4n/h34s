export default defineNitroPlugin((nitroApp) => {
  nitroApp.hooks.hook('request', (event) => {
    event.context.h34s = { development: import.meta.dev }
  })
})
