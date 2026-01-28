package sleepy.addon.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;
import net.minecraft.item.BlockItem;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import sleepy.addon.manager.PlacementManager;

public class TestPlaceCommand extends Command {
    private static final double PLACE_DISTANCE = 3.0;

    public TestPlaceCommand() {
        super("testplace", "Places the currently held block in front of you.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            if (mc.player == null || mc.world == null) {
                error("Not in game.");
                return 0;
            }

            if (!(mc.player.getMainHandStack().getItem() instanceof BlockItem)) {
                error("Hold a block in your main hand.");
                return 0;
            }

            BlockPos target = getTargetPos();
            if (target == null) {
                error("Failed to calculate target position.");
                return 0;
            }

            boolean placed = PlacementManager.get().tryPlace(target,
                ((BlockItem) mc.player.getMainHandStack().getItem()).getBlock());
            if (placed) info("Attempted place at (%d, %d, %d).", target.getX(), target.getY(), target.getZ());
            else error("Place failed.");

            return SINGLE_SUCCESS;
        });
    }

    private BlockPos getTargetPos() {
        if (mc.player == null) return null;
        Vec3d eye = mc.player.getCameraPosVec(1.0f);
        Vec3d look = mc.player.getRotationVec(1.0f);
        Vec3d target = eye.add(look.multiply(PLACE_DISTANCE));
        return BlockPos.ofFloored(target);
    }
}
