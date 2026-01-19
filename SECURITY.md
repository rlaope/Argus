# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 0.1.x   | :white_check_mark: |

## Reporting a Vulnerability

We take the security of Project Argus seriously. If you believe you have found a security vulnerability, please report it to us as described below.

### How to Report

**Please do not report security vulnerabilities through public GitHub issues.**

Instead, please contact the maintainer directly via GitHub: [@rlaope](https://github.com/rlaope)

Please include the following information in your report:

- Type of issue (e.g., buffer overflow, injection, information disclosure)
- Full paths of source file(s) related to the issue
- Location of the affected source code (tag/branch/commit or direct URL)
- Step-by-step instructions to reproduce the issue
- Proof-of-concept or exploit code (if possible)
- Impact of the issue, including how an attacker might exploit it

### Response Timeline

- **Initial Response**: Within 48 hours
- **Status Update**: Within 7 days
- **Resolution Target**: Within 90 days (depending on complexity)

### What to Expect

1. **Acknowledgment**: We will acknowledge receipt of your vulnerability report
2. **Investigation**: We will investigate and validate the reported issue
3. **Communication**: We will keep you informed of our progress
4. **Resolution**: We will work on a fix and coordinate disclosure
5. **Credit**: We will credit you in our release notes (unless you prefer anonymity)

## Security Best Practices

When using Project Argus:

### Agent Security

- Run the agent with minimal required permissions
- Use in development/staging environments before production
- Review JFR event data for sensitive information exposure

### Server Security

- Bind WebSocket server to localhost in development
- Use TLS/SSL in production environments
- Implement authentication for production deployments
- Configure appropriate firewall rules

### Data Handling

- JFR events may contain sensitive stack traces
- Configure event filtering to exclude sensitive packages
- Implement data retention policies for collected metrics

## Security Updates

Security updates will be released as patch versions. We recommend:

- Subscribing to release notifications
- Updating to the latest patch version promptly
- Reviewing changelogs for security-related fixes
