package mlb

// Imports
import zio._
import zio.jdbc._
import zio.http._
import com.github.tototoshi.csv._
import java.io.File
import zio.stream.ZStream
import GameDates.*
import SeasonYears.*
import HomeTeams.*
import AwayTeams.*
import HomeScores.*
import AwayScores.*
import HomeElos.*
import AwayElos.*
import HomeProbElos.*
import AwayProbElos.*
import java.time.LocalDate

object MlbApi extends ZIOAppDefault {

  import DataService._
  import ApiService._

  // Static endpoints, for testing purpose
  val static: App[Any] = Http
    .collect[Request] {
      case Method.GET -> Root / "text" => Response.text("Hello MLB Fans!")
      case Method.GET -> Root / "json" =>
        Response.json("""{"greetings": "Hello MLB Fans!"}""")
    }
    .withDefaultErrorResponse

  val endpoints: App[ZConnectionPool] = Http
    .collectZIO[Request] {
      // Init the database
      case Method.GET -> Root / "init" =>
        ZIO.succeed(
          Response
            .text("Not Implemented, database is created at startup")
            .withStatus(Status.NotImplemented)
        )
      // Get the latest 20 games between two teams
      case Method.GET -> Root / "game" / "latests" / homeTeam / awayTeam =>
        for {
          game: List[Game] <- latests(HomeTeam(homeTeam), AwayTeam(awayTeam))
          res: Response = latestGameResponse(game)
        } yield res
      //  Predict a match between two teams
      case Method.GET -> Root / "game" / "predict" / homeTeam / awayTeam =>
        for {
          game: List[Game] <- predictMatch(
            HomeTeam(homeTeam),
            AwayTeam(awayTeam)
          )
          res: Response = predictMatchResponse(game, homeTeam, awayTeam)
        } yield res
      // Get the elo of a team
      case Method.GET -> Root / "team" / "elo" / homeTeam =>
        for {
          team: Option[Game] <- latest(HomeTeam(homeTeam))
          res: Response = eloTeamGameResponse(team, homeTeam)
        } yield res
      // Get count of all games in history
      case Method.GET -> Root / "games" / "count" =>
        for {
          count: Option[Int] <- count
          res: Response = countResponse(count)
        } yield res
      // Get all games for a team in history
      case Method.GET -> Root / "games" / "history" / team =>
        for {
          games: List[Game] <- findHistoryTeam(team)
          res: Response = teamHistoryResponse(games)
        } yield res
      case _ =>
        ZIO.succeed(Response.text("Not Found").withStatus(Status.NotFound))
    }
    .withDefaultErrorResponse

  // Init the database and start the server
  val appLogic: ZIO[ZConnectionPool & Server, Throwable, Unit] = for {
    _ <- for {
      conn <- create
      source <- ZIO.succeed(
        // Read the csv file, change to mlb_elo.csv for full data
        CSVReader
          // .open(new File("mlb_elo.csv"))
          .open(new File("mlb_elo_latest.csv"))
      )
      stream <- ZStream
        .fromIterator[Seq[String]](source.iterator)
        // Skip the first row and empty rows
        .filter(row => row.nonEmpty && row(0) != "date")
        .map[Game](row =>
          // Create a game from a row
          Game(
            GameDate(LocalDate.parse(row(0))),
            SeasonYear(row(1).toInt),
            HomeTeam(row(4)),
            AwayTeam(row(5)),
            HomeScore(row(24).toIntOption.getOrElse(-1)),
            AwayScore(row(25).toIntOption.getOrElse(-1)),
            HomeElo(row(6).toDouble),
            AwayElo(row(7).toDouble),
            HomeProbElo(row(8).toDouble),
            AwayProbElo(row(9).toDouble)
          )
        )
        .grouped(1000)
        // Insert 1000 rows at a time to the database
        .foreach(chunk => insertRows(chunk.toList))
      _ <- ZIO.succeed(source.close())
      res <- ZIO.succeed(conn)
    } yield res
    _ <- Server.serve[ZConnectionPool](static ++ endpoints)
  } yield ()

  // Start the server, equivalent to main method
  override def run: ZIO[Any, Throwable, Unit] =
    appLogic
      .provide(
        createZIOPoolConfig >>> connectionPool,
        // Change the port here if needed (default is 8080, mine was already in use)
        Server.defaultWithPort(5000)
      )
}

// object used for the response of the api
object ApiService {
  import zio.json.EncoderOps

  def countResponse(count: Option[Int]): Response = {
    count match
      case Some(c) =>
        Response.text(s"$c game(s) in historical data").withStatus(Status.Ok)
      case None =>
        Response.text("No game in historical data").withStatus(Status.NotFound)
  }

  def eloTeamGameResponse(game: Option[Game], homeTeam: String): Response = {
    game match {
      case Some(g) => {
        val elo = f"${g.homeElo}%1.1f"
        val res = s"$homeTeam elo is $elo"
        Response.text(res).withStatus(Status.Ok)
      }
      case None =>
        Response
          .text("No game found in historical data")
          .withStatus(Status.NotFound)
    }
  }

  def latestGameResponse(game: List[Game]): Response = {
    if (game.isEmpty) {
      return Response
        .text("No game found in historical data")
        .withStatus(Status.NotFound)
    }
    Response.json(game.toJson).withStatus(Status.Ok)
  }

