# Security guidance

## Secrets

The local demo currently requires no secrets. Use `.env.example` as the
configuration template when adding environment variables. Store real values in
local environment variables, GitHub Actions secrets, or an approved external
secret manager. Never commit populated `.env` files, credentials, tokens, or
private keys.

Gitleaks and CodeQL run in GitHub Actions on pull requests and pushes to
`main`. Dependabot waits seven days before proposing dependency updates so
new releases can receive community scrutiny.

## Log scrubbing

Application output must not contain passwords, access tokens, private keys,
connection strings, personal data, or complete request credentials. Log stable
identifiers and safe event names instead of raw sensitive values. If a value
must be displayed for troubleshooting, mask all but the final four characters
and sanitize control characters before output.

Review new `System.out` or logger statements for sensitive data before merging.
Prefer a dedicated logger with explicit redaction when structured logging is
introduced.
