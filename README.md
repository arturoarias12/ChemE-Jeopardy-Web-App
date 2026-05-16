# ChemE Jeopardy Web App

Browser-based ChemE Jeopardy for live classroom, review-session, and competition play. The app replaces a single-machine presentation with a server-hosted game that supports a moderator console, player buzzing, and a shared room display. The game flow and default rule assumptions are based on AIChE ChemE Jeopardy competition materials [1].

## Features

- Master game manager for creating multiple parallel game rooms.
- Per-game moderator console for setup, clue selection, judging, scoring, and Final Jeopardy.
- Player console for joining teams, clock synchronization, buzzing, wagers, and Final Jeopardy responses.
- Read-only display screen for the board, team scores, clue prompts, and answer reveals.
- Separate moderator and player join passwords per game room.
- Fairer buzz handling using client clock calibration and synchronized timestamps.
- Configurable teams, categories, clues, point values, timers, Daily Doubles, Final Jeopardy, and tie-breakers.
- Question set loading from existing JSON files or one runtime upload per game.

## Project Structure

- `src/com/chemejeopardy/Main.java` - application entry point.
- `src/com/chemejeopardy/config/AppConfig.java` - environment and deployment configuration.
- `src/com/chemejeopardy/server/AppServer.java` - embedded HTTP server, routes, static assets, and SSE streams.
- `src/com/chemejeopardy/server/AuthManager.java` - moderator and player access control.
- `src/com/chemejeopardy/game/GameEngine.java` - game rules, state machine, scoring, and source-file persistence.
- `src/com/chemejeopardy/util/Json.java` - small JSON parser/stringifier.
- `public/moderator.html` - moderator UI.
- `public/player.html` - player UI.
- `public/display.html` - room display UI.
- `data/game-definition.json` - default question set template for new game rooms.
- `Dockerfile` - optional portable container deployment.
- `.env.example` - local environment variable template.

## Requirements

Local development:

- JDK 21 or newer
- PowerShell on Windows for the helper scripts, or any shell that can run `javac` and `java`

Docker deployment:

- Any platform that can build and run a Dockerfile
- One running instance only

## Configuration

Create a local `.env` file from `.env.example`:

```text
CHEME_MODERATOR_PASSWORD=choose-a-private-moderator-password
CHEME_GAME_FILE=data/game-definition.json
```

Environment variables:

- `CHEME_MODERATOR_PASSWORD` - required. Admin password for `/` and `/games`.
- `CHEME_GAME_FILE` - optional. Path to the default question source JSON file used when creating a game without uploading or selecting another file.
- `CHEME_PUBLIC_DIR` - optional. Path to static browser files. Defaults to `public`.
- `PORT` - optional. Cloud platforms commonly set this automatically. Defaults to `8080`.

Command-line arguments also work:

```powershell
java -cp out com.chemejeopardy.Main 8080 data/game-definition.json
```

## Question Source File

New games can start from a JSON game file. By default, that file is:

```text
data/game-definition.json
```

It includes:

- game title
- team names, colors, and active flags
- team count and max players per team
- board categories
- clue prompts
- official responses
- point values
- Daily Double flags
- Final Jeopardy category, clue, and response
- tie-breaker clues
- game timers

To use a different prepared default game template, set:

```text
CHEME_GAME_FILE=data/my-event-game.json
```

Moderator setup changes and uploaded question sets are runtime-only for each game room.

## Build And Run Locally

Compile:

```powershell
.\build.ps1
```

Run:

```powershell
.\run.ps1
```

Run on another port:

```powershell
.\run.ps1 -Port 8090
```

Run with another game source file:

```powershell
.\run.ps1 -Port 8080 -GameFile data/my-event-game.json
```

Manual compile/run:

```powershell
$files = Get-ChildItem -Recurse src -Filter *.java | ForEach-Object { $_.FullName }
javac -d out $files
java -cp out com.chemejeopardy.Main 8080
```

## Browser URLs

When running locally:

- Admin home and game manager: `http://localhost:8080/`
- Alternate game manager URL: `http://localhost:8080/games`

No game room exists until the admin creates one. If the first room uses the default slug, its links are:

- Player UI: `http://localhost:8080/game-1/player`
- Display UI: `http://localhost:8080/game-1/display`
- Moderator UI: `http://localhost:8080/game-1/moderator`

On another device in the same network, replace `localhost` with the host computer's LAN IP.

## Docker

Docker is optional for local use, but useful for Azure Container Apps, Google Cloud Run, Railway, Render, Fly.io, and similar platforms.

Build:

```powershell
docker build -t cheme-jeopardy .
```

Run:

```powershell
docker run --rm -p 8080:8080 `
  -e CHEME_MODERATOR_PASSWORD="choose-a-private-password" `
  cheme-jeopardy
```

Open:

```text
http://localhost:8080/games
```

To keep a prepared question template outside the image, mount a data directory and set `CHEME_GAME_FILE` to that mounted path.

## Cloud Deployment Notes

Use one instance/replica. Each game room has its own in-memory state inside that instance, so multiple Container App replicas would split the same room into separate states.

Good targets:

- Azure for Students: Azure Container Apps or App Service.
- Google Cloud: Cloud Run using the Dockerfile.
- AWS: Lightsail, EC2, App Runner, or another single-container target.
- Render/Railway/Fly.io: Docker-based web service.

You do not need a custom domain. Cloud hosts provide a default HTTPS URL. A custom domain is optional and mainly makes the link nicer.

For live play, configure the host for one always-on instance when possible:

- Azure Container Apps: min replicas `1`, max replicas `1`.
- Google Cloud Run: min instances `1`, max instances `1`.

If the service scales to zero or restarts, the in-memory live game state can reset.

Set secrets in the cloud dashboard, not in GitHub:

```text
CHEME_MODERATOR_PASSWORD
```

## Moderator Flow

1. Open `/` or `/games`.
2. Log in with `CHEME_MODERATOR_PASSWORD`.
3. Create a game room with a display title, URL slug, moderator password, player password, and question set.
4. Open `/game-1/moderator` or the moderator link for the game room.
5. Log in with that game's moderator password.
6. Load or replace the JSON question set if needed.
7. Edit or review settings and team names.
8. Click **Save Settings**.
9. Start the game.
10. Select clues, open buzzing, judge responses, and click **Return To Board** after answer reveals.

## Player Flow

1. Open `/game-1/player` or the player link for the game room.
2. Enter a display name, team, and player password.
3. Join the team.
4. Resync clock if needed.
5. Buzz, wager, and submit Final Jeopardy responses when prompted.

## Display Flow

Open `/game-1/display` or the display link for the game room on a projector or shared screen. It is read-only and updates automatically as the moderator controls the match.

## Manual Verification Checklist

1. Set `CHEME_MODERATOR_PASSWORD`.
2. Compile the app.
3. Start the server.
4. Open `/` or `/games` and confirm there are no games before creation.
5. Log in as admin and create `game-1`.
6. Open `/game-1/moderator`, `/game-1/player`, and `/game-1/display`.
7. Log in as moderator and confirm the player password.
8. Join a team from the player page.
9. Create `game-2` from `/games` and confirm its moderator/player URLs load independently.
10. Start the game.
11. Select a clue, finish reading, buzz, judge, reveal, and return to board.
12. Test Daily Double, Final Jeopardy, and tie-breaker paths if they are enabled.

## Generative AI

Generative AI was used as an aid during the development process.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).

## References

[1] AIChE, "ChemE Jeopardy Competition," American Institute of Chemical Engineers. Accessed: May 4, 2026. [Online]. Available: https://www.aiche.org/community/awards/cheme-jeopardy-competition
