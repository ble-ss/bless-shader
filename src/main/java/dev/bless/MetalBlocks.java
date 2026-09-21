package dev.bless;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** which blocks are mirrors. the tag is the polite door (a datapack can widen it), but a block tag
 * only exists on the client when the server it joined sends it, and a client-only mod's data never
 * reaches a dedicated server: on the bench and on rori's hosted server the tag is empty. so the same
 * list is also resolved straight from the registry at first use, and a block is a mirror if either
 * says so. */
final class MetalBlocks {
	static final TagKey<Block> REFLECTIVE = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("bless", "reflective"));
	private static final List<String> IDS = List.of("minecraft:iron_block", "minecraft:gold_block", "minecraft:diamond_block", "minecraft:emerald_block", "minecraft:netherite_block", "minecraft:lapis_block", "minecraft:copper_block", "minecraft:exposed_copper", "minecraft:weathered_copper", "minecraft:oxidized_copper", "minecraft:cut_copper", "minecraft:exposed_cut_copper", "minecraft:weathered_cut_copper", "minecraft:oxidized_cut_copper", "minecraft:chiseled_copper", "minecraft:exposed_chiseled_copper", "minecraft:weathered_chiseled_copper", "minecraft:oxidized_chiseled_copper", "minecraft:waxed_copper_block", "minecraft:waxed_exposed_copper", "minecraft:waxed_weathered_copper", "minecraft:waxed_oxidized_copper", "minecraft:waxed_cut_copper", "minecraft:waxed_exposed_cut_copper", "minecraft:waxed_weathered_cut_copper", "minecraft:waxed_oxidized_cut_copper", "minecraft:waxed_chiseled_copper", "minecraft:waxed_exposed_chiseled_copper", "minecraft:waxed_weathered_chiseled_copper", "minecraft:waxed_oxidized_chiseled_copper", "minecraft:polished_deepslate", "minecraft:smooth_quartz", "minecraft:dark_prismarine", "minecraft:packed_ice", "minecraft:blue_ice");
	private static volatile Set<Block> builtIn;

	private MetalBlocks() {}

	static boolean isReflective(BlockState state) {
		Block block = state.getBlock();
		if (builtIn == null) {
			Set<Block> resolved = new HashSet<>();
			for (String id : IDS) BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).ifPresent(resolved::add);
			builtIn = resolved;
		}
		return builtIn.contains(block) || block.builtInRegistryHolder().is(REFLECTIVE);
	}
}
