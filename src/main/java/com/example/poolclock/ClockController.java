package com.example.poolclock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@RestController
@RequestMapping("/clock")
public class ClockController {

    // Shot clock (seconds)
    private int shotTimeLeft = 60;
    private boolean shotRunning = false;

    // Game clock (20 minutes = 1200s)
    private int gameTimeLeft = 20 * 60;
    private boolean gameRunning = false;

    private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<>();

    // --- Shot clock controls ---
    @PostMapping("/start")
    public void start() {
        shotRunning = true;
        gameRunning = true; // start game clock when first started
        broadcast();
    }

    @PostMapping("/stop")
    public void stop() {
        shotRunning = false;
        gameRunning = false;
        broadcast();
    }

    @PostMapping("/reset")
    public void reset() {
        boolean wasRunning = shotRunning;
        shotTimeLeft = 60;
        shotRunning = false; // temporarily stop
        broadcast();

        if (wasRunning) {
            new Thread(() -> {
                try {
                    Thread.sleep(1000); // 1 second pause
                    shotRunning = true;
                    broadcast();
                } catch (InterruptedException ignored) {}
            }).start();
        }
    }

    // --- Game clock controls ---
    @PostMapping("/game/reset")
    public void resetGame() {
        gameTimeLeft = 20 * 60;
        gameRunning = false;
        broadcast();
    }

    @GetMapping("/status")
    public ClockState getStatus() {
        return new ClockState(shotTimeLeft, shotRunning, gameTimeLeft, gameRunning);
    }

    // Tick once per second
    @Scheduled(fixedRate = 1000)
    public void tick() {
        // Shot clock
        if (shotRunning && shotTimeLeft > 0) {
            shotTimeLeft--;
            if (shotTimeLeft == 0) {
                shotRunning = false;
            }
        }

        // Game clock
        if (gameRunning && gameTimeLeft > 0) {
            gameTimeLeft--;
            if (gameTimeLeft == 0) {
                gameRunning = false;
            }
        }

        broadcast();
    }

    @GetMapping("/stream")
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        try {
            emitter.send(getStatus()); // initial state
        } catch (IOException ignored) {}
        return emitter;
    }

    private void broadcast() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(getStatus());
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    record ClockState(int shotTimeLeft, boolean shotRunning,
                      int gameTimeLeft, boolean gameRunning) {}
}
