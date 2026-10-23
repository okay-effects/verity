# verity — the one who remembers your details exactly

Status: v1 IN, 2026-10-20 — `core` (the model, the journal, the effects and handlers, the scenario, the agent's face with
consent by elicitation) and `app` (the terminal, the Swing window, MCP over stdio); the whole application tested under okay-faces' test face by scripts on the machine and
on Free. What this spec says of v1 holds, with the deviations noted inline as DONE/CHANGED. Open source, Apache-2.0.

## Overview

The same details are asked of a person again and again: names as spelled in each language, addresses, passport and
identification numbers, dates of issue, authorities. They must be entered exactly, errors are not acceptable, and they
change: passports expire and are replaced, a name is transliterated three ways, one person holds documents of two
countries. Verity keeps them once, in one file that can be backed up and carried, and hands them out exactly, to the
person who copies them into a form or to an agent that fills the form for them — each under the owner's control.

A structured notebook, then, with the structure extensible, every value versioned, one file, and a policy on who sees
what. It is the test of okay-faces' thesis: one program of the application, two faces at least (the terminal and MCP),
and the second role too (the domain served to an agent directly), with the person's consent as a separate channel.

## The data model

The domain is built on the same principle as the faces: data interpreted by handlers.

### Kind — a kind of record

```scala
final case class KindId(value: String)                       // `person-name`, `passport`, user-defined `uk-ni-number`
final case class Kind(
  id: KindId,
  name: String,                                                // for a person: "Passport"
  schema: Schema,                                              // okay-faces' erased Shape: the fields
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

### The journal is append-only (2026-10-23)

The rule: a save APPENDS to the file the events it does not have yet and never rewrites a line that is there. What
was saved stays saved, whatever a session does after.

- Identity: every event carries `eid`, minted by the store's handler from the same `ids` that mint record and version
  ids (`{"type":"record-added","eid":"…","record":…}`). A line written before ids (an old file) has none; its identity
  is a hash of what it says (`Event.identity`, okay-store), so an old file reads as before and is never rewritten — new
  events are appended after its lines. The header and its version are unchanged: `eid` is an optional field.
- Ids are unique across sessions and processes: the JVM's handler is given `Domain.uniqueIds` (a random UUID), the
  one generator of the real thing; a counter is for tests only, and never shared by two writers.
- A save (`persist`, after every action that may change the vault): `Open` the file, read its events, `Append` the
  vault's events whose identities are not among them (the header first when the file is empty), then `adopt` the
  file's events plus those appended into the store: what is kept becomes the fold of the file as it now stands. A
  repeated save appends nothing. `Files.Append(path, lines)` is the effect's own operation, on every handler: in
  memory, the disk (opened to append, the batch one write, under one lock per process), the server's `/files`.
- TWO WRITERS (two tabs, two sessions of `verity web`/`api`, two processes on one file): each appends only its own
  events, so both writers' events end in the file, in the order they were appended. Each session's vault converges
  at its next save (it adopts what the other appended) and at its start (`run` adopts the whole file), so a reload
  shows every writer's events. Between two saves a session does not see the other's changes: it reads at a save.
- TWO REVISIONS OF ONE RECORD from two writers who both saw version v1 are two versions, both with parent v1: a fork.
  The history shows both, newest (appended later) first, each with its parent; the one appended later is the current
  version. Nothing is lost; v2's merge will offer a fork as a conflict. A retirement by one writer holds over a
  revision by another (the record is retired; its history keeps the revision).
- BACK after a save: the face's back resumes an earlier step with the vault as it was then, in memory; it cannot
  remove what was written. The next save appends the new change and adopts the file, so the stepped-back-over events
  are in the vault again: back is navigation, not undo. A change to a record revised in the stepped-over part is a
  fork, as above.
- On a database (`.db`), the database is the store shared by every session of the process and the journal file its
  backup: the save appends to `<db>.jsonl` what it lacks, and `adopt` takes in, after the database's own events, the
  file's events the database lacks.

Attachments (scans) later: an event with the file's hash, the bytes beside the file in a directory named after it.
Nothing in the model changes for them.

## The domain effects

CHANGED in code: the effect of the vault is `Store` (a `Vault` is the folded state); ids and the clock are given to
its handler, so a test is deterministic; `Reveal`'s policy is its handler (`policy(audience)`), performing `Store` and
`Consent` in the row outside; the owner's consent at their own face is `askingTheOwner(faces)`, a `confirm` of `Face`;
`Navigation[Place]` (okay-faces core) names where the program is, for a window's title.

```scala
sealed trait Vault[X]      // Kinds, Find(query), Get(id), History(id), Define(kind), Add(record), Revise(record), Retire(id, reason)
sealed trait Reveal[X]     // Reveal(id, field): String — a secret in the clear; the only way to one
sealed trait Consent[X]    // Ask(what: View, audience: Audience): Boolean — the OWNER's yes or no, whatever face the asker has
sealed trait Clipboard[X]  // Put(text)
sealed trait Files[X]      // Open(path): lines, Append(path, lines), Save(path, lines) (whole), Merge(path) (v2)
```

The scenario is a program in `Face + Vault + Reveal + Consent + Clipboard + Files`.

- `Vault`'s handler is a stateful handler over the folded journal, handled INSIDE `Face` (so the face's back restores
  the vault's state in memory; the file is written after every change, see the scenario).
- `Reveal` is a separate effect precisely so that a POLICY can sit over it: an interceptor (`around`) that, per
  audience, passes, asks `Consent`, or refuses, and performs `Vault.Add(Revealed(...))` for the audit. The terminal's
  policy passes for the owner; MCP's asks consent for every secret field and never caches it.
- `Consent` is not `Face`: its audience is the owner, the asker may be an agent. `face-term` handles it at the console;
  `face-mcp` by elicitation (specs/faces.md there), refusing when the client cannot elicit.
- `Clipboard`: `pbcopy`/`xclip`/`clip` on the terminal face (a subprocess, no dependency); the value in the tool result
  for the agent (which is a reveal, and goes through the policy).
- `Files`: the file as above; `Open` of a missing path is an empty journal; the scenario only appends.

## The scenario (v1)

A dialogue of offers. The main screen: the records as a table (kind, summary, context, valid, tags; secret summary
fields masked as `•••• 4821`), with actions `find(text, tag?)`, `add`, `open(record)`, `kinds`.

- `open(record)`: the record's fields (`Masked` for secrets), its validity, tags, note; actions `copy(field)`,
  `reveal(field)`, `revise`, `history`, `retire(reason?)`, `done`. `copy` of a secret field goes through `Reveal`.
- THE MARKS (2026-10-21, `Mark` in Model.scala, typed by okay-faces' `Marks[M]`): verity's own words on its screens —
  `kind` on a card's heading, `secret` on a masked field, `revealed` on a secret shown by consent, and on a validity
  `valid`, `expiring` (ends within thirty days) or `expired`, told from `today` when the application knows it (the JVM
  and the browser do; a test fixes the day). `Marking.look` dresses them for the CSS, Swing and the terminal: expired
  in the problem tone, expiring in the warning tone, a revealed secret bold on a surface. `Marking.marks.vocabulary`
  is told to an agent in the MCP instructions and `face://marks`, served at `/sessions/marks` on the API, and makes the
  agent's Markdown say `**kind:** Passport`, `secret: •••• 3456`, `revealed: AB123456` — the meaning, not only the text.
