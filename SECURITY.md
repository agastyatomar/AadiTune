# Security Policy

## Reporting a vulnerability

Please do **not** open public issues for security vulnerabilities.

1. Prefer GitHub's private vulnerability reporting: open a report at
   <https://github.com/agastyatomar/AadiTune/security/advisories/new>.
2. If you cannot use GitHub's form, contact the maintainer
   (`agastyatomar`) directly.

Include: affected version, impact, reproduction steps, and any PoC.

You will receive an acknowledgement within approximately 7 days, and a fix /
advisory where appropriate. Please keep the report confidential until a
release is available.

## Scope

- The AadiTune Android client (`app/` and library modules).
- Anything that leaks user credentials/tokens (YouTube login cookies are
  stored on-device; the app must never exfiltrate them).
- Anything that enables unauthorized access to paid/DRM-protected content —
  such reports are out of scope as *vulnerabilities*, but policy violations
  are still appreciated if genuinely introduced by this codebase.

Out of scope: the upstream YouTube/Google services themselves.

## Supported versions

Only the latest release series (`x.y.z` tags) receives security fixes.
