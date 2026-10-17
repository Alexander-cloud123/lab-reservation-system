/**
 * Markdown 统一渲染入口（R13-2）
 *
 * 全项目 AI 文本只允许通过 renderMarkdown 输出 HTML：
 *  1. markdown-it 关闭内联 HTML（html:false）——模型输出中的 <script>/<img onerror>
 *     在解析阶段即被转义，DOMPurify 再做第二道清洗，v-html 前双重保险；
 *  2. linkify 关闭——URL 自动识别非预约场景必需，且减少误链表面；
 *  3. breaks:true——模型纯文本回答的单换行按 <br> 呈现，保持原有阅读体验；
 *  4. 所有外链强制 target=_blank + rel=noopener noreferrer。
 * 样式不在本文件：消费方为容器挂 markdown-body class，样式映射项目设计 token。
 */
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

const md = new MarkdownIt({
  html: false,
  linkify: false,
  breaks: true,
  typographer: false
})

/**
 * 链接渲染改写：新标签页打开 + noopener（防反向 window.opener 钓鱼）。
 * 保留默认渲染器的其余属性拼装，仅覆盖 target/rel 两项。
 */
const defaultLinkOpen =
  md.renderer.rules.link_open ||
  function (tokens, idx, options, env, self) {
    return self.renderToken(tokens, idx, options)
  }

md.renderer.rules.link_open = function (tokens, idx, options, env, self) {
  const targetIndex = tokens[idx].attrIndex('target')
  if (targetIndex < 0) {
    tokens[idx].attrPush(['target', '_blank'])
  } else {
    tokens[idx].attrs[targetIndex][1] = '_blank'
  }
  const relIndex = tokens[idx].attrIndex('rel')
  if (relIndex < 0) {
    tokens[idx].attrPush(['rel', 'noopener noreferrer'])
  } else {
    tokens[idx].attrs[relIndex][1] = 'noopener noreferrer'
  }
  return defaultLinkOpen(tokens, idx, options, env, self)
}

/**
 * 渲染 Markdown 为已消毒 HTML。
 * @param {string|null|undefined} text 原始文本（流式半截 Markdown 也可传入，输出容错 HTML）
 * @returns {string} 可直接赋给 v-html 的安全 HTML；空输入返回空串
 */
export function renderMarkdown(text) {
  if (!text) {
    return ''
  }
  const rawHtml = md.render(text)
  return DOMPurify.sanitize(rawHtml, {
    // 仅放行渲染必需的标签/属性策略（DOMPurify 默认白名单已禁 script/on* 事件属性，
    // 显式保留 target/rel 供外链新标签策略生效）
    ADD_ATTR: ['target', 'rel']
  })
}
