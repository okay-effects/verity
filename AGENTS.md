# AGENTS.md — verity

Read before anything else. Then `specs/verity.md`; the code follows the spec, a design change is a spec change first.
okay-faces' specs (`../okay-faces/specs/`) are the library's contract.

- A program in `Face + Vault + Reveal + Consent + Clipboard + Files`; nothing in the scenario knows a face.
- Secrets reach a `View` only through `Reveal`, and `Reveal` only through the policy. Never log a secret.
- The file is an append-only journal; never rewrite history, add events.
- Scala 3.3 and 2.13 both; tests on both. Commits: plain messages, no attribution trailers.
