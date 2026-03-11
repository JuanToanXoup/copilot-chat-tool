export function statusIcon(status: string): string {
  switch (status) {
    case 'running': return '⟳'
    case 'completed': return '✓'
    case 'failed': return '✗'
    case 'cancelled': return '⊘'
    default: return '·'
  }
}
