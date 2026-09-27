package k2.link;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The nine hotbar slots as PNG pictures, drawn the way the game draws items in its own GUI — 3D
 * blocks, enchantment glint, items from other mods — so the DisplayPad's slot keys can wear the
 * real icon. Count and wear travel in the state JSON and are drawn by K2.
 *
 * <p>This is the game's own {@code GuiItemAtlas.drawToSlot}, pointed at a private one-slot texture
 * instead of the shared atlas, followed by a GPU readback. The readback is asynchronous: the PNG
 * (and the slot's revision) appear when the copy completes, a frame or two later.</p>
 *
 * <p><b>Only redrawn when the ITEM changes</b> (type, enchantments, other components) — not its
 * count or wear, which K2 draws itself from the state: placing a block updates the number on the
 * key without a new picture. A hotbar that does not change costs one comparison per slot per tick.</p>
 */
final class HotbarIcons {
    /** Pixels per side. The pad draws the icon at about this size, so no rescaling blurs the pixel art. */
    static final int SIZE = 88;

    private static final ItemStack[] last = new ItemStack[9];
    private static final byte[][] png = new byte[9][];
    private static final int[] rev = new int[9];
    private static final int[] generation = new int[9];

    private static GpuTexture texture, depth;
    private static GpuTextureView textureView, depthView;
    private static final SubmitNodeStorage submitNodeStorage = new SubmitNodeStorage();
    private static final PoseStack poseStack = new PoseStack();
    private static final Projection projection = new Projection();
    private static ProjectionMatrixBuffer projectionBuffer;
    private static final ItemStackRenderState renderState = new ItemStackRenderState();
    private static boolean broken;

    private HotbarIcons() {}

    /** The last picture of a slot (1-9), or null for an empty slot or one not drawn yet. */
    static synchronized byte[] png(int slot) {
        return slot >= 1 && slot <= 9 ? png[slot - 1] : null;
    }

    static synchronized int rev(int slot) {
        return rev[slot - 1];
    }

    /** Client thread: redraws the slots whose contents changed. */
    static void update(Minecraft client) {
        LocalPlayer p = client.player;
        if (p == null || broken) return;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = p.getInventory().getItem(i);
            // Compared as the picture shows it: count and wear are drawn by K2 from the state,
            // so placing a block or chipping a tool changes a number, not the icon.
            ItemStack look = pictureOf(stack);
            if (last[i] != null && (last[i].isEmpty() ? look.isEmpty() : ItemStack.isSameItemSameComponents(last[i], look)))
                continue;
            last[i] = look;
            int gen;
            synchronized (HotbarIcons.class) { gen = ++generation[i]; }
            if (stack.isEmpty()) {
                publish(i, gen, null);
                continue;
            }
            try {
                draw(client, stack, i, gen);
            } catch (Throwable t) {
                // A renderer that fails once will fail every time: stop trying rather than
                // throwing on every hotbar change. The slot keys fall back to their captions.
                broken = true;
                K2Link.warn("hotbar icons disabled: " + t);
                return;
            }
        }
    }

    /** The stack as its icon depends on it: one item, undamaged. Everything else in the picture
     *  (item, enchantment glint, custom model, dye...) still counts as a change. */
    private static ItemStack pictureOf(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack look = stack.copyWithCount(1);
        if (look.isDamageableItem()) look.setDamageValue(0);
        return look;
    }

    /** Forgets every slot — leaving a world, so the next one starts from a clean hotbar. */
    static synchronized void reset() {
        for (int i = 0; i < 9; i++) {
            last[i] = null;
            generation[i]++;
            if (png[i] != null) { png[i] = null; rev[i]++; }
        }
    }

    /** Stores a finished picture unless a newer drawing of the same slot was started since. */
    private static synchronized void publish(int slot, int gen, byte[] picture) {
        if (generation[slot] != gen) return;
        png[slot] = picture;
        rev[slot]++;
    }

    private static void ensureTargets() {
        if (texture != null) return;
        GpuDevice device = RenderSystem.getDevice();
        texture = device.createTexture("K2 Link hotbar icon", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
                | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
        textureView = device.createTextureView(texture);
        depth = device.createTexture("K2 Link hotbar icon depth", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.D32_FLOAT, SIZE, SIZE, 1, 1);
        depthView = device.createTextureView(depth);
        projectionBuffer = new ProjectionMatrixBuffer("k2link items");
    }

    private static void draw(Minecraft client, ItemStack stack, int slot, int gen) {
        ensureTargets();
        GpuDevice device = RenderSystem.getDevice();
        device.createCommandEncoder().clearColorAndDepthTextures(texture, GuiRenderer.CLEAR_COLOR, depth, 0.0);

        client.getItemModelResolver().updateForTopItem(renderState, stack, ItemDisplayContext.GUI, client.level, client.player, 0);

        // Same transform as GuiItemAtlas.drawToSlot for a slot that is the whole texture.
        poseStack.pushPose();
        poseStack.translate(SIZE / 2.0F, SIZE / 2.0F, 0.0F);
        poseStack.scale(SIZE, -SIZE, SIZE);
        projection.setupOrtho(-1000.0F, 1000.0F, SIZE, SIZE, true);
        RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
        client.gameRenderer.lighting().setupFor(renderState.usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT);
        renderState.submit(poseStack, submitNodeStorage, 15728880, OverlayTexture.NO_OVERLAY, 0);

        FeatureRenderDispatcher dispatcher = client.gameRenderer.featureRenderDispatcher();
        try (FeatureRenderDispatcher.PreparedFrame frame = dispatcher.prepareFrame(submitNodeStorage);
             RenderPass pass = device.createCommandEncoder()
                     .createRenderPass(() -> "K2 Link hotbar icon", textureView, Optional.empty(), depthView, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            FeatureRenderDispatcher.renderAllFeatures(pass, frame);
        } finally {
            poseStack.popPose();
        }

        readBack(slot, gen);
    }

    /** Copies the texture to a buffer and, when the GPU is done, encodes it as PNG. */
    private static void readBack(int slot, int gen) {
        int block = texture.getFormat().blockSize();
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "K2 Link hotbar readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) SIZE * SIZE * block);
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, buffer, 0L, () -> {
            byte[] picture = null;
            try (GpuBufferSlice.MappedView read = buffer.map(true, false);
                 NativeImage image = new NativeImage(SIZE, SIZE, false)) {
                for (int y = 0; y < SIZE; y++)
                    for (int x = 0; x < SIZE; x++)
                        // GPU rows run bottom-up; keep the alpha so the pad sees a cut-out icon.
                        image.setPixelABGR(x, SIZE - y - 1, read.data().getInt((x + y * SIZE) * block));
                Path tmp = Files.createTempFile("k2link-slot", ".png");
                try {
                    image.writeToFile(tmp);
                    picture = Files.readAllBytes(tmp);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            } catch (Exception e) {
                K2Link.warn("hotbar icon readback failed: " + e);
            } finally {
                buffer.close();
            }
            publish(slot, gen, picture);
        }, 0);
    }
}
