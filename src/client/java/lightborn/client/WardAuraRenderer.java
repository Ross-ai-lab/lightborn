package lightborn.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import lightborn.WardAura;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;

/**
 * The Light ward: a living dome of gold light, drawn on the GPU by {@code shaders/core/ward.vsh/.fsh} (owner,
 * 2026-11-18: it flickered and cost frames). One fixed sphere mesh, two passes:
 *
 * <ol>
 *   <li>Core: the crisp deep-gold criss-cross lines, opaque and writing depth, so clouds behind cannot wash them out.
 *   <li>Soft light: the glowing lattice and faint sheet, brighter where the eye grazes the wall (a rim light); comets
 *       running up both line families toward the crown; two veils of warm shimmer drifting against each other; a
 *       star at every crossing, each twinkling on its own rhythm; every eight seconds a heartbeat ring rising from the
 *       lowest point to the crown; the whole dome breathing.
 * </ol>
 *
 * Every line, comet and star is filtered to the pixel it lands in, so none ever thins below a pixel and crawls as the
 * camera moves: that crawl was the flicker, from lattice textures with no smaller copies for distance. All animation
 * runs on the GPU from the game clock, so the CPU only copies the same mesh each frame (no per-point maths, no
 * sorting), and seven blended layers became two. The lattice repeats scale with the radius, so a grown ward keeps
 * gaps under a block wide. The textured renderer this replaced is in git history (2026-11-18).
 */
public final class WardAuraRenderer extends EntityRenderer<WardAura, WardAuraRenderer.State> {
    private static Identifier tex(String name) {
        return Identifier.fromNamespaceAndPath("lightborn", "textures/entity/" + name + ".png");
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("lightborn", path);
    }

    /**
     * The additive layers' pipeline (the lighthouse pillar and the closing cracks): vanilla's energy swirl, minus its
     * depth write. Vanilla's writes depth over the whole surface, hiding every translucent thing behind it (owner,
     * 2026-11-16: zombies' eyes must shine seen from inside too). Registered at client start with the rest.
     */
    private static final RenderPipeline SWIRL =
            RenderPipelines.register(RenderPipeline.builder(RenderPipelines.ENERGY_SWIRL_SNIPPET)
                    .withLocation(id("pipeline/ward_swirl"))
                    .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
                    .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                    .build());

    /** The core lines: opaque, writing depth. */
    private static final RenderPipeline WARD_CORE =
            RenderPipelines.register(RenderPipeline.builder(RenderPipelines.ENERGY_SWIRL_SNIPPET)
                    .withLocation(id("pipeline/ward_core"))
                    .withVertexShader(id("core/ward"))
                    .withFragmentShader(id("core/ward"))
                    .withShaderDefine("WARD_CORE")
                    .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                    .withColorTargetState(ColorTargetState.DEFAULT) // the snippet names none: opaque colour
                    .withDepthStencilState(DepthStencilState.DEFAULT)
                    .build());

    /**
     * The soft light: premultiplied (its glow covers like translucency, its comets, veils and stars add light), no
     * depth write, so every translucent thing behind it still shows. Its equal-or-nearer test against the core lines'
     * identical depth (the vertex shader's invariant position) settles every tie the same way each frame.
     */
    private static final RenderPipeline WARD_LIGHT =
            RenderPipelines.register(RenderPipeline.builder(RenderPipelines.ENERGY_SWIRL_SNIPPET)
                    .withLocation(id("pipeline/ward_light"))
                    .withVertexShader(id("core/ward"))
                    .withFragmentShader(id("core/ward"))
                    .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
                    .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                    .build());

    /** Loads the class at client start, registering the pipelines. */
    public static void init() {}

