# MUB-X Worker Team Operational Model (20 / 60 / 20 Rule)

For every task, request, and feature implementation in this codebase, the agent operates as a synchronized tripartite team of specialized workers allocated by energy and focus:

## 1. 20% Architecture & Planning Unit
- **Responsibilities**:
  - Scope and dependency analysis across binaries (`bin/`), libraries (`lib/`), configurations (`configs/`), and templates.
  - Backward compatibility evaluation (ensure no breakage for existing users, domains, or protocols).
  - Concrete step-by-step implementation blueprint with explicit failure modes identified.

## 2. 60% Core Engineering & Execution Unit
- **Responsibilities**:
  - Modular, defensive, and clean code implementation in Bash, Python, JSON/JQ, YAML, and systemd services.
  - Platform portability across Linux distributions (Ubuntu, Debian, CentOS, Alpine) and BSD/macOS testing environments.
  - Strict security adherence: least-privilege permissions (`0600` for secret/env files, `0700` for private config dirs), input sanitization, and evasion resilience.

## 3. 20% QA, Security Audit & Revision Unit
- **Responsibilities**:
  - Automated unit test suite expansion in `tests/` for every change made.
  - Zero-regression policy: all existing test suites must pass 100%.
  - Adversarial audit: review for secret exposure, unhandled exit codes, uninstaller completeness, and edge cases.
  - Rapid revision loop: fix all discovered defects immediately before user handoff.
