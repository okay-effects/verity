/** verity's words for okay-store's things: the store is the library's, the kinds and what a record carries beside its
 * fields (`Extra`: context and validity) are verity's */
package object verity {
  type Kind = okay.store.Kind; val Kind = okay.store.Kind
  type KindId = okay.store.KindId; val KindId = okay.store.KindId
  type Record = okay.store.Record; val Record = okay.store.Record
  type RecordId = okay.store.RecordId; val RecordId = okay.store.RecordId
  type VersionId = okay.store.VersionId; val VersionId = okay.store.VersionId
  type Event = okay.store.Event; val Event = okay.store.Event
  type Store[X] = okay.store.Store[X]; val Store = okay.store.Store
  type Vault = okay.store.Kept
  val Journal = okay.store.Journal
}
