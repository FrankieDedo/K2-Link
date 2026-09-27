package k2.link;

import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * K2 Link (Minecraft 26.x): serves what the local player is doing as ONE flat JSON object on
 * {@code http://127.0.0.1:34874/state}, and each hotbar slot's picture on {@code /hotbar/1..9},
 * for K2's Minecraft game profile on the DisplayPad.
 *
 * <p><b>Read-only.</b> Nothing here changes the game: the pad presses the player's own key binds,
 * and this only tells it what to light up. It is client-only, so it works on any server.</p>
 *
 * <p><b>Threads.</b> Game state is read at the end of each client tick (see
 * {@link k2.link.mixin.MinecraftMixin}); the HTTP thread only hands out what was built there.</p>
 */
public final class K2Link implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("k2link");
    private static final int PORT = 34874;
    private static final int EVERY_TICKS = 4; // 5 Hz

    private static volatile String json = "{\"k2\":1,\"inGame\":false}";
    /** This mod's own version, published in the state so K2 can tell when a newer one is out. */
    private static final String VERSION = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getModContainer("k2link").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    private static int tick;
    private static boolean inWorld;

    static void warn(String message) { LOG.warn("K2 Link: {}", message); }

    @Override
    public void onInitializeClient() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0);
            server.createContext("/state", exchange -> {
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
            });
            // One slot's picture: /hotbar/1 .. /hotbar/9. 404 for an empty slot.
            server.createContext("/hotbar/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                int slot;
                try { slot = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1)); }
                catch (NumberFormatException e) { slot = 0; }
                byte[] body = HotbarIcons.png(slot);
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                exchange.getResponseHeaders().add("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
            });
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "K2Link-HTTP");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            LOG.info("K2 Link: serving on http://127.0.0.1:{}/state", PORT);
        } catch (Exception e) {
            // Most likely the port is taken (a second game instance). Never fatal to the game.
            LOG.warn("K2 Link: could not listen on port {}: {}", PORT, e.toString());
        }
    }

    /** End of every client tick, on the client thread. */
    public static void onClientTick(Minecraft client) {
        // Pictures first, every tick: a slot that changed should show up with the state that
        // reports it, not a poll later. Unchanged slots cost one comparison each.
        if (client.player != null) HotbarIcons.update(client);
        else if (inWorld) HotbarIcons.reset();
        inWorld = client.player != null;

        if (++tick % EVERY_TICKS != 0) return;
        try {
            json = build(client);
        } catch (Throwable t) {
            json = "{\"k2\":1,\"inGame\":false,\"error\":" + str(t.getClass().getSimpleName()) + "}";
        }
    }

    private static String build(Minecraft client) {
        LocalPlayer p = client.player;
        ClientLevel w = client.level;
        Json j = new Json();
        j.put("k2", 1);
        j.put("modVersion", VERSION);
        j.put("gameDir", client.gameDirectory.getAbsolutePath());
        if (p == null || w == null) {
            j.put("inGame", false);
            return j.end();
        }
        j.put("inGame", true);

        j.put("health", p.getHealth());
        j.put("maxHealth", p.getMaxHealth());
        j.put("food", p.getFoodData().getFoodLevel());
        j.put("saturation", p.getFoodData().getSaturationLevel());
        j.put("armor", p.getArmorValue());
        j.put("air", Math.max(0, p.getAirSupply()));
        j.put("maxAir", p.getMaxAirSupply());
        j.put("xpLevel", p.experienceLevel);
        j.put("xpProgress", p.experienceProgress);
        if (client.gameMode != null) {
            GameType mode = client.gameMode.getPlayerMode();
            if (mode != null) j.put("gameMode", mode.getLongDisplayName().getString());
        }

        j.put("sprinting", p.isSprinting());
        j.put("sneaking", p.isShiftKeyDown());
        j.put("flying", p.getAbilities().flying);
        j.put("swimming", p.isSwimming());
        j.put("gliding", p.isFallFlying());
        j.put("riding", p.isPassenger());
        j.put("onFire", p.isOnFire());
        j.put("hudHidden", client.gui.hud.isHidden());

        BlockPos pos = p.blockPosition();
        j.put("dimension", pretty(w.dimension().identifier().getPath()));
        w.getBiome(pos).unwrapKey().ifPresent(k ->
            j.put("biome", Component.translatable(Util.makeDescriptionId("biome", k.identifier())).getString()));
        long time = w.getOverworldClockTime();
        long dayTicks = Math.floorMod(time, 24000L);
        int hours = (int) ((dayTicks / 1000 + 6) % 24);
        int minutes = (int) ((dayTicks % 1000) * 60 / 1000);
        j.put("clock", String.format(Locale.ROOT, "%02d:%02d", hours, minutes));
        j.put("day", time / 24000L + 1);
        j.put("weather", w.isThundering() ? "Thunder" : w.isRaining() ? "Rain" : "Clear");
        j.put("light", w.getMaxLocalRawBrightness(pos));

        j.put("x", p.getX());
        j.put("y", p.getY());
        j.put("z", p.getZ());
        j.put("facing", pretty(p.getDirection().getSerializedName()));
        Vec3 v = p.getVehicle() != null ? p.getVehicle().getDeltaMovement() : p.getDeltaMovement();
        j.put("speed", v.length() * 20.0); // blocks per tick -> blocks (metres) per second

        ItemStack held = p.getMainHandItem();
        j.put("heldItem", held.isEmpty() ? "" : held.getHoverName().getString());
        j.put("slot", p.getInventory().getSelectedSlot() + 1);
        // The pictures carry the item only: count and wear are for K2 to draw (1.21.x baked them in).
        j.put("iconsPlain", true);
        // Per-slot picture revision (see HotbarIcons), name, count and wear, for the pad's slot keys.
        for (int i = 1; i <= 9; i++) {
            ItemStack stack = p.getInventory().getItem(i - 1);
            j.put("slotRev" + i, HotbarIcons.png(i) == null ? 0 : HotbarIcons.rev(i));
            j.put("slotName" + i, stack.isEmpty() ? "" : stack.getHoverName().getString());
            j.put("slotCount" + i, stack.getCount());
            if (stack.isDamageableItem() && stack.getMaxDamage() > 0)
                j.put("slotWear" + i, 1.0 - (double) stack.getDamageValue() / stack.getMaxDamage());
        }
        CameraType view = client.options.getCameraType();
        j.put("perspective", view == CameraType.FIRST_PERSON ? "1st" : view == CameraType.THIRD_PERSON_BACK ? "3rd" : "Front");
        j.put("fps", client.getFps());
        return j.end();
    }

    /** "the_nether" -> "The Nether". */
    private static String pretty(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private static String str(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    /** A flat JSON object, built in field order. */
    private static final class Json {
        private final StringBuilder sb = new StringBuilder(768).append('{');

        private void key(String k) {
            if (sb.length() > 1) sb.append(',');
            sb.append(str(k)).append(':');
        }

        void put(String k, boolean v) { key(k); sb.append(v); }
        void put(String k, long v) { key(k); sb.append(v); }
        void put(String k, double v) {
            key(k);
            sb.append(Double.isFinite(v) ? String.format(Locale.ROOT, "%.3f", v) : "null");
        }
        void put(String k, String v) { key(k); sb.append(v == null ? "null" : str(v)); }
        String end() { return sb.append('}').toString(); }
    }
}
