package com.ashvehicles.block;

import com.ashvehicles.match.Deathmatch;
import com.ashvehicles.match.MatchPoint;
import com.ashvehicles.match.MatchState;
import com.ashvehicles.match.MatchTeam;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
 * 拠点の旗竿。試合中、周りの円の中に立ち続けた陣営がこれを取る。
 *
 * <p><b>置いただけでは拠点にならない。</b> 出撃地点と同じで、{@code /tdm point add} を通して初めて
 * 帳簿（{@link MatchPoint}）に載る。半径も名前もそちらが持つ——ブロックが持てば、試合が終わっても
 * 残って次の試合で嘘をつく。
 *
 * <p><b>自陣が握っている拠点は出撃地点にもなる。</b> 押し上げた側がそこから出られないなら、拠点を取る
 * 理由は数字が減ることだけになる。触ったときに開くのは出撃地点ブロックとまったく同じ盤で、判断も同じ
 * 1本（{@link Deathmatch#openDeploy}）を通る。
 *
 * <p>中立の拠点や敵の拠点を触った者には、今の持ち主と制圧の進み具合を1行返す。取りに来た者が知りたい
 * のは「あと何割か」であって、押せないボタンではない。
 */
public class CapturePointBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<CapturePointBlock> CODEC = simpleCodec(CapturePointBlock::new);

    public CapturePointBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public MapCodec<? extends CapturePointBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

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

        MatchState match = MatchState.of(server.server);
        MatchPoint point = match.pointAt(pos);

        if (point == null) {
            server.displayClientMessage(Component.translatable("message.ashvehicles.match.unclaimed_point"), true);

            return InteractionResult.CONSUME;
        }

        MatchTeam mine = match.teamOf(server.getUUID());

        // 自陣の拠点なら出撃盤。それ以外は今の様子を1行で。
        if (mine != null && mine.id().equals(point.owner())) {
            Deathmatch.openDeploy(server, pos);

            return InteractionResult.CONSUME;
        }

        MatchTeam owner = point.owner() == null ? null : match.team(point.owner());

        server.displayClientMessage(Component.translatable("message.ashvehicles.match.point_status",
                Component.literal(point.name()),
                owner == null ? Component.translatable("message.ashvehicles.match.neutral") : owner.display(),
                Math.round(point.fraction() * 100.0F)), true);

        return InteractionResult.CONSUME;
    }

    /** 壊された旗竿は帳簿からも消える。無い旗を取り続ける試合ほど分からないものはない。 */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement,
            boolean movedByPiston) {
        if (!level.isClientSide && !state.is(replacement.getBlock()) && level.getServer() != null) {
            MatchState match = MatchState.of(level.getServer());

            if (match.pointAt(pos) != null) {
                match.removePoint(pos);
                Deathmatch.sync(level.getServer(), match);
            }
        }

        super.onRemove(state, level, pos, replacement, movedByPiston);
    }
}
