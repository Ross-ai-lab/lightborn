package lightborn;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.block.Blocks;

/**
 * The dawn refugee: one night in five, arrives running from outside the ward, pleads at night and offers three places
 * at dawn, leads the way and waits for a straggler, stays on arrival and is gone once unwatched; can be sent off or
 * fought.
 */
final class SelfTestRefugee {
    private SelfTestRefugee() {}

    static void check(String name, boolean ok, Object detail) {
        SelfTest.check("refugee_" + name, ok, detail);
    }

    private static Refugee make(ServerLevel l, BlockPos chest, int state) {
        var r = ModEntities.REFUGEE.create(l, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        r.snapTo(chest.getX() + 2.5, chest.getY(), chest.getZ() + .5, 0, 0);
        r.chest = chest;
        r.state = state;
        l.addFreshEntity(r);
        return r;
    }

    private static void look(FakePlayer p, double x, double z) {
        float yaw = (float) Math.toDegrees(Math.atan2(z - p.getZ(), x - p.getX())) - 90;
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
        p.setXRot(0);
    }

    static void run(ServerLevel l, BlockPos at) {
        var clock = l.registryAccess().getOrThrow(WorldClocks.OVERWORLD);
        long oldTime = l.getOverworldClockTime();
        var shownBefore = l.getAttachedOrElse(Refugee.SHOWN, List.of());
        BlockPos chest = at.offset(-320, 40, 320);
        List<Refugee> made = new ArrayList<>();
        var owner = FakePlayer.get(l, new GameProfile(UUID.randomUUID(), "RefugeeHost"));
        try {
            for (int x = -26; x <= 26; x++)
                for (int z = -26; z <= 26; z++)
                    l.setBlockAndUpdate(chest.offset(x, -1, z), Blocks.STONE.defaultBlockState());

            int nights = 0;
            for (long day = 0; day < 2000; day++) if (Refugee.arrives(l, chest, day)) nights++;
            check("one_night_in_five", nights > 330 && nights < 470, nights + "/2000");

            var runner = Refugee.spawn(l, chest, 6, 0);
            double out = runner == null
                    ? -1
                    : Math.hypot(runner.getX() - chest.getX() - .5, runner.getZ() - chest.getZ() - .5);
            if (runner != null) made.add(runner);
            check(
                    "arrives_running_from_outside_the_ward",
                    runner != null
                            && runner.state == Refugee.RUNNING
                            && out > 17
                            && out < 23
                            && !LightWards.blocked(runner)
                            && runner.getCustomName() != null,
                    out);

            long day = Math.floorDiv(oldTime, 24000) + 1;
            l.clockManager().setTotalTicks(clock, day * 24000 + 18_000);
            owner.setPos(chest.getX() + .5, chest.getY(), chest.getZ() + 3.5);
            var guest = make(l, chest, Refugee.SHELTERED);
            made.add(guest);
            var plea = guest.respond(owner, 0);
            var stay = guest.respond(owner, Refugee.STAY);
            check(
                    "pleads_at_night",
                    plea.choices().size() == 2 // stay or send away; Escape just closes
                            && plea.choices().get(0).action() == Refugee.STAY
                            && plea.speaker().equals(guest.name())
                            && stay.text().startsWith("Thank you")
                            && guest.state == Refugee.SHELTERED,
                    plea);

            l.clockManager().setTotalTicks(clock, (day + 1) * 24000 + 1000);
            BlockPos a = chest.offset(200, 0, 0), b = chest.offset(0, 0, -60), c = chest.offset(-150, 0, 150);
            guest.offers.addAll(List.of(
                    new Refugee.Site("a village", a),
                    new Refugee.Site("a witch hut", b),
                    new Refugee.Site("an igloo", c)));
            guest.searchDone = true;
            var offer = guest.respond(owner, 0);
            check(
                    "offers_three_places_at_dawn",
                    offer.choices().size() == 3
                            && offer.choices().get(1).label().startsWith("Show me a witch hut")
                            && offer.choices().get(1).label().contains("north"),
                    offer.choices());
            guest.respond(owner, Refugee.PICK + 1);
            check(
                    "chosen_place_is_led_to_and_never_offered_again",
                    guest.state == Refugee.GUIDING
                            && b.equals(guest.target)
                            && owner.getUUID().equals(guest.guided)
                            && l.getAttachedOrElse(Refugee.SHOWN, List.of()).contains(b.asLong())
                            && !Refugee.fresh(l, chest, b.offset(10, 0, 10)),
                    guest.target);

            owner.setPos(guest.getX(), guest.getY(), guest.getZ() + 40);
            guest.guideStep(l, owner);
            boolean waited = guest.waiting;
            owner.setPos(guest.getX(), guest.getY(), guest.getZ() + 5);
            guest.guideStep(l, owner);
            check("waits_for_a_straggler", waited && !guest.waiting, waited + " / " + guest.waiting);

            guest.teleportTo(b.getX() + 3.5, guest.getY(), b.getZ() + .5);
            guest.guideStep(l, owner);
            check("stays_on_arrival", guest.state == Refugee.ARRIVED, guest.state);

            // Watched, they stay; once nobody looks for three seconds (and nobody stands close), they are gone.
            owner.setPos(guest.getX() + 20, guest.getY(), guest.getZ());
            look(owner, guest.getX(), guest.getZ());
            for (int i = 0; i < 80; i++) guest.lingerStep(l, List.of(owner));
            boolean stayedWatched = !guest.isRemoved();
            look(owner, guest.getX() + 60, guest.getZ());
            for (int i = 0; i < 61; i++) guest.lingerStep(l, List.of(owner));
            check(
                    "leaves_when_unwatched",
                    stayedWatched && guest.isRemoved(),
                    stayedWatched + " / " + guest.isRemoved());

            check(
                    "only_unfound_places",
                    !Refugee.fresh(l, chest, chest.offset(20, 0, 0))
                            && Refugee.compass(0, -10).equals("north"),
                    Refugee.compass(0, -10));

            var kicked = make(l, chest, Refugee.SHELTERED);
            made.add(kicked);
            kicked.respond(owner, Refugee.SEND_AWAY);
            check("can_be_sent_away", kicked.state == Refugee.LEAVING, kicked.state);

            var fought = make(l, chest, Refugee.SHELTERED);
            made.add(fought);
            owner.setPos(fought.getX() + 1, fought.getY(), fought.getZ());
            fought.hurtServer(l, l.damageSources().playerAttack(owner), 1);
            check(
                    "fights_back_when_hit",
                    fought.state == Refugee.HOSTILE && fought.getTarget() == owner,
                    fought.state + " " + fought.getTarget());

            var empty = make(l, chest, Refugee.SHELTERED);
            made.add(empty);
            empty.searchDone = true;
            int sparks = LightShop.carriedSparks(owner);
            empty.respond(owner, 0);
            check(
                    "no_place_left_pays_sparks",
                    LightShop.carriedSparks(owner) - sparks == Balance.REFUGEE_FALLBACK_SPARKS
                            && empty.state == Refugee.LEAVING,
                    LightShop.carriedSparks(owner) - sparks);
        } finally {
            l.clockManager().setTotalTicks(clock, oldTime);
            l.setAttached(Refugee.SHOWN, shownBefore);
            for (var r : made) r.discard();
            for (int x = -26; x <= 26; x++)
                for (int z = -26; z <= 26; z++)
                    l.setBlockAndUpdate(chest.offset(x, -1, z), Blocks.AIR.defaultBlockState());
            owner.discard();
        }
    }
}
