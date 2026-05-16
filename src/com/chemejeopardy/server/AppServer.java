/*
 * File: AppServer.java
 * Description: Embedded HTTP server, route registry, static asset server, and SSE broadcaster.
 * Author: Arturo Arias
 * Last updated: 2026-05-15
 */
package com.chemejeopardy.server;

import com.chemejeopardy.game.GameEngine;
import com.chemejeopardy.util.Json;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Adapts HTTP requests into calls on one of several GameEngine instances.
 *
 * <p>Each hosted game has its own rules engine, authentication manager, and SSE hubs.
 * The first URL segment selects the game, so /game-1/player and /game-2/player can
 * run in parallel without sharing runtime state.</p>
 */
public final class AppServer {
    /** Game IDs become URL path segments, so keep them simple and predictable. */
    private static final Pattern GAME_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,38}");

    /** Upper bound for browser-uploaded JSON question sets. */
    private static final int MAX_UPLOAD_BYTES = 1_000_000;

    /** Repository or container working directory. */
    private final Path rootDir;

    /** Static asset directory served to browsers. */
    private final Path publicDir;

    /** Startup question source used as the template for newly created games. */
    private final Path defaultDefinitionPath;

    /** Master auth protects game-instance creation. */
    private final AuthManager masterAuthManager;

    /** Active game sessions keyed by URL slug. */
    private final Map<String, GameSession> games = new ConcurrentHashMap<>();

    /** JDK embedded HTTP server. */
    private final HttpServer server;

    /**
     * Creates and configures the embedded server.
     */
    public AppServer(
            Path rootDir,
            Path defaultDefinitionPath,
            Path publicDir,
            int port,
            String moderatorPassword) throws IOException {
        this.rootDir = rootDir.toAbsolutePath().normalize();
        this.publicDir = publicDir.toAbsolutePath().normalize();
        this.defaultDefinitionPath = defaultDefinitionPath.toAbsolutePath().normalize();
        this.masterAuthManager = new AuthManager(moderatorPassword, "", "cheme_master_session");
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newCachedThreadPool());
        registerRoutes();
    }

    /**
     * Starts accepting HTTP requests.
     */
    public void start() {
        server.start();
    }

    /**
     * Stops accepting requests after the requested delay.
     */
    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
    }

    /**
     * Registers a single dispatcher so dynamic game IDs can be resolved at request time.
     */
    private void registerRoutes() {
        server.createContext("/", this::handleRequest);
    }

    /**
     * Top-level request dispatcher for master, static, and per-game routes.
     */
    private void handleRequest(HttpExchange exchange) throws IOException {
        String path = cleanPath(exchange.getRequestURI().getPath());
        if (path.equals("/") || path.equals("/games")) {
            servePage(exchange, "games.html");
            return;
        }
        if (path.startsWith("/assets")) {
            serveStatic(exchange);
            return;
        }
        if (path.equals("/player") || path.equals("/moderator") || path.equals("/display")) {
            redirect(exchange, "/games");
            return;
        }
        if (path.equals("/api/games") || path.equals("/api/games/update") || path.startsWith("/api/master")) {
            handleMasterRoute(exchange, path);
            return;
        }

        GameRoute route = resolveGameRoute(path);
        if (route.session == null) {
            notFound(exchange);
            return;
        }
        handleGameRoute(exchange, route.session, route.localPath);
    }

    /**
     * Handles the master game-instance API.
     */
    private void handleMasterRoute(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/api/games")) {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 200, gamesListPayload());
                return;
            }
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleCreateGame(exchange);
                return;
            }
            methodNotAllowed(exchange, "GET, POST");
            return;
        }
        if (path.equals("/api/games/update")) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                methodNotAllowed(exchange, "POST");
                return;
            }
            handleUpdateGame(exchange);
            return;
        }
        if (path.equals("/api/master/auth-status")) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                methodNotAllowed(exchange, "GET");
                return;
            }
            Map<String, Object> response = ok("Master status loaded.");
            response.put("authenticated", masterAuthManager.isModeratorAuthenticated(exchange));
            writeJson(exchange, 200, response);
            return;
        }
        if (path.equals("/api/master/login")) {
            handleMasterLogin(exchange);
            return;
        }
        if (path.equals("/api/master/logout")) {
            handleMasterLogout(exchange);
            return;
        }
        notFound(exchange);
    }

    /**
     * Handles game-specific pages and APIs after a game session has been selected.
     */
    private void handleGameRoute(HttpExchange exchange, GameSession session, String localPath) throws IOException {
        switch (localPath) {
            case "/" -> redirect(exchange, "/" + session.id + "/player");
            case "/player" -> servePage(exchange, "player.html");
            case "/moderator" -> servePage(exchange, "moderator.html");
            case "/display" -> servePage(exchange, "display.html");
            case "/api/events" -> handleEvents(exchange, session, false);
            case "/api/mod/events" -> handleEvents(exchange, session, true);
            case "/api/state" -> writeJson(exchange, 200, publicState(session));
            case "/api/mod/auth-status" -> handleModeratorAuthStatus(exchange, session);
            case "/api/mod/login" -> handleModeratorLogin(exchange, session);
            case "/api/mod/logout" -> handleModeratorLogout(exchange, session);
            case "/api/mod/state" -> {
                if (requireModerator(exchange, session)) {
                    writeJson(exchange, 200, moderatorState(session));
                }
            }
            case "/api/time-sync" -> writeJson(exchange, 200, session.engine.moderatorTime());
            case "/api/player/join" -> handleFormPost(exchange, form -> {
                if (!session.authManager.isPlayerJoinPasswordSet()) {
                    return error("The moderator needs to set the player password before players can join.");
                }
                if (!session.authManager.authenticatePlayerJoin(form.getOrDefault("joinPassword", ""))) {
                    return error("That player password is not correct.");
                }
                return session.engine.joinPlayer(
                        form.getOrDefault("teamId", ""),
                        form.getOrDefault("displayName", ""));
            });
            case "/api/player/sync" -> handleFormPost(exchange, form ->
                    session.engine.registerSync(
                            form.getOrDefault("playerId", ""),
                            form.getOrDefault("sessionKey", ""),
                            parseLong(form.get("clientSentAt")),
                            parseLong(form.get("clientReceivedAt"))));
            case "/api/player/buzz" -> handleFormPost(exchange, form ->
                    session.engine.submitBuzz(
                            form.getOrDefault("playerId", ""),
                            form.getOrDefault("sessionKey", ""),
                            parseLong(form.get("syncedTimestamp"))));
            case "/api/player/daily-wager" -> handleFormPost(exchange, form ->
                    session.engine.setDailyWager(
                            form.getOrDefault("teamId", ""),
                            parseInt(form.get("wager")),
                            form.getOrDefault("playerId", ""),
                            form.getOrDefault("sessionKey", ""),
                            false));
            case "/api/player/final-wager" -> handleFormPost(exchange, form ->
                    session.engine.submitFinalWager(
                            form.getOrDefault("playerId", ""),
                            form.getOrDefault("sessionKey", ""),
                            parseInt(form.get("wager"))));
            case "/api/player/final-response" -> handleFormPost(exchange, form ->
                    session.engine.submitFinalResponse(
                            form.getOrDefault("playerId", ""),
                            form.getOrDefault("sessionKey", ""),
                            form.getOrDefault("response", "")));
            case "/api/mod/definition" -> handleUpdateDefinition(exchange, session);
            case "/api/mod/load-file" -> handleLoadDefinitionFile(exchange, session);
            case "/api/mod/upload-definition" -> handleUploadDefinition(exchange, session);
            case "/api/mod/player-password" -> handlePlayerPasswordChange(exchange, session);
            case "/api/mod/reset" -> handleModeratorPostNoBody(exchange, session, session.engine::resetRuntime);
            case "/api/mod/start" -> handleModeratorPostNoBody(exchange, session, session.engine::startGame);
            case "/api/mod/select-clue" -> handleModeratorFormPost(exchange, session, form ->
                    session.engine.selectClue(form.getOrDefault("clueId", "")));
            case "/api/mod/finish-reading" -> handleModeratorPostNoBody(exchange, session, session.engine::finishReading);
            case "/api/mod/judge-correct" -> handleModeratorPostNoBody(exchange, session, () -> session.engine.judgeCurrent(true));
            case "/api/mod/judge-incorrect" -> handleModeratorPostNoBody(exchange, session, () -> session.engine.judgeCurrent(false));
            case "/api/mod/continue" -> handleModeratorPostNoBody(exchange, session, session.engine::continueAfterReveal);
            case "/api/mod/daily-wager" -> handleModeratorFormPost(exchange, session, form ->
                    session.engine.setDailyWager(
                            form.getOrDefault("teamId", ""),
                            parseInt(form.get("wager")),
                            "",
                            "",
                            true));
            case "/api/mod/start-daily-double" -> handleModeratorPostNoBody(exchange, session, session.engine::startDailyDouble);
            case "/api/mod/adjust-score" -> handleModeratorFormPost(exchange, session, form ->
                    session.engine.adjustScore(form.getOrDefault("teamId", ""), parseInt(form.get("delta"))));
            case "/api/mod/start-final-wager" -> handleModeratorPostNoBody(exchange, session, session.engine::startFinalWager);
            case "/api/mod/reveal-final-clue" -> handleModeratorPostNoBody(exchange, session, session.engine::revealFinalClue);
            case "/api/mod/start-tiebreaker" -> handleModeratorPostNoBody(exchange, session, session.engine::startTieBreaker);
            case "/api/mod/next-tiebreaker" -> handleModeratorPostNoBody(exchange, session, session.engine::nextTieBreakerClue);
            default -> notFound(exchange);
        }
    }

    /**
     * Creates a new runtime game from the master page.
     */
    private void handleCreateGame(HttpExchange exchange) throws IOException {
        if (!requireMaster(exchange)) {
            return;
        }
        Map<String, Object> payload = readRequestMap(exchange);
        String requestedId = slugOrDefault(payloadString(payload, "gameId"));
        if (!isValidGameId(requestedId)) {
            writeJson(exchange, error("Use a game URL slug like game-2 or finals-room."));
            return;
        }
        if (isReservedPath(requestedId)) {
            writeJson(exchange, error("That game URL is reserved by the app."));
            return;
        }
        if (games.containsKey(requestedId)) {
            writeJson(exchange, error("That game already exists."));
            return;
        }
        String moderatorPassword = payloadString(payload, "moderatorPassword");
        if (moderatorPassword.isBlank()) {
            writeJson(exchange, error("Enter a moderator password for the new game."));
            return;
        }

        Path definitionPath = defaultDefinitionPath;
        String sourcePath = payloadString(payload, "sourcePath");
        String uploadedContent = payloadString(payload, "uploadedContent");
        String uploadedFileName = cleanFileName(payloadString(payload, "uploadedFileName"));
        boolean hasUpload = !uploadedContent.isBlank();
        try {
            if (!hasUpload && !sourcePath.isBlank()) {
                definitionPath = resolveExistingFile(sourcePath);
            }
            GameSession session = createGameSession(
                    requestedId,
                    payloadString(payload, "displayName").isBlank()
                            ? displayNameFromId(requestedId)
                            : cleanText(payloadString(payload, "displayName")),
                    definitionPath,
                    false,
                    moderatorPassword,
                    payloadString(payload, "playerPassword"));
            if (hasUpload) {
                if (uploadedContent.getBytes(StandardCharsets.UTF_8).length > MAX_UPLOAD_BYTES) {
                    writeJson(exchange, error("Uploaded question set is too large."));
                    return;
                }
                Object parsedDefinition = Json.parse(uploadedContent);
                Map<String, Object> loadResult = session.engine.loadQuestionSet(
                        parsedDefinition,
                        "uploaded file " + uploadedFileName);
                if (!Json.asBoolean(loadResult.get("ok"), false)) {
                    writeJson(exchange, loadResult);
                    return;
                }
                session.uploadedFileName = uploadedFileName;
                session.questionSourceLabel = "Uploaded file: " + uploadedFileName;
            }
            games.put(session.id, session);
            Map<String, Object> response = ok("Game created.");
            response.put("game", gameSummary(session));
            writeJson(exchange, 200, response);
        } catch (Exception ex) {
            writeJson(exchange, error("Unable to create game: " + ex.getMessage()));
        }
    }

    /**
     * Updates a game room's admin-facing title and URL slug.
     */
    private void handleUpdateGame(HttpExchange exchange) throws IOException {
        if (!requireMaster(exchange)) {
            return;
        }
        Map<String, Object> payload = readRequestMap(exchange);
        String currentId = cleanText(payloadString(payload, "currentGameId"));
        String requestedId = slugOrDefault(payloadString(payload, "gameId"));
        String displayName = cleanText(payloadString(payload, "displayName"));
        if (currentId.isBlank()) {
            writeJson(exchange, error("Choose a game to update."));
            return;
        }
        if (!isValidGameId(requestedId)) {
            writeJson(exchange, error("Use a game URL slug like game-2 or finals-room."));
            return;
        }
        if (isReservedPath(requestedId)) {
            writeJson(exchange, error("That game URL is reserved by the app."));
            return;
        }
        synchronized (games) {
            GameSession session = games.get(currentId);
            if (session == null) {
                writeJson(exchange, error("That game no longer exists."));
                return;
            }
            if (!requestedId.equals(currentId) && games.containsKey(requestedId)) {
                writeJson(exchange, error("Another game already uses that URL."));
                return;
            }
            if (displayName.isBlank()) {
                displayName = displayNameFromId(requestedId);
            }
            if (!requestedId.equals(currentId)) {
                games.remove(currentId);
                session.id = requestedId;
                games.put(requestedId, session);
            }
            session.displayName = displayName;
            broadcastState(session);
            Map<String, Object> response = ok("Game updated.");
            response.put("game", gameSummary(session));
            writeJson(exchange, 200, response);
        }
    }

    /**
     * Authenticates the master game manager.
     */
    private void handleMasterLogin(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        Map<String, String> form = parseForm(readBody(exchange));
        if (!masterAuthManager.authenticateModerator(form.getOrDefault("password", ""))) {
            writeJson(exchange, 401, error("Master password is not correct."));
            return;
        }
        String token = masterAuthManager.createModeratorSession();
        exchange.getResponseHeaders().add("Set-Cookie", masterAuthManager.moderatorSessionCookie(token));
        writeJson(exchange, 200, ok("Game manager unlocked."));
    }

    /**
     * Revokes the master manager session.
     */
    private void handleMasterLogout(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        masterAuthManager.revokeModeratorSession(exchange);
        exchange.getResponseHeaders().add("Set-Cookie", masterAuthManager.expiredModeratorSessionCookie());
        writeJson(exchange, 200, ok("Game manager locked."));
    }

    /**
     * Reports whether the current browser already has a moderator session for this game.
     */
    private void handleModeratorAuthStatus(HttpExchange exchange, GameSession session) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        Map<String, Object> response = ok("Moderator status loaded.");
        response.put("authenticated", session.authManager.isModeratorAuthenticated(exchange));
        writeJson(exchange, 200, response);
    }

    /**
     * Authenticates a game moderator and issues a game-scoped session cookie.
     */
    private void handleModeratorLogin(HttpExchange exchange, GameSession session) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        Map<String, String> form = parseForm(readBody(exchange));
        if (!session.authManager.authenticateModerator(form.getOrDefault("password", ""))) {
            writeJson(exchange, 401, error("Moderator password is not correct."));
            return;
        }
        String token = session.authManager.createModeratorSession();
        exchange.getResponseHeaders().add("Set-Cookie", session.authManager.moderatorSessionCookie(token));
        writeJson(exchange, 200, ok("Moderator unlocked."));
    }

    /**
     * Revokes the game moderator session cookie.
     */
    private void handleModeratorLogout(HttpExchange exchange, GameSession session) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        session.authManager.revokeModeratorSession(exchange);
        exchange.getResponseHeaders().add("Set-Cookie", session.authManager.expiredModeratorSessionCookie());
        writeJson(exchange, 200, ok("Moderator locked."));
    }

    /**
     * Updates the password that players must enter before joining a team in one game.
     */
    private void handlePlayerPasswordChange(HttpExchange exchange, GameSession session) throws IOException {
        if (!requireModerator(exchange, session)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        Map<String, String> form = parseForm(readBody(exchange));
        String password = form.getOrDefault("password", "");
        if (password.isBlank()) {
            writeJson(exchange, error("Enter a player password before saving."));
            return;
        }
        session.authManager.setPlayerJoinPassword(password);
        broadcastState(session);
        writeJson(exchange, ok("Player password saved. Share it only with this game's players."));
    }

    /**
     * Saves game settings and team names from the moderator setup screen.
     */
    private void handleUpdateDefinition(HttpExchange exchange, GameSession session) throws IOException {
        if (!requireModerator(exchange, session)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        try {
            String body = readBody(exchange);
            writeJson(exchange, session.engine.updateDefinition(Json.parse(body)));
        } catch (Exception ex) {
            writeJson(exchange, 400, error("Unable to save the definition: " + ex.getMessage()));
        }
    }

    /**
     * Loads a question set from a server-side JSON file path.
     */
    private void handleLoadDefinitionFile(HttpExchange exchange, GameSession session) throws IOException {
        if (!requireModerator(exchange, session)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        Map<String, String> form = parseForm(readBody(exchange));
        try {
            Path source = resolveExistingFile(form.getOrDefault("path", ""));
            Object parsed = Json.parse(Files.readString(source, StandardCharsets.UTF_8));
            String label = displayPath(source);
            Map<String, Object> result = session.engine.loadQuestionSet(parsed, label);
            if (Json.asBoolean(result.get("ok"), false)) {
                session.questionSourceLabel = label;
                session.uploadedFileName = "";
                result.put("questionSource", session.questionSourceMap());
                broadcastState(session);
            }
            writeJson(exchange, result);
        } catch (Exception ex) {
            writeJson(exchange, error("Unable to load question file: " + ex.getMessage()));
        }
    }

    /**
     * Loads a browser-uploaded JSON file into memory for this game only.
     */
    private void handleUploadDefinition(HttpExchange exchange, GameSession session) throws IOException {
        if (!requireModerator(exchange, session)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        String body = readBody(exchange);
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_UPLOAD_BYTES * 2) {
            writeJson(exchange, error("Uploaded question set is too large."));
            return;
        }
        try {
            Map<String, Object> payload = Json.asObject(Json.parse(body));
            String fileName = cleanFileName(Json.asString(payload.get("fileName"), "uploaded-game.json"));
            String content = Json.asString(payload.get("content"), "");
            if (content.isBlank()) {
                writeJson(exchange, error("Choose a JSON game file before uploading."));
                return;
            }
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_UPLOAD_BYTES) {
                writeJson(exchange, error("Uploaded question set is too large."));
                return;
            }
            boolean replacedUpload = !session.uploadedFileName.isBlank();
            Object parsedDefinition = Json.parse(content);
            Map<String, Object> result = session.engine.loadQuestionSet(parsedDefinition, "uploaded file " + fileName);
            if (Json.asBoolean(result.get("ok"), false)) {
                session.uploadedFileName = fileName;
                session.questionSourceLabel = "Uploaded file: " + fileName;
                if (replacedUpload) {
                    result.put("message", result.get("message") + " Previous uploaded file replaced.");
                }
                result.put("replacedUpload", replacedUpload);
                result.put("questionSource", session.questionSourceMap());
                broadcastState(session);
            }
            writeJson(exchange, result);
        } catch (Exception ex) {
            writeJson(exchange, error("Unable to load uploaded JSON: " + ex.getMessage()));
        }
    }

    /**
     * Serves a fixed HTML page from the public directory.
     */
    private void servePage(HttpExchange exchange, String fileName) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        Path target = publicDir.resolve(fileName).normalize();
        if (!target.startsWith(publicDir) || !Files.exists(target)) {
            notFound(exchange);
            return;
        }
        byte[] content = Files.readAllBytes(target);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(content);
        }
    }

    /**
     * Serves static assets while preventing path traversal outside publicDir.
     */
    private void serveStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String relative = path.startsWith("/") ? path.substring(1) : path;
        Path target = publicDir.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(publicDir) || !Files.exists(target) || Files.isDirectory(target)) {
            notFound(exchange);
            return;
        }
        byte[] content = Files.readAllBytes(target);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(target));
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(content);
        }
    }

    /**
     * Opens a server-sent-events stream for live UI updates.
     */
    private void handleEvents(HttpExchange exchange, GameSession session, boolean moderatorStream) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        if (moderatorStream && !requireModerator(exchange, session)) {
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache");
        headers.set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        SseClient client = new SseClient(exchange);
        if (moderatorStream) {
            session.moderatorSseHub.add(client);
            client.send(Json.stringify(moderatorState(session)));
        } else {
            session.publicSseHub.add(client);
            client.send(Json.stringify(publicState(session)));
        }
    }

    /**
     * Handles protected or unprotected POST routes without a request body.
     */
    private void handlePostNoBody(HttpExchange exchange, ThrowingSupplier<Map<String, Object>> action) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        writeJson(exchange, action.get());
    }

    /**
     * Handles form-encoded POST routes.
     */
    private void handleFormPost(HttpExchange exchange, ThrowingFunction<Map<String, String>, Map<String, Object>> action) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        Map<String, String> form = parseForm(readBody(exchange));
        writeJson(exchange, action.apply(form));
    }

    /**
     * Security proxy wrapper for moderator POST actions.
     */
    private void handleModeratorPostNoBody(
            HttpExchange exchange,
            GameSession session,
            ThrowingSupplier<Map<String, Object>> action) throws IOException {
        if (!requireModerator(exchange, session)) {
            return;
        }
        handlePostNoBody(exchange, action);
    }

    /**
     * Security proxy wrapper for moderator form POST actions.
     */
    private void handleModeratorFormPost(
            HttpExchange exchange,
            GameSession session,
            ThrowingFunction<Map<String, String>, Map<String, Object>> action) throws IOException {
        if (!requireModerator(exchange, session)) {
            return;
        }
        handleFormPost(exchange, action);
    }

    /**
     * Stops a protected request unless the master manager is authenticated.
     */
    private boolean requireMaster(HttpExchange exchange) throws IOException {
        if (masterAuthManager.isModeratorAuthenticated(exchange)) {
            return true;
        }
        writeJson(exchange, 401, error("Master login required."));
        return false;
    }

    /**
     * Stops a protected request unless the selected game moderator is authenticated.
     */
    private boolean requireModerator(HttpExchange exchange, GameSession session) throws IOException {
        if (session.authManager.isModeratorAuthenticated(exchange)) {
            return true;
        }
        writeJson(exchange, 401, error("Moderator login required."));
        return false;
    }

    /**
     * Combines public game state with game metadata and public security metadata.
     */
    private Map<String, Object> publicState(GameSession session) {
        Map<String, Object> state = session.engine.getPublicState();
        state.put("gameInstance", session.publicMap());
        state.put("security", session.authManager.publicSecurityState());
        return state;
    }

    /**
     * Combines moderator game state with game metadata and moderator security metadata.
     */
    private Map<String, Object> moderatorState(GameSession session) {
        Map<String, Object> state = session.engine.getModeratorState();
        state.put("gameInstance", session.publicMap());
        state.put("questionSource", session.questionSourceMap());
        state.put("security", session.authManager.moderatorSecurityState());
        return state;
    }

    /**
     * Broadcasts the latest state for one game to its live browser streams.
     */
    private void broadcastState(GameSession session) {
        session.publicSseHub.broadcast(Json.stringify(publicState(session)));
        session.moderatorSseHub.broadcast(Json.stringify(moderatorState(session)));
    }

    /**
     * Builds the public game list payload.
     */
    private Map<String, Object> gamesListPayload() {
        Map<String, Object> payload = ok("Games loaded.");
        List<Map<String, Object>> summaries = games.values().stream()
                .sorted((left, right) -> left.id.compareTo(right.id))
                .map(this::gameSummary)
                .toList();
        payload.put("games", summaries);
        return payload;
    }

    /**
     * Summarizes one game for the master view.
     */
    private Map<String, Object> gameSummary(GameSession session) {
        Map<String, Object> state = session.engine.getPublicState();
        Map<String, Object> definition = Json.asObject(state.get("definition"));
        Map<String, Object> config = Json.asObject(definition.get("config"));
        Map<String, Object> game = Json.asObject(state.get("game"));
        int maxPlayersPerTeam = Json.asInt(config.get("maxPlayersPerTeam"), 1);
        int joined = 0;
        int capacity = 0;
        for (Object rawTeam : Json.asList(game.get("teams"))) {
            Map<String, Object> team = Json.asObject(rawTeam);
            if (!Json.asBoolean(team.get("active"), true)) {
                continue;
            }
            capacity += maxPlayersPerTeam;
            joined += Json.asList(team.get("players")).size();
        }

        Map<String, Object> summary = session.publicMap();
        summary.put("title", session.displayName);
        summary.put("definitionTitle", Json.asString(definition.get("title"), ""));
        summary.put("phase", Json.asString(game.get("phase"), "SETUP"));
        summary.put("round", Json.asString(game.get("round"), "SINGLE"));
        summary.put("joinedPlayers", joined);
        summary.put("playerCapacity", capacity);
        summary.put("questionSource", session.questionSourceMap());
        return summary;
    }

    /**
     * Creates a new game session around a GameEngine and game-scoped AuthManager.
     */
    private GameSession createGameSession(
            String id,
            String displayName,
            Path definitionPath,
            boolean persistDefinitionChanges,
            String moderatorPassword,
            String initialPlayerJoinPassword) {
        GameEngine engine = new GameEngine(definitionPath, persistDefinitionChanges);
        AuthManager authManager = new AuthManager(
                moderatorPassword,
                initialPlayerJoinPassword,
                "cheme_mod_" + id.replace("-", "_"));
        GameSession session = new GameSession(
                id,
                displayName,
                engine,
                authManager,
                displayPath(definitionPath));
        engine.setChangeListener(() -> broadcastState(session));
        return session;
    }

    /**
     * Resolves a request path into a selected game and local route path.
     */
    private GameRoute resolveGameRoute(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        String gameId = slash < 0 ? trimmed : trimmed.substring(0, slash);
        String localPath = slash < 0 ? "/" : "/" + trimmed.substring(slash + 1);
        return new GameRoute(games.get(gameId), localPath);
    }

    /**
     * Resolves a server-side question file. Relative paths are resolved from the app root.
     */
    private Path resolveExistingFile(String configuredPath) {
        String cleaned = cleanText(configuredPath);
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("Enter a JSON file path.");
        }
        Path path = Path.of(cleaned);
        Path resolved = path.isAbsolute()
                ? path.normalize()
                : rootDir.resolve(path).normalize();
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("File does not exist: " + cleaned);
        }
        if (Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("Path is a directory: " + cleaned);
        }
        return resolved;
    }

    /**
     * Converts a path to a compact display label when it is inside the app root.
     */
    private String displayPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            if (absolute.startsWith(rootDir)) {
                return rootDir.relativize(absolute).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
            // Fall through to the absolute path label.
        }
        return absolute.toString();
    }

    /**
     * Normalizes a requested game slug or chooses the next available game-N id.
     */
    private String slugOrDefault(String requested) {
        String cleaned = cleanText(requested).toLowerCase()
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (!cleaned.isBlank()) {
            return cleaned;
        }
        int index = games.size() + 1;
        while (games.containsKey("game-" + index)) {
            index++;
        }
        return "game-" + index;
    }

    /**
     * Validates a game id URL segment.
     */
    private boolean isValidGameId(String id) {
        return GAME_ID_PATTERN.matcher(id).matches();
    }

    /**
     * Prevents collisions with app-level routes.
     */
    private boolean isReservedPath(String id) {
        return id.equals("api")
                || id.equals("assets")
                || id.equals("games")
                || id.equals("player")
                || id.equals("moderator")
                || id.equals("display");
    }

    /**
     * Gives a generated display name to a game id.
     */
    private String displayNameFromId(String id) {
        String[] pieces = id.split("-");
        StringBuilder builder = new StringBuilder();
        for (String piece : pieces) {
            if (piece.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(piece.charAt(0))).append(piece.substring(1));
        }
        return builder.isEmpty() ? id : builder.toString();
    }

    /**
     * Convenience payload builder for successful operations.
     */
    private Map<String, Object> ok(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("message", message);
        return payload;
    }

    /**
     * Sends a browser redirect.
     */
    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    /**
     * Sends a 405 response with an Allow header.
     */
    private void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        exchange.sendResponseHeaders(405, -1);
        exchange.close();
    }

    /**
     * Sends a 404 response.
     */
    private void notFound(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    /**
     * Writes JSON and chooses status by the payload ok flag.
     */
    private void writeJson(HttpExchange exchange, Map<String, Object> payload) throws IOException {
        int status = Json.asBoolean(payload.get("ok"), false) ? 200 : 400;
        writeJson(exchange, status, payload);
    }

    /**
     * Writes JSON with an explicit HTTP status code.
     */
    private void writeJson(HttpExchange exchange, int status, Map<String, Object> payload) throws IOException {
        byte[] content = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, content.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(content);
        }
    }

    /**
     * Convenience payload builder for validation and authorization errors.
     */
    private Map<String, Object> error(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("message", message);
        return payload;
    }

    /**
     * Reads the full request body as UTF-8 text.
     */
    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Reads either JSON or form-encoded request data into a simple object map.
     */
    private Map<String, Object> readRequestMap(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null && contentType.toLowerCase().contains("application/json")) {
            return Json.asObject(Json.parse(body));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.putAll(parseForm(body));
        return payload;
    }

    /**
     * Reads a string field from a request payload.
     */
    private String payloadString(Map<String, Object> payload, String key) {
        return cleanText(Json.asString(payload.get(key), ""));
    }

    /**
     * Parses application/x-www-form-urlencoded request bodies.
     */
    private Map<String, String> parseForm(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return form;
        }
        for (String pair : body.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] pieces = pair.split("=", 2);
            String key = decode(pieces[0]);
            String value = pieces.length > 1 ? decode(pieces[1]) : "";
            form.put(key, value);
        }
        return form;
    }

    /**
     * URL-decodes one form key or value.
     */
    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /**
     * Parses an integer form value with a safe fallback.
     */
    private int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * Parses a long form value with a safe fallback.
     */
    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /**
     * Removes accidental path pieces from browser file names.
     */
    private String cleanFileName(String fileName) {
        String cleaned = cleanText(fileName).replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        cleaned = cleaned.replaceAll("[^A-Za-z0-9._-]+", "_");
        return cleaned.isBlank() ? "uploaded-game.json" : cleaned;
    }

    /**
     * Normalizes display text and form fields.
     */
    private String cleanText(String value) {
        return value == null ? "" : value.replace("\r", "").trim();
    }

    /**
     * Normalizes request paths by removing trailing slashes except for root.
     */
    private String cleanPath(String path) {
        String cleaned = path == null || path.isBlank() ? "/" : path;
        while (cleaned.length() > 1 && cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    /**
     * Minimal content type detection for static assets.
     */
    private String contentType(Path target) {
        String name = target.getFileName().toString().toLowerCase();
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        return "application/octet-stream";
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws IOException;
    }

    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T value) throws IOException;
    }

    private static final class GameRoute {
        private final GameSession session;
        private final String localPath;

        private GameRoute(GameSession session, String localPath) {
            this.session = session;
            this.localPath = localPath;
        }
    }

    private static final class GameSession {
        private String id;
        private String displayName;
        private final GameEngine engine;
        private final AuthManager authManager;
        private final SseHub publicSseHub = new SseHub();
        private final SseHub moderatorSseHub = new SseHub();
        private volatile String questionSourceLabel;
        private volatile String uploadedFileName = "";

        private GameSession(
                String id,
                String displayName,
                GameEngine engine,
                AuthManager authManager,
                String questionSourceLabel) {
            this.id = id;
            this.displayName = displayName;
            this.engine = engine;
            this.authManager = authManager;
            this.questionSourceLabel = questionSourceLabel;
        }

        private Map<String, Object> publicMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("displayName", displayName);
            map.put("basePath", "/" + id);
            map.put("playerUrl", "/" + id + "/player");
            map.put("moderatorUrl", "/" + id + "/moderator");
            map.put("displayUrl", "/" + id + "/display");
            return map;
        }

        private Map<String, Object> questionSourceMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("label", questionSourceLabel);
            map.put("uploadedFileName", uploadedFileName.isBlank() ? null : uploadedFileName);
            map.put("hasRuntimeUpload", !uploadedFileName.isBlank());
            return map;
        }
    }

    private static final class SseHub {
        /** Thread-safe list because broadcasts can overlap with connection setup. */
        private final List<SseClient> clients = new CopyOnWriteArrayList<>();

        private void add(SseClient client) {
            clients.add(client);
        }

        private void broadcast(String json) {
            List<SseClient> deadClients = new ArrayList<>();
            for (SseClient client : clients) {
                if (!client.send(json)) {
                    deadClients.add(client);
                }
            }
            clients.removeAll(deadClients);
        }
    }

    private static final class SseClient {
        /** Open HTTP exchange whose response body receives SSE events. */
        private final HttpExchange exchange;

        private SseClient(HttpExchange exchange) {
            this.exchange = exchange;
        }

        private boolean send(String json) {
            try {
                String payload = "event: state\ndata: " + json.replace("\n", "") + "\n\n";
                exchange.getResponseBody().write(payload.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                return true;
            } catch (IOException ex) {
                try {
                    exchange.close();
                } catch (Exception ignored) {
                }
                return false;
            }
        }
    }
}
