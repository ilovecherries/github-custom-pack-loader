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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

	private @Nullable Path expandFilename(String filename) {
		Path folder = Paths.get(MOD_FOLDER);

		if (DEV_MODE && filename.equals(SELF_NAME)) {
			return null;
		}

		try {
			Stream<Path> files = Files.list(folder);
			return files.filter(x -> x.getFileName().toString().matches(filename + EXPANDED_NAME_REGEX)
							|| x.getFileName().toString().equals(filename))
					.findFirst()
					.orElse(null);
		} catch (IOException e) {
			System.out.println("There was a problem getting files from the mods folder: " + e);
			return null;
		}
	}

	private @Nullable String downloadAndGetFilename(FileMetadata metadata) {
		try {
			final URL url = new URL(metadata.download_url);
			try (InputStream is = url.openStream()) {
				Path path = Paths.get(MOD_FOLDER + metadata.name);
				Files.copy(is, path);
				return path.getFileName().toString();
			} catch (Exception e) {
				System.err.println("There was an error downloading " + metadata.name + ": " + e);
			}
		} catch (IOException e) {
			System.err.println("There was an error downloading " + metadata.name + ": " + e);
		}
		return null;
	}

	private void parseGithubResponse(String data) {
		Gson gson = new Gson();

		FileMetadata[] fileArray = gson.fromJson(data, FileMetadata[].class);

		// first, we should go through the cache that we have saved on our system and see if
		// some mods need to be removed
		Path cacheFile = Paths.get(MOD_FOLDER + CACHE_FILENAME);

		if (Files.exists(cacheFile) && !Files.isDirectory(cacheFile)) {
			try {
				String cachedData = Files.readString(cacheFile);
				FileMetadata[] cachedFileArray = gson.fromJson(cachedData, FileMetadata[].class);

				Arrays.stream(cachedFileArray)
					.filter(x -> Arrays.stream(fileArray)
						.noneMatch(y -> trimVersionTag(x.name).equals(trimVersionTag(y.name))))
					.map(x -> Paths.get(MOD_FOLDER + x.name))
					.filter(Files::exists)
					.forEach(x -> {try {
						Files.delete(x);
						GCPState.addDeleted(x.toString());
					} catch (IOException e) {
						System.err.println("There was a problem deleting " + x + ": " + e);
					}
					});

				try {
					Files.writeString(cacheFile, data);
				} catch (IOException e) {
					System.err.println("Unable to write the cache file: " + e);
				}
			} catch (IOException e) {
				System.err.println("Unable to read from cache file: " + e);
			}
		} else {
			try {
				Files.writeString(cacheFile, data);
			} catch (IOException e) {
				System.err.println("Unable to write the cache file: " + e);
			}
		}

		Map<Boolean, List<ImmutablePair<Path, FileMetadata>>> fileExists = Arrays.stream(fileArray)
				.filter(g -> !(DEV_MODE && trimVersionTag(g.name).equals(SELF_NAME)))
				.map(g -> {
					Path f = expandFilename(trimVersionTag(g.name));
					f = f == null ? Paths.get(MOD_FOLDER + g.name) : f;
					return new ImmutablePair<>(f, g);
				})
				.collect(Collectors.partitioningBy(x -> Files.exists(x.getLeft()) && !Files.isDirectory(x.getLeft())));

		// if the file exists, then attempt to continue with the file under the
		// guise that it will be "updated"
		Map<Boolean, List<ImmutablePair<Path, FileMetadata>>> updated = fileExists.get(true).stream()
				.filter(x -> !x.getRight().name.equals(x.getLeft().getFileName().toString()))
				.collect(Collectors.partitioningBy(x -> {
					try {
						Files.delete(x.getLeft());
						return true;
					} catch (IOException e) {
						System.err.println("Failed to delete "
								+ x.getLeft().getFileName().toString()
								+ ": " + e);
						return false;
					}
				}));

		updated.get(true).stream()
				.map(x -> downloadAndGetFilename(x.getRight()))
				.filter(Objects::nonNull)
				.forEach(GCPState::addUpdated);

		fileExists.get(false).stream()
				.map(x -> downloadAndGetFilename(x.getRight()))
				.filter(Objects::nonNull)
				.forEach(GCPState::addDownloaded);

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