  def teamHistoryResponse(
      games: List[Game]
  ): Response = {
    Response.json(games.toJson).withStatus(Status.Ok)
  }

  def predictMatchResponse(
      game: List[Game],
      homeTeam: String,
      awayTeam: String
  ): Response = {
    if (game.isEmpty) {
      return Response
        .text("No game found in historical data")
        .withStatus(Status.NotFound)
    }
    // The prediction is based on the last 20 games between the two teams
    // Count the number of win for each team
    val homeWin: Int = game.count(g =>
      HomeScore.unapply(g.homeScore) > AwayScore.unapply(g.awayScore)
    )
    val awayWin: Int = game.count(g =>
      HomeScore.unapply(g.homeScore) < AwayScore.unapply(g.awayScore)
    )

    // Calculate the win rate for each team
    val homeWinRate: Double = homeWin.toDouble / game.size * 100
    val awayWinRate: Double = awayWin.toDouble / game.size * 100

    // Calculate the average elo for each team
    val homeProbEloAvg: Double =
      game.map(g => HomeProbElo.unapply(g.homeProbElo)).sum / game.size * 100
    val awayProbEloAvg: Double =
      game.map(g => AwayProbElo.unapply(g.awayProbElo)).sum / game.size * 100

    // Format the response
    val homeWinRateStr: String = f"$homeWinRate%1.0f"
    val awayWinRateStr: String = f"$awayWinRate%1.0f"
    val homeProbStr: String = f"${homeProbEloAvg}%1.0f"
    val awayProbStr: String = f"${awayProbEloAvg}%1.0f"
    val predictResponse: String =
      s"Prediction for $homeTeam vs $awayTeam:" +
        s"\n$homeTeam wins $homeWinRateStr% of the time, $awayTeam wins $awayWinRateStr%." +
        s"\nBased on ${game.size} latest games." +
        s"\nBased on elo, $homeTeam wins $homeProbStr% of the time, $awayTeam wins $awayProbStr%."
    Response.text(predictResponse).withStatus(Status.Ok)
  }
}

// object used for the database
object DataService {

  // Create the database
  val createZIOPoolConfig: ULayer[ZConnectionPoolConfig] =
    ZLayer.succeed(ZConnectionPoolConfig.default)

  val properties: Map[String, String] = Map(
    "user" -> "postgres",
    "password" -> "postgres"
  )

  val connectionPool
      : ZLayer[ZConnectionPoolConfig, Throwable, ZConnectionPool] =
    ZConnectionPool.h2mem(
      database = "mlb",
      props = properties
    )

  val create: ZIO[ZConnectionPool, Throwable, Unit] = transaction {
    execute(
      sql"CREATE TABLE IF NOT EXISTS games(date DATE NOT NULL, season_year INT NOT NULL, home_team VARCHAR(3), away_team VARCHAR(3), home_score INT, away_score INT, home_elo DOUBLE, away_elo DOUBLE, home_prob_elo DOUBLE, away_prob_elo DOUBLE)"
    )
  }
  // Insert a list of games in the database
  def insertRows(
      games: List[Game]
  ): ZIO[ZConnectionPool, Throwable, UpdateResult] = {
    val rows: List[Game.Row] = games.map(_.toRow)
    transaction {
      insert(
        sql"INSERT INTO games(date, season_year, home_team, away_team, home_score, away_score, home_elo, away_elo, home_prob_elo, away_prob_elo)"
          .values[Game.Row](rows)
      )
    }
  }

  // After are the different queries used in the api

  val count: ZIO[ZConnectionPool, Throwable, Option[Int]] = transaction {
    selectOne(
      sql"SELECT COUNT(*) FROM games".as[Int]
    )
  }

  def latests(
      homeTeam: HomeTeam,
      awayTeam: AwayTeam
  ): ZIO[ZConnectionPool, Throwable, List[Game]] = {
    transaction {
      selectAll(
        sql"SELECT * FROM games WHERE home_team = ${HomeTeam
            .unapply(homeTeam)} AND away_team = ${AwayTeam.unapply(awayTeam)} ORDER BY date DESC LIMIT 20"
          .as[Game]
      ).map(_.toList)
    }
  }

  def latest(
      homeTeam: HomeTeam
  ): ZIO[ZConnectionPool, Throwable, Option[Game]] = {
    transaction {
      selectOne(
        sql"SELECT * FROM games WHERE home_team = ${HomeTeam
            .unapply(homeTeam)} OR away_team = ${HomeTeam.unapply(homeTeam)} ORDER BY date DESC LIMIT 1"
          .as[Game]
      )
    }
  }

  def predictMatch(
      homeTeam: HomeTeam,
      awayTeam: AwayTeam
  ): ZIO[ZConnectionPool, Throwable, List[Game]] = {
    transaction {
      selectAll(
        sql"SELECT * FROM games WHERE home_team = ${HomeTeam
            .unapply(homeTeam)} AND away_team = ${AwayTeam.unapply(awayTeam)} AND home_score != -1 AND away_score != -1 order by date desc limit 20"
          .as[Game]
      ).map(_.toList)
    }
  }

  def findHistoryTeam(
      homeTeam: String
  ): ZIO[ZConnectionPool, Throwable, List[Game]] = {
    transaction {
      selectAll(
        sql"SELECT * FROM games WHERE home_team = ${homeTeam}"
          .as[Game]
      ).map(_.toList)
    }
  }
}