- `add`: `choose` a kind (built-in and defined), then `ask` the form from its `Shape`, then context/validity/tags, then
  `confirm` the summary. A user-defined kind: `define` asks the kind's name and its fields (name, type, secret) and
  builds the `Schema` — the meta-schema's form.
- `revise`: the same form prefilled with the current version; the result a new version with `parent`.
- `history`: the versions as a table; `open` an old version read-only.
- EVERY CHANGE IS SAVED AT ONCE (2026-10-23): after each main-screen action that may change the vault (`add`,
  `define`, and whatever was done inside an opened record: `revise`, `retire`, the audited `copy`/`reveal`) the
  vault's new events are appended to the file — quietly, no screen for it; a FAILED save is shown as a problem.
  There is no `save` action: it would have nothing to do. See "The journal is append-only".
- NO QUIT, anywhere (the operator's rule, 2026-10-23: "an app runs for as long as it is open"). The main screen loops;
  the application ends only by its face: a terminal at the end of its input or Ctrl-C, a window by its close, a page
  by its tab, an agent by its client. Nothing is lost by closing, since nothing is ever unsaved past an action.

Back is the face's.

## The faces of v1

- `face-test`: every scenario path above as a script, the views golden.
- `face-term`: `verity [file]`; the default file `~/.verity/verity.jsonl`.
- `face-mcp`: `verity mcp [file]`. The dialogue projection: the agent sees the same screens and actions as the person.
  The domain projection (after okay-faces' step 5): `verity_find`, `verity_get`, `verity_history` as tools over
  `Describe[Vault]`; `Reveal` under the MCP policy, so a secret reaches the agent only after the owner's consent, each
  time, and each time written to the journal.

## Security, v1

- Secrets are never in a `View` except through `Reveal`; the kind's `secret` set decides, the owner edits it.
- The policy is per audience: owner at the terminal, agent over MCP. Defaults: owner passes; agent gets open fields,
  consents per secret field, every reveal audited.
- The file is plain in v1 and says so in its header. v2 encrypts.
- No network in v1, nothing leaves the process but stdout to the client it was started by.

## v2

In order of what stresses the design most:

1. Merge: `Files.Merge(other)` folds two journals by event identity; diverged chains are conflicts offered one by one —
   keep mine, keep theirs, keep both as versions, edit — a dialogue with branching and back.
2. Encryption of the file: AES-256-GCM, key from a passphrase by Argon2id (`okay-security-argon2` exists in okay;
   here a small JVM implementation or BouncyCastle as the first dependency — decide then), the key held by an effect
   `Crypto` so an OS keychain can replace the passphrase.
3. Attachments.
5. The store described (2026-10-20): `okay.store.Stores.describe`, served as REST resources and performed from afar;
   `RemoteStoreSuite` shows one program answering the same by the vault and over REST. Not on the `api` port: a raw
   `Store` bypasses the policy over `Reveal`; a masked subset for agents is its own description, later.
6. okay-store extracted (2026-10-20, github.com/okay-effects/okay-store): `Kind`, `Record`, `Event`, `Journal`, `Kept`
   and the effect `Store` with its handler and description are the library's; verity keeps `Context` and `Period` in a
   record's `meta` (`Extra`), `contextual` in a kind's `meta` (`Kinds`), its built-in kinds, and `Vault` as a name for
   `Kept` with those kinds. `Event.Revealed` is `Event.Accessed(id, field, by, at)`; the journal's header is
   `{"format":"verity","version":1,...}`.
9. MCP over HTTP (2026-10-21): the `api` mode serves `/mcp` — okay-faces' streamable-HTTP transport, a session per
   agent with its own dialogue and the single tool `act`; when the client accepts an event stream the owner's consent
   is asked by elicitation, the question parked between requests, as over stdio; without one the policy refuses
   every secret and the server's instructions say so.
8. The store on SQLite (2026-10-20): a `.db` file in any mode is okay-store's `jdbc` handler instead of `keeping` over
   the journal file; `Main.Storage` is the one store per process, shared by every session, and the dialogue's saving
   writes `<db>.jsonl` beside it as the journal's backup. The dialogue does not know which; the results of every mode
   are `Unit` now, the vault no longer threaded through them.
7. The other cut (2026-10-20): `verity browser` — the dialogue in the browser (`web/Local`, the same `Verity` scenario
   under Laminar, the owner asked at this face, the clipboard the browser's), `Store` and `Files` performed over REST
   against the server's `/store` and `/files` (`RestDomains.serve` under the vault kept between requests and the one
   file). `core` is cross-built for it; `Agents`, `Disk` and `SystemClipboard` are JVM-only (`scala-jvm`). Tested
   under jsdom with the server in the same page (`LocalSuite`); one script serves `app` (remote) and `browser` (local)
   by the root's `data-mode`.
4. ~~A web face through okay-faces' okay-ui bridge or Laminar.~~ Done as `verity app`: okay-faces' remote face (Laminar) over the REST face, the page knowing nothing of verity; `web/` is the linked script.

## Build

sbt, Scala 3.3 LTS and 2.13 (the application compiles on both, as the library does, to keep the library honest);
depends on okay-faces `publishLocal` until okay-faces is released to the okay-effects Pages repository; JVM first,
Native when okay-faces' `face-term` and `face-mcp` are. One executable `verity` (`sbt stage` or a launcher script).

## Tests

- The journal: fold of a golden file; a damaged line reported by number; round trip of every event through its shape.
- The vault: add, revise (chain), retire, history, find by text and tag; a user-defined kind's form validates.
- The policy: an agent's reveal without consent is refused and not audited; with consent, revealed once and audited;
  the owner's passes.
- The scenario on `face-test`: each path, golden views; back restores the vault's state before an `add`.
- Append-only: two sessions over one file each add a record, interleaved — the file has both, a reload shows both;
  a change, back past it, another change — the file keeps the first; a repeated save appends nothing; two sessions'
  ids differ; an old journal (no `eid`) reads, and is appended to, not rewritten.
- `face-term` end to end with a scripted console; `face-mcp` with a scripted session.
