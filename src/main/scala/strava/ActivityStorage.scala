package strava

import cats.effect.IO
import fs2.{Stream, text}
import fs2.io.file.Files

import io.circe.Json
import io.circe.parser._

import java.nio.file.{Paths, StandardOpenOption}

object ActivityStorage {

  private val path = Paths.get("activities.json")

  def writeActivitiesJson(activities: Vector[Json]): IO[Unit] = {
    val asString = Json.fromValues(activities).spaces2
    Stream.emit(asString)
      .through(text.utf8Encode)
      .through(Files[IO].writeAll(path, flags = List(
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
      ))).compile.drain
  }

  def readActivities: IO[Vector[Activity]] =
    Files[IO].readAll(path, 4096).through(text.utf8Decode)
      .compile.string
      .flatMap { str =>
        parse(str).flatMap(_.as[Vector[Activity]]) match {
          case Left(err) => IO.raiseError(err)
          case Right(activities) => IO.pure(activities)
        }
    }
}
