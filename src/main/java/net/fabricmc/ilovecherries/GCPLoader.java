package net.fabricmc.ilovecherries;

import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.ilovecherries.github.FileMetadata;
import net.minecraft.client.MinecraftClient;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class GCPLoader implements ModInitializer {
	private static final String API_FORMAT = "https://api.github.com/repos/%s/%s";
	private static final String MOD_FOLDER = MinecraftClient
			.getInstance()
			.runDirectory
			.getAbsolutePath()
			+ "/mods/";
	private static final String EXPANDED_NAME_REGEX = "(?i)-[0-9].+\\.jar";
	private static final boolean DEV_MODE = false;
	private static final String CONFIG_FILENAME = "custompackconfig.json";
	private static final String CACHE_FILENAME = "custompackcache.json";
	private static final String SELF_NAME = "github-custom-pack-loader";
	// this is used to generate a config file if it isn't already found
	// i will replace this with a gradle build config parameter at some point
	private static final String DEFAULT_REPO = "LegoDevStudio/coney-pony-mods";

	@Contract(pure = true)
	private @NotNull String trimVersionTag(@NotNull String name) {
		return name.replaceAll(EXPANDED_NAME_REGEX, "");
	}

	private @Nullable File expandFilename(String filename) {
		File folder = new File(MOD_FOLDER);

		if (DEV_MODE && filename.equals(SELF_NAME)) {
			return null;
		}

		if (filename.toLowerCase(Locale.ROOT).endsWith(".jar")) {
			File[] files = folder.listFiles();
			return files == null ? null : Arrays.stream(files)
					.filter(x -> x.getName().matches(filename + EXPANDED_NAME_REGEX))
					.findFirst()
					.orElse(null);
		} else {
			File file = new File(MOD_FOLDER + filename);
			return file.exists() ? file : null;
		}
	}

	private void parseGithubResponse(String data) {
		Gson gson = new Gson();

		FileMetadata[] fileArray = gson.fromJson(data, FileMetadata[].class);

		// first, we should go through the cache that we have saved on our system and see if
		// some mods need to be removed
		File cacheFile = new File(MOD_FOLDER + CACHE_FILENAME);

		if (cacheFile.exists() && !cacheFile.isDirectory()) {
			try {
				String cachedData = FileUtils.readFileToString(cacheFile, StandardCharsets.UTF_8);
				FileMetadata[] cachedFileArray = gson.fromJson(cachedData, FileMetadata[].class);

				Arrays.stream(cachedFileArray)
					.filter(x -> Arrays.stream(fileArray)
						.noneMatch(y -> trimVersionTag(x.name).equals(trimVersionTag(y.name))))
					.map(x -> expandFilename(x.name))
					.filter(Objects::nonNull)
					.filter(File::delete)
					.forEach(x -> GCPState.addDeleted(x.getName()));

				try {
					FileUtils.writeStringToFile(cacheFile, data, StandardCharsets.UTF_8);
				} catch (IOException e) {
					System.err.println("Unable to write the cache file: " + e);
				}
			} catch (IOException e) {
				System.err.println("Unable to delete files: " + e);
			}
		} else {
			try {
				FileUtils.writeStringToFile(cacheFile, data, StandardCharsets.UTF_8);
			} catch (IOException e) {
				System.err.println("Unable to write the cache file: " + e);
			}
		}

		Map<Boolean, List<ImmutablePair<File, FileMetadata>>> fileExists = Arrays.stream(fileArray)
				.filter(g -> !(DEV_MODE && trimVersionTag(g.name).equals(SELF_NAME)))
				.map(g -> {
					File f = expandFilename(trimVersionTag(g.name));
					f = f == null ? new File(MOD_FOLDER + g.name) : f;
					return new ImmutablePair<>(f, g);
				})
				.collect(Collectors.partitioningBy(x -> x.getLeft().exists() && !x.getLeft().isDirectory()));

		// if the file exists, then attempt to continue with the file under the
		// guise that it will be "updated"
		Map<Boolean, List<ImmutablePair<File, FileMetadata>>> updated = fileExists.get(true).stream()
				.filter(x -> !x.getRight().name.equals(x.getLeft().getName()))
				.collect(Collectors.partitioningBy(x -> x.getLeft().delete()));

		// handle all the files that can't be deleted
		updated.get(false).forEach(x -> System.err.println("Failed to delete file: " + x.getLeft().getName()));

		// otherwise, let's download the file from the URL in the file metadata
		for (ImmutablePair<File, FileMetadata> i : updated.get(true)) {
			FileMetadata metadata = i.getRight();
			try {
				FileUtils.copyURLToFile(new URL(metadata.download_url),
						new File(MOD_FOLDER + metadata.name));
				GCPState.addUpdated(i.getLeft().getName(), metadata.name);
			} catch (IOException e) {
				System.err.println("Failed to download " + metadata.name + ": " + e);
			}
		}

		// all the files that /don't/ exist will just be downloaded
		for (ImmutablePair<File, FileMetadata> i : fileExists.get(false)) {
			FileMetadata metadata = i.getRight();
			try {
				FileUtils.copyURLToFile(new URL(metadata.download_url), i.getLeft());
				GCPState.addDownloaded(metadata.name);
			} catch (IOException e) {
				System.err.println("Failed to download " + metadata.name + ": " + e);
			}
		}

		if (GCPState.anyChange()) {
			System.exit(-1);
		}
	}

	@Override
	public void onInitialize() {
		File configFile = new File(MOD_FOLDER + CONFIG_FILENAME);
		Gson gson = new Gson();

		if (!DEFAULT_REPO.isEmpty() && !configFile.exists()) {
			System.out.println("No config exists, generating now...");
			try {
				GCPConfig config = new GCPConfig(DEFAULT_REPO);
				FileUtils.writeStringToFile(configFile, gson.toJson(config), StandardCharsets.UTF_8);
				configFile = new File(MOD_FOLDER + CONFIG_FILENAME);
			} catch (IOException e) {
				System.err.println("Unable to generate a default config file: " + e);
			}
		}

		if (configFile.exists() && !configFile.isDirectory()) {
			try {
				String configData = FileUtils.readFileToString(configFile, StandardCharsets.UTF_8);
				GCPConfig config = gson.fromJson(configData, GCPConfig.class);

				HttpClient client = HttpClient.newHttpClient();

				HttpRequest request = HttpRequest.newBuilder()
						.uri(URI.create(String.format(API_FORMAT, config.getRepoURL(), "contents/")))
						.build();

				client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
						.thenApply(HttpResponse::body)
						.thenAccept(this::parseGithubResponse)
						.join();
			} catch (IOException e) {
				System.err.println("Unable to load config file: " + e);
			}
		} else {
			System.err.println("Unable to find a config file.");
		}
	}
}
