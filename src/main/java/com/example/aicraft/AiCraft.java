package com.example.aicraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

public class AiCraft implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("aicraft");

    private static final List<String> CHAT_LOG = new CopyOnWriteArrayList<>();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static Config config;
    public static KeyBinding stopKey;
    public static boolean aiActive = false;

    private static final List<JsonObject> actionQueue = new ArrayList<>();
    private static boolean waitingForApi = false;
    private static int stepDelayTimer = 0;

    @Override
    public void onInitializeClient() {
        config = Config.load();
        LOG.info("AICraft initialized successfully!");

        // 1. Регистрация клавиши остановки (по умолчанию 'K')
        stopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aicraft.stop",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.aicraft"
        ));

        // 2. Перехват сообщений чата (потокобезопасный)
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            addChat(message.getString());
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) addChat(message.getString());
        });

        // 3. Главный цикл тиков
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private static void addChat(String msg) {
        if (msg == null || msg.isBlank()) return;
        CHAT_LOG.add(msg);
        while (CHAT_LOG.size() > 50) {
            CHAT_LOG.remove(0);
        }
    }

    public static List<String> recentChat(int limit) {
        int size = CHAT_LOG.size();
        if (size == 0) return Collections.emptyList();
        int from = Math.max(0, size - limit);
        return new ArrayList<>(CHAT_LOG.subList(from, size));
    }

    private void onClientTick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;

        // Нажатие горячей клавиши включает/выключает ИИ
        if (stopKey.wasPressed()) {
            aiActive = !aiActive;
            actionQueue.clear();
            waitingForApi = false;
            String status = aiActive ? "§a[AICraft] ИИ активирован" : "§c[AICraft] ИИ остановлен";
            mc.player.sendMessage(Text.literal(status), false);
        }

        if (!aiActive) return;

        // Если очереди действий нет и мы не ждем ответа от API -> просим ИИ сделать следующий ход
        if (actionQueue.isEmpty() && !waitingForApi) {
            if (stepDelayTimer > 0) {
                stepDelayTimer--;
                return;
            }
            stepDelayTimer = config.step_delay_ticks;
            requestNextAction(mc);
            return;
        }

        // Выполнение текущего действия из очереди
        if (!actionQueue.isEmpty()) {
            JsonObject action = actionQueue.remove(0);
            executeAction(mc, action);
        }
    }

    private void requestNextAction(MinecraftClient mc) {
        waitingForApi = true;
        JsonObject obs = Observation.build(mc);

        CompletableFuture.runAsync(() -> {
            try {
                String responseJson = callLlmapi(obs);
                mc.execute(() -> {
                    waitingForApi = false;
                    processLlmResponse(mc, responseJson);
                });
            } catch (Exception e) {
                LOG.error("API Error", e);
                mc.execute(() -> {
                    waitingForApi = false;
                    if (mc.player != null) {
                        mc.player.sendMessage(Text.literal("§c[AICraft API Error] " + e.getMessage()), false);
                    }
                });
            }
        });
    }

    private String callLlmapi(JsonObject obs) throws Exception {
        String endpoint;
        String body;

        boolean isAnthropic = "anthropic".equalsIgnoreCase(config.provider);
        if (isAnthropic) {
            endpoint = config.base_url + "/v1/messages";
            JsonObject payload = new JsonObject();
            payload.addProperty("model", config.model);
            payload.addProperty("max_tokens", config.max_tokens != null ? config.max_tokens : 1024);
            payload.addProperty("system", config.prompt);
            JsonArray messages = new JsonArray();
            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", obs.toString());
            messages.add(userMsg);
            payload.add("messages", messages);
            body = payload.toString();
        } else {
            endpoint = config.base_url + "/chat/completions";
            JsonObject payload = new JsonObject();
            payload.addProperty("model", config.model);
            JsonArray messages = new JsonArray();
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", config.prompt);
            messages.add(sysMsg);
            JsonObject userMsg = new JsonObject();