package sleepy.addon.util;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashSet;
import java.util.Set;

public final class MossUtil {
    private static final int AZALEA_CLEAR_HEIGHT = 7;
    public static final int MOSS_PATCH_MAX_HORIZONTAL_RADIUS = 3;
    public static final int MOSS_PATCH_VERTICAL_RANGE = 5;

    private MossUtil() {
    }

    public static boolean isGrassPlant(BlockState state) {
        return state != null && (state.isOf(Blocks.SHORT_GRASS) || state.isOf(Blocks.TALL_GRASS));
    }

    public static boolean isAzalea(BlockState state) {
        return state != null && (state.isOf(Blocks.AZALEA) || state.isOf(Blocks.FLOWERING_AZALEA));
    }

    public static boolean isSnowLayer(BlockState state) {
        return state != null && state.isOf(Blocks.SNOW);
    }

    public static boolean isMossBlock(BlockState state) {
        return state != null && (state.isOf(Blocks.MOSS_BLOCK) || state.isOf(Blocks.PALE_MOSS_BLOCK));
    }

    public static boolean isMossCarpet(BlockState state) {
        return state != null && (state.isOf(Blocks.MOSS_CARPET) || state.isOf(Blocks.PALE_MOSS_CARPET));
    }

    public static boolean isSafeMossObstruction(BlockState state) {
        if (state == null || state.isAir()) return false;
        if (state.isOf(Blocks.OAK_LOG)) return false;
        return isGrassPlant(state) || isMossCarpet(state) || isAzalea(state) || isSnowLayer(state);
    }

    public static boolean canMossActuallySpreadTo(ClientWorld world, BlockPos groundPos) {
        if (world == null || groundPos == null) return false;
        BlockPos airPos = groundPos.up();
        if (!world.isChunkLoaded(groundPos) || !world.isChunkLoaded(airPos)) return false;

        BlockState groundState = world.getBlockState(groundPos);
        if (isMossBlock(groundState)) return false;
        if (!groundState.isIn(BlockTags.MOSS_REPLACEABLE)) return false;

        return world.getBlockState(airPos).isAir();
    }

    public static boolean canMossSpreadToAfterClearingSnow(ClientWorld world, BlockPos groundPos) {
        if (world == null || groundPos == null) return false;
        BlockPos snowPos = groundPos.up();
        if (!world.isChunkLoaded(groundPos) || !world.isChunkLoaded(snowPos)) return false;

        BlockState groundState = world.getBlockState(groundPos);
        if (isMossBlock(groundState)) return false;
        if (!groundState.isIn(BlockTags.MOSS_REPLACEABLE)) return false;

        return isSnowLayer(world.getBlockState(snowPos));
    }

    public static boolean canUseMossSource(ClientWorld world, BlockPos mossPos) {
        return canUseMossSource(world, mossPos, false);
    }

    public static Set<BlockPos> findMossPatchSpreadTargets(ClientWorld world, BlockPos mossPos) {
        return findMossPatchSpreadTargets(world, mossPos, false);
    }

    public static Set<BlockPos> findMossPatchSpreadTargets(ClientWorld world, BlockPos mossPos, boolean assumeSourceMoss) {
        Set<BlockPos> targets = new HashSet<>();
        if (!canUseMossSource(world, mossPos, assumeSourceMoss)) return targets;

        for (int dx = -MOSS_PATCH_MAX_HORIZONTAL_RADIUS; dx <= MOSS_PATCH_MAX_HORIZONTAL_RADIUS; dx++) {
            for (int dz = -MOSS_PATCH_MAX_HORIZONTAL_RADIUS; dz <= MOSS_PATCH_MAX_HORIZONTAL_RADIUS; dz++) {
                if (!isPossibleBonemealPatchColumn(dx, dz)) continue;

                BlockPos ground = findMossPatchGroundFromSource(world, mossPos, dx, dz, assumeSourceMoss);
                if (ground == null || !canMossActuallySpreadTo(world, ground)) continue;
                targets.add(ground);
            }
        }

        return targets;
    }

    public static Set<BlockPos> findMossPatchSnowLayerTargets(ClientWorld world, BlockPos mossPos) {
        Set<BlockPos> targets = new HashSet<>();
        if (!canUseMossSource(world, mossPos, false)) return targets;

        for (int dx = -MOSS_PATCH_MAX_HORIZONTAL_RADIUS; dx <= MOSS_PATCH_MAX_HORIZONTAL_RADIUS; dx++) {
            for (int dz = -MOSS_PATCH_MAX_HORIZONTAL_RADIUS; dz <= MOSS_PATCH_MAX_HORIZONTAL_RADIUS; dz++) {
                if (!isPossibleBonemealPatchColumn(dx, dz)) continue;

                BlockPos ground = findMossPatchGroundFromSource(world, mossPos, dx, dz, false, true);
                if (ground == null || !canMossSpreadToAfterClearingSnow(world, ground)) continue;
                targets.add(ground.up().toImmutable());
            }
        }

        return targets;
    }

