# Documentation browser review

AI-assisted review. Human review pending.

Checked on 2026-10-15 in the prepared Linux x86_64 environment with
Chromium 154.0.8037.57 against the production Node server build. Desktop
checks used a 1440 × 1000 viewport; mobile checks used 390 × 844.

## Screenshots

- [Desktop homepage](desktop.png): ZIO and Kyo entry points in the shared design.
- [Mobile homepage](mobile.png): stacked adoption paths and handler guides.
- [Dark homepage](dark.png): the same content in the dark theme.
- [Kyo guide](kyo.png): navigation, highlighted Scala examples, services, and lifecycle guidance.

The screenshots capture full pages. Homepage screenshots use a fresh page
state after the interaction checks.

## Checks passed

- All 11 content routes returned HTTP 200 with one primary heading and no
  document-level horizontal overflow at the desktop viewport.
- The homepage Kyo card, desktop sidebar, and mobile menu opened the Kyo
  guide. Its code examples and JDK 25 prerequisite rendered correctly.
- The mobile homepage and Kyo guide stayed within the viewport.
- The keyboard skip link moved focus to the main content. Copying the
  homepage handler populated the clipboard and announced success.
- Search found both Skunk and Kyo documentation. Dark mode survived a
  reload, and switching back to light mode worked.
- Scala code used multiple syntax colors. No browser page errors or
  hydration warnings were observed.
- The agent documentation endpoints and raw ZIO/Kyo Markdown returned
  content. An unknown route returned 404. The AI assistant stayed disabled.

Cross-browser and screen-reader checks remain unverified. The requested
`better-ui` skill was unavailable in this environment; this review used
the existing shared layer conventions and direct browser checks.
