package strava

import cats.effect.{IO, IOApp}

object ProcessActivities extends IOApp.Simple {
  def run: IO[Unit] = ActivityStorage.readActivities.flatMap(summarizeActivities)

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