    public static boolean isNearMossSpreadEdge(ClientWorld world, BlockPos mossPos, int horizontalRadius) {
        if (world == null || mossPos == null || horizontalRadius <= 0) return false;
        if (!world.isChunkLoaded(mossPos) || !isMossBlock(world.getBlockState(mossPos))) return false;

        int radius = Math.min(horizontalRadius, MOSS_PATCH_MAX_HORIZONTAL_RADIUS);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) continue;

                BlockPos checkPos = mossPos.add(dx, 0, dz);
                if (!world.isChunkLoaded(checkPos)) continue;
                if (!isMossBlock(world.getBlockState(checkPos))) {
                    return true;
                }
            }
        }

        return false;
    }

    public static BlockPos findMossPatchGroundFromSource(ClientWorld world, BlockPos mossPos, int offsetX, int offsetZ) {
        return findMossPatchGroundFromSource(world, mossPos, offsetX, offsetZ, false);
    }

    public static BlockPos findMossPatchGroundFromSource(ClientWorld world, BlockPos mossPos, int offsetX, int offsetZ, boolean assumeSourceMoss) {
        return findMossPatchGroundFromSource(world, mossPos, offsetX, offsetZ, assumeSourceMoss, false);
    }

    private static BlockPos findMossPatchGroundFromSource(ClientWorld world, BlockPos mossPos, int offsetX, int offsetZ,
                                                         boolean assumeSourceMoss, boolean treatSnowAsAir) {
        if (!canUseMossSource(world, mossPos, assumeSourceMoss)) return null;
        if (!isPossibleBonemealPatchColumn(offsetX, offsetZ)) return null;

        BlockPos.Mutable scan = mossPos.up().add(offsetX, 0, offsetZ).mutableCopy();
        int moved = 0;
        while (isFeatureAir(world, scan, mossPos, assumeSourceMoss, treatSnowAsAir) && moved < MOSS_PATCH_VERTICAL_RANGE) {
            scan.move(Direction.DOWN);
            moved++;
        }

        moved = 0;
        while (isFeatureSolid(world, scan, mossPos, assumeSourceMoss, treatSnowAsAir) && moved < MOSS_PATCH_VERTICAL_RANGE) {
            scan.move(Direction.UP);
            moved++;
        }

        BlockPos airPos = scan.toImmutable();
        BlockPos groundPos = airPos.down();
        if (!world.isChunkLoaded(airPos) || !world.isChunkLoaded(groundPos)) return null;
        if (!isFeatureAir(world, airPos, mossPos, assumeSourceMoss, treatSnowAsAir)) return null;
        if (!isSolidTopSurface(world, groundPos, mossPos, assumeSourceMoss)) return null;

        return groundPos.toImmutable();
    }

    public static int countMossPatchSpreadTargets(ClientWorld world, BlockPos mossPos) {
        return findMossPatchSpreadTargets(world, mossPos).size();
    }

    private static boolean canUseMossSource(ClientWorld world, BlockPos mossPos, boolean assumeSourceMoss) {
        if (world == null || mossPos == null) return false;
        if (!world.isChunkLoaded(mossPos) || !world.isChunkLoaded(mossPos.up())) return false;
        if (!assumeSourceMoss && !isMossBlock(world.getBlockState(mossPos))) return false;

        BlockState aboveState = world.getBlockState(mossPos.up());
        return aboveState.isAir() || isSafeMossObstruction(aboveState);
    }

    private static boolean isPossibleBonemealPatchColumn(int offsetX, int offsetZ) {
        int absX = Math.abs(offsetX);
        int absZ = Math.abs(offsetZ);
        if (absX > MOSS_PATCH_MAX_HORIZONTAL_RADIUS || absZ > MOSS_PATCH_MAX_HORIZONTAL_RADIUS) return false;
        return absX < MOSS_PATCH_MAX_HORIZONTAL_RADIUS || absZ < MOSS_PATCH_MAX_HORIZONTAL_RADIUS;
    }

    private static boolean isFeatureAir(ClientWorld world, BlockPos pos, BlockPos sourceMoss, boolean assumeSourceMoss,
                                        boolean treatSnowAsAir) {
        if (world == null || pos == null || !world.isChunkLoaded(pos)) return false;
        if (assumeSourceMoss && pos.equals(sourceMoss)) return false;
        if (treatSnowAsAir && isSnowLayer(world.getBlockState(pos))) return true;
        if (pos.equals(sourceMoss.up())) {
            BlockState sourceTopState = world.getBlockState(pos);
            return sourceTopState.isAir() || isSafeMossObstruction(sourceTopState);
        }
        return world.getBlockState(pos).isAir();
    }

    private static boolean isFeatureSolid(ClientWorld world, BlockPos pos, BlockPos sourceMoss, boolean assumeSourceMoss,
                                          boolean treatSnowAsAir) {
        return world != null && pos != null && world.isChunkLoaded(pos)
            && !isFeatureAir(world, pos, sourceMoss, assumeSourceMoss, treatSnowAsAir);
    }

    private static boolean isSolidTopSurface(ClientWorld world, BlockPos groundPos, BlockPos sourceMoss, boolean assumeSourceMoss) {
        if (world == null || groundPos == null || !world.isChunkLoaded(groundPos)) return false;
        if (assumeSourceMoss && groundPos.equals(sourceMoss)) return true;
        return world.getBlockState(groundPos).isSideSolidFullSquare(world, groundPos, Direction.UP);
    }

    public static boolean canFallbackMossStartAt(ClientWorld world, BlockPos groundPos) {
        if (!canMossActuallySpreadTo(world, groundPos)) return false;

        BlockPos placePos = groundPos.up();
        BlockPos interactAirPos = placePos.up();
        return world.isChunkLoaded(interactAirPos) && world.getBlockState(interactAirPos).isAir();
    }

    public static boolean hasNearbyMossSource(ClientWorld world, BlockPos pos) {
        if (world == null || pos == null) return false;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    BlockPos sourcePos = pos.add(dx, dy, dz);
                    if (!world.isChunkLoaded(sourcePos)) continue;
                    if (isMossBlock(world.getBlockState(sourcePos))) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public static boolean canAzaleaTreeGrowAt(ClientWorld world, BlockPos azaleaPos) {
        if (world == null || azaleaPos == null) return false;
        if (!world.isChunkLoaded(azaleaPos)) return false;
        if (!isAzalea(world.getBlockState(azaleaPos))) return false;

        for (int y = 1; y <= AZALEA_CLEAR_HEIGHT; y++) {
            BlockPos pos = azaleaPos.up(y);
            if (!world.isChunkLoaded(pos) || !isTreeSpaceReplaceable(world.getBlockState(pos))) {
                return false;
            }
        }

        for (int y = 3; y <= AZALEA_CLEAR_HEIGHT; y++) {
            int radius = y == AZALEA_CLEAR_HEIGHT ? 2 : 3;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if ((dx * dx + dz * dz) > radius * radius) continue;

                    BlockPos pos = azaleaPos.add(dx, y, dz);
                    if (!world.isChunkLoaded(pos) || !isTreeSpaceReplaceable(world.getBlockState(pos))) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    public static boolean hasOakLogWithin(ClientWorld world, BlockPos center, int horizontalRadius, int verticalDown, int verticalUp) {
        if (world == null || center == null) return false;

        int spacingSq = horizontalRadius * horizontalRadius;
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dy = -verticalDown; dy <= verticalUp; dy++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                    if ((dx * dx + dz * dz) > spacingSq) continue;

                    BlockPos pos = center.add(dx, dy, dz);
                    if (!world.isChunkLoaded(pos)) continue;
                    if (world.getBlockState(pos).isOf(Blocks.OAK_LOG)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public static int nearestGrassBlockDistanceSq(ClientWorld world, BlockPos center, int horizontalRadius) {
        if (world == null || center == null) return Integer.MAX_VALUE;

        int best = Integer.MAX_VALUE;
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    if (!world.isChunkLoaded(pos)) continue;
                    if (!world.getBlockState(pos).isOf(Blocks.GRASS_BLOCK)) continue;

                    int distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq < best) {
                        best = distSq;
                    }
                }
            }
        }

        return best;
    }

    private static boolean isTreeSpaceReplaceable(BlockState state) {
        if (state == null) return false;
        if (!state.getFluidState().isEmpty()) return false;
        return state.isAir()
            || state.isIn(BlockTags.REPLACEABLE_BY_TREES)
            || state.isIn(BlockTags.LEAVES)
            || state.isReplaceable();
    }
}
