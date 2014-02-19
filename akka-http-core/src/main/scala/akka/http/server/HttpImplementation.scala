package akka.http.server

import akka.streams.io.TcpStream
import akka.util.ByteString
import spray.http._
import scala.concurrent.Future
import akka.streams._
import Operation._
import spray.http.ChunkedResponseStart
import spray.can
import spray.can.Parser
import spray.can.server.ServerSettings
import rx.async.api.Producer

object HttpImplementation {
  type Stage[I, O] = Operation[I, O]

  def handleParts(io: TcpStream.IOStream, settings: ServerSettings)(handler: ParsedPartOrResponse ==> HttpResponsePart)(implicit factory: ImplementationFactory): Pipeline[_] = {
    val (in, out) = io

    in.toSource /*.andThen(DeliverInByteChunks(23))*/ .andThen(parse(new Parser(settings))).andThen(handler).andThen(render).finish(out)
  }

  def handleRequests(io: TcpStream.IOStream, settings: ServerSettings)(handler: HttpRequestStream ⇒ Future[MaybeStreamedHttpResponse])(implicit factory: ImplementationFactory): Pipeline[_] = {
    val (in, out) = io

    in.toSource
      .andThen(parse(new Parser(settings)))
      .andThen(collect)
      .flatMap {
        case Parsed(request)             ⇒ handler(request).toSource
        case ImmediateResponse(response) ⇒ Future.successful(ImmediateHttpResponse(response)).toSource
      }
      .andThen(toParts)
      .andThen(render).finish(out)
  }

  sealed trait ParsedOrResponse[+T]
  case class Parsed[T](part: T) extends ParsedOrResponse[T]
  case class ImmediateResponse(response: HttpResponse) extends ParsedOrResponse[Nothing]

  type ParsedPartOrResponse = ParsedOrResponse[HttpRequestPart]

  def parse(parser: Parser): Stage[ByteString, ParsedPartOrResponse] =
    Process[ByteString, ParsedPartOrResponse, parser.Parser](parser.rootParser, parser.onNext(_, _), parser.onComplete)

  def render: Stage[HttpResponsePart, ByteString] =
    Identity[HttpResponsePart]().map(can.Renderer.renderPart)

  def endOfRequest(p: ParsedPartOrResponse): Boolean = p match {
    case Parsed(h: HttpMessageEnd) ⇒ true
    case Parsed(_)                 ⇒ false
    case _: ImmediateResponse      ⇒ true
  }

  def collect(implicit factory: ImplementationFactory): Stage[ParsedPartOrResponse, ParsedOrResponse[HttpRequestStream]] =
    Identity[ParsedPartOrResponse]().span(endOfRequest).headTail
      .map {
        case (Parsed(req: HttpRequest), rest) ⇒
          Parsed[HttpRequestStream]((req, emptyProducer[ByteString]))
        case (Parsed(ChunkedRequestStart(req)), rest) ⇒
          def extract(p: ParsedPartOrResponse): HttpRequestPart =
            p.asInstanceOf[Parsed[HttpRequestPart]].part // TODO: provide proper error message

          val bodyParts =
            rest.map(extract).takeWhile(_.isInstanceOf[MessageChunk]).map {
              case MessageChunk(data, "") ⇒ data.toByteString
            }
          Parsed[HttpRequestStream]((req, bodyParts.toProducer()))
        case (i: ImmediateResponse, _) ⇒ i
      }

  def toParts: Stage[MaybeStreamedHttpResponse, HttpResponsePart] =
    Identity[MaybeStreamedHttpResponse]().flatMap {
      case StreamedHttpResponse(headers, body) ⇒
        Source[HttpResponsePart](ChunkedResponseStart(headers)) ++
          body.toSource.map(bytes ⇒ MessageChunk(HttpData(bytes))) ++
          Source(ChunkedMessageEnd)
      case ImmediateHttpResponse(response) ⇒ SingletonSource(response)
    }

  implicit class RichHttpRequestPart(val part: HttpRequestPart) extends AnyVal {
    def isEnd: Boolean = part.isInstanceOf[HttpMessageEnd]
    def isChunk: Boolean = part.isInstanceOf[MessageChunk]
  }

  def emptyProducer[T](implicit factory: ImplementationFactory): Producer[T] = Source.empty[T].toProducer()
}