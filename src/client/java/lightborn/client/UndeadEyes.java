package lightborn.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lightborn.Monsters;
import lightborn.SurfaceLights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/**
 * Glowing eyes for every zombie and skeleton kind, shown only at night.
 *
 * <p>One path for all of them: a kind that ships {@code <skin>_eyes.png} uses it, any other skin has its eyes found on
 * the head's front face (coloured pupils or dark sockets in the eye band, else the vanilla eye spots). Either way the
 * mask holds the eye pixels only, drawn full-bright by one layer added to every living renderer. The prepared mask is cached per skin and model.
 */
public final class UndeadEyes {
    private UndeadEyes() {}

    private static final Logger LOG = LogUtils.getLogger();
    private static final int ZOMBIE_GLOW = 0xFF9AFFAD, SKELETON_GLOW = 0xFFBFEFFF;

    private static final Map<String, Identifier> CACHE = new HashMap<>();

    public interface State {
        Identifier lightborn$eyes();

        void lightborn$eyes(Identifier texture);

        /** One of the player's minions (drawn lit from within). */
        boolean lightborn$minion();

        void lightborn$minion(boolean minion);
    }

    /** A face rectangle in skin pixels. */
    private record Face(int x, int y, int w, int h) {
        boolean contains(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    public static boolean family(LivingEntity e) {
        if (Monsters.isAtmosphericProp(e)) return false;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        return e instanceof Zombie
                || e instanceof AbstractSkeleton
                || id.equals("minecraft:giant")
                || e instanceof Enemy
                        && (id.contains("zombie") || id.contains("skeleton") || id.startsWith("mutantszombies:"));
    }

    /** Eyes glow at night; the Nether and the End have no day, so there they always glow. */
    public static boolean lit(Level level) {
        return level.dimension() != Level.OVERWORLD || SurfaceLights.night(level);
    }

    /** The eye mask to draw over this skin, or null when the creature has no glowing eyes. */
    public static Identifier texture(LivingEntity e, Identifier skin, EntityModel<?> model) {
        if (!family(e)) return null;
        String key = skin + "/" + model.getClass().getName();
        if (CACHE.containsKey(key)) return CACHE.get(key);
        Identifier id = null;
        try {
            NativeImage mask = build(e, skin, model);
            if (mask != null) {
                id = Identifier.fromNamespaceAndPath("lightborn", "undead_eyes/" + CACHE.size());
                Minecraft.getInstance()
                        .getTextureManager()
                        .register(id, new DynamicTexture(() -> "Lightborn undead eyes: " + skin, mask));
            }
        } catch (IOException | RuntimeException ex) {
            LOG.warn("No glowing eyes for {}: {}", skin, ex.toString());
        }
        CACHE.put(key, id);
        return id;
    }

    private static NativeImage build(LivingEntity e, Identifier skin, EntityModel<?> model) throws IOException {
        var resources = Minecraft.getInstance().getResourceManager();
        try (var skinStream = resources.getResourceOrThrow(skin).open();
                var source = NativeImage.read(skinStream)) {
            int w = source.getWidth(), h = source.getHeight();
            List<Face> faces = frontFaces(model, w, h);
            if (faces.isEmpty()) {
                // The standard humanoid head: an 8 x 8 face at (8, 8) of a 64-wide skin.
                faces.add(new Face(w / 8, w / 8, w / 8, w / 8));
            }
            var own = Identifier.fromNamespaceAndPath(
                    skin.getNamespace(), skin.getPath().replace(".png", "_eyes.png"));
            NativeImage mask;
            var ownResource = resources.getResource(own);
            if (ownResource.isPresent()) {
                try (var eyeStream = ownResource.get().open();
                        var drawn = NativeImage.read(eyeStream)) {
                    if (drawn.getWidth() != w || drawn.getHeight() != h) return null;
                    mask = new NativeImage(w, h, true);
                    mask.copyFrom(drawn);
                }
            } else {
                mask = new NativeImage(w, h, true);
                mask.fillRect(0, 0, w, h, 0);
                int glow = skeleton(e) ? SKELETON_GLOW : ZOMBIE_GLOW;
                for (Face face : faces) paintEyes(source, mask, face, glow);
            }
            return mask;
        }
    }

    private static boolean skeleton(LivingEntity e) {
        return e instanceof AbstractSkeleton
                || BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().contains("skeleton");
    }

    /** The front (-Z facing) faces of the model's head cubes, in skin pixels. Hats, jaws and noses are skipped. */
    private static List<Face> frontFaces(EntityModel<?> model, int w, int h) {
        var faces = new ArrayList<Face>();
        model.root().visit(new PoseStack(), (pose, name, index, cube) -> {
            String n = name.toLowerCase(Locale.ROOT);
            if (!n.contains("head")
                    || n.contains("hat")
                    || n.contains("jaw")
                    || n.contains("nose")
                    || cube.maxX - cube.minX < 4
                    || cube.maxY - cube.minY < 4) return;
            for (var polygon : cube.polygons) {
                if (polygon == null || polygon.normal().z() > -.9f) continue;
                float u = 1, v = 1, maxU = 0, maxV = 0;
                for (var vertex : polygon.vertices()) {
                    u = Math.min(u, vertex.u());
                    v = Math.min(v, vertex.v());
                    maxU = Math.max(maxU, vertex.u());
                    maxV = Math.max(maxV, vertex.v());
                }
                var face = new Face(
                        Math.round(u * w), Math.round(v * h), Math.round((maxU - u) * w), Math.round((maxV - v) * h));
                if (face.w >= 4 && face.h >= 4 && !faces.contains(face)) faces.add(face);
            }
        });
        return faces;
    }

    /**
     * Finds the eyes in the face's eye band (rows 3/8 to 5/8): coloured pupils, or sockets much darker than the face.
     * Both halves must hold a small, plausible eye; otherwise the vanilla spots (columns 1-2 and 5-6 of 8) are used.
     */
    private static void paintEyes(NativeImage source, NativeImage mask, Face face, int glow) {
        int top = face.y + Math.round(face.h * 3 / 8f), bottom = face.y + Math.max(1, Math.round(face.h * 5 / 8f));
        int median = medianLuma(source, face);
        var left = new ArrayList<int[]>();
        var right = new ArrayList<int[]>();
        for (int py = top; py < bottom; py++)
            for (int px = face.x + 1; px < face.x + face.w - 1; px++) {
                int c = source.getPixel(px, py);
                if (pupil(c) || median > 40 && luma(c) < median * .55f)
                    (px < face.x + face.w / 2 ? left : right).add(new int[] {px, py});
            }
        int limit = Math.max(2, (bottom - top) * face.w / 4);
        if (left.isEmpty() || right.isEmpty() || left.size() > limit || right.size() > limit) {
            left.clear();
            right.clear();
            int row = face.y + face.h / 2, thick = Math.max(1, face.h / 8);
            for (float start : new float[] {.125f, .625f})
                for (int py = row; py < row + thick; py++)
                    for (int px = face.x + Math.round(face.w * start);
                            px < face.x + Math.round(face.w * (start + .25f));
                            px++) left.add(new int[] {px, py});
        }
        for (var list : List.of(left, right))
            for (int[] p : list) {
                int c = source.getPixel(p[0], p[1]);
                mask.setPixel(p[0], p[1], pupil(c) ? brighten(c) : glow);
            }
    }

    // No halo around the eyes (owner, 2026-11-16): at skin resolution a one-pixel glow is a lit box across the face,
    // which read as a bug. Only the eye pixels themselves shine.

    private static boolean pupil(int c) {
        int r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return (c >>> 24) > 0x80 && max > 60 && max - min > 35;
    }

    /** Full-strength version of a pupil colour (NativeImage pixels read as ARGB). */
    private static int brighten(int c) {
        int r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
        float gain = 255f / Math.max(1, Math.max(r, Math.max(g, b)));
        return 0xFF000000
                | Math.min(255, (int) (r * gain)) << 16
                | Math.min(255, (int) (g * gain)) << 8
                | Math.min(255, (int) (b * gain));
    }

    private static int luma(int c) {
        return ((c >> 16 & 255) * 3 + (c >> 8 & 255) * 6 + (c & 255)) / 10;
    }

    private static int medianLuma(NativeImage source, Face face) {
        int[] values = new int[face.w * face.h];
        int n = 0;
        for (int y = face.y; y < face.y + face.h && y < source.getHeight(); y++)
            for (int x = face.x; x < face.x + face.w && x < source.getWidth(); x++)
                values[n++] = luma(source.getPixel(x, y));
        java.util.Arrays.sort(values, 0, n);
        return n == 0 ? 0 : values[n / 2];
    }

    /** Draws the prepared mask full-bright on whatever model the renderer is showing (adult or baby). */
    public static final class Layer extends EyesLayer<LivingEntityRenderState, EntityModel<LivingEntityRenderState>> {
        private RenderType type;

        @SuppressWarnings({"rawtypes", "unchecked"})
        public Layer(LivingEntityRenderer renderer) {
            super(renderer);
        }

        @Override
        public void submit(
                PoseStack pose,
                SubmitNodeCollector collector,
                int light,
                LivingEntityRenderState state,
                float yRot,
                float xRot) {
            var id = ((State) state).lightborn$eyes();
            if (id == null) return;
            type = RenderTypes.eyes(id);
            super.submit(pose, collector, light, state, yRot, xRot);
        }

        @Override
        public RenderType renderType() {
            return type;
        }
    }
}
