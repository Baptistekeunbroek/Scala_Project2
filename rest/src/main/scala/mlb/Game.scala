package mlb

import zio.json._
import zio.jdbc._

import java.time.LocalDate

object HomeTeams {

  opaque type HomeTeam = String

  object HomeTeam {

    def apply(value: String): HomeTeam = value

    def unapply(homeTeam: HomeTeam): String = homeTeam
  }

  given CanEqual[HomeTeam, HomeTeam] = CanEqual.derived
  implicit val homeTeamEncoder: JsonEncoder[HomeTeam] = JsonEncoder.string
  implicit val homeTeamDecoder: JsonDecoder[HomeTeam] = JsonDecoder.string
}

object AwayTeams {

  opaque type AwayTeam = String

  object AwayTeam {

    def apply(value: String): AwayTeam = value

    def unapply(awayTeam: AwayTeam): String = awayTeam
  }

  given CanEqual[AwayTeam, AwayTeam] = CanEqual.derived
  implicit val awayTeamEncoder: JsonEncoder[AwayTeam] = JsonEncoder.string
  implicit val awayTeamDecoder: JsonDecoder[AwayTeam] = JsonDecoder.string
}

object HomeScores {

  opaque type HomeScore = Int

  object HomeScore {

    def apply(value: Int): HomeScore = value

    def unapply(homeScore: HomeScore): Int = homeScore
  }

  given CanEqual[HomeScore, HomeScore] = CanEqual.derived
  implicit val awayTeamEncoder: JsonEncoder[HomeScore] = JsonEncoder.int
  implicit val awayTeamDecoder: JsonDecoder[HomeScore] = JsonDecoder.int
}

object AwayScores {

  opaque type AwayScore = Int

  object AwayScore {

    def apply(value: Int): AwayScore = value

    def unapply(awayScore: AwayScore): Int = awayScore
  }

  given CanEqual[AwayScore, AwayScore] = CanEqual.derived
  implicit val awayTeamEncoder: JsonEncoder[AwayScore] = JsonEncoder.int
  implicit val awayTeamDecoder: JsonDecoder[AwayScore] = JsonDecoder.int
}
object GameDates {

  opaque type GameDate = LocalDate

  object GameDate {

    def apply(value: LocalDate): GameDate = value

    def unapply(gameDate: GameDate): LocalDate = gameDate
  }

  given CanEqual[GameDate, GameDate] = CanEqual.derived
  implicit val gameDateEncoder: JsonEncoder[GameDate] = JsonEncoder.localDate
  implicit val gameDateDecoder: JsonDecoder[GameDate] = JsonDecoder.localDate
}

object SeasonYears {

  opaque type SeasonYear <: Int = Int

  object SeasonYear {

    def apply(year: Int): SeasonYear = year

    def safe(value: Int): Option[SeasonYear] =
      Option.when(value >= 1876 && value <= LocalDate.now.getYear)(value)

    def unapply(seasonYear: SeasonYear): Int = seasonYear
  }

  given CanEqual[SeasonYear, SeasonYear] = CanEqual.derived
  implicit val seasonYearEncoder: JsonEncoder[SeasonYear] = JsonEncoder.int
  implicit val seasonYearDecoder: JsonDecoder[SeasonYear] = JsonDecoder.int
}

import GameDates.*
import SeasonYears.*
import HomeTeams.*
import AwayTeams.*
import HomeScores.*
import AwayScores.*

final case class Game(
    date: GameDate,
    season: SeasonYear,
    homeTeam: HomeTeam,
    awayTeam: AwayTeam,
    homeScore: HomeScore,
    awayScore: AwayScore
)

object Game {

  given CanEqual[Game, Game] = CanEqual.derived
  implicit val gameEncoder: JsonEncoder[Game] = DeriveJsonEncoder.gen[Game]
  implicit val gameDecoder: JsonDecoder[Game] = DeriveJsonDecoder.gen[Game]

  def unapply(
      game: Game
  ): (GameDate, SeasonYear, HomeTeam, AwayTeam, HomeScore, AwayScore) =
    (
      game.date,
      game.season,
      game.homeTeam,
      game.awayTeam,
      game.homeScore,
      game.awayScore
    )

  // a custom decoder from a tuple
  type Row = (String, Int, String, String, Int, Int)

  extension (g: Game)
    def toRow: Row =
      val (d, y, h, a, hs, as) = Game.unapply(g)
      (
        GameDate.unapply(d).toString,
        SeasonYear.unapply(y),
        HomeTeam.unapply(h),
        AwayTeam.unapply(a),
        HomeScore.unapply(hs),
        AwayScore.unapply(as)
      )

  implicit val jdbcDecoder: JdbcDecoder[Game] = JdbcDecoder[Row]().map[Game] {
    t =>
      val (date, season, home, away, homeScore, awayScore) = t
      Game(
        GameDate(LocalDate.parse(date)),
        SeasonYear(season),
        HomeTeam(home),
        AwayTeam(away),
        HomeScore(homeScore),
        AwayScore(awayScore)
      )
  }
}
