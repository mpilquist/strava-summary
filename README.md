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
  - as a result of the user granting access, a single-use authentication code is provided to our application
  - using the authentication code, the application fetches an access token
  - the access token is then used in all Strava API requests

For those with OAuth 2 experience, there's no need to implement token renewal as Strava's access tokens are valid for 6 hours -- we're building a command line app which will terminate in a few seconds.

