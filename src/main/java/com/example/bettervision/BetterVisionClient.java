package com.example.bettervision;

import java.util.Locale;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

public class BetterVisionClient implements ClientModInitializer {

    public static final String MOD_ID = "bettervision";

    // --- konfiguracja (zmien wartosci tutaj) ---
    static boolean enabled = true;
    static boolean showPlayers = true;
    static boolean showMobs = true;
    static boolean showNametags = true;   // nazwa + HP + dystans nad celem
    static double maxRange = 128.0;       // 0 = bez limitu
    static float lineThickness = 1.5f;    // grubosc linii
    static boolean aimAtEyes = false;     // false = srodek ciala, true = oczy

    // kolory ARGB
    static final int COLOR_PLAYER  = 0xFFFF3030; // czerwony
    static final int COLOR_HOSTILE = 0xFFFF9020; // pomaranczowy
    static final int COLOR_PASSIVE = 0xFF40FF60; // zielony

    private static KeyMapping toggleKey, playersKey, mobsKey, nametagKey;

    // --- pipeline do rysowania linii przez sciany (jak w dokumentacji Fabrica dla 1.21.11) ---
    private static final RenderPipeline THROUGH_WALLS = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/tracers_through_walls"))
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .build()
    );
    private static final ByteBufferBuilder allocator = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static MappableRingBuffer vertexBuffer;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bettervision.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_BACKSLASH, category));
        playersKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bettervision.players", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        mobsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bettervision.mobs", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        nametagKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bettervision.nametags", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                enabled = !enabled;
                msg(client, "BetterVision: " + (enabled ? "ON" : "OFF"));
            }
            while (playersKey.consumeClick()) {
                showPlayers = !showPlayers;
                msg(client, "Gracze: " + (showPlayers ? "ON" : "OFF"));
            }
            while (mobsKey.consumeClick()) {
                showMobs = !showMobs;
                msg(client, "Moby: " + (showMobs ? "ON" : "OFF"));
            }
            while (nametagKey.consumeClick()) {
                showNametags = !showNametags;
                msg(client, "Nametagi: " + (showNametags ? "ON" : "OFF"));
            }
        });

        // linie rysowane w swiecie
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(BetterVisionClient::renderTracers);

        // nametagi rysowane na HUD (rzutowanie pozycji celu na ekran)
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "nametags"),
                BetterVisionClient::renderNametags);
    }

    private static void msg(Minecraft mc, String s) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(s), true);
    }

    // ------------------------------------------------------------------
    // filtrowanie celow
    // ------------------------------------------------------------------
    private static boolean shouldShow(Minecraft mc, Entity e) {
        if (!(e instanceof LivingEntity living) || e == mc.player || !living.isAlive()) return false;
        boolean isPlayer = e instanceof Player;
        if (isPlayer && !showPlayers) return false;
        if (!isPlayer && !showMobs) return false;
        return maxRange <= 0 || e.distanceTo(mc.player) <= maxRange;
    }

    private static int colorFor(Entity e) {
        if (e instanceof Player) return COLOR_PLAYER;
        return e instanceof Monster ? COLOR_HOSTILE : COLOR_PASSIVE;
    }

    private static Vec3 interpolatedPos(Entity e, float pt) {
        return new Vec3(
                Mth.lerp(pt, e.xo, e.getX()),
                Mth.lerp(pt, e.yo, e.getY()),
                Mth.lerp(pt, e.zo, e.getZ()));
    }

    // ------------------------------------------------------------------
    // LINIE (rysowane jako cienkie wstegi zwrocone do kamery)
    // ------------------------------------------------------------------
    private static void renderTracers(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null || mc.level == null) return;

        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        PoseStack matrices = context.matrices();
        Vec3 cam = context.worldState().cameraRenderState.pos;

        // poczatek linii: troche przed kamera, na srodku ekranu
        Vec3 start = cam.add(mc.player.getViewVector(pt).scale(0.5));

        matrices.pushPose();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4fc pose = matrices.last().pose();

        BufferBuilder buffer = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!shouldShow(mc, e)) continue;

            Vec3 end = interpolatedPos(e, pt)
                    .add(0, aimAtEyes ? e.getEyeHeight() : e.getBbHeight() / 2.0, 0);

            if (buffer == null) {
                buffer = new BufferBuilder(allocator, THROUGH_WALLS.getVertexFormatMode(), THROUGH_WALLS.getVertexFormat());
            }
            addRibbon(buffer, pose, cam, start, end, colorFor(e));
        }

        matrices.popPose();

        if (buffer != null) draw(mc, buffer);
    }

    private static void addRibbon(BufferBuilder buf, Matrix4fc pose, Vec3 cam, Vec3 a, Vec3 b, int argb) {
        Vec3 dir = b.subtract(a);
        Vec3 viewRay = a.add(b).scale(0.5).subtract(cam);
        double halfWidth = lineThickness * 0.0015 * Math.max(0.5, viewRay.length());
        Vec3 side = dir.cross(viewRay).normalize().scale(halfWidth);

        Vec3 p1 = a.add(side), p2 = a.subtract(side), p3 = b.subtract(side), p4 = b.add(side);

        float al = (argb >>> 24 & 255) / 255f;
        float r = (argb >> 16 & 255) / 255f;
        float g = (argb >> 8 & 255) / 255f;
        float bl = (argb & 255) / 255f;

        // dwustronnie, zeby nie zalezalo od kierunku rysowania scianek
        quad(buf, pose, p1, p2, p3, p4, r, g, bl, al);
        quad(buf, pose, p4, p3, p2, p1, r, g, bl, al);
    }

    private static void quad(BufferBuilder buf, Matrix4fc pose, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                             float r, float g, float bl, float al) {
        buf.addVertex(pose, (float) a.x, (float) a.y, (float) a.z).setColor(r, g, bl, al);
        buf.addVertex(pose, (float) b.x, (float) b.y, (float) b.z).setColor(r, g, bl, al);
        buf.addVertex(pose, (float) c.x, (float) c.y, (float) c.z).setColor(r, g, bl, al);
        buf.addVertex(pose, (float) d.x, (float) d.y, (float) d.z).setColor(r, g, bl, al);
    }

    // ---- wysylanie bufora na GPU (wg dokumentacji Fabrica dla 1.21.11) ----
    private static void draw(Minecraft client, BufferBuilder buffer) {
        RenderPipeline pipeline = THROUGH_WALLS;

        MeshData builtBuffer = buffer.buildOrThrow();
        MeshData.DrawState drawParameters = builtBuffer.drawState();
        VertexFormat format = drawParameters.format();

        int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();
        if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
            if (vertexBuffer != null) vertexBuffer.close();
            vertexBuffer = new MappableRingBuffer(() -> MOD_ID + " tracers",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, vertexBufferSize);
        }

        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(
                vertexBuffer.currentBuffer().slice(0, builtBuffer.vertexBuffer().remaining()), false, true)) {
            MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
        }
        GpuBuffer vertices = vertexBuffer.currentBuffer();

        GpuBuffer indices;
        VertexFormat.IndexType indexType;
        if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
            builtBuffer.sortQuads(allocator, RenderSystem.getProjectionType().vertexSorting());
            indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(builtBuffer.indexBuffer());
            indexType = builtBuffer.drawState().indexType();
        } else {
            RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());
            indices = shapeIndexBuffer.getBuffer(drawParameters.indexCount());
            indexType = shapeIndexBuffer.type();
        }

        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> MOD_ID + " tracers rendering",
                        client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
                        client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
            renderPass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setVertexBuffer(0, vertices);
            renderPass.setIndexBuffer(indices, indexType);
            renderPass.drawIndexed(0 / format.getVertexSize(), 0, drawParameters.indexCount(), 1);
        }

        builtBuffer.close();
        vertexBuffer.rotate();
    }

    // ------------------------------------------------------------------
    // NAMETAGI na HUD: nazwa + HP + dystans
    // ------------------------------------------------------------------
    private static void renderNametags(GuiGraphics graphics, DeltaTracker tracker) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || !showNametags || mc.player == null || mc.level == null) return;

        float pt = tracker.getGameTimeDeltaPartialTick(false);

        // baza kamery liczona z pozycji i obrotu gracza (widok z pierwszej osoby)
        Vec3 eye = mc.player.getEyePosition(pt);
        double yaw = Math.toRadians(mc.player.getViewYRot(pt));
        double pitch = Math.toRadians(mc.player.getViewXRot(pt));
        Vec3 forward = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3 up = right.cross(forward);

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        double aspect = (double) width / (double) height;
        double tanHalfFov = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!shouldShow(mc, e)) continue;
            LivingEntity living = (LivingEntity) e;

            Vec3 target = interpolatedPos(e, pt).add(0, e.getBbHeight() + 0.4, 0);
            Vec3 rel = target.subtract(eye);

            double depth = rel.dot(forward);
            if (depth < 0.1) continue; // za plecami

            double ndcX = (rel.dot(right) / depth) / (tanHalfFov * aspect);
            double ndcY = (rel.dot(up) / depth) / tanHalfFov;
            if (Math.abs(ndcX) > 1.2 || Math.abs(ndcY) > 1.2) continue;

            int sx = (int) ((ndcX + 1.0) / 2.0 * width);
            int sy = (int) ((1.0 - ndcY) / 2.0 * height);

            float hp = living.getHealth();
            float maxHp = living.getMaxHealth();
            float ratio = maxHp > 0 ? hp / maxHp : 0f;
            float dist = e.distanceTo(mc.player);

            String label = String.format(Locale.ROOT, "%s  %.1f/%.0f HP  %.0fm",
                    e.getName().getString(), hp, maxHp, dist);

            int color = ratio > 0.66f ? 0xFF55FF55 : ratio > 0.33f ? 0xFFFFFF55 : 0xFFFF5555;

            int textWidth = mc.font.width(label);
            int x = sx - textWidth / 2;
            int y = sy - 4;
            graphics.fill(x - 2, y - 2, x + textWidth + 2, y + 10, 0x66000000);
            graphics.drawString(mc.font, label, x, y, color, false);
        }
    }
}
