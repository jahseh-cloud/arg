package net.spindle;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.enums.NoteBlockInstrument;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

public final class SpindleBlocks {
	/** What the Hivemind is made of. Black, faintly corrupted, tough to dig and very hard to blow up. */
	public static final Block HIVEMASS = Registry.register(Registries.BLOCK, Spindle.id("hivemass"),
			new Block(AbstractBlock.Settings.create()
					.mapColor(MapColor.BLACK)
					.instrument(NoteBlockInstrument.BASEDRUM)
					.requiresTool()
					.strength(6.0f, 1200.0f)
					.sounds(BlockSoundGroup.SCULK)));

	public static final Item HIVEMASS_ITEM = Registry.register(Registries.ITEM, Spindle.id("hivemass"),
			new BlockItem(HIVEMASS, new Item.Settings()));

	public static void init() {
	}

	private SpindleBlocks() {
	}
}
