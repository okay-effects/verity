# verity — the one who remembers your details exactly

Names as spelled in each language, addresses, passports and identification numbers, with their dates and versions:
kept once, in one file you can back up, and handed out exactly — to you, to copy into a form, or to an agent filling
the form for you, with your consent each time.

```
sbt "app/run"                 # at the terminal, ~/.verity/verity.jsonl
sbt "app/run my.jsonl"        # another file
sbt "app/run swing my.jsonl"  # in a window
sbt "app/run mcp my.jsonl"    # for an agent, MCP over stdio: the same screens as tools, a secret only with your consent
sbt "app/run web 8080 my.jsonl"  # in a browser, pages from the server, no script: http://127.0.0.1:8080/
sbt "app/run api 8081 my.jsonl"  # as a JSON API with links: POST /sessions, then follow the links
```

## What it is, in one paragraph

A structured notebook with the structure extensible. A KIND of record is a schema — six built in (a name in a language,
an address, an email, a phone, a passport, an identifier), and your own defined at run time, kept in the file with the
records. A RECORD is a versioned fact: a change is a new version, the chain is kept, a record is retired, never
deleted. The FILE is an append-only journal of events, a JSON object per line (`.jsonl`), the audit of every reveal
part of it. Secret fields (a passport's number) are never shown except through `Reveal`, over which a POLICY sits: the
owner at their own face is shown; an agent is shown only with the owner's consent, each time, and it is written down.

## How it is built

One program — the scenario — in six effects of [okay-simple](https://github.com/okay-effects/okay-simple):
`Face` ([okay-face](https://github.com/okay-effects/okay-face)'s dialogue), `Store` (the vault), `Reveal`, `Consent`,
`Clipboard`, `Files`, and `Navigation` for where it is. No line of it knows a face or a disk. The terminal, the Swing
window and the test script are handlers of `Face`; the disk and a map in memory are handlers of `Files`; the policy is
the handler of `Reveal`, performing `Store` and `Consent` outside it. The whole application runs under the test face by
a script of choices, every handler in memory, on okay-simple's machine and on its `Free` monad alike (`core/src/test`).

Status: v1 at the terminal, in a window, in a browser, as a JSON API, and for an agent over MCP (the owner asked through the client by elicitation before
a secret is shown, every showing written down). Next: merging two files with conflicts shown, encryption, the web face. [specs/verity.md](specs/verity.md).
