package com.example.aicraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Locale;

/** Настройки: config/aicraft.json и системный промт config/aicraft_prompt.txt */
public final class Config {
    public String provider = "openai";
    public String base_url = "https://api.openai.com/v1";
    public String api_key = "";
    public String model = "PUT-MODEL-NAME-HERE";
    public Double temperature = null;
    public Integer max_tokens = null;
    public int timeout_seconds = 120;
    public int max_steps = 50;
    public int step_delay_ticks = 10;
    public String language = "Russian";
    public boolean http_api_enabled = false;
    public int http_port = 25580;
    public String http_token = "";
    public boolean allow_commands = false;
    public boolean safety = true;

    /** Текст системного промта (читается из aicraft_prompt.txt). */
    public String prompt = DEFAULT_PROMPT;
    /** Если конфиг не удалось прочитать - здесь текст ошибки. */
    public String loadError = "";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("aicraft.json");
    }

    public static Path promptFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("aicraft_prompt.txt");
    }

    public static Config load() {
        Config c = new Config();
        boolean needSave = false;
        Path f = file();
        if (Files.exists(f)) {
            try {
                String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                JsonObject o = JsonParser.parseString(text).getAsJsonObject();
                c.provider = str(o, "provider", c.provider).toLowerCase(Locale.ROOT);
                c.base_url = str(o, "base_url", c.base_url);
                c.api_key = str(o, "api_key", c.api_key);
                c.model = str(o, "model", c.model);
                c.temperature = dbl(o, "temperature");
                Double mt = dbl(o, "max_tokens");
                c.max_tokens = mt == null ? null : Integer.valueOf((int) Math.round(mt));
                c.timeout_seconds = integer(o, "timeout_seconds", c.timeout_seconds);
                c.max_steps = integer(o, "max_steps", c.max_steps);
                c.step_delay_ticks = integer(o, "step_delay_ticks", c.step_delay_ticks);
                c.language = str(o, "language", c.language);
                c.http_api_enabled = bool(o, "http_api_enabled", c.http_api_enabled);
                c.http_port = integer(o, "http_port", c.http_port);
                c.http_token = str(o, "http_token", c.http_token);
                c.allow_commands = bool(o, "allow_commands", c.allow_commands);
                c.safety = bool(o, "safety", c.safety);
            } catch (Exception e) {
                c.loadError = String.valueOf(e.getMessage());
                AiCraft.LOG.error("Cannot read " + f + ", using defaults", e);
            }
        } else {
            needSave = true;
        }

        if (!"anthropic".equals(c.provider)) {
            c.provider = "openai";
        }
        if (c.base_url.isEmpty()) {
            c.base_url = "anthropic".equals(c.provider) ? "https://api.anthropic.com" : "https://api.openai.com/v1";
        }
        while (c.base_url.endsWith("/")) {
            c.base_url = c.base_url.substring(0, c.base_url.length() - 1);
        }
        if (c.timeout_seconds < 5) c.timeout_seconds = 5;
        if (c.max_steps < 1) c.max_steps = 1;
        if (c.step_delay_ticks < 0) c.step_delay_ticks = 0;
        if (c.http_port < 1 || c.http_port > 65535) c.http_port = 25580;

        if (c.http_token.isEmpty()) {
            c.http_token = randomToken();
            needSave = true;
        }
        if (needSave && c.loadError.isEmpty()) {
            c.save();
        }
        c.loadPrompt();
        return c;
    }

    public void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("_help", "temperature and max_tokens: leave null to not send them. provider: openai or anthropic. Do not share this file: it contains your keys.");
            o.addProperty("provider", provider);
            o.addProperty("base_url", base_url);
            o.addProperty("api_key", api_key);
            o.addProperty("model", model);
            o.addProperty("temperature", temperature);
            o.addProperty("max_tokens", max_tokens);
            o.addProperty("timeout_seconds", timeout_seconds);
            o.addProperty("max_steps", max_steps);
            o.addProperty("step_delay_ticks", step_delay_ticks);
            o.addProperty("language", language);
            o.addProperty("http_api_enabled", http_api_enabled);
            o.addProperty("http_port", http_port);
            o.addProperty("http_token", http_token);
            o.addProperty("allow_commands", allow_commands);
            o.addProperty("safety", safety);
            Files.createDirectories(file().getParent());
            Files.write(file(), GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            AiCraft.LOG.error("Cannot write config", e);
        }
    }

    private void loadPrompt() {
        Path pf = promptFile();
        try {
            if (!Files.exists(pf)) {
                Files.createDirectories(pf.getParent());
                Files.write(pf, DEFAULT_PROMPT.getBytes(StandardCharsets.UTF_8));
                prompt = DEFAULT_PROMPT;
            } else {
                String t = new String(Files.readAllBytes(pf), StandardCharsets.UTF_8).trim();
                prompt = t.isEmpty() ? DEFAULT_PROMPT : t;
            }
        } catch (Exception e) {
            AiCraft.LOG.error("Cannot read prompt file", e);
            prompt = DEFAULT_PROMPT;
        }
    }

    private static String randomToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) return def;
        try {
            return e.getAsString().trim();
        } catch (Exception ex) {
            return def;
        }
    }

    private static Double dbl(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) return null;
        try {
            String s = e.getAsString().trim();
            if (s.isEmpty()) return null;
            return Double.valueOf(Double.parseDouble(s));
        } catch (Exception ex) {
            return null;
        }
    }

    private static int integer(JsonObject o, String k, int def) {
        Double d = dbl(o, k);
        return d == null ? def : (int) Math.round(d);
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) return def;
        try {
            return Boolean.parseBoolean(e.getAsString().trim());
        } catch (Exception ex) {
            return def;
        }
    }

    public static final String DEFAULT_PROMPT = """
You are the brain of a Minecraft Java Edition player. You control ONE character through a text interface.
Every turn you receive the RESULT of your previous actions and an OBSERVATION (compact JSON of what the character sees).
You answer with exactly ONE JSON object and NOTHING else: no markdown fences, no comments, no text outside the JSON.

Reply format:
{"say":"short message to the human","actions":[ ...actions... ],"done":false}

Rules:
- "say" is shown only to the human. Keep it short.
- "actions" run one after another, each with a timeout. If one fails, the rest are skipped and the reason is in the next RESULT.
- Prefer short batches (1-6 actions), then read the new OBSERVATION. Never assume an action worked - check.
- Set "done":true when the task is finished or impossible (explain in "say"); otherwise "done":false.
- Block, item and mob ids are shown without the "minecraft:" prefix.
- Coordinates are integer block coordinates. +x = east, -x = west, +z = south, -z = north, +y = up.
- yaw: 0 = looking south (+z), 90 = west (-x), 180 = north (-z), -90 = east (+x). pitch: -90 = straight up, 0 = horizon, 90 = straight down.
- 20 ticks = 1 second.

Observation fields: pos (exact), block (integer position of your feet), yaw, pitch, facing, hp, food, dimension, biome, time, daytime,
hand, slot (selected hotbar slot 1-9), hotbar, inv (item totals), armor, looking_at, standing_on, front [block at feet level, block at head level],
blocks ("id x y z" = interesting blocks nearby), entities (living mobs/players: type, dist, hp, at, hostile), drops (dropped items on the ground),
danger (warnings), chat (last chat lines), screen (an open GUI).

Actions:
{"type":"move","dir":"forward","ticks":20,"sprint":false,"jump":false}   dir: forward|back|left|right
{"type":"jump"}
{"type":"sneak","ticks":20}
{"type":"look","yaw":90,"pitch":0}                       either field may be omitted
{"type":"look_at","x":10,"y":64,"z":-3}                  look at the centre of that block
{"type":"goto","x":10,"y":64,"z":-3,"range":1.5}         walk there (y and range optional). Jumps over 1-block steps, stops before lava and cliffs. There is NO real pathfinding: walls and holes stop it.
{"type":"mine","x":10,"y":65,"z":-3}                     break ONE block. Aims and picks the best tool itself. The block must be within 4.5 blocks and visible: use goto first.
{"type":"place","x":10,"y":64,"z":-3,"slot":2}           place the block from hotbar slot 2 (1-9) at that EMPTY position; it must touch an existing solid block
{"type":"attack","target":"nearest"}                     "nearest" = closest hostile mob, or an entity type like "zombie" or "cow". Walks to it and fights until it dies.
{"type":"use","ticks":2}                                 hold right click: eat/drink (use about 40 ticks), open a door, press a button, use the held item. Optional x,y,z = look at that block first.
{"type":"hotbar","slot":1}
{"type":"wait","ticks":10}
{"type":"chat","text":"hello"}                           public chat message (commands starting with / may be disabled)

Tips:
- To pick up dropped items just walk into them (see "drops").
- Trees: stand next to the trunk and mine the lowest log first, then the ones above it.
- Never dig straight down and never walk into lava. Read "danger". Eat when food is low.
- If goto fails because of a wall, mine through it or go around with look/move.
- GUIs (chest, crafting table, furnace) are not supported and are closed automatically.

Examples (your whole reply is only the JSON):
{"say":"Going to the tree.","actions":[{"type":"goto","x":12,"y":64,"z":5,"range":2},{"type":"mine","x":12,"y":65,"z":6}],"done":false}
{"say":"A zombie is close, fighting.","actions":[{"type":"attack","target":"zombie"}],"done":false}
{"say":"I have the logs. Task finished.","actions":[],"done":true}
""";
}
