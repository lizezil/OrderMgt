import { ReadableStream } from 'node:stream/web'
import { TextDecoder, TextEncoder } from 'node:util'

// jsdom ships neither the encoding pair nor streams; the SPA's streaming reader needs all three.
// Taking them from Node (rather than stubbing them) keeps the tests honest about the real types.
for (const [name, value] of Object.entries({ ReadableStream, TextDecoder, TextEncoder })) {
  if (!(name in globalThis)) {
    Object.defineProperty(globalThis, name, { value, writable: true, configurable: true })
  }
}
