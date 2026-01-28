package sleepy.addon;

import sleepy.addon.hud.InvLimit;
import sleepy.addon.hud.PlaceLimit;
import sleepy.addon.features.AntiCheat;
import sleepy.addon.features.Airplace;
import sleepy.addon.features.Printer;
import sleepy.addon.features.SilentMine;
import sleepy.addon.commands.TestPlaceCommand;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import sleepy.addon.util.SwapLimiter;

public class SleepyAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("SleepyAddon");
    public static final HudGroup HUD_GROUP = new HudGroup("SleepyAddon");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Meteor Addon Template");

        // Modules
        Modules.get().add(new Airplace());
        Modules.get().add(new Printer());
        Modules.get().add(new SilentMine());
        Modules.get().add(new AntiCheat());
        Modules.get().get(AntiCheat.class).toggle();

        // Commands
        Commands.add(new TestPlaceCommand());

        // Packet limiter
        MeteorClient.EVENT_BUS.subscribe(SwapLimiter.class);

        // HUD
        Hud.get().register(InvLimit.INFO);
        Hud.get().register(PlaceLimit.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "sleepy.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("Sleepy", "SleepyAddon");
    }
}
