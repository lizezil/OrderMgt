import { describe, expect, it } from 'vitest'
import { renderInline, renderMarkdown } from './markdown'

describe('转义', () => {
  it('原文里的标签只能是文字，成不了元素', () => {
    expect(renderMarkdown('<img src=x onerror=alert(1)>')).toBe(
      '<p>&lt;img src=x onerror=alert(1)&gt;</p>',
    )
  })

  it('加粗与代码里的尖括号同样是文字', () => {
    expect(renderInline('**a < b** and `c > d`')).toBe(
      '<strong>a &lt; b</strong> and <code>c &gt; d</code>',
    )
  })
})

describe('行内标记', () => {
  it('加粗、斜体、行内代码、删除线', () => {
    expect(renderInline('**粗** *斜* `码` ~~删~~')).toBe(
      '<strong>粗</strong> <em>斜</em> <code>码</code> <del>删</del>',
    )
  })

  it('下划线只在下划线两侧不是词时才当强调，snake_case 原样不动', () => {
    expect(renderInline('_斜_ 与 GADGET_02_NAME')).toBe('<em>斜</em> 与 GADGET_02_NAME')
  })

  it('代码 span 里的星号不再当标记', () => {
    expect(renderInline('`a ** b`')).toBe('<code>a ** b</code>')
  })

  it('链接带上新窗口与 noopener', () => {
    expect(renderInline('见 [文档](https://example.com/x)')).toBe(
      '见 <a href="https://example.com/x" target="_blank" rel="noopener noreferrer">文档</a>',
    )
  })

  it('非白名单协议不生成链接，原样留成文字', () => {
    expect(renderInline('[点我](javascript:alert(1))')).toBe('[点我](javascript:alert(1))')
  })

  it('图片只留 alt 文字，不替浏览器发请求', () => {
    expect(renderInline('![追踪像素](https://evil.example/pixel.png)')).toBe('追踪像素')
  })
})

describe('块级', () => {
  it('空文本什么都不渲染', () => {
    expect(renderMarkdown('')).toBe('')
    expect(renderMarkdown('\n  \n')).toBe('')
  })

  it('一段里的换行保留成 <br />，空行分段', () => {
    expect(renderMarkdown('第一行\n第二行\n\n另一段')).toBe(
      '<p>第一行<br />第二行</p>\n<p>另一段</p>',
    )
  })

  it('标题按级别成 h1–h6', () => {
    expect(renderMarkdown('## 你有 3 张订单')).toBe('<h2>你有 3 张订单</h2>')
  })

  it('无序列表、有序列表各自成表', () => {
    expect(renderMarkdown('- 一张\n- 两张')).toBe('<ul><li>一张</li><li>两张</li></ul>')
    expect(renderMarkdown('1. 先付款\n2. 再发货')).toBe('<ol><li>先付款</li><li>再发货</li></ol>')
  })

  it('缩进两格的列表嵌在上一条里', () => {
    expect(renderMarkdown('- 订单 ORD1\n  - CREATED\n- 订单 ORD2')).toBe(
      '<ul><li>订单 ORD1<ul><li>CREATED</li></ul></li><li>订单 ORD2</li></ul>',
    )
  })

  it('列表项里的续行仍属于这一条', () => {
    expect(renderMarkdown('- 金额\n  由服务端算')).toBe('<ul><li>金额<br />由服务端算</li></ul>')
  })

  it('从 3. 开始的有序列表保留自己的序号', () => {
    expect(renderMarkdown('3. 三\n4. 四')).toBe('<ol start="3"><li>三</li><li>四</li></ol>')
  })

  it('围栏代码块整块保留，内部不做行内解析', () => {
    expect(renderMarkdown('```\n{"a": 1}\n**不是粗体**\n```')).toBe(
      '<pre class="md-code"><code>{&quot;a&quot;: 1}\n**不是粗体**</code></pre>',
    )
  })

  it('工具结果行的渲染由后端决定，前端不再为它留特殊排版', () => {
    // 后端现在把只读工具的结果渲染成人话（ADR-0015），正文里不再有 [工具名] {JSON}。
    // 方括号开头的一行因此只是一行普通文字，遇到裸 JSON 也不例外。
    expect(renderMarkdown('[queryMyOrders] {"total":0}')).toBe(
      '<p>[queryMyOrders] {&quot;total&quot;:0}</p>',
    )
  })

  it('以方括号开头的行内链接照常成链接', () => {
    expect(renderMarkdown('[文档](https://example.com)')).toBe(
      '<p><a href="https://example.com" target="_blank" rel="noopener noreferrer">文档</a></p>',
    )
  })

  it('引用块里的内容照常成段', () => {
    expect(renderMarkdown('> 价格由服务端算\n> 不是我说的')).toBe(
      '<blockquote><p>价格由服务端算<br />不是我说的</p></blockquote>',
    )
  })

  it('表格成 thead/tbody，对齐由分隔行决定', () => {
    expect(renderMarkdown('| 单号 | 金额 |\n| --- | ---: |\n| ORD1 | 99.00 |')).toBe(
      '<table><thead><tr><th>单号</th><th style="text-align:right">金额</th></tr></thead>' +
        '<tbody><tr><td>ORD1</td><td style="text-align:right">99.00</td></tr></tbody></table>',
    )
  })

  it('表格单元格里可以有行内标记', () => {
    expect(renderMarkdown('| a |\n| - |\n| `x` |')).toBe(
      '<table><thead><tr><th>a</th></tr></thead><tbody><tr><td><code>x</code></td></tr></tbody></table>',
    )
  })

  it('散文里的竖线不构成表格', () => {
    expect(renderMarkdown('A|B\n不是表格')).toBe('<p>A|B<br />不是表格</p>')
  })

  it('横线只在独占一行时才是分隔线', () => {
    expect(renderMarkdown('标题\n\n---\n\n正文')).toBe('<p>标题</p>\n<hr />\n<p>正文</p>')
    expect(renderMarkdown('没有空行的 --- 那一行仍是一段')).toBe(
      '<p>没有空行的 --- 那一行仍是一段</p>',
    )
  })

  it('短横线分隔行紧跟文字时是段落加横线，不是一列表格', () => {
    expect(renderMarkdown('一句话\n---')).toBe('<p>一句话</p>\n<hr />')
  })
})
