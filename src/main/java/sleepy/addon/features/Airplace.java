package sleepy.addon.features;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import sleepy.addon.SleepyAddon;
import sleepy.addon.events.UpdateEvent;
import sleepy.addon.manager.PlacementManager;

public class Airplace extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> distance = sgGeneral.add(new DoubleSetting.Builder()
        .name("distance")
        .description("Distance from the crosshair to air place.")
        .defaultValue(3.0)
        .min(0.1)
        .sliderMax(4.5)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the target air place position.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shape is rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of the rendered box.")
        .defaultValue(new SettingColor(80, 180, 255, 25))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the rendered box.")
        .defaultValue(new SettingColor(80, 180, 255, 200))
        .build()
    );

    public Airplace() {
        super(SleepyAddon.CATEGORY, "airplace", "Places blocks in mid air.");
    }

    @Override
    public void onActivate() {
    }

    @EventHandler
    private void onTick(UpdateEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (!mc.options.useKey.isPressed()) return;

        if (mc.crosshairTarget instanceof BlockHitResult result && !mc.world.isAir(result.getBlockPos())) {
            return;
        }

        ItemStack stack = mc.player.getMainHandStack();
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) return;

        HitResult result = mc.player.raycast(distance.get(), 1.0f, false);
        if (!(result instanceof BlockHitResult blockHitResult)) return;

        if (mc.player.isUsingItem()) return;

        BlockPos blockPos = BlockPos.ofFloored(blockHitResult.getPos());
        if (!mc.world.isAir(blockPos) || isEntityInBlockPos(blockPos)) return;

        if (!(stack.getItem() instanceof BlockItem blockItem)) return;
        PlacementManager.get().tryPlace(blockPos, blockItem.getBlock());
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || mc.player == null || mc.world == null) return;

        if (mc.crosshairTarget instanceof BlockHitResult result && !mc.world.isAir(result.getBlockPos())) {
            return;
        }

        ItemStack stack = mc.player.getMainHandStack();
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) return;

        HitResult result = mc.player.raycast(distance.get(), 1.0f, false);
        if (!(result instanceof BlockHitResult blockHitResult)) return;

        BlockPos blockPos = BlockPos.ofFloored(blockHitResult.getPos());
        if (!mc.world.isAir(blockPos) || isEntityInBlockPos(blockPos)) return;

        event.renderer.box(blockPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }

    private boolean isEntityInBlockPos(BlockPos blockPos) {
        if (mc.world == null || blockPos == null) return true;
        Box box = new Box(blockPos);
        for (Entity entity : mc.world.getOtherEntities(mc.player, box)) {
            if (entity.getBoundingBox().intersects(box)) return true;
        }
        return false;
    }
}
