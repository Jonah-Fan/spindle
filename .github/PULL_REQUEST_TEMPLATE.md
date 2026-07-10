<!-- Thanks for contributing! If this is anything beyond a small fix, please open an issue first
     to agree the approach (see CONTRIBUTING.md). Keep this PR focused on one concern. -->

## Summary
<!-- What does this change do, and why? Reference the issue if there is one (e.g. "Closes #12"). -->

## Checklist
- [ ] Advice catches and records its own errors — no exception escapes into the host application
- [ ] Hot path (advice on every matched call) stays allocation-light and non-blocking
- [ ] Tests added for new pure-logic behavior (config parsing, matchers, propagation)
- [ ] README configuration reference updated if a user-facing knob or behavior changed
- [ ] `CHANGELOG.md` entry added
- [ ] `./mvnw -B -ntp clean verify` passes locally
