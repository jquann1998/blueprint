package com.teamabnormals.blueprint.common.remolder.data;

import com.google.gson.*;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.teamabnormals.blueprint.common.remolder.RemoldedResourceManager;
import com.teamabnormals.blueprint.common.remolder.Remolding;
import com.teamabnormals.blueprint.core.Blueprint;
import com.teamabnormals.blueprint.core.util.registry.BasicRegistry;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceMetadata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

public final class MoldingTypes {
	private static final Gson GSON = new GsonBuilder().setLenient().create();
	private static final BasicRegistry<MoldingType<?>> REGISTRY = new BasicRegistry<>();
	public static final Codec<MoldingType<?>> TYPE_CODEC = REGISTRY;
	public static final MetadataSectionSerializer<JsonElement> JSON_METADATA_SERIALIZER = new MetadataSectionSerializer<>() {
		@Override
		public String getMetadataSectionName() {
			return "json";
		}

		@Override
		public JsonElement fromJson(JsonObject object) {
			return object;
		}
	};

	public static final MoldingType<JsonElement> JSON = register(
			"json",
			JsonMolding.INSTANCE,
			JsonOps.INSTANCE,
			resource -> {
				try (Reader reader = resource.openAsReader()) {
					return GSON.fromJson(reader, JsonElement.class);
				} catch (IllegalArgumentException | IOException | JsonParseException exception) {
					return null;
				}
			},
			MoldingTypes::serializeJsonElement,
			"json"
	);

	public static synchronized <T> MoldingType<T> register(String name, Molding<T> molding, DynamicOps<T> ops, Function<Resource, T> deserializer, Function<T, byte[]> serializer, String... fileExtensions) {
		var moldingType = new MoldingType<>(molding, ops, deserializer, serializer, fileExtensions);
		REGISTRY.register(name, moldingType);
		return moldingType;
	}

	private static byte[] serializeJsonElement(JsonElement element) {
		return GSON.toJson(element).getBytes(StandardCharsets.UTF_8);
	}

	public record MoldingType<T>(Molding<T> molding, DynamicOps<T> ops, Function<Resource, T> deserializer, Function<T, byte[]> serializer, String... fileExtensions) {
		@SuppressWarnings("unchecked")
		private static <T> T getMetadata(Resource resource, DynamicOps<T> ops) {
			try {
				var optional = resource.metadata().getSection(JSON_METADATA_SERIALIZER);
				if (optional.isPresent()) return ops instanceof JsonOps ? (T) optional.get() : JsonOps.INSTANCE.convertTo(ops, optional.get());
			} catch (IOException ignored) {}
			return null;
		}

		private static void logFailedRemolder(Remolding<?> remolding, Exception exception, String location) {
			Blueprint.LOGGER.error("Error while applying remolder {}: {}", remolding, exception);
			Blueprint.LOGGER.warn("Restoring and stopping Remolder data changes at location: {}", location);
		}

		@SuppressWarnings("unchecked")
		public Resource remold(String location, Resource resource, List<RemoldedResourceManager.Entry> entries) {
			T root = this.deserializer().apply(resource);
			if (root == null) return resource;
			String pack = resource.sourcePackId();
			DynamicOps<T> ops = this.ops;
			T metadata = getMetadata(resource, ops);
			Pair<T, T> result;
			for (RemoldedResourceManager.Entry entry : entries) {
				if (!entry.packFilter().test(pack)) continue;
				try {
					result = ((Remolding<T>) entry.remolding()).apply(ops, root, metadata, ops.emptyMap());
				} catch (Exception exception) {
					logFailedRemolder(entry.remolding(), exception, location);
					return resource;
				}
				root = result.getFirst();
				metadata = result.getSecond();
			}
			InputStream inputStream = new ByteArrayInputStream(this.serializer().apply(root));
			if (metadata == null) {
				return new Resource(resource.source(), () -> inputStream);
			} else {
				try {
					ResourceMetadata resourceMetadata = ResourceMetadata.fromJsonStream(new ByteArrayInputStream(serializeJsonElement(metadata instanceof JsonElement element ? element : ops.convertTo(JsonOps.INSTANCE, metadata))));
					return new Resource(resource.source(), () -> inputStream, () -> resourceMetadata);
				} catch (IOException exception) {
					exception.printStackTrace();
					return resource;
				}
			}
		}
	}
}