    /** An additive, scrolling, non-depth-writing layer (the lighthouse pillar uses it too). */
    static RenderType swirl(Identifier texture, float u, float v) {
        return RenderType.create(
                "lightborn_ward_swirl",
                RenderSetup.builder(SWIRL)
                        .withTexture("Sampler0", texture)
                        .setTextureTransform(new TextureTransform.OffsetTextureTransform(u, v))
                        .useLightmap()
                        .useOverlay()
                        .sortOnUpload()
                        .createRenderSetup());
    }

    private static RenderType ward(String name, RenderPipeline pipeline) {
        return RenderType.create(
                name,
                RenderSetup.builder(pipeline)
                        .withTexture("Sampler0", tex("ward_veil"))
                        .useLightmap()
                        .useOverlay()
                        .createRenderSetup());
    }

    private static final RenderType CORE = ward("lightborn_ward_core", WARD_CORE);
    private static final RenderType LIGHT = ward("lightborn_ward_light", WARD_LIGHT);

    /**
     * The mesh: 48 x 24 keeps the silhouette within a tenth of a block of round at the largest ward (48); the lattice is
     * worked out per pixel, so it does not depend on the mesh.
     */
    private static final int LON = 48, LAT = 24;
    /** Unit-sphere grid points, crown first. */
    private static final float[] NX, NY, NZ;

    static {
        int points = (LON + 1) * (LAT + 1);
        NX = new float[points];
        NY = new float[points];
        NZ = new float[points];
        for (int y = 0; y <= LAT; y++)
            for (int x = 0; x <= LON; x++) {
                int i = y * (LON + 1) + x;
                double a = x / (double) LON * Math.PI * 2, b = y / (double) LAT * Math.PI;
                NX[i] = (float) (Math.sin(b) * Math.cos(a));
                NY[i] = (float) Math.cos(b);
                NZ[i] = (float) (Math.sin(b) * Math.sin(a));
            }
    }

    public static final class State extends EntityRenderState {
        float radius, time, scars;
        int scarTarget;
    }

    private static final RenderType CRACK_CORE = RenderTypes.entityTranslucentEmissive(tex("ward_crack"));
    private static final Identifier CRACK = tex("ward_crack");
    /** Steps in a crack's main run; its branch splits off at step 4 and runs four more. */
    private static final int CRACK_STEPS = 9, BRANCH_AT = 4, BRANCH_STEPS = 4;
    /**
     * The Cleanse's cracks, one set of segments per crack: start and end on the unit sphere, then the moment (0 to 1)
     * it opens. Each crack starts somewhere over the dome's upper half, spread round it, and wanders.
     */
    private static final float[][][] CRACKS = new float[WardAura.MAX_SCARS][][];

    static {
        for (int c = 0; c < CRACKS.length; c++) {
            var rnd = new java.util.Random(911L * (c + 1));
            double b = Math.PI * (.12 + rnd.nextDouble() * .3), a = c * Math.PI * 2 / CRACKS.length + rnd.nextDouble();
            double[] p = {Math.sin(b) * Math.cos(a), Math.cos(b), Math.sin(b) * Math.sin(a)};
            double[] t = turn(p, tangent(p), rnd.nextDouble() * Math.PI * 2);
            var segs = new java.util.ArrayList<float[]>();
            double[] branchP = null, branchT = null;
            for (int s = 0; s < CRACK_STEPS; s++) {
                double[] q = step(p, t, .06 + rnd.nextDouble() * .03);
                segs.add(seg(p, q, s / (float) CRACK_STEPS));
                t = turn(q, project(t, q), (rnd.nextDouble() - .5) * 1.3);
                p = q;
                if (s == BRANCH_AT) {
                    branchP = p;
                    branchT = turn(p, t, (rnd.nextBoolean() ? 1 : -1) * (.7 + rnd.nextDouble() * .4));
                }
            }
            for (int s = 0; s < BRANCH_STEPS; s++) {
                double[] q = step(branchP, branchT, .05 + rnd.nextDouble() * .02);
                segs.add(seg(branchP, q, (BRANCH_AT + 1 + s) / (float) CRACK_STEPS));
                branchT = turn(q, project(branchT, q), (rnd.nextDouble() - .5) * 1.1);
                branchP = q;
            }
            CRACKS[c] = segs.toArray(new float[0][]);
        }
    }

