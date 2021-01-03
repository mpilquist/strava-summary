package strava

import cats.effect.{Blocker, ContextShift, IO}
import fs2.{Stream, text}
import fs2.io.file

import io.circe.Json
import io.circe.parser._

import java.nio.file.{Paths, StandardOpenOption}

object ActivityStorage {

  private val path = Paths.get("activities.json")

  def writeActivitiesJson(blocker: Blocker, activities: Vector[Json])(implicit cs: ContextShift[IO]): IO[Unit] = {
    val asString = Json.fromValues(activities).spaces2
    Stream.emit(asString)
      .through(text.utf8Encode)
      .through(file.writeAll[IO](path, blocker, flags = List(
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
      ))).compile.drain
  }

  def readActivities(blocker: Blocker)(implicit cs: ContextShift[IO]): IO[Vector[Activity]] =
    file.readAll[IO](path, blocker, 4096).through(text.utf8Decode)
      .compile.string
      .flatMap { str =>
        parse(str).flatMap(_.as[Vector[Activity]]) match {
          case Left(err) => IO.raiseError(err)
          case Right(activities) => IO.pure(activities)
        }
    }
}