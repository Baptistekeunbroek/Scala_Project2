package mlb

import zio._
import zio.jdbc._
import zio.http._

import java.sql.Date
import com.github.tototoshi.csv._
import java.io.File
import zio.stream.ZStream
import mlb.GameDates.GameDate
import mlb.SeasonYears.SeasonYear
import java.time.LocalDate
import GameDates.*
import SeasonYears.*
import HomeTeams.*
import AwayTeams.*
import HomeScores.*
import AwayScores.*
import HomeElos.*
import AwayElos.*

object MlbApi extends ZIOAppDefault {

  import DataService._
  import ApiService._

  val static: App[Any] = Http
    .collect[Request] {
      case Method.GET -> Root / "text" => Response.text("Hello MLB Fans!")
      case Method.GET -> Root / "json" =>
        Response.json("""{"greetings": "Hello MLB Fans!"}""")
    }
    .withDefaultErrorResponse

  val endpoints: App[ZConnectionPool] = Http
    .collectZIO[Request] {
      case Method.GET -> Root / "init" =>
        ZIO.succeed(
          Response
            .text("Not Implemented, database is created at startup")
            .withStatus(Status.NotImplemented)
        )
      case Method.GET -> Root / "game" / "latests" / homeTeam / awayTeam =>
        for {
          game: List[Game] <- latests(HomeTeam(homeTeam), AwayTeam(awayTeam))
          res: Response = latestGameResponse(game)
        } yield res
      case Method.GET -> Root / "game" / "predict" / homeTeam / awayTeam =>
        for {
          game: List[Game] <- predictMatch(
            HomeTeam(homeTeam),
            AwayTeam(awayTeam)
          )
          res: Response = predictMatchResponse(game, homeTeam, awayTeam)
        } yield res
      case Method.GET -> Root / "team" / "elo" / homeTeam =>
        for {
          team: Option[Game] <- latest(HomeTeam(homeTeam))
          res: Response = eloTeamGameResponse(team, homeTeam)
        } yield res
      case Method.GET -> Root / "games" / "count" =>
        for {
          count: Option[Int] <- count
          res: Response = countResponse(count)
        } yield res
      case Method.GET -> Root / "games" / "history" / team =>
        for {
          games: List[Game] <- findHistoryTeam(team)
          res: Response = teamHistoryResponse(games)
        } yield res
      case _ =>
        ZIO.succeed(Response.text("Not Found").withStatus(Status.NotFound))
    }
    .withDefaultErrorResponse

  val appLogic: ZIO[ZConnectionPool & Server, Throwable, Unit] = for {
    _ <- for {
      conn <- create
      source <- ZIO.succeed(
        CSVReader
          .open(new File("mlb_elo_latest.csv"))
      )
      stream <- ZStream
        .fromIterator[Seq[String]](source.iterator)
        .filter(row => row.nonEmpty && row(0) != "date")
        .map[Game](row =>
          Game(
            GameDate(LocalDate.parse(row(0))),
            SeasonYear(row(1).toInt),
            HomeTeam(row(4)),
            AwayTeam(row(5)),
            HomeScore(row(24).toIntOption.getOrElse(-1)),
            AwayScore(row(25).toIntOption.getOrElse(-1)),
            HomeElo(row(6).toDouble),
            AwayElo(row(7).toDouble)
          )
        )
        .grouped(1000)
        .foreach(chunk => insertRows(chunk.toList))
      _ <- ZIO.succeed(source.close())
      res <- ZIO.succeed(conn)
    } yield res
    _ <- Server.serve[ZConnectionPool](static ++ endpoints)
  } yield ()

  override def run: ZIO[Any, Throwable, Unit] =
    appLogic
      .provide(
        createZIOPoolConfig >>> connectionPool,
        Server.defaultWithPort(5000)
      )
}

object ApiService {

  import zio.json.EncoderOps
  import Game._

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
    val homeWin: Int = game.count(g =>
      HomeScore.unapply(g.homeScore) > AwayScore.unapply(g.awayScore)
    )
    val awayWin: Int = game.count(g =>
      HomeScore.unapply(g.homeScore) < AwayScore.unapply(g.awayScore)
    )
    val draw: Int = game.count(g =>
      HomeScore.unapply(g.homeScore) == AwayScore.unapply(g.awayScore)
    )
    val homeWinRate: Double = homeWin.toDouble / game.size * 100
    val awayWinRate: Double = awayWin.toDouble / game.size * 100
    val drawRate: Double = draw.toDouble / game.size
    val homeWinRateStr: String = f"$homeWinRate%1.0f"
    val awayWinRateStr: String = f"$awayWinRate%1.0f"
    val drawRateStr: String = f"$drawRate%1.0f"
    val predictResponse: String =
      s"Prediction for $homeTeam vs $awayTeam: $homeTeam wins $homeWinRateStr%, $awayTeam wins $awayWinRateStr%, draw $drawRateStr%. Based on ${game.size} games."
    Response.text(predictResponse).withStatus(Status.Ok)
  }
}

object DataService {

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
      sql"CREATE TABLE IF NOT EXISTS games(date DATE NOT NULL, season_year INT NOT NULL, home_team VARCHAR(3), away_team VARCHAR(3), home_score INT, away_score INT, home_elo DOUBLE, away_elo DOUBLE)"
    )
  }

  def insertRows(
      games: List[Game]
  ): ZIO[ZConnectionPool, Throwable, UpdateResult] = {
    val rows: List[Game.Row] = games.map(_.toRow)
    transaction {
      insert(
        sql"INSERT INTO games(date, season_year, home_team, away_team, home_score, away_score, home_elo, away_elo)"
          .values[Game.Row](rows)
      )
    }
  }

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
            .unapply(homeTeam)} AND away_team = ${AwayTeam.unapply(awayTeam)} ORDER BY date DESC LIMIT 10"
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
            .unapply(homeTeam)} AND away_team = ${AwayTeam.unapply(awayTeam)} AND home_score != -1 AND away_score != -1"
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
