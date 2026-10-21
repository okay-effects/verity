package verity

import okay.effects.Runner
import okay.effects.json.Json
import okay.face.{Faces, Step}
import okay.face.mcp.{Markdown, McpFace}
import okay.mcp.Link

/** VERITY FOR AN AGENT: the same dialogue, served over MCP — the agent sees what a person would, as tools. The audience
 * is an agent, so the policy over `Reveal` asks the OWNER through the client: `Consent` by elicitation */
class Agents[F <: Runner](val faces: Faces[F]) {
  import faces.fx._
  val face: McpFace[F, faces.type] = McpFace(faces)
  val domain: Domain[faces.fx.type] = new Domain[faces.fx.type]()(faces.fx)

  /** the owner's consent asked through the client (`elicitation/create`): a yes or no with the record and the field named */
  def consentByElicitation: ConsentByElicitation = new ConsentByElicitation
  final class ConsentByElicitation {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Consent, R, Rest], l: In[Link, Rest]): A ! Rest =
      handle[Consent, R, Rest, A, A](p, new Handler[Consent, Rest, A, A] {
        def ret(a: A) = pure(a)
        def op[X](op: Consent[X], k: X => A ! Rest) = op match {
          case Consent.Ask(what, answer) =>
            face.servers.elicit[Rest](Markdown.view(what), Json.obj("type" -> Json.Str("object"),
              "properties" -> Json.obj("allow" -> Json.obj("type" -> Json.Str("boolean"), "description" -> Json.Str("show it to the agent"))), "required" -> Json.arr(Json.Str("allow"))))
              .flatMap {
                case Right(o: Json.Obj) => k(answer(o.get("allow") == Some(Json.Bool(true))))
                case _ => k(answer(false))
              }
        }
      })
  }

  /** the server: the dialogue's steps over the link's row */
  def server[A, R[_]](first: Step[A, ({ type L[X] = X ! R })#L] ! R, elicitation: Boolean = true, singleTool: Boolean = false): face.servers.Server[R] =
    face.server[A, R]("verity", "0.1", first, Some("Verity keeps the owner's personal details: names, addresses, passports, identifiers. " +
      "You see the owner's own screens as tools. A secret field reaches you only with the owner's consent, asked each time, and every reveal is written down." +
      (if (elicitation) "" else " Over this transport no consent can be asked, so no secret is shown.")), elicitation = elicitation, singleTool = singleTool,
      vocabulary = Marking.marks.vocabulary)
}
