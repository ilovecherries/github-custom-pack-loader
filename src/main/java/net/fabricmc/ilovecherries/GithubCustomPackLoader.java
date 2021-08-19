package net.fabricmc.ilovecherries;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.ilovecherries.github.FileMetadata;
import net.fabricmc.loader.launch.FabricClientTweaker;
import net.fabricmc.loom.configuration.FabricApiExtension;
import net.minecraft.client.MinecraftClient;
import org.apache.commons.io.FileUtils;

import javax.swing.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public class GithubCustomPackLoader implements ModInitializer {
	private static final String API_FORMAT = "https://api.github.com/repos/%s/%s";

	private static String getFileChecksum(MessageDigest digest, File file) throws IOException
	{
		//Get file input stream for reading the file content
		FileInputStream fis = new FileInputStream(file);

		//Create byte array to read data in chunks
		byte[] byteArray = new byte[1024];
		int bytesCount;

		//Read file data and update in message digest
		while ((bytesCount = fis.read(byteArray)) != -1) {
			digest.update(byteArray, 0, bytesCount);
		}

		//close the stream; We don't need it now.
		fis.close();

		//Get the hash's bytes
		byte[] bytes = digest.digest();

		//This bytes[] has bytes in decimal format;
		//Convert it to hexadecimal format
		StringBuilder sb = new StringBuilder();
		for(int i=0; i< bytes.length ;i++)
		{
			sb.append(Integer.toString((bytes[i] & 0xff) + 0x100, 16).substring(1));
		}

		//return complete hash
		return sb.toString();
	}

	private void parseGithubResponse(String data) {
		boolean change = false;
		Gson gson = new Gson();

		FileMetadata[] fileArray = gson.fromJson(data, FileMetadata[].class);
		final String MOD_FOLDER = MinecraftClient
				.getInstance()
				.runDirectory
				.getAbsolutePath()
				+ "/mods/%s";

		// first, we should go through the cache that we have saved on our system and see if
		// some of the mods need to be removed
		File cacheFile = new File(String.format(MOD_FOLDER, "custompackcache.json"));
		if (cacheFile.exists() && !cacheFile.isDirectory()) {
			try {
				String cachedData = FileUtils.readFileToString(cacheFile, StandardCharsets.UTF_8);
				// if they are unequal, then we should delete files that have been removed
				if (!cachedData.equals(data)) {
					FileMetadata[] cachedFileArray = gson.fromJson(cachedData, FileMetadata[].class);
					for (FileMetadata cachedFileMetadata : cachedFileArray) {
						if (Arrays.stream(fileArray).noneMatch(x -> cachedFileMetadata.sha.equals(x.sha))) {
							File cachedFile = new File(String.format(MOD_FOLDER, cachedFileMetadata.name));
							System.out.println("Deleting: " + cachedFileMetadata.name);
							if (cachedFile.delete()) {
								System.out.println("Successfully deleted: " + cachedFileMetadata.name);
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
				try {
					MessageDigest shaDigest = MessageDigest.getInstance("SHA-256");
					try {
						String shaChecksum = getFileChecksum(shaDigest, file);
						if (shaChecksum.equals(githubFile.sha)) {
							System.out.println("Updating: " + githubFile.name);
							URL url = new URL(githubFile.download_url);
							FileUtils.copyURLToFile(url, file);
							change = true;
						}
					} catch (IOException e) {
						System.err.println(e);
					}
				} catch (NoSuchAlgorithmException e) {
					System.err.println("Not able to find SHA-256 algorithm on your system???");
				}
			}
			else {
				try {
					System.out.println("Downloading " + githubFile.name);
					URL url = new URL(githubFile.download_url);
					FileUtils.copyURLToFile(url, file);
					change = true;
				} catch (IOException e) {
					System.err.println(e);
				}
			}
		}

	}

	@Override
	public void onInitialize() {
		HttpClient client = HttpClient.newHttpClient();

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(String.format(API_FORMAT, "ilovecherries/test-load-repo", "contents/")))
				.build();

		client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(HttpResponse::body)
				.thenAccept(this::parseGithubResponse)
				.join();
	}
}
