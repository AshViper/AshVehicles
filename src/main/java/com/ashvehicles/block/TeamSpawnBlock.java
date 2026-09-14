package com.ashvehicles.block;

import com.ashvehicles.match.Deathmatch;
import com.ashvehicles.match.MatchState;
import com.ashvehicles.match.MatchTeam;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 出撃地点。試合中、自陣の物を触れば機体を選んで出撃できる。
 *
 * <p><b>ブロックは陣営を持たない。</b> どの陣営の旗かを持っているのは試合の帳簿
 * （{@link MatchState#teamAt}）で、置いただけのブロックはまだ誰の物でもない。
 * {@code /tdm spawn <陣営>} が登録して初めて旗になる。ブロック側に持たせなかったのは、試合が終われば
 * 陣営ごと消えるべき情報だからだ——ブロックに書けば、次の試合まで残って嘘をつく。
 *
 * <p>向きは置いた向き。湧いた機体はそちらを向くので、滑走路と平行に置けば機体は滑走路を向いて出る。
 *
 * <p>触ったときに開くのは画面だが、開けるかどうかを決めるのはサーバーだ。無所属の者や敵陣の旗に対して
 * は画面すら出さず1行返す。クライアントは自分がどの陣営かを知っているが、それは配られた写しであって
 * 判断の根拠にはしない。
 */
public class TeamSpawnBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<TeamSpawnBlock> CODEC = simpleCodec(TeamSpawnBlock::new);

    public TeamSpawnBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public MapCodec<? extends TeamSpawnBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** 置いた者が見ている向き。機体はそちらへ機首を向けて出る。 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        if (!(player instanceof ServerPlayer server)) {
            return InteractionResult.CONSUME;
        }

        Deathmatch.openDeploy(server, pos);

        return InteractionResult.CONSUME;
    }

    /**
     * 壊された旗は登録からも消える。残しておけば、そこに何も無いのに湧かせようとする画面が開く。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement,
            boolean movedByPiston) {
        if (!level.isClientSide && !state.is(replacement.getBlock()) && level.getServer() != null) {
            MatchState match = MatchState.of(level.getServer());
            MatchTeam owner = match.teamAt(pos);

            if (owner != null) {
                owner.removeSpawn(pos);
                match.setDirty();
                Deathmatch.sync(level.getServer(), match);
            }
        }

        super.onRemove(state, level, pos, replacement, movedByPiston);
    }
}
