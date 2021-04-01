package appeng.client.render.model;

import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.renderer.model.BakedQuad;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.client.renderer.model.ItemCameraTransforms;
import net.minecraft.client.renderer.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockDisplayReader;
import appeng.api.implementations.items.IBiometricCard;
import appeng.api.util.AEColor;
import appeng.client.render.cablebus.CubeBuilder;

class BiometricCardBakedModel implements IBakedModel, FabricBakedModel {

    private final IBakedModel baseModel;

    private final TextureAtlasSprite texture;

    BiometricCardBakedModel(IBakedModel baseModel, TextureAtlasSprite texture) {
        this.baseModel = baseModel;
        this.texture = texture;
    }

    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    @Override
    public void emitBlockQuads(IBlockDisplayReader blockView, BlockState state, BlockPos pos,
            Supplier<Random> randomSupplier, RenderContext context) {
        // Not intended as a block
    }

    @Override
    public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier, RenderContext context) {
        context.fallbackConsumer().accept(this.baseModel);

        // Get the player's name hash from the card
        int hash = getHash(stack);

        emitColorCode(context.getEmitter(), hash);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, Random rand) {
        return this.baseModel.getQuads(state, side, rand);
    }

    private void emitColorCode(QuadEmitter emitter, int hash) {
        CubeBuilder builder = new CubeBuilder(emitter);

        builder.setTexture(this.texture);

        AEColor col = AEColor.values()[Math.abs(3 + hash) % AEColor.values().length];
        if (hash == 0) {
            col = AEColor.BLACK;
        }

        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 6; y++) {
                final boolean isLit;

                // This makes the border always use the darker color
                if (x == 0 || y == 0 || x == 7 || y == 5) {
                    isLit = false;
                } else {
                    isLit = (hash & (1 << x)) != 0 || (hash & (1 << y)) != 0;
                }

                if (isLit) {
                    builder.setColorRGB(col.mediumVariant);
                } else {
                    final float scale = 0.3f / 255.0f;
                    builder.setColorRGB(((col.blackVariant >> 16) & 0xff) * scale,
                            ((col.blackVariant >> 8) & 0xff) * scale, (col.blackVariant & 0xff) * scale);
                }

                builder.addCube(4 + x, 6 + y, 7.5f, 4 + x + 1, 6 + y + 1, 8.5f);
            }
        }
    }

    @Override
    public boolean isAmbientOcclusion() {
        return this.baseModel.isAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return this.baseModel.isGui3d();
    }

    @Override
    public boolean isSideLit() {
        return false; // This is an item model
    }

    @Override
    public boolean isBuiltInRenderer() {
        return this.baseModel.isBuiltInRenderer();
    }

    @Override
    public TextureAtlasSprite getParticleTexture() {
        return this.baseModel.getParticleTexture();
    }

    @Override
    public ItemCameraTransforms getItemCameraTransforms() {
        return this.baseModel.getItemCameraTransforms();
    }

    @Override
    public ItemOverrideList getOverrides() {
        return ItemOverrideList.EMPTY;
    }

    private static int getHash(ItemStack stack) {
        String username = "";
        if (stack.getItem() instanceof IBiometricCard) {
            final GameProfile gp = ((IBiometricCard) stack.getItem()).getProfile(stack);
            if (gp != null) {
                if (gp.getId() != null) {
                    username = gp.getId().toString();
                } else {
                    username = gp.getName();
                }
            }
        }

        return !username.isEmpty() ? username.hashCode() : 0;
    }

}
