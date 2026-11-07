/**
 * 轻量 Markdown 渲染（无第三方依赖）
 *
 * 对话回复里会出现标题、列表、代码块、表格、加粗与链接，直接 innerHTML
 * 会带来 XSS 风险，所以先把所有文本转义，再按标记生成受控的标签。
 * 支持的语法：标题(#~####)、有序/无序列表、引用、代码块、行内代码、
 * 表格、分隔线、加粗、斜体、删除线、链接。
 */

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 行内标记：代码 → 粗体 → 斜体 → 删除线 → 链接 */
function inline(raw: string): string {
  let text = escapeHtml(raw)
  text = text.replace(/`([^`\n]+)`/g, '<code>$1</code>')
  text = text.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
  text = text.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
  text = text.replace(/~~([^~\n]+)~~/g, '<del>$1</del>')
  text = text.replace(
    /\[([^\]\n]+)\]\((https?:\/\/[^)\s]+)\)/g,
    '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>'
  )
  return text
}

/** 表格：| a | b | + | --- | --- | */
function renderTable(rows: string[]): string {
  const cells = rows.map(row =>
    row
      .trim()
      .replace(/^\|/, '')
      .replace(/\|$/, '')
      .split('|')
      .map(c => c.trim())
  )
  if (cells.length < 2) {
    return `<p>${inline(rows.join(' '))}</p>`
  }
  const head = cells[0]
  const body = cells.slice(2)
  let html = '<div class="md-table"><table><thead><tr>'
  head.forEach(c => {
    html += `<th>${inline(c)}</th>`
  })
  html += '</tr></thead><tbody>'
  body.forEach(row => {
    html += '<tr>'
    head.forEach((_, i) => {
      html += `<td>${inline(row[i] ?? '')}</td>`
    })
    html += '</tr>'
  })
  html += '</tbody></table></div>'
  return html
}

export function renderMarkdown(input: string): string {
  if (!input) return ''
  const source = String(input).replace(/\r\n/g, '\n')

  // 1) 先把代码块抽出来，避免其中的标记被解析
  const codeBlocks: string[] = []
  const withPlaceholders = source.replace(/```([\w+-]*)\n([\s\S]*?)```/g, (_m, lang, code) => {
    const index = codeBlocks.length
    codeBlocks.push(
      `<pre class="md-code"><code data-lang="${escapeHtml(String(lang || ''))}">${escapeHtml(
        String(code).replace(/\n$/, '')
      )}</code></pre>`
    )
    return `\u0000CODE${index}\u0000`
  })

  const lines = withPlaceholders.split('\n')
  const html: string[] = []
  let i = 0

  const isTableRow = (line: string) => /^\s*\|.*\|\s*$/.test(line)
  const isTableSep = (line: string) => /^\s*\|[\s:|-]+\|\s*$/.test(line) && line.includes('-')

  while (i < lines.length) {
    const line = lines[i]

    // 空行
    if (!line.trim()) {
      i += 1
      continue
    }

    // 代码块占位符
    const codeMatch = line.trim().match(/^\u0000CODE(\d+)\u0000$/)
    if (codeMatch) {
      html.push(codeBlocks[Number(codeMatch[1])] || '')
      i += 1
      continue
    }

    // 标题
    const heading = line.match(/^(#{1,4})\s+(.*)$/)
    if (heading) {
      const level = Math.min(heading[1].length + 2, 6) // # → h3，避免和页面标题冲突
      html.push(`<h${level}>${inline(heading[2])}</h${level}>`)
      i += 1
      continue
    }

    // 分隔线
    if (/^\s*(---|\*\*\*|___)\s*$/.test(line)) {
      html.push('<hr />')
      i += 1
      continue
    }

    // 表格
    if (isTableRow(line) && i + 1 < lines.length && isTableSep(lines[i + 1])) {
      const rows: string[] = []
      while (i < lines.length && isTableRow(lines[i])) {
        rows.push(lines[i])
        i += 1
      }
      html.push(renderTable(rows))
      continue
    }

    // 引用
    if (/^\s*>\s?/.test(line)) {
      const quote: string[] = []
      while (i < lines.length && /^\s*>\s?/.test(lines[i])) {
        quote.push(lines[i].replace(/^\s*>\s?/, ''))
        i += 1
      }
      html.push(`<blockquote>${inline(quote.join(' '))}</blockquote>`)
      continue
    }

    // 无序列表
    if (/^\s*[-*•]\s+/.test(line)) {
      const items: string[] = []
      while (i < lines.length && /^\s*[-*•]\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s*[-*•]\s+/, ''))
        i += 1
      }
      html.push(`<ul>${items.map(it => `<li>${inline(it)}</li>`).join('')}</ul>`)
      continue
    }

    // 有序列表
    if (/^\s*\d+[.)]\s+/.test(line)) {
      const items: string[] = []
      while (i < lines.length && /^\s*\d+[.)]\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s*\d+[.)]\s+/, ''))
        i += 1
      }
      html.push(`<ol>${items.map(it => `<li>${inline(it)}</li>`).join('')}</ol>`)
      continue
    }

    // 普通段落：连续非空行合并
    const para: string[] = []
    while (
      i < lines.length &&
      lines[i].trim() &&
      !/^\s*(#{1,4}\s|[-*•]\s|\d+[.)]\s|>|---|\u0000CODE)/.test(lines[i]) &&
      !(isTableRow(lines[i]) && i + 1 < lines.length && isTableSep(lines[i + 1]))
    ) {
      para.push(lines[i])
      i += 1
    }
    if (para.length) {
      html.push(`<p>${inline(para.join('<br />'))}</p>`)
    } else {
      i += 1
    }
  }

  return html.join('\n')
}

export default renderMarkdown
