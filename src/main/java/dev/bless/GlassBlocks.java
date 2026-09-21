package dev.bless;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** glass reflections' own reflective set (glass brief item 2), same shape as MetalBlocks: a tag
 * (a datapack's polite door, empty on a dedicated server since the client mod's own tag data never
 * reaches it) plus a straight registry resolve. unlike MetalBlocks' fixed id list, glass is walked
 * off the whole block registry once and kept by suffix -- "ends in glass or stained_glass, plus
 * tinted_glass" (brief item 2) collapses to one check, since every stained_glass id already ends in
 * "glass" and every pane variant ends in "pane" instead, so panes are excluded for free (brief item
 * 2's own exclusion: thin geometry, a cube mask would be wrong). grep-verified against
 * minecraft-client.jar's assets/minecraft/blockstates/*.json (26.2): exactly 18 ids match --
 * glass, tinted_glass, and the 16 colours of stained_glass -- and zero pane variants do. */
final class GlassBlocks {
	static final TagKey<Block> GLASS = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("bless", "glass"));
	// panes brief item 1: the pane twin of GLASS above -- ids ending in "glass_pane" (plain
	// glass_pane and the 16 stained variants, an IronBarsBlock subclass) plus a tag door of its own.
	static final TagKey<Block> GLASS_PANE = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("bless", "glass_pane"));
	private static volatile Set<Block> builtIn;
	private static volatile Set<Block> builtInPanes;

	private GlassBlocks() {}

	static boolean isGlass(BlockState state) {
		Block block = state.getBlock();
		if (builtIn == null) {
			Set<Block> resolved = new HashSet<>();
			for (Identifier id : BuiltInRegistries.BLOCK.keySet())
				if (id.getPath().endsWith("glass")) resolved.add(BuiltInRegistries.BLOCK.getValue(id));
			builtIn = resolved;
		}
		return builtIn.contains(block) || block.builtInRegistryHolder().is(GLASS);
	}

	/** panes brief item 1: same registry-walk shape as isGlass, "ends in glass_pane" instead --
	 * grep-verified against minecraft-client.jar's assets/minecraft/blockstates/*.json (26.2): the
	 * plain glass_pane plus the 16 stained_glass_pane colours, 17 ids, zero overlap with isGlass's set. */
	static boolean isPane(BlockState state) {
		Block block = state.getBlock();
		if (builtInPanes == null) {
			Set<Block> resolved = new HashSet<>();
			for (Identifier id : BuiltInRegistries.BLOCK.keySet())
				if (id.getPath().endsWith("glass_pane")) resolved.add(BuiltInRegistries.BLOCK.getValue(id));
			builtInPanes = resolved;
		}
		return builtInPanes.contains(block) || block.builtInRegistryHolder().is(GLASS_PANE);
	}

	/** panes brief item 1: the same colour rule isGlass' callers already use inline (white for
	 * plain, MapColor for stained) -- there is no tinted pane, so unlike isGlass/isTinted this is
	 * never excluded, only ever called for a state isPane() already accepted. */
	static int paneTint(BlockState state, net.minecraft.world.level.BlockGetter level, net.minecraft.core.BlockPos pos) {
		if (state.is(net.minecraft.world.level.block.Blocks.GLASS_PANE)) return 0xFFFFFF;
		var map = state.getMapColor(level, pos);
		return map == net.minecraft.world.level.material.MapColor.NONE ? 0xFFFFFF : map.col;
	}

	/** glass-light brief item 1: tinted_glass is the one member of isGlass() vanilla actually blocks
	 * light with (no noOcclusion() in its properties, unlike plain/stained glass) -- VoxelVolume's
	 * FLAG_FILTER and the shadow-tint draw both exclude it, leaving it to fall through to the ordinary
	 * canOcclude() occluder path instead of colouring anything. */
	static boolean isTinted(BlockState state) {
		return state.is(net.minecraft.world.level.block.Blocks.TINTED_GLASS);
	}

	/** the exact ids the registry walk found, for a handback/status read -- not called from any hot
	 * path (isGlass above never calls this; it only reuses the cached Set<Block>). */
	static java.util.List<String> resolvedIds() {
		isGlass(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()); // forces builtIn to populate
		var ids = new java.util.ArrayList<String>();
		for (Block block : builtIn) ids.add(BuiltInRegistries.BLOCK.getKey(block).toString());
		java.util.Collections.sort(ids);
		return ids;
	}
}
