package com.teamabnormals.blueprint.core.api.conditions;

import com.google.common.collect.Maps;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.teamabnormals.blueprint.core.annotations.ConfigKey;
import com.teamabnormals.blueprint.core.api.conditions.config.IConfigPredicate;
import com.teamabnormals.blueprint.core.api.conditions.config.IConfigPredicateSerializer;
import com.teamabnormals.blueprint.core.util.DataUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.crafting.conditions.ICondition;
import net.minecraftforge.common.crafting.conditions.IConditionSerializer;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

/**
 * A condition that checks against the values of config values annotated with {@link ConfigKey}.
 * <p>Uses the recipe system, but is also compatible with modifiers etc.</p>
 *
 * <p>To make your mod's config values compatible with it, annotate them with {@link ConfigKey} taking in the string
 * value that should be used to deserialize the field, then call {@link DataUtil#registerConfigCondition(String, Object...)}
 * in the mod setup, passing your mod id as the first parameter, and the config objects with the
 * {@link ForgeConfigSpec.ConfigValue} instances that should be checked against as the second.</p>
 *
 * <p>For a condition with type {@code "[modid]:config"}, it takes the arguments:
 * <ul>
 *   <li>{@code value}      - the name of the config value to check against, defined by its corresponding {@link ConfigKey} annotation.</li>
 *   <li>{@code predicates} - an array of JSON objects that deserialize to an {@link IConfigPredicate}, which prevent
 *                            the condition from passing if one of more of them return false. Optional if {@code value}
 *                            maps to a boolean {@link ForgeConfigSpec.ConfigValue}.</li>
 *   <li>{@code inverted} (optional)   - whether the condition should be inverted, so it will pass if {@code predicates} return false instead.</li>
 * </ul></p>
 *
 * @author abigailfails
 * @see DataUtil#registerConfigCondition(String, Object...)
 */
public class ConfigValueCondition implements ICondition {
	private final ForgeConfigSpec.ConfigValue<?> value;
	private final String valueID;
	private final Map<IConfigPredicate, Boolean> predicates;
	private final boolean inverted;
	private final ResourceLocation location;

	public ConfigValueCondition(ResourceLocation location, ForgeConfigSpec.ConfigValue<?> value, String valueID, Map<IConfigPredicate, Boolean> predicates, boolean inverted) {
		this.location = location;
		this.value = value;
		this.valueID = valueID;
		this.predicates = predicates;
		this.inverted = inverted;
	}

	public ConfigValueCondition(String modid, String valueID, Map<IConfigPredicate, Boolean> predicates, boolean inverted) {
		this(new ResourceLocation(modid, "config"), null, valueID, predicates, inverted);
	}

	public ConfigValueCondition(String modid, String valueID, boolean inverted) {
		this(modid, valueID, Maps.newHashMap(), inverted);
	}

	public ConfigValueCondition(String modid, String valueID) {
		this(modid, valueID, false);
	}

	@Override
	public ResourceLocation getID() {
		return this.location;
	}

	@Override
	public boolean test(IContext context) {
		boolean returnValue;
		Map<IConfigPredicate, Boolean> predicates = this.predicates;
		ForgeConfigSpec.ConfigValue<?> value = this.value;
		if (predicates.size() > 0) {
			returnValue = predicates.keySet().stream().allMatch(c -> predicates.get(c) != c.test(value));
		} else if (value.get() instanceof Boolean bool) {
			returnValue = bool;
		} else
			throw new IllegalStateException("Predicates required for non-boolean ConfigLootCondition, but none found");
		return this.inverted != returnValue;
	}

	public static class Serializer implements IConditionSerializer<ConfigValueCondition> {
		public static final Hashtable<ResourceLocation, IConfigPredicateSerializer<?>> CONFIG_PREDICATE_SERIALIZERS = new Hashtable<>();
		private final Map<String, ForgeConfigSpec.ConfigValue<?>> configValues;
		private final ResourceLocation location;

		public Serializer(String modId, Map<String, ForgeConfigSpec.ConfigValue<?>> configValues) {
			this.location = new ResourceLocation(modId, "config");
			this.configValues = configValues;
		}

		@Override
		public void write(JsonObject json, ConfigValueCondition value) {
			json.addProperty("value", value.valueID);
			if (!value.predicates.isEmpty()) {
				JsonArray predicates = new JsonArray();
				json.add("predicates", predicates);
				for (Map.Entry<IConfigPredicate, Boolean> predicatePair : value.predicates.entrySet()) {
					IConfigPredicate predicate = predicatePair.getKey();
					ResourceLocation predicateID = predicate.getID();
					JsonObject object = new JsonObject();
					predicates.add(object);
					object.addProperty("type", predicateID.toString());
					CONFIG_PREDICATE_SERIALIZERS.get(predicateID).write(object, predicate);
					object.addProperty("inverted", predicatePair.getValue());
				}
			}
			if (value.inverted) json.addProperty("inverted", true);
		}

		@Override
		public ConfigValueCondition read(JsonObject json) {
			if (!json.has("value"))
				throw new JsonSyntaxException("Missing 'value', expected to find a string");
			String name = GsonHelper.getAsString(json, "value");
			ForgeConfigSpec.ConfigValue<?> configValue = configValues.get(name);
			if (configValue == null)
				throw new JsonSyntaxException("No config value of name '" + name + "' found");
			Map<IConfigPredicate, Boolean> predicates = new HashMap<>();
			if (GsonHelper.isValidNode(json, "predicates")) {
				for (JsonElement predicateElement : GsonHelper.getAsJsonArray(json, "predicates")) {
					if (!predicateElement.isJsonObject())
						throw new JsonSyntaxException("Predicates must be an array of JsonObjects");
					JsonObject predicateObject = predicateElement.getAsJsonObject();
					ResourceLocation type = new ResourceLocation(GsonHelper.getAsString(predicateObject, "type"));
					IConfigPredicateSerializer<?> serializer = CONFIG_PREDICATE_SERIALIZERS.get(type);
					if (serializer == null)
						throw new JsonSyntaxException("Unknown predicate type: " + type);
					predicates.put(serializer.read(predicateObject), predicateObject.has("inverted") && GsonHelper.getAsBoolean(predicateObject, "inverted"));
				}
			} else if (!(configValue.get() instanceof Boolean)) {
				throw new JsonSyntaxException("Missing 'predicates' for non-boolean config value '" + name + "', expected to find an array");
			}
			return new ConfigValueCondition(location, configValue, name, predicates, json.has("inverted") && GsonHelper.getAsBoolean(json, "inverted"));
		}

		@Override
		public ResourceLocation getID() {
			return this.location;
		}
	}
}
