package mlb

import munit._
import zio._
import zio.http._
import zio.test._
import zio.jdbc.ZConnectionPool

class MlbApiSpec extends munit.ZSuite {

  val app: App[ZConnectionPool] = mlb.MlbApi.endpoints

  testZ("should return 501 Not Implemented for /init") {
    val req = Request.get(URL(Root / "init"))
    assertEqualsZ(
      app
        .runZIO(req)
        .map(_.status),
      Status.NotImplemented
    )
  }
}

// testZ("should return the latest games between two teams") {
//   val homeTeam = "homeTeam"
//   val awayTeam = "awayTeam"
//   val req = Request(
//     Method.GET,
//     url"http://localhost:8080/game/latests/$homeTeam/$awayTeam"
//   )
//   assertM(app.run(req).map(_.status))(equalTo(Status.Ok))
// }

// testZ("should predict a match between two teams") {
//   val homeTeam = "homeTeam"
//   val awayTeam = "awayTeam"
//   val req = Request(
//     Method.GET,
//     url"http://localhost:8080/game/predict/$homeTeam/$awayTeam"
//   )
//   assertM(app.run(req).map(_.status))(equalTo(Status.Ok))
// }

// testZ("should return the elo of a team") {
//   val homeTeam = "homeTeam"
//   val req = Request(Method.GET, url"http://localhost:8080/team/elo/$homeTeam")
//   assertM(app.run(req).map(_.status))(equalTo(Status.Ok))
// }

// testZ("should return the count of all games in history") {
//   val req = Request(Method.GET, url"http://localhost:8080/games/count")
//   assertM(app.run(req).map(_.status))(equalTo(Status.Ok))
// }

// testZ("should return all games for a team in history") {
//   val team = "team"
//   val req =
//     Request(Method.GET, url"http://localhost:8080/games/history/$team")
//   assertM(app.run(req).map(_.status))(equalTo(Status.Ok))
// }
// }
