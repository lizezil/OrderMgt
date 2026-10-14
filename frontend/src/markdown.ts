/**
 * Markdown, rendered here rather than by a dependency.
 *
 * The SPA ships one runtime dependency (Vue); this is the only place that needs a parser, and the
 * dialect assistant prose actually uses is narrow -- paragraphs, emphasis, lists, the odd table.
 * What hand-rolling buys is one rule that is easy to state and easy to keep: **the source is
 * untrusted** (a model writes it, and a customer types it), so nothing ever reaches the output
 * before it has been escaped. Escaping therefore happens at the leaves -- inline text, code blocks,
 * table cells -- and every tag in the result is one this file wrote itself. A link becomes an `<a>`
 * only when its scheme is on the allowlist, so `javascript:` stays text.
 *
 * The cost of hand-rolling shows up in what is deliberately absent: no HTML passthrough, no
 * reference links, no setext headings, no footnotes. None of it is prose written here.
 */

const ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  // The marker that stands in for an extracted code span is dropped from the input, so text can
  // never impersonate one and have it expand into a `<code>` this file did not write.
  '\u0000': '',
}

/** The placeholder a code span leaves behind while the rest of the line is still being read. */
const PLACEHOLDER = /\u0000(\d+)\u0000/g

const CODE_SPAN = /`([^`\n]+)`/g
const IMAGE_OR_LINK = /(!?)\[([^\]]*)\]\(\s*([^)\s]*)(?:\s+"[^"]*")?\s*\)/g
const BOLD = /\*\*([\s\S]+?)\*\*|__([\s\S]+?)__/g
const ITALIC = /\*([^*\n]+?)\*|(?<![\w])_([^_\n]+?)_(?![\w])/g
const STRIKE = /~~([\s\S]+?)~~/g

/** Schemes and shapes a link may have; anything else keeps its markdown spelling as text. */
const SAFE_URL = /^(?:https?:\/\/|mailto:|\/|#)/i

const HEADING = /^(#{1,6})\s+(.*)$/
const FENCE = /^\s*```/
const FENCE_END = /^\s*```\s*$/
const RULE = /^\s*(?:-{3,}|\*{3,}|_{3,})\s*$/
const QUOTE_LINE = /^\s*>\s?/
const LIST_ITEM = /^(\s*)(?:([-*+])|(\d+)[.)])\s+(.*)$/

/** A nested list has to be indented this far past its parent to count as one. */
const NEST_INDENT = 2

/** Inline markup. `source` is raw; escaping is this function's first act, not the caller's. */
export function renderInline(source: string, allowLinks = true): string {
  const codes: string[] = []
  let html = escapeHtml(source).replace(CODE_SPAN, (_match, code: string) => {
    codes.push(code)
    return `\u0000${codes.length - 1}\u0000`
  })

  if (allowLinks) {
    html = html.replace(IMAGE_OR_LINK, (match, bang: string, text: string, url: string) => {
      // An image's alt text is the honest part of it: the browser is not sent fetching anything.
      if (bang === '!') return renderInline(text, false)
      if (!SAFE_URL.test(url)) return match
      return `<a href="${url}" target="_blank" rel="noopener noreferrer">${renderInline(text, false)}</a>`
    })
  }

  html = html.replace(BOLD, (_match, strong: string, strongAlt: string) => {
    return `<strong>${strong ?? strongAlt}</strong>`
  })
  html = html.replace(ITALIC, (_match, em: string, emAlt: string) => {
    return `<em>${em ?? emAlt}</em>`
  })
  html = html.replace(STRIKE, (_match, struck: string) => `<del>${struck}</del>`)

  return html.replace(
    PLACEHOLDER,
    (_match, index: string) => `<code>${codes[Number(index)]}</code>`,
  )
}

/** Block markup; the entry point the view calls. */
export function renderMarkdown(source: string): string {
  return renderBlocks(source.replace(/\r\n?/g, '\n').split('\n'))
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"\u0000]/g, (char) => ESCAPES[char]!)
}

function renderBlocks(lines: string[]): string {
  const html: string[] = []
  let index = 0

  while (index < lines.length) {
    const line = lines[index]!
    if (!line.trim()) {
      index++
      continue
    }

    if (FENCE.test(line)) {
      const body: string[] = []
      index++
      while (index < lines.length && !FENCE_END.test(lines[index]!)) {
        body.push(lines[index]!)
        index++
      }
      if (index < lines.length) index++
      html.push(`<pre class="md-code"><code>${escapeHtml(body.join('\n'))}</code></pre>`)
      continue
    }

    const heading = HEADING.exec(line)
    if (heading) {
      const level = heading[1]!.length
      html.push(`<h${level}>${renderInline(heading[2]!.trim())}</h${level}>`)
      index++
      continue
    }

    if (RULE.test(line)) {
      html.push('<hr />')
      index++
      continue
    }

    if (QUOTE_LINE.test(line)) {
      const quoted: string[] = []
      while (index < lines.length && QUOTE_LINE.test(lines[index]!)) {
        quoted.push(lines[index]!.replace(QUOTE_LINE, ''))
        index++
      }
      html.push(`<blockquote>${renderBlocks(quoted)}</blockquote>`)
      continue
    }

    if (isTableStart(lines, index)) {
      const table = renderTable(lines, index)
      html.push(table.html)
      index = table.next
      continue
    }

    if (listItem(line)) {
      const list = renderList(lines, index)
      html.push(list.html)
      index = list.next
      continue
    }

    const paragraph = renderParagraph(lines, index)
    html.push(paragraph.html)
    index = paragraph.next
  }

  return html.join('\n')
}

/**
 * One paragraph: every line up to a blank one or the start of another block. Line breaks are kept
 * (`<br />`) rather than softened into spaces -- prose from the assistant arrives pre-wrapped, and
 * collapsing its line breaks would run lists-without-markers and enumerations together.
 */
function renderParagraph(lines: string[], start: number): { html: string; next: number } {
  const body: string[] = []
  let index = start
  while (index < lines.length) {
    const line = lines[index]!
    if (!line.trim() || startsBlock(lines, index)) break
    body.push(renderInline(line.trim()))
    index++
  }
  return { html: `<p>${body.join('<br />')}</p>`, next: index }
}

function startsBlock(lines: string[], index: number): boolean {
  const line = lines[index]!
  return (
    FENCE.test(line) ||
    RULE.test(line) ||
    QUOTE_LINE.test(line) ||
    HEADING.test(line) ||
    isTableStart(lines, index) ||
    listItem(line) !== null
  )
}

interface ListMarker {
  indent: number
  ordered: boolean
  /** The number the item spelled out, so a list starting at 3. keeps its own numbering. */
  number: number | null
  text: string
}

function listItem(line: string): ListMarker | null {
  const match = LIST_ITEM.exec(line)
  if (!match) return null
  return {
    indent: match[1]!.length,
    ordered: match[3] !== undefined,
    number: match[3] === undefined ? null : Number(match[3]),
    text: match[4]!,
  }
}

function indentOf(line: string): number {
  return line.length - line.trimStart().length
}

function nextNonBlank(lines: string[], from: number): number {
  for (let index = from; index < lines.length; index++) {
    if (lines[index]!.trim()) return index
  }
  return -1
}

function renderList(lines: string[], start: number): { html: string; next: number } {
  const opener = listItem(lines[start]!)!
  const base = opener.indent
  const ordered = opener.ordered
  const tag = ordered ? 'ol' : 'ul'
  // A list that starts at 1 needs no attribute; one that starts at 3. must keep its count.
  const at = ordered && opener.number !== 1 ? ` start="${opener.number}"` : ''
  const html: string[] = [`<${tag}${at}>`]
  let open = false
  let index = start

  while (index < lines.length) {
    const line = lines[index]!

    if (!line.trim()) {
      // A blank line only ends the list if nothing indented follows it.
      const ahead = nextNonBlank(lines, index)
      const item = ahead === -1 ? null : listItem(lines[ahead]!)
      if (!item || item.indent < base) break
      index++
      continue
    }

    const indent = indentOf(line)
    if (indent < base) break
    const item = listItem(line)

    // A nested list belongs to the item above it, so it is written inside that item.
    if (item && indent >= base + NEST_INDENT) {
      const nested = renderList(lines, index)
      html.push(nested.html)
      index = nested.next
      continue
    }

    if (item) {
      if (item.ordered !== ordered) break
      if (open) html.push('</li>')
      html.push(`<li>${renderInline(item.text.trim())}`)
      open = true
      index++
      continue
    }

    // An indented line that is not a marker: still the open item's text.
    if (open && indent >= base + NEST_INDENT) {
      html.push(`<br />${renderInline(line.trim())}`)
      index++
      continue
    }

    break
  }

  if (open) html.push('</li>')
  html.push(`</${tag}>`)
  return { html: html.join(''), next: index }
}

function splitRow(line: string): string[] {
  return line
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((cell) => cell.trim())
}

/** `---`, `:--`, `--:`, `:-:` -- how a table says its columns are aligned. */
function columnAlignment(cell: string): string | null {
  if (!/^:?-+:?$/.test(cell)) return null
  if (cell.startsWith(':') && cell.endsWith(':')) return 'center'
  if (cell.endsWith(':')) return 'right'
  return 'left'
}

function isTableStart(lines: string[], index: number): boolean {
  const header = lines[index]
  const divider = lines[index + 1]
  // The divider must carry pipes of its own: without that, `text` over `---` is a paragraph and a
  // horizontal rule, not a one-column table.
  if (!header || !divider || !header.includes('|') || !divider.includes('|')) return false
  const cells = splitRow(divider)
  return cells.length > 0 && cells.every((cell) => columnAlignment(cell) !== null)
}

function renderTable(lines: string[], start: number): { html: string; next: number } {
  const header = splitRow(lines[start]!)
  const alignment = splitRow(lines[start + 1]!).map(columnAlignment)
  const cell = (tag: 'th' | 'td', value: string, column: number): string => {
    const at = alignment[column]
    const style = at === 'center' || at === 'right' ? ` style="text-align:${at}"` : ''
    return `<${tag}${style}>${renderInline(value)}</${tag}>`
  }

  let index = start + 2
  const body: string[] = []
  while (index < lines.length && lines[index]!.trim() && lines[index]!.includes('|')) {
    const row = splitRow(lines[index]!)
    body.push(`<tr>${row.map((value, column) => cell('td', value, column)).join('')}</tr>`)
    index++
  }

  const head = `<thead><tr>${header.map((value, column) => cell('th', value, column)).join('')}</tr></thead>`
  return {
    html: `<table>${head}<tbody>${body.join('')}</tbody></table>`,
    next: index,
  }
}
