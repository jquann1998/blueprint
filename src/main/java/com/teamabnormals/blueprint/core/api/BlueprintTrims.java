package com.teamabnormals.blueprint.core.api;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import com.teamabnormals.blueprint.client.renderer.texture.atlas.BlueprintPalettedPermutations;
import com.teamabnormals.blueprint.core.Blueprint;
import com.teamabnormals.blueprint.core.other.tags.BlueprintTrimMaterialTags;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.ItemOverride;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.atlas.sources.DirectoryLister;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.models.ItemModelGenerators;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.armortrim.TrimMaterial;
import net.minecraft.world.item.armortrim.TrimPattern;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;
import java.util.function.BiFunction;

/**
 * Class for managing Blueprint's Armor Trims API.
 * <p><b>WARNING: Still somewhat experimental</b></p>
 *
 * @author SmellyModder (Luke Tonon)
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Blueprint.MOD_ID, value = Dist.CLIENT)
public class BlueprintTrims {
	public static final ResourceLocation ARMOR_TRIMS_ATLAS = new ResourceLocation("armor_trims");
	private static final ResourceLocation PALETTE_KEY = new ResourceLocation("trims/color_palettes/trim_palette");
	private static final HashMap<String, ResourceLocation> PERMUTATIONS = Util.make(new HashMap<>(), map -> {
		map.put("quartz", new ResourceLocation("trims/color_palettes/quartz"));
		map.put("iron", new ResourceLocation("trims/color_palettes/iron"));
		map.put("gold", new ResourceLocation("trims/color_palettes/gold"));
		map.put("diamond", new ResourceLocation("trims/color_palettes/diamond"));
		map.put("netherite", new ResourceLocation("trims/color_palettes/netherite"));
		map.put("redstone", new ResourceLocation("trims/color_palettes/redstone"));
		map.put("copper", new ResourceLocation("trims/color_palettes/copper"));
		map.put("emerald", new ResourceLocation("trims/color_palettes/emerald"));
		map.put("lapis", new ResourceLocation("trims/color_palettes/lapis"));
		map.put("amethyst", new ResourceLocation("trims/color_palettes/amethyst"));
		map.put("iron_darker", new ResourceLocation("trims/color_palettes/iron_darker"));
		map.put("gold_darker", new ResourceLocation("trims/color_palettes/gold_darker"));
		map.put("diamond_darker", new ResourceLocation("trims/color_palettes/diamond_darker"));
		map.put("netherite_darker", new ResourceLocation("trims/color_palettes/netherite_darker"));
	});
	private static final List<ResourceLocation> ITEM_TRIMS = List.of(
			new ResourceLocation("trims/items/helmet_trim"),
			new ResourceLocation("trims/items/chestplate_trim"),
			new ResourceLocation("trims/items/leggings_trim"),
			new ResourceLocation("trims/items/boots_trim")
	);
	public static final ResourceLocation TRIM_TYPE_PREDICATE_ID = new ResourceLocation(Blueprint.MOD_ID, "trim_type");
	private static final IdentityHashMap<ResourceKey<TrimMaterial>, Map<ArmorMaterial, String>> TRIM_MATERIAL_ARMOR_MATERIAL_OVERRIDES = new IdentityHashMap<>();
	private static final LinkedHashMap<ResourceKey<TrimMaterial>, Pair<TrimMaterial, Float>> GENERATED_OVERRIDE_INDICES = new LinkedHashMap<>();
	private static final ArrayList<RevertibleOverrides> REVERTIBLE_OVERRIDES = new ArrayList<>();

	public static void init() {
		ItemProperties.registerGeneric(TRIM_TYPE_PREDICATE_ID, (stack, level, entity, num) -> {
			if (stack.is(ItemTags.TRIMMABLE_ARMOR) && level != null) {
				var trimMaterialHolder = ArmorTrim.getTrim(level.registryAccess(), stack).map(ArmorTrim::material);
				if (trimMaterialHolder.isPresent()) {
					var key = trimMaterialHolder.get().unwrapKey();
					if (key.isPresent()) {
						var pair = GENERATED_OVERRIDE_INDICES.get(key.get());
						if (pair != null) return pair.getSecond();
					}
				}
			}
			return Float.NEGATIVE_INFINITY;
		});
	}

	/**
	 * Creates a new {@link BlueprintPalettedPermutations} instance that is all the permutations of vanilla materials for given trim pattern keys.
	 *
	 * @param keys An array of {@link TrimPattern} {@link ResourceKey} instances for which patterns should be permuted.
	 * @return A new {@link BlueprintPalettedPermutations} instance that is all the permutations of vanilla materials for the given trim pattern keys.
	 */
	@SafeVarargs
	public static BlueprintPalettedPermutations patternPermutationsOfVanillaMaterials(ResourceKey<TrimPattern>... keys) {
		List<ResourceLocation> textures = new ArrayList<>(keys.length << 1);
		for (var key : keys) {
			ResourceLocation location = key.location();
			textures.add(location.withPath(string -> "trims/models/armor/" + string));
			textures.add(location.withPath(string -> "trims/models/armor/" + string + "_leggings"));
		}
		return new BlueprintPalettedPermutations(Either.right(textures), PALETTE_KEY, PERMUTATIONS);
	}

	@SafeVarargs
	private static HashMap<String, ResourceLocation> getPermutations(ResourceKey<TrimMaterial>... keys) {
		HashMap<String, ResourceLocation> permutations = new HashMap<>();
		for (var key : keys) {
			ResourceLocation location = key.location();
			permutations.put(location.getNamespace() + "_" + location.getPath(), location.withPath(string -> "trims/color_palettes/" + string));
		}
		return permutations;
	}

	/**
	 * Creates a new {@link BlueprintPalettedPermutations} instance that is the permutations of all materials for given trim material keys.
	 *
	 * @param keys An array of {@link TrimMaterial} {@link ResourceKey} instances for which materials should be permuted.
	 * @return A new {@link BlueprintPalettedPermutations} instance that is the permutations of all materials for the given trim material keys.
	 */
	@SafeVarargs
	public static BlueprintPalettedPermutations materialPatternPermutations(ResourceKey<TrimMaterial>... keys) {
		return new BlueprintPalettedPermutations(Either.left(List.of(new DirectoryLister("trims/models/armor", "trims/models/armor/"))), PALETTE_KEY, getPermutations(keys));
	}

	/**
	 * Creates a new {@link BlueprintPalettedPermutations} instance that is all the armor item permutations for given trim material keys.
	 *
	 * @param keys An array of {@link TrimMaterial} {@link ResourceKey} instances for which materials should be permuted.
	 * @return A new {@link BlueprintPalettedPermutations} instance that is all the armor item permutations for given trim material keys.
	 */
	@SafeVarargs
	public static BlueprintPalettedPermutations materialPermutationsForItemLayers(ResourceKey<TrimMaterial>... keys) {
		return new BlueprintPalettedPermutations(Either.right(ITEM_TRIMS), PALETTE_KEY, getPermutations(keys));
	}

	/**
	 * Registers armor material overrides for a given Trim Material key.
	 * <p>Vanilla's {@link TrimMaterial#overrideArmorMaterials()} does not support modded materials, but this overcomes that limitation.</p>
	 *
	 * @param key                    The {@link TrimMaterial} {@link ResourceKey} to register armor material overrides for.
	 * @param overrideArmorMaterials The map of armor material overrides.
	 */
	public static synchronized void registerArmorMaterialOverrides(ResourceKey<TrimMaterial> key, Map<ArmorMaterial, String> overrideArmorMaterials) {
		TRIM_MATERIAL_ARMOR_MATERIAL_OVERRIDES.put(key, overrideArmorMaterials);
	}

	public static Map<ArmorMaterial, String> getOverrideArmorMaterials(ResourceKey<TrimMaterial> key) {
		return TRIM_MATERIAL_ARMOR_MATERIAL_OVERRIDES.get(key);
	}

	private static ModelResourceLocation generateModelLocation(String namespace, ResourceLocation itemName, String assetName, ArrayList<ItemOverride.Predicate> predicates) {
		StringBuilder builder = new StringBuilder();
		builder.append("item/").append(itemName.getNamespace()).append('/').append(itemName.getPath()).append('_').append(assetName).append("_trim");
		for (ItemOverride.Predicate predicate : predicates) {
			builder.append('_').append(predicate.getProperty().getNamespace()).append('_');
			String path = predicate.getProperty().getPath();
			for (int i = 0, length = path.length(); i < length; i++) {
				char c = path.charAt(i);
				builder.append(c == '/' ? '_' : c);
			}
			builder.append('_').append(predicate.getValue());
		}
		return new ModelResourceLocation(Blueprint.MOD_ID, builder.toString(), "inventory");
	}

	private static ItemOverrides.BakedOverride createBakedOverride(ItemOverrides.PropertyMatcher[] matchers, ModelBakery bakery, BiFunction<ResourceLocation, Material, TextureAtlasSprite> textureGetter, ModelResourceLocation location) {
		return new ItemOverrides.BakedOverride(matchers, bakery.new ModelBakerImpl(textureGetter, location).bake(location, BlockModelRotation.X0_Y0));
	}

	@SuppressWarnings({"deprecation", "unchecked"})
	private static void modifyTrimmableItemModels(RegistryAccess registryAccess) {
		if (GENERATED_OVERRIDE_INDICES.isEmpty()) return;

		var itemRegistry = registryAccess.registryOrThrow(Registries.ITEM);
		var trimmableArmorTag = itemRegistry.getTag(ItemTags.TRIMMABLE_ARMOR);
		if (trimmableArmorTag.isEmpty()) return;

		var trimMaterials = registryAccess.registryOrThrow(Registries.TRIM_MATERIAL);
		int armorItemTypeCount = ITEM_TRIMS.size();
		HashMap<Float, ResourceLocation>[] trimTextureForArmorItemType = new HashMap[armorItemTypeCount];
		for (int i = 0; i < armorItemTypeCount; i++) {
			HashMap<Float, ResourceLocation> map = new HashMap<>();
			ResourceLocation trimTextureBase = ITEM_TRIMS.get(i);
			for (var entry : trimMaterials.entrySet()) {
				if (!entry.getKey().location().getNamespace().equals("minecraft")) continue;
				TrimMaterial material = entry.getValue();
				map.put(material.itemModelIndex(), trimTextureBase.withSuffix("_" + material.assetName()));
			}
			trimTextureForArmorItemType[i] = map;
		}

		ModelManager modelManager = Minecraft.getInstance().getModelManager();
		ModelBakery modelBakery = modelManager.getModelBakery();
		var unbakedModels = modelBakery.unbakedCache;
		BiFunction<ResourceLocation, Material, TextureAtlasSprite> textureGetter = (location, material) -> {
			return modelManager.getAtlas(material.atlasLocation()).getSprite(material.texture());
		};

		// Curse technique
		trimmableArmorTag.get().forEach(itemHolder -> {
			if (!(itemHolder.get() instanceof ArmorItem armorItem)) return;
			var itemKeyOptional = itemHolder.unwrapKey();
			if (itemKeyOptional.isEmpty()) return;
			ResourceLocation itemName = itemKeyOptional.get().location();
			ModelResourceLocation inventoryModelLocation = new ModelResourceLocation(itemName, "inventory");
			BakedModel inventoryModel = modelManager.getModel(inventoryModelLocation);
			ItemOverrides bakedModelOverrides = inventoryModel.getOverrides();
			ItemOverrides.BakedOverride[] overrides = bakedModelOverrides.overrides;
			int overridesLength = overrides.length;
			if (overridesLength == 0) return;
			UnbakedModel unbakedInventoryModel = unbakedModels.get(inventoryModelLocation);
			if (!(unbakedInventoryModel instanceof BlockModel unbakedBlockModel)) return;
			var unbakedOverrides = unbakedBlockModel.getOverrides();
			if (unbakedOverrides.size() > overridesLength) return;

			int armorItemTypeIndex = armorItem.getType().ordinal();
			ResourceLocation armorTrimTypeLocation = ITEM_TRIMS.get(armorItemTypeIndex);
			HashMap<Float, ResourceLocation> indexToItemTrimTexture = trimTextureForArmorItemType[armorItemTypeIndex];
			HashMap<ArrayList<ItemOverride.Predicate>, Pair<BlockModel, ArrayList<String>>> significantOverrides = new HashMap<>();
			for (int i = unbakedOverrides.size() - 1; i > -1; i--) {
				ItemOverride override = unbakedOverrides.get(i);
				UnbakedModel unbakedOverrideModel = unbakedModels.get(override.getModel());
				if (!(unbakedOverrideModel instanceof BlockModel overrideBlockModel)) continue;
				var predicatesIterator = override.getPredicates().iterator();
				boolean foundReplicableTrimOverride = false;
				ArrayList<ItemOverride.Predicate> predicates = new ArrayList<>();
				ArrayList<String> trimKeysInTextureMap = new ArrayList<>();
				while (predicatesIterator.hasNext()) {
					ItemOverride.Predicate predicate = predicatesIterator.next();
					if (!foundReplicableTrimOverride && predicate.getProperty().equals(ItemModelGenerators.TRIM_TYPE_PREDICATE_ID)) {
						ResourceLocation validTexture = indexToItemTrimTexture.get(predicate.getValue());
						if (validTexture == null) break;
						for (var entry : overrideBlockModel.textureMap.entrySet()) {
							var materialOptional = entry.getValue().left();
							if (materialOptional.isEmpty()) continue;
							Material material = materialOptional.get();
							if (!material.atlasLocation().equals(TextureAtlas.LOCATION_BLOCKS) || !material.texture().equals(validTexture))
								continue;
							trimKeysInTextureMap.add(entry.getKey());
							foundReplicableTrimOverride = true;
						}
						if (!foundReplicableTrimOverride) break;
					} else {
						predicates.add(predicate);
					}
				}
				if (foundReplicableTrimOverride) {
					significantOverrides.putIfAbsent(predicates, Pair.of(overrideBlockModel, trimKeysInTextureMap));
				}
			}
			if (significantOverrides.isEmpty()) return;

			// Add Blueprint's trim index property to the end of the properties array
			Object2IntOpenHashMap<ResourceLocation> propertyToIndex = new Object2IntOpenHashMap<>();
			propertyToIndex.defaultReturnValue(-1);
			var properties = bakedModelOverrides.properties;
			int oldLength = properties.length;
			ResourceLocation[] newProperties = new ResourceLocation[oldLength + 1];
			for (int i = 0; i < oldLength; i++) {
				propertyToIndex.put(newProperties[i] = properties[i], i);
			}
			newProperties[oldLength] = TRIM_TYPE_PREDICATE_ID;
			bakedModelOverrides.properties = newProperties;
			ArmorMaterial armorMaterial = armorItem.getMaterial();
			ItemOverrides.BakedOverride[] bakedOverridesToAdd = new ItemOverrides.BakedOverride[GENERATED_OVERRIDE_INDICES.size() * significantOverrides.size()];
			int i = bakedOverridesToAdd.length;
			for (var entry : GENERATED_OVERRIDE_INDICES.entrySet()) {
				var value = entry.getValue();
				TrimMaterial trimMaterial = value.getFirst();
				var trimMaterialKey = entry.getKey();
				String trimMaterialNamespace = trimMaterialKey.location().getNamespace();
				var armorMaterialOverrides = getOverrideArmorMaterials(trimMaterialKey);
				String assetName = armorMaterialOverrides != null ? armorMaterialOverrides.getOrDefault(armorMaterial, trimMaterial.assetName()) : trimMaterial.assetName();
				float overrideIndex = value.getSecond();
				ResourceLocation textureLocation = armorTrimTypeLocation.withSuffix("_" + assetName);
				Either<Material, String> texture = Either.left(new Material(TextureAtlas.LOCATION_BLOCKS, textureLocation));
				generateOverride:
				for (var significantOverride : significantOverrides.entrySet()) {
					var predicates = significantOverride.getKey();
					int predicateCount = predicates.size();
					ItemOverrides.PropertyMatcher[] matchers = new ItemOverrides.PropertyMatcher[predicateCount + 1];
					matchers[0] = new ItemOverrides.PropertyMatcher(oldLength, overrideIndex);
					for (int j = 0; j < predicateCount; j++) {
						ItemOverride.Predicate predicate = predicates.get(j);
						int index = propertyToIndex.getInt(predicate.getProperty());
						if (index > 0) {
							matchers[j + 1] = new ItemOverrides.PropertyMatcher(index, predicate.getValue());
						} else {
							// Possibly unsafe, so let's skip this override variation
							continue generateOverride;
						}
					}

					var modelAndReplaceableTextures = significantOverride.getValue();
					BlockModel model = modelAndReplaceableTextures.getFirst();
					ModelResourceLocation modelResourceLocation = generateModelLocation(trimMaterialNamespace, itemName, assetName, predicates);
					// If a resource pack wants to replace our generated models
					if (unbakedModels.containsKey(modelResourceLocation)) {
						bakedOverridesToAdd[--i] = createBakedOverride(matchers, modelBakery, textureGetter, modelResourceLocation);
						continue;
					}

					var replaceableTextures = modelAndReplaceableTextures.getSecond();
					var textureMap = model.textureMap;
					Either<Material, String>[] oldValues = new Either[replaceableTextures.size()];
					// Modify the necessary textures so that they use the trim material's textures
					int j = 0;
					while (j < replaceableTextures.size()) {
						String replaceableTexture = replaceableTextures.get(j);
						var oldValue = textureMap.get(replaceableTexture);
						oldValues[j++] = oldValue;
						textureMap.put(replaceableTexture, texture);
					}
					unbakedModels.put(modelResourceLocation, model);
					bakedOverridesToAdd[--i] = createBakedOverride(matchers, modelBakery, textureGetter, modelResourceLocation);
					// Undo texture modifications after we've performed a "permuted bake"
					for (j = 0; j < replaceableTextures.size(); ) {
						textureMap.put(replaceableTextures.get(j), oldValues[j++]);
					}
					unbakedModels.remove(modelResourceLocation);
				}
			}
			// Copy bakedOverridesToAdd from its end to i and insert it before the existing bakedModelOverrides.overrides
			int bakedOverridesToAddLength = bakedOverridesToAdd.length;
			int newBakedOverridesCount = bakedOverridesToAddLength - i;
			ItemOverrides.BakedOverride[] newOverrides = new ItemOverrides.BakedOverride[overridesLength + newBakedOverridesCount];
			System.arraycopy(bakedOverridesToAdd, i, newOverrides, 0, newBakedOverridesCount);
			System.arraycopy(overrides, 0, newOverrides, newBakedOverridesCount, overridesLength);
			bakedModelOverrides.overrides = newOverrides;
			REVERTIBLE_OVERRIDES.add(new RevertibleOverrides(bakedModelOverrides, overrides, properties));
		});
	}

	@SubscribeEvent
	public static void onClientLoggingIntoServer(ClientPlayerNetworkEvent.LoggingIn event) {
		RegistryAccess registryAccess = event.getPlayer().clientLevel.registryAccess();
		var trimMaterials = registryAccess.registryOrThrow(Registries.TRIM_MATERIAL);
		var neededTrimMaterialsOptional = trimMaterials.getTag(BlueprintTrimMaterialTags.GENERATES_OVERRIDES);
		if (neededTrimMaterialsOptional.isEmpty()) return;
		var neededTrimMaterials = neededTrimMaterialsOptional.get();
		if (neededTrimMaterials.size() == 0) return;
		neededTrimMaterials.forEach(trimMaterialHolder -> {
			var key = trimMaterialHolder.unwrapKey();
			if (key.isEmpty()) return;
			GENERATED_OVERRIDE_INDICES.put(key.get(), Pair.of(trimMaterialHolder.value(), (float) GENERATED_OVERRIDE_INDICES.size()));
		});
		modifyTrimmableItemModels(registryAccess);
	}

	@SubscribeEvent
	public static void onClientLoggingOutOfServer(ClientPlayerNetworkEvent.LoggingOut event) {
		// Undo our override additions
		for (int i = REVERTIBLE_OVERRIDES.size() - 1; i > -1; i--) {
			REVERTIBLE_OVERRIDES.remove(i).revert();
		}
		GENERATED_OVERRIDE_INDICES.clear();
	}

	public static void onModelsBaked(ModelEvent.BakingCompleted event) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		// Models reloaded after client joined a server
		REVERTIBLE_OVERRIDES.clear();
		modifyTrimmableItemModels(level.registryAccess());
	}

	private record RevertibleOverrides(ItemOverrides itemOverrides, ItemOverrides.BakedOverride[] overrides,
									   ResourceLocation[] properties) {
		private void revert() {
			this.itemOverrides.overrides = this.overrides;
			this.itemOverrides.properties = this.properties;
		}
	}
}
