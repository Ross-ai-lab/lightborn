package lightborn.client;

import com.mojang.blaze3d.platform.InputConstants;
import lightborn.Balance;
import lightborn.ChestTravel;
import lightborn.Collection;
import lightborn.DragonStory;
import lightborn.DragonTalk;
import lightborn.Furniture;
import lightborn.GameMusic;
import lightborn.GiantZombie;
import lightborn.LightChestBlock;
import lightborn.LightGear;
import lightborn.LightShop;
import lightborn.ModEntities;
import lightborn.Net;
import lightborn.Spells;
import lightborn.SurfaceLights;
import lightborn.TomeCycle;
import lightborn.TomeItem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Client side: the story keys, chest screens and the HUD. Spells need no keys: they cast from the off-hand tome on right-click. */
public class LightbornClient implements ClientModInitializer {
    public static boolean spiritScoping(Object player) {
        var game = Minecraft.getInstance();
        return player == game.player
                && lightborn.FormSpells.spiritSight(game.player)
                && game.gui.screen() == null
                && game.options.keyUse.isDown();
    }

    static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("lightborn", "keys"));
    static KeyMapping storyPower, dragonBreath, spellstoneFive;

    @Override
    public void onInitializeClient() {
        WardAuraRenderer.init();
        // Dark Goo draws with its own black-violet frames (lava's, recoloured by tools/make_dark_realm.py).
        net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry.register(
                lightborn.DarkGoo.SOURCE,
                lightborn.DarkGoo.FLOWING,
                new net.minecraft.client.renderer.block.FluidModel.Unbaked(
                        new net.minecraft.client.resources.model.sprite.Material(
                                net.minecraft.resources.Identifier.fromNamespaceAndPath(
                                        "lightborn", "block/dark_goo_still")),
                        new net.minecraft.client.resources.model.sprite.Material(
                                net.minecraft.resources.Identifier.fromNamespaceAndPath(
                                        "lightborn", "block/dark_goo_flow")),
                        null,
                        null));
        EntityRendererRegistry.register(ModEntities.WARD_AURA, WardAuraRenderer::new);
        EntityRendererRegistry.register(ModEntities.RADIANT_WAVE, RadiantWaveRenderer::new);
        EntityRendererRegistry.register(lightborn.StaffFlight.TYPE, StaffMountRenderer::new);
        EntityRendererRegistry.register(lightborn.Taunt.TYPE, TotemRenderer::new);
        ClientChunkEvents.CHUNK_LOAD.register(SurfaceLights::loaded);
        ClientChunkEvents.CHUNK_UNLOAD.register(SurfaceLights::unloaded);
        storyPower = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.lightborn.story_power", InputConstants.KEY_R, CATEGORY));
        dragonBreath = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.lightborn.dragon_breath", InputConstants.KEY_V, CATEGORY));
        spellstoneFive = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.lightborn.spellstone_five", InputConstants.KEY_C, CATEGORY));
        ModelLayerRegistry.registerModelLayer(LastScribeRenderer.LAYER, LastScribeRenderer::layer);
        EntityRendererRegistry.register(ModEntities.LAST_SCRIBE, LastScribeRenderer::new);
        // Tome tooltips show what this player would actually deal: level, Spell Power (staff included) and masteries.
        TomeItem.viewerPlayer = () -> Minecraft.getInstance().player;
        TomeItem.viewerDamage = spell -> {
            var player = Minecraft.getInstance().player;
            return player == null ? spell.baseDamage * Balance.SPELL_DAMAGE : Spells.damage(player, spell);
        };

        EntityRendererRegistry.register(Furniture.SEAT, NoopRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(
                GameMusic.Mood.TYPE, (packet, ctx) -> MusicDirector.scene = packet.name());
        ClientPlayNetworking.registerGlobalReceiver(ChestTravel.Show.TYPE, (packet, ctx) -> {
            if (ctx.client().gui.screen() instanceof CollectionScreen s) s.receiveTravel(packet);
        });
        // The Chest of Light: a plain use opens the book; Ctrl + use with an item gives one (only the client sees
        // Ctrl).
        EntityRendererRegistry.register(
                ModEntities.DARKNESS_ZOMBIE, ctx -> new ZombieKindRenderer(ctx, "darkness_zombie", 1f));
        EntityRendererRegistry.register(
                ModEntities.VAMPIRE_ZOMBIE, ctx -> new ZombieKindRenderer(ctx, "vampire_zombie", 1f));
        EntityRendererRegistry.register(
                ModEntities.GIANT_ZOMBIE, ctx -> new ZombieKindRenderer(ctx, "giant_zombie", GiantZombie.SIZE));
        EntityRendererRegistry.register(
                ModEntities.EMBER_SKELETON, ctx -> new MonsterKindRenderers.Skeleton(ctx, "ember_skeleton"));
        EntityRendererRegistry.register(
                ModEntities.DEADEYE_SKELETON, ctx -> new MonsterKindRenderers.Skeleton(ctx, "deadeye_skeleton"));
        EntityRendererRegistry.register(
                ModEntities.WEAVER_SPIDER, ctx -> new MonsterKindRenderers.SpiderKind(ctx, "weaver_spider"));
        EntityRendererRegistry.register(
                ModEntities.HUNTER_SPIDER, ctx -> new MonsterKindRenderers.SpiderKind(ctx, "hunter_spider"));
        EntityRendererRegistry.register(ModEntities.BAT_ZOMBIE, MonsterKindRenderers.BatZombieRenderer::new);
        EntityRendererRegistry.register(ModEntities.SPIRIT_WOLF, MonsterKindRenderers.SpiritWolf::new);
        EntityRendererRegistry.register(ModEntities.SPIRIT_STEED, SpiritSteedRenderer::new);
        EntityRendererRegistry.register(ModEntities.LIGHT_BUTTERFLY, LightButterflyRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(
                lightborn.WorkSpells.OwlSight.TYPE, (packet, ctx) -> OwlSightView.show(packet.ids()));
        EntityRendererRegistry.register(
                ModEntities.FROSTBITTEN_ZOMBIE, ctx -> new ZombieKindRenderer(ctx, "frostbitten_zombie", 1f));
        EntityRendererRegistry.register(ModEntities.MIRE_ZOMBIE, ctx -> new ZombieKindRenderer(ctx, "mire_zombie", 1f));
        EntityRendererRegistry.register(
                ModEntities.DUNE_SKELETON, ctx -> new MonsterKindRenderers.Skeleton(ctx, "dune_skeleton"));
        EntityRendererRegistry.register(
                ModEntities.CANOPY_SPIDER, ctx -> new MonsterKindRenderers.SpiderKind(ctx, "canopy_spider"));
        EntityRendererRegistry.register(
                ModEntities.DEEP_STALKER_SPIDER,
                ctx -> new MonsterKindRenderers.SpiderKind(ctx, "deep_stalker_spider"));
        EntityRendererRegistry.register(ModEntities.DROWNED_CAPTAIN, MonsterKindRenderers.Captain::new);
        GiantRenderers.register();
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
                lightborn.DarkPhantom.TYPE, DarkPhantomRenderer::new);
        LightChestBlock.openScreen = pos -> Minecraft.getInstance().gui.setScreen(new CollectionScreen(pos));
        ModelLayerRegistry.registerModelLayer(LightDragonModel.LAYER, LightDragonModel::layer);
        EntityRendererRegistry.register(ModEntities.LIGHT_DRAGON, LightDragonRenderer::new);
        EntityRendererRegistry.register(ModEntities.REFUGEE, RefugeeRenderer::new);
        LightChestBlock.ctrlDown = () -> Minecraft.getInstance().hasControlDown();
        LightChestBlock.giveOne = pos -> ClientPlayNetworking.send(new Net.GiveOne(pos));
        ClientPlayNetworking.registerGlobalReceiver(
                Net.RecipeShow.TYPE, (payload, ctx) -> CollectionScreen.receive(payload));
        ClientPlayNetworking.registerGlobalReceiver(
                DragonTalk.Reply.TYPE, (payload, ctx) -> ctx.client().gui.setScreen(new DragonDialogueScreen(payload)));
        ClientPlayNetworking.registerGlobalReceiver(
                TomeCycle.Strip.TYPE, (payload, ctx) -> TomeStripView.receive(payload));
        ClientPlayNetworking.registerGlobalReceiver(lightborn.Souls.Note.TYPE, (payload, ctx) -> {
            if (ctx.client().gui.screen() instanceof CollectionScreen screen) screen.receiveSpirit(payload);
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.Unlocked.TYPE, (payload, ctx) -> {
            if (ctx.client().gui.screen() instanceof CollectionScreen screen) screen.receiveUnlocked(payload);
        });
        ClientPlayNetworking.registerGlobalReceiver(lightborn.Cleanse.Show.TYPE, (payload, ctx) -> {
            if (ctx.client().gui.screen() instanceof CollectionScreen screen) screen.receiveCleanse(payload);
        });
        ClientPlayNetworking.registerGlobalReceiver(lightborn.ChestCrafting.Show.TYPE, (payload, ctx) -> {
            if (ctx.client().gui.screen() instanceof CollectionScreen screen) screen.receiveCrafting(payload);
        });
        ClientPlayNetworking.registerGlobalReceiver(LightShop.Show.TYPE, (payload, ctx) -> {
            if (ctx.client().gui.screen() instanceof CollectionScreen screen) screen.receiveShop(payload);
        });
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            var mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            // Creative search builds every item's tooltip on a worker thread and matches its text: these hints there
            // made "light" (or "new") find nearly every item (owner, 2026-11-16: search broken), and the player data
            // they read is not the worker's to touch. They belong only on a tooltip being shown.
            if (!mc.isSameThread()) return;
            int light = LightGear.ITEMS.indexOf(stack.getItem());
            if (light >= 0)
                lines.add(
                        1,
                        Component.literal("As strong as your best ore: "
                                        + new String[] {"Iron", "Diamond", "Netherite"}[LightGear.oreLevel(stack) - 1])
                                .withStyle(ChatFormatting.GOLD));
            if (light >= 0 && stack.isBroken()) {
                // Broken, the piece does nothing: its powers are not listed until it is mended (owner, 2026-11-20).
                lines.add(
                        1,
                        Component.literal("Broken: mend it with glowstone dust at an anvil")
                                .withStyle(ChatFormatting.RED));
            }
            if (light < 0 && stack.isBroken() && LightGear.keeps(stack))
                // Upgraded gear breaks and stays too (owner, 2026-11-23), mended with its own material.
                lines.add(1, Component.literal("Broken: mend it at an anvil").withStyle(ChatFormatting.RED));
            if (light >= 0 && !stack.isBroken()) {
                String[] mechanics = {
                    "Immune to Blindness while worn",
                    "Skeleton hits: 20% chance of a radiant skeleton-only burst",
                    "Immune to Poison while worn",
                    "Immune to Slowness while worn",
                    "Immune to Wither while worn",
                    "At night, spell impacts briefly leave light",
                    "While you glide, the ground beneath you lights up",
                    "At night, a blow it blocks sets the striker alight"
                };
                // Creative search builds every item's tooltip on a worker thread: a missing line must never throw.
                if (light < mechanics.length)
                    lines.add(Component.literal(mechanics[light]).withStyle(ChatFormatting.AQUA));
                // Every piece worn or held adds to the player's own light at night (radiance); owner, 2026-11-17.
                lines.add(Component.literal(
                                light == 1 || light == 5 || light == lightborn.LightGear.SHIELD
                                        ? "At night, gives off light while held"
                                        : "At night, gives off light while worn")
                        .withStyle(ChatFormatting.YELLOW));
            }
            // Upgrade Gear's perk, once bought into tier III: what the piece now does.
            int kind = lightborn.LightGearTree.kind(stack);
            if (kind >= 0 && lightborn.LightGearTree.chosen(stack)[1] >= 0) {
                var node = lightborn.LightGearTree.tiers(kind).get(1).get(lightborn.LightGearTree.chosen(stack)[1]);
                if (node.perk())
                    lines.add(
                            Component.literal(node.name() + ": " + node.text()).withStyle(ChatFormatting.GOLD));
            }
            if (kind >= 0 && lightborn.LightGearTree.chosen(stack)[2] >= 0) {
                var perk = lightborn.LightGearTree.tiers(kind).get(2).get(lightborn.LightGearTree.chosen(stack)[2]);
                lines.add(Component.literal(perk.name() + ": " + perk.text()).withStyle(ChatFormatting.GOLD));
            }
            if (stack.is(DragonStory.SCALE)) {
                lines.add(Component.literal("Use: reveal the current story destination")
                        .withStyle(ChatFormatting.AQUA));
                lines.add(Component.literal(
                                "After the ending: R calls your Dragon or takes Dragon Form; V breathes light")
                        .withStyle(ChatFormatting.GOLD));
            }

            Collection.build(mc.level.enabledFeatures(), mc.level.registryAccess());
            if (Collection.newForLight(mc.player, stack)) {
                lines.add(Component.literal("New for the Light").withStyle(ChatFormatting.GOLD));
            }
        });

        // The Spellstone's keys go first, before the game reads Q, R and V for themselves.
        ClientTickEvents.START_CLIENT_TICK.register(SpellstoneView::tick);
        net.minecraft.client.gui.screens.MenuScreens.register(lightborn.Spellstone.MENU, SpellstoneView.Screen::new);
        net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback.EVENT.register(
                data -> data instanceof lightborn.Spellstone.Slots s ? new SpellstoneView.Tooltip(s.tomes()) : null);
        BenchView.init();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            LightArmsView.tick(client);
            if (client.level != null) SurfaceLights.tick(client.level);
            ClientState.tick++;
            MusicDirector.tick();
            DragonTransitView.tick();
            WardThreshold.tick(client);
            WardGlow.tick(client);
            while (storyPower.consumeClick())
                if (client.player != null) ClientPlayNetworking.send(new DragonStory.Power(1));
            while (dragonBreath.consumeClick())
                if (client.player != null) ClientPlayNetworking.send(new DragonStory.Power(2));
            if (client.player == null) return;
        });

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.fromNamespaceAndPath("lightborn", "ward_threshold"),
                WardThreshold::extract);
        // Mana sits directly above the hearts; the armor row moves up by the bar's height to make room.
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.HEALTH_BAR,
                Identifier.fromNamespaceAndPath("lightborn", "mana_bar"),
                ModHud::extractManaBar);
        HudElementRegistry.replaceElement(VanillaHudElements.ARMOR_BAR, armor -> (g, delta) -> {
            g.pose().pushMatrix();
            g.pose().translate(0, ModHud.drop() - ModHud.manaRowHeight());
            armor.extractRenderState(g, delta);
            g.pose().popMatrix();
        });
        // No experience bar, so the rows above the hotbar close its empty strip (owner, 2026-11-23).
        for (var row : java.util.List.of(
                VanillaHudElements.HEALTH_BAR,
                VanillaHudElements.FOOD_BAR,
                VanillaHudElements.AIR_BAR,
                VanillaHudElements.MOUNT_HEALTH))
            HudElementRegistry.replaceElement(row, old -> (g, delta) -> {
                g.pose().pushMatrix();
                g.pose().translate(0, ModHud.drop());
                old.extractRenderState(g, delta);
                g.pose().popMatrix();
            });
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.HOTBAR,
                Identifier.fromNamespaceAndPath("lightborn", "spellstone"),
                SpellstoneView::extractHud);
        LevelRenderEvents.COLLECT_SUBMITS.register(LighthouseView::submit);
        LevelRenderEvents.COLLECT_SUBMITS.register(NestSmokeView::submit);
        LevelRenderEvents.COLLECT_SUBMITS.register(PortalView::submit);
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.fromNamespaceAndPath("lightborn", "shard_whispers"),
                (g, delta) -> ShardWhisperView.draw(g, delta.getGameTimeDeltaPartialTick(false)));
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.fromNamespaceAndPath("lightborn", "travel_cloud"),
                (g, delta) -> DragonTransitView.draw(g));
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.fromNamespaceAndPath("lightborn", "tome_strip"),
                (g, delta) -> TomeStripView.draw(g));
    }

    static Minecraft mc() {
        return Minecraft.getInstance();
    }
}
