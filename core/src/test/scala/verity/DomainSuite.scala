package verity

import okay.effects.{Runner, Sum, Void}
import okay.effects.json.Json.{Str, obj}
import okay.effects.machine.Machine
import okay.effects.free.Free
import okay.faces.View

/** THE DOMAIN'S HANDLERS: the store, the policy over reveal, consent, files */
abstract class DomainChecks(val fx0: Runner, name: String) extends munit.FunSuite {
  val domain: Domain[fx0.type] = new Domain[fx0.type]()(fx0)
  import fx0._
  import domain._
  type S[X] = Sum[Void, Store, X]
  type SC[X] = Sum[S, Consent, X]
  type SCR[X] = Sum[SC, Reveal, X]
  var n = 0
  def ids(): String = { n += 1; s"id$n" }
  def clock(): Long = 1000L + n

  def addPassport[R[_]](implicit s: In[Store, R]): Record ! R =
    add[R](Kinds.passport.id, obj("country" -> Str("UA"), "number" -> Str("AB123456"), "issued" -> Str("2020-01-02"), "expires" -> Str("2030-01-01"), "holder" -> Str("S.")),
      Extra.of(Context(None, Some("UA")), Period(None, None)), Set("travel"), None)

  test(s"$name: the store — add, revise, retire, find, history, journaled and saved") {
    val program: (List[Record], List[Record], Option[Record], Int) ! S = addPassport[S].flatMap { r =>
      revise[S](r.id, r.fields, r.meta, Set("travel", "old"), Some("renewed")).flatMap(_ =>
        find[S]("ab12", None).flatMap(found => history[S](r.id).flatMap(h => get[S](r.id).flatMap(cur => journaled[S].map { case (_, unsaved) => (found, h, cur, unsaved) }))))
    }
    val (vault, (found, h, cur, unsaved)) = fx0.run(store(Vault.empty, ids, clock)(program))
    assertEquals(found.size, 1); assertEquals(h.size, 2); assertEquals(cur.flatMap(_.note), Some("renewed")); assertEquals(unsaved, 2)
    assertEquals(h.head.parent, Some(h(1).version))
    assertEquals(vault.events.size, 2)
    assertEquals(fx0.run(store(vault, ids, clock)(find[S]("", Some("old"))))._2.size, 1)
    assertEquals(fx0.run(store(vault, ids, clock)(find[S]("", Some("nope"))))._2.size, 0)
    assertEquals(fx0.run(store(vault, ids, clock)(retire[S](cur.get.id, None).flatMap(_ => find[S]("", None))))._2, Nil)
  }
  test(s"$name: the policy — the owner is shown; an agent only with consent, each time, and audited") {
    def ask[R[_]](implicit rv: In[Reveal, R], s: In[Store, R]): (Option[String], Option[String]) ! R =
      addPassport[R].flatMap(r => reveal[R](r.id, "number").flatMap(a => reveal[R](r.id, "number").map(b => (a, b))))
    val owner = fx0.run(store(Vault.empty, ids, clock)(consenting(Nil)(policy(Audience.Owner)(ask[SCR]))))
    assertEquals(owner._2, ((Some("AB123456"), Some("AB123456"))))
    assertEquals(owner._1.events.collect { case e: Event.Accessed => e.by }, List("owner", "owner"))
    val agent = fx0.run(store(Vault.empty, ids, clock)(consenting(List(true, false))(policy(Audience.Agent("claude"))(ask[SCR]))))
    assertEquals(agent._2, ((Some("AB123456"), None)))
    assertEquals(agent._1.events.collect { case e: Event.Accessed => e.by }, List("agent:claude"))
    val refused = fx0.run(store(Vault.empty, ids, clock)(consenting(Nil)(policy(Audience.Agent("x"))(ask[SCR]))))
    assertEquals(refused._2, ((None, None)))
    assert(!refused._1.events.exists(_.isInstanceOf[Event.Accessed]))
  }
  test(s"$name: files in memory, and the journal through them") {
    type F[X] = Sum[Void, Files, X]
    val lines = Journal.write(Journal.Header("verity", 1, 1L), Nil)
    val (files, got) = fx0.run(filesInMemory(Map.empty)(append[F]("a.jsonl", lines).flatMap(_ => open[F]("a.jsonl"))))
    assertEquals(got, Right(lines)); assertEquals(files.keySet, Set("a.jsonl"))
    assertEquals(fx0.run(filesInMemory(Map.empty)(open[F]("none")))._2, Right(Nil))
  }
}
class DomainOnMachine extends DomainChecks(Machine, "machine")
class DomainOnFree extends DomainChecks(Free, "free")