    private static float[] seg(double[] a, double[] b, float when) {
        return new float[] {(float) a[0], (float) a[1], (float) a[2], (float) b[0], (float) b[1], (float) b[2], when};
    }

    private static double[] norm(double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[] {v[0] / l, v[1] / l, v[2] / l};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] tangent(double[] p) {
        return norm(cross(p, Math.abs(p[1]) > .9 ? new double[] {1, 0, 0} : new double[] {0, 1, 0}));
    }

    /** {@code t} made flat against the sphere at {@code p}. */
    private static double[] project(double[] t, double[] p) {
        double d = t[0] * p[0] + t[1] * p[1] + t[2] * p[2];
        return norm(new double[] {t[0] - p[0] * d, t[1] - p[1] * d, t[2] - p[2] * d});
    }

    /** {@code t} turned by {@code angle} about the sphere's normal at {@code p}. */
    private static double[] turn(double[] p, double[] t, double angle) {
        double[] s = cross(p, t);
        double c = Math.cos(angle), n = Math.sin(angle);
        return norm(new double[] {t[0] * c + s[0] * n, t[1] * c + s[1] * n, t[2] * c + s[2] * n});
    }

    private static double[] step(double[] p, double[] t, double length) {
        return norm(new double[] {p[0] + t[0] * length, p[1] + t[1] * length, p[2] + t[2] * length});
    }

    public WardAuraRenderer(EntityRendererProvider.Context c) {
        super(c);
        shadowRadius = 0;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(WardAura e, State s, float partial) {
        super.extractRenderState(e, s, partial);
        s.radius = e.radius();
        s.time = (e.tickCount + partial) / 20f;
        s.scars = e.shownScars(partial);
        s.scarTarget = e.scars();
    }

    @Override
    protected AABB getBoundingBoxForCulling(WardAura e, float partial) {
        return new AABB(e.position(), e.position()).inflate(e.radius());
    }

    /** Lattice repeats around the dome: 8 at the starter radius of 18, growing so the gaps stay the same width. */
    static int repeats(float radius) {
        return Math.max(8, 4 * Math.round(radius / 9f));
    }

    /**
     * Emits the open cracks as flat strips on the dome. Crack {@code i} is open by {@code scars - i}; one that is
     * closing (its index at or past the synced count) turns gold as it goes. An open rift is black, drawn at
     * {@code shade} opacity; the additive glow belongs to a closing one only.
     */
    private static void cracks(
            PoseStack.Pose p,
            VertexConsumer out,
            State s,
            float r,
            float width,
            boolean glow,
            float flicker,
            float shade) {
        for (int c = 0; c < CRACKS.length; c++) {
            float open = Math.clamp(s.scars - c, 0f, 1f);
            if (open <= 0) break;
            boolean healing = c >= s.scarTarget;
            if (glow && !healing) continue; // additive black is nothing: an open rift has no glow
            int red, green, blue, alpha;
            if (glow) {
                float k = flicker * .9f;
                red = Math.round(255 * k);
                green = Math.round(255 * k * .8f);
                blue = Math.round(255 * k * .35f);
                alpha = 255;
            } else {
                red = healing ? 255 : 5;
                green = healing ? 214 : 4;
                blue = healing ? 120 : 6;
                alpha = Math.round(255 * shade * (healing ? .6f * open : .97f));
            }
            float rr = r * 1.004f;
            for (float[] g : CRACKS[c]) {
                float from = g[6], span = 1f / CRACK_STEPS;
                if (open <= from) continue;
                float f = Math.min(1, (open - from) / span);
                float ax = g[0] * rr, ay = g[1] * rr, az = g[2] * rr;
                float bx = ax + (g[3] * rr - ax) * f, by = ay + (g[4] * rr - ay) * f, bz = az + (g[5] * rr - az) * f;
                // The strip's side: across the segment, flat against the dome.
                float dx = bx - ax, dy = by - ay, dz = bz - az;
                float sx = dy * g[2] - dz * g[1], sy = dz * g[0] - dx * g[2], sz = dx * g[1] - dy * g[0];
                float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz) + 1e-5f;
                float w = width / 2 / sl;
                sx *= w;
                sy *= w;
                sz *= w;
                float u0 = from, u1 = from + span * f;
                vertex(p, out, ax - sx, ay - sy, az - sz, u0, 0, red, green, blue, alpha, g);
                vertex(p, out, ax + sx, ay + sy, az + sz, u0, 1, red, green, blue, alpha, g);
                vertex(p, out, bx + sx, by + sy, bz + sz, u1, 1, red, green, blue, alpha, g);
                vertex(p, out, bx - sx, by - sy, bz - sz, u1, 0, red, green, blue, alpha, g);
            }
        }
    }

