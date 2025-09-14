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

    private int timeLeft = 60;
    private boolean running = false;
    private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<>();

    @GetMapping("/status")
    public ClockState getStatus() {
        return new ClockState(timeLeft, running);
    }

    @PostMapping("/start")
    public void start() {
        running = true;
        broadcast();
    }

    @PostMapping("/stop")
    public void stop() {
        running = false;
        broadcast();
    }

    @PostMapping("/reset")
    public void reset() {
        boolean wasRunning = running;
        timeLeft = 60;
        running = false; // temporarily stop
        broadcast();

        if (wasRunning) {
            // restart after 1 second delay
            new Thread(() -> {
                try {
                    Thread.sleep(1000); // 1 second pause
                    running = true;
                    broadcast();
                } catch (InterruptedException ignored) {}
            }).start();
        }
    }


    // Tick once per second (server side only)
    @Scheduled(fixedRate = 1000)
    public void tick() {
        if (running && timeLeft > 0) {
            timeLeft--;
            if (timeLeft == 0) {
                running = false;
            }
            broadcast();
        }
    }

    @GetMapping("/stream")
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        try {
            emitter.send(getStatus()); // send initial state
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

    record ClockState(int timeLeft, boolean running) {}
}