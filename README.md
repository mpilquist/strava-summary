I like to run and bike. Like many other athletes, I use [Strava](https://www.strava.com) for tracking my workouts. When I run outside, I almost always start a Strava run on my watch and a [Peloton](https://www.onepeloton.com) class on my phone (Strava for metrics & GPS, Peloton for instruction and company), resulting in two activites getting logged. In the past, only the Strava run would count towards the annual distance metric but recently, I noticed this changed, with both activities reporting distance. Thankfully, Strava has a [very well documented API](https://developers.strava.com) which allows full access to recorded activities. In this article, we'll look at how to use the Strava API to de-duplicate these runs as well as compute some additional metrics. We'll use a number of [Typelevel](https://typelevel.org) libraries to implement a console application, including [http4s](https://http4s.org), [fs2](https://fs2.io), and [Circe](https://circe.github.io/circe/).

# Overview

Let's start by thinking about de-duplication. The general idea is to look for 2 or more time-wise overlapping activities, taking the activity with the longest distance and discarding the others. This approach assumes there's a single "longest" encompassing activity (e.g., a Strava run) with other shorter activities (e.g., a Peloton class or two). To implement de-duplication, we'll need a list of activities in a time period, with each activity providing at minimum, a period and a distance.

A quick persual of the [API docs](https://developers.strava.com/docs/reference/#api-Activities-getLoggedInAthleteActivities) shows that a simple `GET /athlete/activities` request returns a list of activities, with each activity providing the data necessary for de-duplication. The `/athlete/activities` resource supports filtering activities by start date via `before` and `after` query params. Futher, it supports pagination, with a maximum of 200 activities per page, and with an end of result signaled by an empty response.

Hence, all we have to do is make a few calls to `/athlete/activities`, implement our de-duplication logic, and summarize the results!

# Application Architecture

We'll implement two console applications -- one that fetches activities for a specified year and writes them to local storage and another that reads the stored activities, de-duplicates them, and summarizes them. We could do this as a single application instead but I've found it's useful to fetch the data once and then iterate on the processing logic without authentication and waiting for activity retrieval.

# Authentication

One complication is that Strava uses [OAuth2 for authentication](http://developers.strava.com/docs/authentication/). The linked authentication documentation gets in to all the details, but in summary:
- we register our application with Strava, which gives us a "client id" and "client secret"
- we implement a bearer token retrieval scheme which:
  - launches a web browser to the Strava website, where the user logs in to Strava and grants our application access to read their activities
  - as a result of the user granting access, a single-use authorization code is provided to our application
  - using the authorization code, the application fetches an access token
  - the access token is then used in all Strava API requests

For those with OAuth 2 experience, there's no need to implement token renewal as Strava's access tokens are valid for 6 hours -- we're building a command line app which will terminate in a few seconds.

After the user grants access to our application, the Strava website redirects the user's browser to a URL of our choosing, providing the needed authorization code. Hence, we'll need to start an HTTP server in order for the redirect to have a target. After starting the HTTP server, we'll open a browser and ask the user to grant access. Once Strava redirects back to our application, we can extract the authorization code and tear down the HTTP server. Here's how we can implement this:

```scala
def getAuthorizationCode(blocker: Blocker, clientId: ClientId): IO[AuthorizationCode] = {
  Deferred[IO, AuthorizationCode].flatMap { deferredAuthCode =>
    BlazeServerBuilder[IO](global)
      .bindHttp(port = 0)
      .withHttpApp { 
        object CodeParam extends QueryParamDecoderMatcher[String]("code")
        HttpRoutes.of[IO] {
          case GET -> Root / "exchange_token" :? CodeParam(code) =>
            deferredAuthCode.complete(AuthorizationCode(code)).as(Response(Status.Ok))
        }.orNotFound
      }
      .stream.flatMap { server =>
        val port = server.address.getPort
        Stream.eval(requestAuthCode(blocker, clientId, port) *> deferredAuthCode.get)
      }.compile.lastOrError
  }
}
```

We first create a `Deferred[IO, AuthorizationCode]`, which will eventually be completed with the code provided by Strava. We then start an http4s server with a route that handles the `/exchange_token?code=${authorizationCode}` redirect by completing the `Deferred[IO, AuthorizationCode]`. To avoid a port conflict, we bind the server to port 0, which causes the operating system to pick an unused ephemeral port number. We query for the selected port number and use it in the subsequent redirect URI generation. Finally, we open a browser to Strava and then wait for the deferred authorization code to be completed. This is all expressed as an `fs2.Stream` which is compiled to a value of `IO[AuthorizationCode]` via `.compile.lastOrError`. As a result, when the `AuthorizationCode` is returned, the web server is shut down as part of stream finalization.

Opening a browser is accomplished via the `open` utility:

```scala
def requestAuthCode(blocker: Blocker, clientId: ClientId, localPort: Int): IO[Unit] = {
  blocker.delay[IO, Unit] {
    import scala.sys.process._
    s"open http://www.strava.com/oauth/authorize?client_id=${clientId}&response_type=code&redirect_uri=http://localhost:${localPort}/exchange_token&approval_prompt=force&scope=read,activity:read".!
  }
}
```

Here we've used `scala.sys.process` to shell out to `open` but for more complicated interaction with processes, check out the [prox](https://vigoo.github.io/prox/) library. Because `scala.sys.process` blocks for the process to complete, we wrap its execution with `blocker.delay` to ensure the blocking does not occur on our main compute pool. (Note: in the forthcoming cats-effect 3, `Blocker` is gone and the equivalent is `IO.blocking { s"open http://...".! }`).

Alright, now that we have an authorization code, we can fetch a bearer token:

```scala
def fetchBearerToken(client: Client[IO], clientId: ClientId, clientSecret: ClientSecret, authorizationCode: AuthorizationCode): IO[BearerToken] = {
  val request = Method.POST(
    UrlForm(
      "client_id" -> clientId.value,
      "client_secret" -> clientSecret.value,
      "code" -> authorizationCode.value,
      "grant_type" -> "authorization_code"),
    Uri.uri("https://www.strava.com/oauth/token"))
  client.expect(request)(jsonOf[IO, BearerToken])
}
```

This is straightforward usage of the http4s client API, though it relies on the client DSL (`Http4sClientDsl[IO]`) being mixed in to the containing type. The `Client[IO]` parameter is a value created at startup and used to make all client HTTP requests. The `clientId` and `clientSecret` parameters are values we obtained as a result of registering our application with Strava. We pass these as parameters to avoid putting any secrets in the code -- for this app, we get them from command line arguments but in a production grade application, we'd fetch these from [Vault](https://www.vaultproject.io) or some other secret storage system.

Putting these pieces together gives us the overall authentication workflow:

```scala
def getBearerToken(blocker: Blocker, client: Client[IO], clientId: ClientId, clientSecret: ClientSecret): IO[BearerToken] =
  getAuthorizationCode(blocker, clientId).flatMap(fetchBearerToken(client, clientId, clientSecret, _))
```

# Fetching Activities

Now that we have a bearer token, we can fetch activities via `GET /athlete/activities`. The only complication we need to handle is pagination. Each request takes a `page=${n}` query parameter, starting with 1. The API docs instruct us to increment the page number until we receive a response with no activities.

This is a common pattern when considering how streams of elements are constructed -- there's an initial state, the page number, and an effectful action which generates both the stream elements (activities) and the next state (page number + 1). The `fs2.Stream` object expresses this pattern via the `unfoldLoopEval` constructor:

```scala
def unfoldLoopEval[F[_], S, O](s: S)(f: S => F[(O, Option[S])]): Stream[F, O]
```

We'll use this with `F = IO`, `S = Int` (page number), and `O = Vector[Json]` (page of activities). Each invocation of `f` produces another page of elements and then either the next page number (wrapped in `Some`) or `None`, indicating there are no more pages to fetch.

```scala
def fetchActivitiesJson(client: Client[IO], bearerToken: BearerToken, after: ZonedDateTime, before: ZonedDateTime, page: Int = 1): Stream[IO, Json] = {
  Stream.unfoldLoopEval(1) { page =>
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
```

We decoded each page with Circe to a `Vector[Json]`, which may seem odd -- why not fully decode the activities as a `Vector[Activity]` or do no decoding and return a single `Json`? Our goal is to just write these values out to storage so decoding to an `Activity` just to later re-encode to `Json` is unnecessary. If we did no decoding and returned a single `Json` value for the entire page, we would not be able to emit individual activities and hide pagination from the caller.

Note `unfoldLoopEval` gives us a `Stream[IO, Vector[Json]]` and we want a `Stream[IO, Json]` -- we accomplish that by flat mapping the result of `unfoldLoopEval` and turning each page of activities in to a stream of individual activities.

# Activity Storage

This application needs simple local storage for the activities list. Since we can fit all activities in to memory and don't need any query abilities, we'll just store the full list of activities as json to a local file using the `fs2.io.file` package.

```scala
private val path = Paths.get("activities.json")

def writeActivitiesJson(blocker: Blocker, activities: Vector[Json])(implicit cs: ContextShift[IO]): IO[Unit] = {
  val asString = Json.fromValues(activities).spaces2
  Stream.emit(asString)
    .through(text.utf8Encode)
    .through(file.writeAll[IO](path, blocker, flags = List(
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
    ))).compile.drain
}
```

Wiring everything together gives us this application:

```scala
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
}
```

This application uses the `fetchActivitiesForYearJson` method, which is implemented via the `fetchActivitiesJson` method we wrote above. Given the relatively small number of activities, we chose to accumulate all activities in to a single `Vector[Json]` via `.compile.toVector` before writing them to storage. We could have handled this in a streaming fashion instead by using [`circe-fs2`](https://github.com/circe/circe-fs2) and a streaming JSON file format.

Take note of the way the global resources, the `Blocker` and `Client[IO]` are instantiated in `run` as `cats.effect.Resource[IO, *]` values and then used (via `.use(r => ...)`), ensuring cleanup.

# Processing Activities

The processing application is much simpler. We need to read & decode the stored activities and then run our various computations on the result.

Let's start with reading & decoding:

```scala
def readActivities(blocker: Blocker)(implicit cs: ContextShift[IO]): IO[Vector[Activity]] =
  file.readAll[IO](path, blocker, 4096).through(text.utf8Decode)
    .compile.string
    .flatMap { str =>
      parse(str).flatMap(_.as[Vector[Activity]]) match {
        case Left(err) => IO.raiseError(err)
        case Right(activities) => IO.pure(activities)
      }
  }
```

We use `fs2.io.file` again, this time reading the full contents of the file, decoding as UTF8. We accumulate the decoded results in to a single value of `IO[String]` via `.compile.string`. Then we parse that string to a `Json` value and then decode that value as a `Vector[Activity]`.

Next, we need to compute various metrics on the `Vector[Activity]`. These metrics are all straightforward usage of the Scala collection API. For example, computing the total mileage of a `Vector[Activity]` and rendering as a friendly string:

```scala
def totalMiles(activities: Vector[Activity]): String =
  f"${metersToMiles(activities.foldLeft(0.0d)(_ + _.distance))}%.2f mi"

def metersToMiles(meters: Double): Double = meters * 0.000621371
```

Each activity has a `type` field indicating whether it is a ride, run, etc. We can use this field and some others to compute interesting breakouts:

```scala
val runs = activities.filter(_.tpe == "Run")
val zwiftRides = activities.filter(_.tpe == "VirtualRide")
val rides = activities.filter(_.tpe == "Ride")
val pelotonRides = rides.filter(_.trainer)
val outdoorRides = rides.filterNot(_.trainer)
```

The de-duplication logic is a bit more complicated:

```scala
def dedupe(activities: Vector[Activity]): Vector[Activity] = {
  val remaining = collection.mutable.SortedSet(activities: _*)(
    Ordering.by((a: Activity) => (-a.distance, a.startDate.toEpochMilli, a.name))
  )
  val bldr = Vector.newBuilder[Activity]
  while (remaining.nonEmpty) {
    val head = remaining.head
    val dupes = remaining.filter(_.overlaps(head))
    bldr += head
    remaining --= dupes
  }
  bldr.result()
}
```

We sort the activities so the longest activity is first and the shortest activity is last. We then take the longest activity, discard any other activities which overlap with it, and repeat. We continue the iteration until there are no remaining activities.

Hence, the full processing application is:

```scala
object ProcessActivities extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    Blocker[IO].use { blocker =>
      ActivityStorage.readActivities(blocker).flatMap(summarizeActivities)
    }.as(ExitCode.Success)
  }

  def totalMiles(activities: Vector[Activity]): String =
    f"${metersToMiles(activities.foldLeft(0.0d)(_ + _.distance))}%.2f mi"

  def metersToMiles(meters: Double): Double = meters * 0.000621371

  def dedupe(activities: Vector[Activity]): Vector[Activity] = {
    val remaining = collection.mutable.SortedSet(activities: _*)(
      Ordering.by((a: Activity) => (-a.distance, a.startDate.toEpochMilli, a.name))
    )
    val bldr = Vector.newBuilder[Activity]
    while (remaining.nonEmpty) {
      val head = remaining.head
      val dupes = remaining.filter(_.overlaps(head))
      bldr += head
      remaining --= dupes
    }
    bldr.result()
  }

  def summarizeActivities(activities: Vector[Activity]): IO[Unit] = {
    val runs = activities.filter(_.tpe == "Run")
    val dedupedRuns = dedupe(runs)
    val zwiftRides = activities.filter(_.tpe == "VirtualRide")
    val rides = activities.filter(_.tpe == "Ride")
    val pelotonRides = rides.filter(_.trainer)
    val outdoorRides = rides.filterNot(_.trainer)
    IO(println(
      s"""|Loaded ${activities.size} activities
          |
          |Run mileage (${runs.size}): ${totalMiles(runs)}
          | - Deduped run mileage (${dedupedRuns.size}): ${totalMiles(dedupedRuns)}
          |
          |Total ride mileage (${zwiftRides.size + rides.size}): ${totalMiles(zwiftRides ++ rides)}
          | - Zwift mileage (${zwiftRides.size}): ${totalMiles(zwiftRides)}
          | - Peloton mileage (${pelotonRides.size}): ${totalMiles(pelotonRides)}
          | - Outdoor ride mileage (${outdoorRides.size}): ${totalMiles(outdoorRides)}
          """.stripMargin))
  }
}
```

# Summary

