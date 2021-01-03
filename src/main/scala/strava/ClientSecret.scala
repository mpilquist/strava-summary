package strava

case class ClientSecret(value: String) {
  override def toString: String = value
}