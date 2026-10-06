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
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", obs.toString());
            messages.add(userMsg);
            payload.add("messages", messages);

            if (config.temperature != null) payload.addProperty("temperature", config.temperature);
            if (config.max_tokens != null) payload.addProperty("max_tokens", config.max_tokens);
            body = payload.toString();
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(config.timeout_seconds))
                .header("Content-Type", "application/json");

        if (isAnthropic) {
            builder.header("x-api-key", config.api_key);
            builder.header("anthropic-version", "2023-06-01");
        } else {
            builder.header("Authorization", "Bearer " + config.api_key);
        }

        HttpRequest req = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private void processLlmResponse(MinecraftClient mc, String jsonResponse) {
        try {
            JsonObject root = JsonParser.parseString(jsonResponse).getAsJsonObject();
            String textContent = "";

            if (root.has("choices")) { // OpenAI
                textContent = root.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString();
            } else if (root.has("content")) { // Anthropic
                JsonArray arr = root.getAsJsonArray("content");
                textContent = arr.get(0).getAsJsonObject().get("text").getAsString();
            }

            // Очистка от возможных markdown ```json тегов
            textContent = textContent.replaceAll("```json", "").replaceAll("```", "").trim();
            JsonObject res = JsonParser.parseString(textContent).getAsJsonObject();

            if (res.has("say") && mc.player != null) {
                String say = res.get("say").getAsString();
                if (!say.isBlank()) {
                    mc.player.sendMessage(Text.literal("§b[AI]: " + say), false);
                }
            }

            if (res.has("actions")) {
                JsonArray acts = res.getAsJsonArray("actions");
                for (JsonElement e : acts) {
                    if (e.isJsonObject()) {
                        actionQueue.add(e.getAsJsonObject());
                    }
                }
            }

            if (res.has("done") && res.get("done").getAsBoolean()) {
                aiActive = false;
                if (mc.player != null) {
                    mc.player.sendMessage(Text.literal("§a[AICraft] Задача завершена."), false);
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to parse LLM answer", e);
        }
    }

    private void executeAction(MinecraftClient mc, JsonObject action) {
        if (mc.player == null || !action.has("type")) return;
        String type = action.get("type").getAsString();

        switch (type) {
            case "chat" -> {
                if (action.has("text") && mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendChatMessage(action.get("text").getAsString());
                }
            }
            case "hotbar" -> {
                if (action.has("slot")) {
                    int slot = action.get("slot").getAsInt() - 1;
                    if (slot >= 0 && slot < 9) {
                        mc.player.getInventory().selectedSlot = slot;
                    }
                }
            }
            case "look" -> {
                if (action.has("yaw")) mc.player.setYaw(action.get("yaw").getAsFloat());
                if (action.has("pitch")) mc.player.setPitch(action.get("pitch").getAsFloat());
            }
            case "look_at", "goto" -> {
                if (action.has("x") && action.has("z")) {
                    double x = action.get("x").getAsDouble();
                    double y = action.has("y") ? action.get("y").getAsDouble() : mc.player.getY();
                    double z = action.get("z").getAsDouble();

                    Vec3d target = new Vec3d(x + 0.5, y, z + 0.5);
                    Vec3d diff = target.subtract(mc.player.getPos());
                    double yaw = Math.toDegrees(Math.atan2(-diff.x, diff.z));
                    mc.player.setYaw((float) yaw);

                    if (type.equals("goto")) {
                        mc.player.setSprinting(true);
                        if (mc.player.horizontalCollision) {
                            Vec3d currentVelocity = mc.player.getVelocity();
                            mc.player.setVelocity(currentVelocity.x, 0.42, currentVelocity.z);
                        }
                    }
                }
            }
            case "jump" -> mc.player.jump();
            case "attack" -> {
                Entity target = mc.world.getOtherEntities(mc.player, mc.player.getBoundingBox().expand(4.0))
                        .stream().filter(e -> e instanceof LivingEntity)
                        .findFirst().orElse(null);
                if (target != null && mc.interactionManager != null) {
                    mc.interactionManager.attackEntity(mc.player, target);
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
            }
            case "mine" -> {
                if (action.has("x") && action.has("y") && action.has("z") && mc.interactionManager != null) {
                    BlockPos p = new BlockPos(action.get("x").getAsInt(), action.get("y").getAsInt(), action.get("z").getAsInt());
                    mc.interactionManager.updateBlockBreakingProgress(p, Direction.UP);
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
            }
            case "use" -> {
                if (mc.interactionManager != null) {
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                }
            }
        }
    }
}