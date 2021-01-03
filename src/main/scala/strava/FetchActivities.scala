package strava

import cats.effect._
import cats.effect.concurrent.Deferred
import cats.implicits._

import fs2.{Chunk, Stream}

import io.circe.Json

import org.http4s._
import org.http4s.circe._
import org.http4s.client.blaze._
import org.http4s.client._
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.dsl.io._
import org.http4s.headers._
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder

import scala.concurrent.ExecutionContext.Implicits.global

import java.time.{LocalDate, Year, ZonedDateTime, ZoneId}

object FetchActivities extends IOApp with Http4sClientDsl[IO] {
  def run(args: List[String]): IO[ExitCode] = {
    if (args.size != 3) {
      IO(Console.err.println("Syntax: FetchActivities <client id> <client secret> <year to fetch>")).as(ExitCode.Error)
    } else {
      val clientId = ClientId(args.head)
      val clientSecret = ClientSecret(args.tail.head)
      val yearToFetch = Year.of(args.tail.tail.head.toInt)
      Blocker[IO].use { blocker =>
        BlazeClientBuilder[IO](global).resource.use { client =>
          getBearerToken(blocker, client, clientId, clientSecret).flatMap { bearerToken =>
            fetchActivitiesForYearJson(client, bearerToken, yearToFetch)
              .compile.toVector
              .flatTap(activities => IO(println(s"Fetched ${activities.size} activities")))
              .flatMap(ActivityStorage.writeActivitiesJson(blocker, _))
          }
        }
      }.as(ExitCode.Success)
    }
  }

  def getBearerToken(blocker: Blocker, client: Client[IO], clientId: ClientId, clientSecret: ClientSecret): IO[BearerToken] = {
    getAuthorizationCode(blocker, clientId).flatMap(fetchBearerToken(client, clientId, clientSecret, _))
  }

  def getAuthorizationCode(blocker: Blocker, clientId: ClientId): IO[String] = {
    Deferred[IO, String].flatMap { deferredAuthCode =>
      BlazeServerBuilder[IO](global)
        .bindHttp(port = 0)
        .withHttpApp { 
          object CodeParam extends QueryParamDecoderMatcher[String]("code")
          HttpRoutes.of[IO] {
            case GET -> Root / "exchange_token" :? CodeParam(code) =>
              deferredAuthCode.complete(code).as(Response(Status.Ok))
          }.orNotFound
        }
        .stream.flatMap { server =>
          val port = server.address.getPort
          println("Bound port " + port)
          Stream.eval(requestAuthCode(blocker, clientId, port) *> deferredAuthCode.get)
        }.compile.lastOrError
    }
  }

  def requestAuthCode(blocker: Blocker, clientId: ClientId, localPort: Int): IO[Unit] = {
    blocker.delay[IO, Unit] {
      import scala.sys.process._
      s"open http://www.strava.com/oauth/authorize?client_id=${clientId}&response_type=code&redirect_uri=http://localhost:${localPort}/exchange_token&approval_prompt=force&scope=read,activity:read".!
    }
  }

  def fetchBearerToken(client: Client[IO], clientId: ClientId, clientSecret: ClientSecret, authorizationCode: String): IO[BearerToken] = {
    val request = Method.POST(
      UrlForm(
        "client_id" -> clientId.value,
        "client_secret" -> clientSecret.value,
        "code" -> authorizationCode,
        "grant_type" -> "authorization_code"),
       Uri.uri("https://www.strava.com/oauth/token"))
       client.expect(request)(jsonOf[IO, BearerToken])
  }

  def fetchActivitiesForYearJson(client: Client[IO], bearerToken: BearerToken, year: Year): Stream[IO, Json] = {
    Stream.eval(IO(ZoneId.systemDefault())).flatMap { tz =>
      val startDate = LocalDate.of(year.getValue, 1, 1)
      val after = startDate.atStartOfDay(tz)
      val before = startDate.plusYears(1).atStartOfDay(tz)
      fetchActivitiesJson(client, bearerToken, after, before)
    }
  }

  def fetchActivitiesJson(client: Client[IO], bearerToken: BearerToken, after: ZonedDateTime, before: ZonedDateTime, page: Int = 1): Stream[IO, Json] = {
    Stream.unfoldLoopEval(1) { page =>
      println(s"Fetching page $page")
      val request = Method.GET(
        Uri.unsafeFromString(s"https://www.strava.com/api/v3/athlete/activities?after=${after.toEpochSecond}&before=${before.toEpochSecond}&page=${page}&per_page=200"),
          Accept(MediaType.application.json),
          Authorization(Credentials.Token(AuthScheme.Bearer, bearerToken.accessToken))
      )
      client.expect(request)(jsonOf[IO, Vector[Json]]).map { activities =>
        val nextPage = if (activities.nonEmpty) Some(page + 1) else None
        (activities, nextPage)
      }
    }.flatMap(activities => Stream.chunk(Chunk.vector(activities)))
  }
}
