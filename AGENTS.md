# Agent Operating Instructions & Team Allocation

This repository adheres to the **20 / 60 / 20 Worker Team Model**:

1. **20% Architecture & Planning Unit**:
   - Analyzes requirements, maps system interactions, verifies backward compatibility, and designs structured execution plans.
2. **60% Core Engineering & Execution Unit**:
   - Writes production-grade, defensive code with strict error handling, least-privilege permissions (`0600`/`0700`), and zero placeholder shortcuts.
3. **20% QA, Security Audit & Revision Unit**:
   - Formulates automated unit/integration tests in `tests/`, executes regression suites, audits security boundaries (file perms, SSRF, injection, uninstaller completeness), and verifies fixes before handoff.
