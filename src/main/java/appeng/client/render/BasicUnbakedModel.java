package appeng.client.render;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.mojang.datafixers.util.Pair;

import net.minecraft.client.render.model.UnbakedModel;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.util.Identifier;

/**
 * An unbaked model that has standard models as a dependency and produces a custom baked model as a result.
 */
public interface BasicUnbakedModel extends UnbakedModel {

    @Override
    default Collection<Identifier> getModelDependencies() {
        return Collections.emptyList();
    }

    default Stream<SpriteIdentifier> getAdditionalTextures() {
        return Stream.empty();
    }

    @Override
    default Collection<SpriteIdentifier> getTextureDependencies(Function<Identifier, UnbakedModel> unbakedModelGetter,
            Set<Pair<String, String>> unresolvedTextureReferences) {
        return Stream.concat(
                getModelDependencies().stream().map(unbakedModelGetter).flatMap(
                        ubm -> ubm.getTextureDependencies(unbakedModelGetter, unresolvedTextureReferences).stream()),
                getAdditionalTextures()).collect(Collectors.toList());
    }

}
