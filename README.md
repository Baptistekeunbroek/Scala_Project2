# Projet Scala

The Game file defines a class called Game that represents a baseball game. The Game class has ten fields, including the date of the game, the season in which it was played, the home team and away team, the scores of both teams, and various Elo ratings that are used to predict the outcome of the game.
The Game class also defines a custom decoder and encoder for JSON serialization and deserialization, which is done using the zio.json library. Additionally, the class defines a custom decoder for reading data from a JDBC database.
The Game class makes use of several other objects defined in the code, including HomeTeams, AwayTeams, HomeScores, AwayScores, HomeElos, AwayElos, HomeProbElos, AwayProbElos, GameDates, and SeasonYears.

The MlbApi file is in 3 parts :
The first part is the definition of the different endpoints of the API. The endpoints are defined using the zio-http library. It's also in this part that the endpoints are linked to the different functions of the API, and that the database is initialized.
The second part is the definition of the different functions of the API. The functions are defined using the zio library. The functions are linked to the endpoints in the first part. For example, the function latestGameResponse is linked to the endpoint GET /game/latests/{homeTeam}/{awayTeam}.
The last part is where the database is created and where the different queries are defined. The queries are defined using the ZIO library. The queries are then used in the functions of the second part.

We tried doing tests but we couldn't get them to work. We tried to do tests on the different routes of the API, but nothing worked. We tried using scalaTest and zio-test, but both did not succeed. We left some of the tests we tried in the code, but they are commented out.

## Installation

To install this project, you will need to have Scala and SBT installed on your machine. Once you have those installed, you can clone this repository and do :

```bash
sbt
project rest
compile
~reStart
```

## Usage

To use this project, simply run the application using ~reStart. This will start the application on localhost:5000. You can then use the following endpoints:

- GET /init: Initializes the database.
- GET /game/latests/{homeTeam}/{awayTeam}: Gets the latest 20 games between two teams.
- GET /game/predict/{homeTeam}/{awayTeam}: Predicts a match between two teams.
- GET /team/elo/{homeTeam}: Gets the elo of a team.
- GET /games/count: Gets the count of all games in the database.
- GET /games/history/{team}: Gets all games for a team in history.
