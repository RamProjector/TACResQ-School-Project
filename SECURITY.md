# Security policy

## Supported branch

Security fixes are developed against `main`.

## Reporting a vulnerability

Please do not open a public issue for a suspected vulnerability. Use GitHub's private vulnerability reporting or contact the repository maintainer through the private contact method configured on GitHub.

Include:

- A clear description of the issue
- Reproduction steps or a minimal proof of concept
- Affected file, route, migration, or workflow
- Potential impact
- Any suggested mitigation

Do not include real user data, production credentials, access tokens, or real emergency information in a report.

## Security requirements for contributors

- Never commit Supabase service-role or secret keys.
- Never commit Render API credentials, database passwords, Laravel `APP_KEY`, or private signing keys.
- Use only publishable client configuration in Android code.
- Keep Supabase Row Level Security and database role checks enabled.
- Treat location data and phone numbers as sensitive personal information.
- Use disposable CI values and fake records for tests.

The public Supabase publishable key is client configuration. It does not replace authentication, RLS, or server-side authorization.
