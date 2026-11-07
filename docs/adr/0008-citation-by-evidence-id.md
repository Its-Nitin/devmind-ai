# ADR-0008: Model cites evidence IDs; server resolves and validates
- Status: Accepted
- Date: 2026-11-06

## Context
The blueprint asks the model to emit `filePath/startLine/endLine` and then validates them. LLMs routinely get line numbers slightly wrong or invent plausible paths. Validation then has to decide whether "lines 40–57 vs given 41–58" is valid. That's fuzzy, and a weak foundation for a "verifiable citations" claim.

## Options considered
| Option | For | Against |
|---|---|---|
| A. Model emits paths + lines; server checks they exist | Matches the blueprint | Off-by-N errors, invented paths, fuzzy validation |
| B. **Model emits evidence IDs from the provided set; server maps IDs → exact path/lines/revision** | Hallucinated locations become impossible; validation is exact set membership | Granularity is limited to chunk boundaries |
| C. B + optional sub-range (model gives line offsets inside a chunk) | Finer citations | Reintroduces line errors. Defer. |

## Decision
**B.** Each evidence block sent to the model has an ID `E1..En`. The schema requires `claims[].evidenceIds` drawn from that set. The server:
1. Drops IDs not in the set (counted as `citations.dropped`).
2. Moves claims that lose all evidence into `uncertainties`.
3. Suppresses the answer (`mode=EVIDENCE_ONLY`) if no valid citations remain and `insufficientEvidence=false`.
4. Resolves the IDs it keeps into `(path, symbol, startLine, endLine, revisionSha)` for the response.

Validity (the citation exists) is automatic. **Support** (the cited code backs the claim) is measured separately by manual review in the benchmark. The two are never conflated in reports.

## Consequences
- \+ Experiment C becomes crisp: count of dropped IDs with the validator on, vs. the same answers shown raw.
- \+ A UI citation always opens real code at the real revision.
- − Citation granularity = chunk (method-level), which fits code well.
