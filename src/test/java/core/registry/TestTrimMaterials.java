package core.registry;

import core.BlueprintTest;
import net.minecraft.Util;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstapContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.armortrim.TrimMaterial;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;

public class TestTrimMaterials {
	public static final ResourceKey<TrimMaterial> TEST = createKey("test");

	public static void bootstrap(BootstapContext<TrimMaterial> context) {
		register(context, TEST, Items.FLOWERING_AZALEA, Style.EMPTY.withColor(0), Map.of());
	}

	private static ResourceKey<TrimMaterial> createKey(String name) {
		return ResourceKey.create(Registries.TRIM_MATERIAL, new ResourceLocation(BlueprintTest.MOD_ID, name));
	}

	private static void register(BootstapContext<TrimMaterial> context, ResourceKey<TrimMaterial> key, Item item, Style style, Map<ArmorMaterials, String> overrides) {
		ResourceLocation location = key.location();
		context.register(key, new TrimMaterial(location.getNamespace() + "_" + location.getPath(), ForgeRegistries.ITEMS.getHolder(item).get(), -1.0F, overrides, Component.translatable(Util.makeDescriptionId("trim_material", location)).withStyle(style)));
	}
}
