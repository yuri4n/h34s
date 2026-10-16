export default function h34sDevelopment(nitroApp) {
  nitroApp.hooks.hook('request', (event) => {
    event.context.h34s = { development: import.meta.dev === true }
  })
}
