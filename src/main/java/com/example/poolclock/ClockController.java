package com.example.poolclock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@RestController
@RequestMapping("/clock")
public class ClockController {

    // Master key to delete/control any game
    private static final String MASTER_KEY = "RVPOOL"; // replace with a secure value

    // Store all games and emitters
    private final Map<String, GameClock> games = new ConcurrentHashMap<>();
    private final Map<String, String> gameKeys = new ConcurrentHashMap<>();
    private final Map<String, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    // === Create a new game ===
    @PostMapping("/create")
    public Map<String, Object> createGame(@RequestParam String gameId) {
        if (games.containsKey(gameId)) {
            return Map.of("error", "Game already exists");
        }

        // Generate a control key
        String controlKey = UUID.randomUUID().toString().substring(0, 8);
        GameClock clock = new GameClock(gameId);

        games.put(gameId, clock);
        gameKeys.put(gameId, controlKey);
        emitters.put(gameId, new CopyOnWriteArraySet<>());

        return Map.of(
                "gameId", gameId,
                "key", controlKey,
                "createdAt", Instant.now().toString()
        );
    }

    // === List all games ===
    @GetMapping("/list")
    public List<Map<String, String>> listGames() {
        List<Map<String, String>> list = new ArrayList<>();
        for (String gameId : games.keySet()) {
            list.add(Map.of("gameId", gameId));
        }
        return list;
    }

    // === Stream updates ===
    @GetMapping("/{gameId}/stream")
    public SseEmitter stream(@PathVariable String gameId) {
        SseEmitter emitter = new SseEmitter(0L); // never timeout
        emitters.computeIfAbsent(gameId, g -> new CopyOnWriteArraySet<>()).add(emitter);

        emitter.onCompletion(() -> emitters.get(gameId).remove(emitter));
        emitter.onTimeout(() -> emitters.get(gameId).remove(emitter));
        emitter.onError(e -> emitters.get(gameId).remove(emitter));

        GameClock clock = games.computeIfAbsent(gameId, GameClock::new);
        try {
            emitter.send(SseEmitter.event().data(clock.toMap()));
        } catch (IOException ignored) {}

        return emitter;
    }

    // === Control Endpoints ===
    @PostMapping("/{gameId}/start")
    public void start(@PathVariable String gameId, @RequestParam(required = false) String key) {
        if (!isAuthorized(gameId, key)) return;
        getOrCreate(gameId).start();
    }

    @PostMapping("/{gameId}/stop")
    public void stop(@PathVariable String gameId, @RequestParam(required = false) String key) {
        if (!isAuthorized(gameId, key)) return;
        getOrCreate(gameId).stop();
    }

    @PostMapping("/{gameId}/reset")
    public void resetShot(@PathVariable String gameId, @RequestParam(required = false) String key) {
        if (!isAuthorized(gameId, key)) return;
        getOrCreate(gameId).resetShot();
    }

    @PostMapping("/{gameId}/game/reset")
    public void resetGame(@PathVariable String gameId, @RequestParam(required = false) String key) {
        if (!isAuthorized(gameId, key)) return;
        getOrCreate(gameId).resetGame();
    }

    // --- Delete a game (supports master key) ---
    @PostMapping("/{gameId}/delete")
    public Map<String, String> deleteGame(@PathVariable String gameId,
                                          @RequestParam String key) {
        if (!isAuthorized(gameId, key)) {
            return Map.of("status", "error", "message", "Unauthorized");
        }

        // Notify all SSE clients the game is deleted
        Set<SseEmitter> gameEmitters = emitters.remove(gameId);
        if (gameEmitters != null) {
            for (SseEmitter emitter : gameEmitters) {
                try {
                    emitter.send("{\"deleted\":true}");
                } catch (IOException ignored) {}
                emitter.complete();
            }
        }

        games.remove(gameId);
        gameKeys.remove(gameId);

        return Map.of("status", "success", "message", "Game deleted");
    }

    // === Background updater ===
    @Scheduled(fixedRate = 1000)
    public void tick() {
        for (GameClock clock : games.values()) {
            if (clock.isRunning()) {
                clock.tick();
            }
        }
        broadcast();
    }

    // === Send updates to clients ===
    private void broadcast() {
        for (String gameId : emitters.keySet()) {
            GameClock clock = games.get(gameId);
            if (clock == null) continue;

            Set<SseEmitter> gameEmitters = emitters.get(gameId);
            for (SseEmitter emitter : gameEmitters) {
                try {
                    emitter.send(SseEmitter.event().data(clock.toMap()));
                } catch (IOException e) {
                    emitter.complete();
                    gameEmitters.remove(emitter);
                }
            }
        }
    }

    // === Authorization check (supports master key) ===
    private boolean isAuthorized(String gameId, String key) {
        if (key == null) return false;
        if (MASTER_KEY.equals(key)) return true; // master key bypass
        String stored = gameKeys.get(gameId);
        return stored != null && stored.equals(key);
    }

    private GameClock getOrCreate(String gameId) {
        return games.computeIfAbsent(gameId, GameClock::new);
    }

    // === Inner class: GameClock ===
    private static class GameClock {
        private final String gameId;
        private int shotTimeLeft = 60;
        private int gameTimeLeft = 20 * 60;
        private boolean running = false;

        public GameClock(String gameId) {
            this.gameId = gameId;
        }

        public void start() { running = true; }
        public void stop() { running = false; }
        public void resetShot() { shotTimeLeft = 60; }
        public void resetGame() { gameTimeLeft = 20 * 60; }

        public boolean isRunning() { return running; }

        public void tick() {
            if (shotTimeLeft > 0) shotTimeLeft--;
            if (gameTimeLeft > 0) gameTimeLeft--;
            if (shotTimeLeft == 0) running = false;
        }

        public Map<String, Object> toMap() {
            return Map.of(
                    "gameId", gameId,
                    "shotTimeLeft", shotTimeLeft,
                    "gameTimeLeft", gameTimeLeft,
                    "running", running
            );
        }
    }
}
