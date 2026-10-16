import { describe, it, expect } from 'vitest'
import { analysisActive, reviewLabels } from './api'
describe('review state is distinct from Actions delivery', () => {
  it('does not label a saved report as published', () => {
    expect(reviewLabels.REPORT_READY).toBe('Отчёт готов')
    expect(reviewLabels.COMPLETED).toBe('Опубликовано')
    expect(analysisActive('REPORT_READY')).toBe(false)
  })
  it('tracks pending analysis without causing a POST retry', () => {
    expect(analysisActive('ANALYZING')).toBe(true)
    expect(analysisActive('QUEUED')).toBe(true)
    expect(analysisActive('INTERRUPTED')).toBe(false)
    expect(analysisActive('SUPERSEDED')).toBe(false)
  })
})
