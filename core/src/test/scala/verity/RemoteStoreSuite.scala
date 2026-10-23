package verity

import okay.effects.{Sum, Void}
import okay.effects.json.Json
import okay.effects.machine.Machine
import okay.faces.rest.RestDomains
import okay.http.{Fetch, Request, Response}

/** THE STORE FROM AFAR: the same program, its Store handled here by the vault and there by REST — the server's vault
 * in its own runs, the client over Fetch — gives the same answers. The mechanism of the other cut: the dialogue in the
 * browser, the domain on the server */
class RemoteStoreSuite extends munit.FunSuite {
  import Machine._
  val domain: Domain[Machine.type] = new Domain[Machine.type]()(Machine)
  import domain._
  val rest: RestDomains[Machine.type] = new RestDomains[Machine.type]()(Machine)
  type S[X] = Sum[Void, Store, X]
  type F[X] = Sum[Void, Fetch, X]
  type FS[X] = Sum[F, Store, X]

  def ids(): () => String = { var n = 0; () => { n += 1; s"id$n" } }
  val now = () => 1000L
  /** the program: a passport kept, found, revised, its history, retired, the journal's count */
  def program[R[_]](implicit in: In[Store, R]): (List[Record], Option[Record], List[Record], Int) ! R = {
    val passport = Kinds.passport
    define[R](passport).flatMap(_ => add[R](passport.id, Json.obj("number" -> Json.Str("AB123456"), "country" -> Json.Str("UA")), Extra.of(Context(None, Some("UA")), Period(None, None)), Set("travel"), None)).flatMap { r =>
      revise[R](r.id, Json.obj("number" -> Json.Str("AB123457"), "country" -> Json.Str("UA")), r.meta, r.tags, Some("renewed")).flatMap(_ =>
        find[R]("AB1234").flatMap(found => get[R](r.id).flatMap(got => history[R](r.id).flatMap(h => retire[R](r.id, Some("lost")).flatMap(_ => journaled[R].map { case (_, n) => (found, got, h, n) })))))
    }
  }

  test("the same answers: the store handled by the vault, and the store performed over REST against a server's vault") {
    val (_, direct) = Machine.run(store(Vault.empty, ids(), now)(program[S]))
    // the server: the described store served at /store, the vault kept between requests, ids its own
    var vault = Vault.empty
    val serverIds = ids()
    val served = rest.serve[Store, S](okay.store.Stores.describe, "/store")
    var calls: List[String] = Nil
    val fetched = new TailHandler[Fetch, Void, (List[Record], Option[Record], List[Record], Int), (List[Record], Option[Record], List[Record], Int)] {
      def ret(a: (List[Record], Option[Record], List[Record], Int)) = pure(a)
      def answer[X](op: Fetch[X]): X = op match {
        case Fetch.Call(m, url, hs, body, k) =>
          calls = s"$m $url" :: calls
          val (v, r) = Machine.run(store(vault, serverIds, now)(served.service(Request(m, url).copy(headers = hs.map { case (h, x) => h.toLowerCase -> List(x) }, body = body))))
          vault = v; k(r)
      }
    }
    val remote = Machine.run(handle[Fetch, F, Void, (List[Record], Option[Record], List[Record], Int), (List[Record], Option[Record], List[Record], Int)](
      rest.remote[Store](okay.store.Stores.describe, "/store").apply[FS, F, (List[Record], Option[Record], List[Record], Int)](program[FS]), fetched))
    assertEquals(remote, direct)
    assertEquals(remote._1.map(_.fields.get("number")), List(Some(Json.Str("AB123457"))))
    assertEquals(remote._3.size, 2); assertEquals(remote._4, 4)
    assertEquals(vault.retired, Set(RecordId("id2")))   // id1 the definition's event
    assert(calls.reverse.head.startsWith("POST /store/define"), calls.toString)
    assert(calls.exists(_.startsWith("GET /store/find?text=AB1234")), calls.toString)
    assert(calls.exists(_.startsWith("GET /store/get?id=id2")), calls.toString)
  }
  test("the description lists every operation of the store with its method") {
    val (_, r) = Machine.run(store(Vault.empty, ids(), now)(rest.serve[Store, S](okay.store.Stores.describe, "/store").service(Request.get("/store"))))
    okay.store.Stores.describe.operations.foreach(o => assert(r.body.contains(s""""rel":"${o.name}""""), o.name))
    assert(r.body.contains(""""rel":"get","href":"/store/get","method":"GET"""") && r.body.contains(""""rel":"add","href":"/store/add","method":"POST""""), r.body)
  }
}
