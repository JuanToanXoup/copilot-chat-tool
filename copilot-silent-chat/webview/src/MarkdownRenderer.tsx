import { useState, useEffect } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { createHighlighter, type Highlighter, type BundledLanguage } from 'shiki'

let highlighterPromise: Promise<Highlighter> | null = null
let cachedHighlighter: Highlighter | null = null

function getHighlighter(): Promise<Highlighter> {
  if (!highlighterPromise) {
    highlighterPromise = createHighlighter({
      themes: ['dark-plus'],
      langs: ['kotlin', 'java', 'typescript', 'javascript', 'python', 'json',
              'html', 'css', 'bash', 'shell', 'xml', 'yaml', 'sql', 'markdown',
              'go', 'rust', 'c', 'cpp', 'csharp', 'swift', 'ruby', 'php',
              'groovy', 'toml', 'diff'],
    }).then(h => {
      cachedHighlighter = h
      return h
    })
  }
  return highlighterPromise
}

function useHighlighter(): Highlighter | null {
  const [hl, setHl] = useState<Highlighter | null>(cachedHighlighter)
  useEffect(() => {
    if (!cachedHighlighter) {
      getHighlighter().then(setHl)
    }
  }, [])
  return hl
}

interface Props {
  content: string
}

export default function MarkdownRenderer({ content }: Props) {
  const highlighter = useHighlighter()

  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        // Let code handle everything — pre just passes through
        pre({ children }) {
          return <>{children}</>
        },
        code({ className, children, ...props }) {
          const match = /language-(\w+)/.exec(className || '')
          const codeString = String(children).replace(/\n$/, '')

          // Block code with language
          if (match) {
            if (highlighter) {
              const lang = match[1]
              const loaded = highlighter.getLoadedLanguages()
              if (loaded.includes(lang)) {
                const html = highlighter.codeToHtml(codeString, {
                  lang: lang as BundledLanguage,
                  theme: 'dark-plus',
                })
                return <div className="shiki-wrapper" dangerouslySetInnerHTML={{ __html: html }} />
              }
            }
            // Fallback: plain code block
            return <pre className="code-block"><code>{codeString}</code></pre>
          }

          // Block code without language (no className but multi-line)
          if (!className && codeString.includes('\n')) {
            return <pre className="code-block"><code>{codeString}</code></pre>
          }

          // Inline code
          return <code className="inline-code" {...props}>{children}</code>
        },
      }}
    >
      {content}
    </ReactMarkdown>
  )
}
