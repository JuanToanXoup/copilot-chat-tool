interface InputAreaProps {
  input: string
  isLoading: boolean
  onInputChange: (v: string) => void
  onSend: () => void
  onStop: () => void
  onKeyDown: (e: React.KeyboardEvent) => void
}

export default function InputArea({ input, isLoading, onInputChange, onSend, onStop, onKeyDown }: InputAreaProps) {
  return (
    <div className="input-area">
      <textarea
        value={input}
        onChange={e => onInputChange(e.target.value)}
        onKeyDown={onKeyDown}
        placeholder="Type a message… (Enter to send, Shift+Enter for newline)"
        rows={2}
        disabled={isLoading}
      />
      <button
        onClick={isLoading ? onStop : onSend}
        disabled={!isLoading && !input.trim()}
        className={isLoading ? 'stop' : ''}
      >
        {isLoading ? 'Stop' : 'Send'}
      </button>
    </div>
  )
}
