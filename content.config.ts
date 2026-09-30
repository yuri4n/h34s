import { defineCollection, defineContentConfig } from '@nuxt/content'

import z from 'zod'

export default defineContentConfig({
  collections: {
    focuses: defineCollection({
      type: 'page',
      source: 'focuses/*.md',
      schema: z.object({
        // title: z.string(),
        icon: z.string().optional(),
      }),
    }),
  },
})
