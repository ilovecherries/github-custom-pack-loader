package net.fabricmc.ilovecherries;

import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.ilovecherries.github.FileMetadata;
import net.minecraft.client.MinecraftClient;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

public class GCPLoader implements ModInitializer {
	private static final String API_FORMAT = "https://api.github.com/repos/%s/%s";
	private static final String MOD_FOLDER = MinecraftClient
			.getInstance()
			.runDirectory
			.getAbsolutePath()
			+ "/mods/";
	private static final String EXPANDED_NAME_REGEX = "-[0-9].+\\.jar";
	private static boolean DEV_MODE = false;
	private static String SELF_NAME = "github-custom-pack-loader";

	private String trimJarName(String name) {
		return name.replaceAll(EXPANDED_NAME_REGEX, "");
	}

	private File expandJarName(String filename) {
		File folder = new File(MOD_FOLDER);

		if (DEV_MODE && filename.equals(SELF_NAME)) {
			return null;
		}

		return Arrays.stream(folder.listFiles())
				.filter(x -> x.getName().matches(filename + EXPANDED_NAME_REGEX))
				.findFirst()
				.orElse(null);
	}

	private void parseGithubResponse(String data) {
		Gson gson = new Gson();

		FileMetadata[] fileArray = gson.fromJson(data, FileMetadata[].class);

		// first, we should go through the cache that we have saved on our system and see if
		// some of the mods need to be removed
		File cacheFile = new File(MOD_FOLDER + "custompackcache.json");

		if (cacheFile.exists() && !cacheFile.isDirectory()) {
			try {
				String cachedData = FileUtils.readFileToString(cacheFile, StandardCharsets.UTF_8);
				FileMetadata[] cachedFileArray = gson.fromJson(cachedData, FileMetadata[].class);

				Arrays.stream(cachedFileArray)
					.filter(x -> Arrays.stream(fileArray)
						.noneMatch(y -> trimJarName(x.name).equals(trimJarName(y.name))))
					.map(x -> expandJarName(x.name))
					.filter(Objects::nonNull)
					.filter(File::delete)
					.forEach(x -> GCPState.addDeleted(x.getName()));

				try {
					FileUtils.writeStringToFile(cacheFile, data, StandardCharsets.UTF_8);
				} catch (IOException e) {
					System.err.println(e);
				}
			} catch (IOException e) {
				System.err.println(e);
			}
		} else {
			try {
				FileUtils.writeStringToFile(cacheFile, data, StandardCharsets.UTF_8);
			} catch (IOException e) {
				System.err.println(e);
			}
		}

		for (FileMetadata githubFile : fileArray) {
			File file = expandJarName(trimJarName(githubFile.name));
			file = file == null
					? new File(MOD_FOLDER + githubFile.name)
					: file;

			// check if the file exists first I suppose
			if (file.exists() && !file.isDirectory()) {
				// check if the SHA-256 hash matches
				try (InputStream is = new FileInputStream(file)) {
					String hash = DigestUtils.sha256Hex(is);
					if (!(DEV_MODE && trimJarName(githubFile.name).equals(SELF_NAME))
						&& hash.equals(githubFile.sha)) {
						System.out.println("Updating " + file.getName() + " to " + githubFile.name);
						URL url = new URL(githubFile.download_url);
						file.delete();
						File newFile = new File(MOD_FOLDER + githubFile.name);
						FileUtils.copyURLToFile(url, newFile);
						System.out.println("Updated: " + trimJarName(githubFile.name));
						GCPState.addUpdated(githubFile.name);
					}
				} catch (IOException e) {
					System.err.println(e);
				}
			}
			else if (!(DEV_MODE && trimJarName(githubFile.name).equals(SELF_NAME))){
				try {
					System.out.println("Downloading " + githubFile.name);
					URL url = new URL(githubFile.download_url);
					FileUtils.copyURLToFile(url, file);
					System.out.println("Downloaded: " + githubFile.name);
					GCPState.addDownloaded(githubFile.name);
				} catch (IOException e) {
					System.err.println(e);
				}
			}
		}

		if (GCPState.anyChange()) {
			System.exit(-1);
		}
	}

	@Override
	public void onInitialize() {
		File configFile = new File(MOD_FOLDER + "custompackconfig.json");

		if (configFile.exists() && !configFile.isDirectory()) {
			try {
				String configData = FileUtils.readFileToString(configFile, StandardCharsets.UTF_8);
				Gson gson = new Gson();
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
			System.err.println("Missing config file (mods/custompackconfig.json) for Github Custom Pack Loader.");
		}
	}
}
