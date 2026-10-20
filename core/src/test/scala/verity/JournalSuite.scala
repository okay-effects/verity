package verity

import okay.effects.json.Json
import okay.effects.json.Json.{Str, obj}
import okay.effects.shape.Day

class JournalSuite extends munit.FunSuite {
  val passport = Record(RecordId("r1"), VersionId("v1"), None, Kinds.passport.id,
    obj("country" -> Str("UA"), "number" -> Str("AB123456"), "issued" -> Str("2020-01-02"), "expires" -> Str("2030-01-01"), "holder" -> Str("SHCHERBYNA SERHII")),
    Context(None, Some("UA")), Period(Some(Day(2020, 1, 2)), Some(Day(2030, 1, 1))), Set("travel"), None, 1000L)
  val revised = passport.copy(version = VersionId("v2"), parent = Some(VersionId("v1")), fields = passport.fields, at = 2000L)
  val events = List(Event.RecordAdded(passport), Event.RecordRevised(revised), Event.Revealed(passport.id, "number", "owner", 3000L),
    Event.KindDefined(Kind(KindId("uk-ni"), "UK NI number", okay.effects.shape.Schema.Record(Vector(okay.effects.shape.Schema.Field("value", okay.effects.shape.Schema.Text(Nil), Some("Value"), None, true, None))), Set("value"), List("value"), false), 4000L))

  test("every event round trips through its shape and the file") {
    events.foreach(e => assertEquals(Event.shape.decode(Event.shape.encode(e)), Right(e)))
    val lines = Journal.write(Journal.Header(1, 1000L), events)
    assertEquals(lines.size, 5)
    val (h, es, damaged) = Journal.read(lines)
    assertEquals(h, Some(Journal.Header(1, 1000L))); assertEquals(es, events); assertEquals(damaged, Nil)
  }
  test("a damaged line is reported by number and skipped; blank lines are nothing") {
    val lines = Journal.write(Journal.Header(1, 1L), events)
    val broken = lines.take(2) ++ List("{\"type\": \"record-added\", \"record\": 5}", "", "not json") ++ lines.drop(2)
    val (_, es, damaged) = Journal.read(broken)
    assertEquals(es, events)
    assertEquals(damaged.map(_.line), List(3, 5))
  }
  test("the vault folded: versions newest first, the retired gone from the list, history kept, kinds added") {
    val v = Vault.of(events :+ Event.RecordRetired(RecordId("other"), None, 5L))
    assertEquals(v.current(passport.id), Some(revised))
    assertEquals(v.history(passport.id), List(revised, passport))
    assertEquals(v.records.map(_.version), List(VersionId("v2")))
    assert(v.kinds.contains(KindId("uk-ni")))
    assertEquals(v.unsaved, 0)
    assertEquals(v(Event.RecordRetired(passport.id, Some("lost"), 6L)).records, Nil)
    assertEquals(v(Event.RecordRetired(passport.id, None, 6L)).history(passport.id).size, 2)
  }
  test("the built-in kinds: forms from their schemas, the secrets named") {
    Kinds.builtIn.foreach(k => assert(k.shape.erased == k.schema))
    assertEquals(Kinds.passport.shape.decode(passport.fields).isRight, true)
    assertEquals(Kinds.passport.shape.decode(obj("country" -> Str("UA"))).left.map(_.map(_.path.mkString)), Left(List("number", "issued", "expires", "holder")))
    assertEquals(Kinds.email.shape.decode(obj("address" -> Str("nope"))).left.map(_.head.message), Left("not an email address"))
    assert(Kinds.passport.secret("number"))
  }
}