    private static void vertex(
            PoseStack.Pose p,
            VertexConsumer out,
            float x,
            float y,
            float z,
            float u,
            float v,
            int red,
            int green,
            int blue,
            int alpha,
            float[] g) {
        out.addVertex(p, x, y, z)
                .setColor(red, green, blue, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(15728880)
                .setNormal(p, g[0], g[1], g[2]);
    }

    @Override
    public void submit(State s, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        float r = s.radius, t = s.time;
        int ku = repeats(r);
        collector.submitCustomGeometry(pose, CORE, (p, out) -> sphere(p, out, r, ku));
        collector.submitCustomGeometry(pose, LIGHT, (p, out) -> sphere(p, out, r, ku));
        // The Cleanse's cracks: each runs open as the fight wears on and, after dawn, closes in a flush of gold.
        if (s.scars > .01f) {
            float flicker = .85f + .15f * (float) Math.sin(t * 7.3f);
            // A soft black bruise round each rift, then the rift itself, near-black and solid (owner, 2026-11-17:
            // black, not violet); the additive pass only lights the gold of a crack that is closing.
            collector.submitCustomGeometry(
                    pose, CRACK_CORE, (p, out) -> cracks(p, out, s, r, .62f, false, flicker, .42f));
            collector.submitCustomGeometry(
                    pose, CRACK_CORE, (p, out) -> cracks(p, out, s, r, .24f, false, flicker, 1f));
            collector.submitCustomGeometry(
                    pose, swirl(CRACK, 0, 0), (p, out) -> cracks(p, out, s, r, .5f, true, flicker, 1f));
        }
    }

    /**
     * Emits the dome's quads, the same every frame for a given radius: positions, the outward normal, lattice
     * coordinates in UV0 ({@code ku} tiles round, {@code ku / 2} crown to foot), the tiles round and mesh rows in UV1,
     * and the mesh column and row in UV2, which the shaders read in place of overlay and light.
     */
    private static void sphere(PoseStack.Pose p, VertexConsumer out, float r, int ku) {
        int row = LON + 1;
        float kv = ku / 2f;
        int rounds = ku | LAT << 16;
        for (int y = 0; y < LAT; y++)
            for (int x = 0; x < LON; x++)
                for (int corner = 0; corner < 4; corner++) {
                    int gx = x + (corner == 2 || corner == 3 ? 1 : 0), gy = y + (corner == 1 || corner == 2 ? 1 : 0);
                    int i = gy * row + gx;
                    out.addVertex(p, NX[i] * r, NY[i] * r, NZ[i] * r)
                            .setColor(255, 255, 255, 255)
                            .setUv(gx / (float) LON * ku, gy / (float) LAT * kv)
                            .setOverlay(rounds)
                            .setLight(gx | gy << 16)
                            .setNormal(p, NX[i], NY[i], NZ[i]);
                }
    }
}
