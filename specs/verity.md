# verity — the one who remembers your details exactly

Status: v1 IN, 2026-10-20 — `core` (the model, the journal, the effects and handlers, the scenario) and `app` (the
terminal and the Swing window); the whole application tested under okay-face's test face by scripts on the machine and
on Free. What this spec says of v1 holds, with the deviations noted inline as DONE/CHANGED. Open source, Apache-2.0.

## Overview

The same details are asked of a person again and again: names as spelled in each language, addresses, passport and
identification numbers, dates of issue, authorities. They must be entered exactly, errors are not acceptable, and they
change: passports expire and are replaced, a name is transliterated three ways, one person holds documents of two
countries. Verity keeps them once, in one file that can be backed up and carried, and hands them out exactly, to the
person who copies them into a form or to an agent that fills the form for them — each under the owner's control.

A structured notebook, then, with the structure extensible, every value versioned, one file, and a policy on who sees
what. It is the test of okay-face's thesis: one program of the application, two faces at least (the terminal and MCP),
and the second role too (the domain served to an agent directly), with the person's consent as a separate channel.

## The data model

The domain is built on the same principle as the faces: data interpreted by handlers.

### Kind — a kind of record

```scala
final case class KindId(value: String)                       // `person-name`, `passport`, user-defined `uk-ni-number`
final case class Kind(
  id: KindId,
  name: String,                                                // for a person: "Passport"
  schema: Schema,                                              // okay-face's erased Shape: the fields
  secret: Set[String],                                         // field names never shown unless revealed
  summary: List[String],                                       // fields that identify a record in a list ("country, number…" masked)
  contextual: Boolean)                                         // records carry a language/country context
```

Built-in kinds are predefined `Shape`s with their `Kind`; user-defined kinds are `Kind`s in the file, their forms, tool
schemas and validation derived from `schema` exactly as the built-in ones'. Adding a kind of data needs no new code.

Built-in kinds of v1:

| Kind | Fields | Secret by default |
|---|---|---|
| `person-name` | given, family, middle (optional), language | — |
| `address` | lines (many), city, region (optional), postal code, country | — |
| `email` | address, purpose (optional) | — |
| `phone` | number (E.164), purpose (optional) | — |
| `passport` | country, number, issued (date), expires (date), authority, name as printed | number |
| `identifier` | type ("tax id", "SSN", "driver licence"…), value, issuer, issued/expires (optional) | value |

### Record — a versioned fact

```scala
final case class RecordId(value: String)                      // stable across versions; a UUID
final case class VersionId(value: String)                     // one per version; a UUID
final case class Record(
  id: RecordId, version: VersionId, parent: Option[VersionId],
  kind: KindId,
  fields: Value.Obj,                                           // validated against the kind's schema
  context: Context,                                            // language and/or country, when the kind is contextual
  valid: Period,                                               // from/to, each optional
  tags: Set[String], note: Option[String],
  at: Instant)                                                 // when this version was written
```

A change is a new version with `parent` the old one. The versions of one `RecordId` form a chain, like commits; two
files merged whose chains diverged hold a CONFLICT (v2). A record is retired, not deleted: its history stays.

### Journal — the file

The file is an append-only journal of events; the state is its fold and is not stored:

```scala
sealed trait Event
object Event {
  final case class KindDefined(kind: Kind, at: Instant) extends Event
  final case class KindRevised(kind: Kind, at: Instant) extends Event
  final case class RecordAdded(record: Record) extends Event
  final case class RecordRevised(record: Record) extends Event      // parent set
  final case class RecordRetired(id: RecordId, reason: Option[String], at: Instant) extends Event
  final case class Revealed(id: RecordId, field: String, audience: Audience, at: Instant) extends Event  // the audit, in the file
}
```

Format: UTF-8 text, one JSON object per line; the first line a header `{"verity": 1, "created": ..., "device": ...}`.
Every line decodes through a `Shape[Event]`, so the file is read by the same decoder as everything else, and a damaged
line is reported with its number, not thrown. Layered: a later version wraps the whole file in encryption
(`{"verity": 1, "encrypted": "aes-256-gcm", "kdf": "argon2id", ...}` then one base64 body); the journal inside is
unchanged. DECIDED: v1 writes the plain layer only, with the header designed for the wrapper.

Attachments (scans) later: an event with the file's hash, the bytes beside the file in a directory named after it.
Nothing in the model changes for them.

## The domain effects

CHANGED in code: the effect of the vault is `Store` (a `Vault` is the folded state); ids and the clock are given to
its handler, so a test is deterministic; `Reveal`'s policy is its handler (`policy(audience)`), performing `Store` and
`Consent` in the row outside; the owner's consent at their own face is `askingTheOwner(faces)`, a `confirm` of `Face`;
`Navigation[Place]` (okay-face core) names where the program is, for a window's title.

