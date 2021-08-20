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

public class GCPLoader implements ModInitializer {
	private static final String API_FORMAT = "https://api.github.com/repos/%s/%s";
	private static final String MOD_FOLDER = MinecraftClient
			.getInstance()
			.runDirectory
			.getAbsolutePath()
			+ "/mods/%s";

	private void parseGithubResponse(String data) {
		Gson gson = new Gson();

		FileMetadata[] fileArray = gson.fromJson(data, FileMetadata[].class);

		// first, we should go through the cache that we have saved on our system and see if
		// some of the mods need to be removed
		File cacheFile = new File(String.format(MOD_FOLDER, "custompackcache.json"));
		if (cacheFile.exists() && !cacheFile.isDirectory()) {
			try {
				String cachedData = FileUtils.readFileToString(cacheFile, StandardCharsets.UTF_8);
				FileMetadata[] cachedFileArray = gson.fromJson(cachedData, FileMetadata[].class);

				for (FileMetadata cachedFileMetadata : cachedFileArray) {
					if (Arrays.stream(fileArray).noneMatch(x -> cachedFileMetadata.name.equals(x.name))) {
						File cachedFile = new File(String.format(MOD_FOLDER, cachedFileMetadata.name));

						System.out.println("Deleting: " + cachedFileMetadata.name);

						if (cachedFile.delete()) {
							System.out.println("Successfully deleted: " + cachedFileMetadata.name);
							GCPState.addDeleted(cachedFileMetadata.name);
						} else {
							System.out.println("Unable to delete: " + cachedFileMetadata.name);
						}
					}
				}

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
			final String fileLocation = String.format(MOD_FOLDER, githubFile.name);
			File file = new File(fileLocation);

			// check if the file exists first I suppose
			if (file.exists() && !file.isDirectory()) {
				// check if the SHA-256 hash matches
				try (InputStream is = new FileInputStream(file)) {
					String hash = DigestUtils.sha256Hex(is);
					if (hash.equals(githubFile.sha)) {
						System.out.println("Updating: " + githubFile.name);
						URL url = new URL(githubFile.download_url);
						FileUtils.copyURLToFile(url, file);
						System.out.println("Updated: " + githubFile.name);
						GCPState.addUpdated(githubFile.name);
					}
				} catch (IOException e) {
					System.err.println(e);
				}
			}
			else {
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
		File configFile = new File(String.format(MOD_FOLDER, "custompackconfig.json"));

		if (configFile.exists() && !configFile.isDirectory()) {
			try {
				String configData = FileUtils.readFileToString(configFile, StandardCharsets.UTF_8);
				Gson gson = new Gson();
				GCPConfig config = gson.fromJson(configData, GCPConfig.class);

				HttpClient client = HttpClient.newHttpClient();

				HttpRequest request = HttpRequest.newBuilder()
						.uri(URI.create(String.format(API_FORMAT, "ilovecherries/test-load-repo", "contents/")))
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
