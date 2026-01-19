# Contributing to Project Argus

Thank you for your interest in contributing to Project Argus!

## Getting Started

### Prerequisites

- Java 21+
- Gradle 8.4+

### Setup

```bash
# Clone the repository
git clone https://github.com/your-org/argus.git
cd argus

# Build the project
./gradlew build
```

## Development Workflow

### Branch Naming

- `feature/` - New features
- `fix/` - Bug fixes
- `docs/` - Documentation updates
- `refactor/` - Code refactoring

### Code Style

- Follow existing code conventions
- Use 4 spaces for indentation
- Maximum line length: 120 characters
- Run `./gradlew build` before committing

### Commit Messages

Use conventional commit format:

```
type(scope): description

[optional body]
```

Types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`

Examples:
- `feat(agent): add memory leak detection`
- `fix(server): handle WebSocket disconnection`
- `docs(readme): update installation instructions`

## Pull Request Process

1. Fork the repository
2. Create a feature branch from `main`
3. Make your changes
4. Ensure tests pass: `./gradlew test`
5. Submit a pull request

### PR Checklist

- [ ] Code compiles without warnings
- [ ] Tests pass
- [ ] Documentation updated (if applicable)
- [ ] Commit messages follow conventions

## Reporting Issues

When reporting issues, please include:

- Java version (`java -version`)
- Operating system
- Steps to reproduce
- Expected vs actual behavior
- Relevant logs or stack traces

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