```scala
sealed trait Vault[X]      // Kinds, Find(query), Get(id), History(id), Define(kind), Add(record), Revise(record), Retire(id, reason)
sealed trait Reveal[X]     // Reveal(id, field): String — a secret in the clear; the only way to one
sealed trait Consent[X]    // Ask(what: View, audience: Audience): Boolean — the OWNER's yes or no, whatever face the asker has
sealed trait Clipboard[X]  // Put(text)
sealed trait Files[X]      // Open(path): Journal, Save(path, journal), Merge(path) (v2)
```

The scenario is a program in `Face + Vault + Reveal + Consent + Clipboard + Files`.

- `Vault`'s handler is a stateful handler over the folded journal, handled INSIDE `Face` (so the face's back restores
  the vault's state in memory; the file is written by `Save`, an explicit action).
- `Reveal` is a separate effect precisely so that a POLICY can sit over it: an interceptor (`around`) that, per
  audience, passes, asks `Consent`, or refuses, and performs `Vault.Add(Revealed(...))` for the audit. The terminal's
  policy passes for the owner; MCP's asks consent for every secret field and never caches it.
- `Consent` is not `Face`: its audience is the owner, the asker may be an agent. `face-term` handles it at the console;
  `face-mcp` by elicitation (specs/faces.md there), refusing when the client cannot elicit.
- `Clipboard`: `pbcopy`/`xclip`/`clip` on the terminal face (a subprocess, no dependency); the value in the tool result
  for the agent (which is a reveal, and goes through the policy).
- `Files`: the file as above; `Open` of a missing path is an empty journal.

## The scenario (v1)

A dialogue of offers. The main screen: the records as a table (kind, summary, context, valid, tags; secret summary
fields masked as `•••• 4821`), with actions `find(text, tag?)`, `add`, `open(record)`, `kinds`, `save`, `quit`.

- `open(record)`: the record's fields (`Masked` for secrets), its validity, tags, note; actions `copy(field)`,
  `reveal(field)`, `revise`, `history`, `retire(reason?)`, `done`. `copy` of a secret field goes through `Reveal`.
- `add`: `choose` a kind (built-in and defined), then `ask` the form from its `Shape`, then context/validity/tags, then
  `confirm` the summary. A user-defined kind: `define` asks the kind's name and its fields (name, type, secret) and
  builds the `Schema` — the meta-schema's form.
- `revise`: the same form prefilled with the current version; the result a new version with `parent`.
- `history`: the versions as a table; `open` an old version read-only.
- `save`/`quit`: `quit` with unsaved changes asks `confirm`.

Back is the face's. `quit` is the program's.

## The faces of v1

- `face-test`: every scenario path above as a script, the views golden.
- `face-term`: `verity [file]`; the default file `~/.verity/verity.jsonl`.
- `face-mcp`: `verity mcp [file]`. The dialogue projection: the agent sees the same screens and actions as the person.
  The domain projection (after okay-face's step 5): `verity_find`, `verity_get`, `verity_history` as tools over
  `Describe[Vault]`; `Reveal` under the MCP policy, so a secret reaches the agent only after the owner's consent, each
  time, and each time written to the journal.

## Security, v1

- Secrets are never in a `View` except through `Reveal`; the kind's `secret` set decides, the owner edits it.
- The policy is per audience: owner at the terminal, agent over MCP. Defaults: owner passes; agent gets open fields,
  consents per secret field, every reveal audited.
- The file is plain in v1 and says so in its header; the owner is told at first save. v2 encrypts.
- No network in v1, nothing leaves the process but stdout to the client it was started by.

## v2

In order of what stresses the design most:

1. Merge: `Files.Merge(other)` folds two journals by event identity; diverged chains are conflicts offered one by one —
   keep mine, keep theirs, keep both as versions, edit — a dialogue with branching and back.
2. Encryption of the file: AES-256-GCM, key from a passphrase by Argon2id (`okay-security-argon2` exists in okay;
   here a small JVM implementation or BouncyCastle as the first dependency — decide then), the key held by an effect
   `Crypto` so an OS keychain can replace the passphrase.
3. Attachments.
4. A web face through okay-face's okay-ui bridge or Laminar.

## Build

sbt, Scala 3.3 LTS and 2.13 (the application compiles on both, as the library does, to keep the library honest);
depends on okay-face `publishLocal` until okay-face is released to the okay-effects Pages repository; JVM first,
Native when okay-face's `face-term` and `face-mcp` are. One executable `verity` (`sbt stage` or a launcher script).

## Tests

- The journal: fold of a golden file; a damaged line reported by number; round trip of every event through its shape.
- The vault: add, revise (chain), retire, history, find by text and tag; a user-defined kind's form validates.
- The policy: an agent's reveal without consent is refused and not audited; with consent, revealed once and audited;
  the owner's passes.
- The scenario on `face-test`: each path, golden views; back restores the vault's state before an `add`.
- `face-term` end to end with a scripted console; `face-mcp` with a scripted session.
