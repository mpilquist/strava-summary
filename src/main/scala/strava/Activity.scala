package strava

import io.circe._
import java.time.Instant

case class Activity(name: String, distance: Double, elapsedTime: Int, tpe: String, startDate: Instant, trainer: Boolean) {
  def endDate: Instant = startDate.plusSeconds(elapsedTime)
  def overlaps(that: Activity): Boolean = {
    (startDate.toEpochMilli <= that.startDate.toEpochMilli && endDate.toEpochMilli >= that.endDate.toEpochMilli) ||
    (startDate.toEpochMilli >= that.startDate.toEpochMilli && endDate.toEpochMilli <= that.endDate.toEpochMilli)
  }
}

object Activity {
  implicit val decoderInstant: Decoder[Instant] = Decoder.decodeString.emapTry { str =>
    scala.util.Try(Instant.parse(str))
  }

  implicit val decoder: Decoder[Activity] = (c: HCursor) => 
    for {
      name <- c.downField("name").as[String]
      distance <- c.downField("distance").as[Double]
      elapsedTime <- c.downField("elapsed_time").as[Int]
      tpe <- c.downField("type").as[String]
      startDate <- c.downField("start_date").as[Instant]
      trainer <- c.downField("trainer").as[Boolean]
    } yield Activity(name, distance, elapsedTime, tpe, startDate, trainer)
}

