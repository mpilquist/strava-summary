package strava

import io.circe._

case class BearerToken(accessToken: String)
object BearerToken {
  implicit val decoder: Decoder[BearerToken] =
    (c: HCursor) => c.downField("access_token").as[String].map(BearerToken(_))
}

