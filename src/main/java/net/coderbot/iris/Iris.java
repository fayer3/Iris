package net.coderbot.iris;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipException;

import com.google.common.base.Throwables;
import com.mojang.blaze3d.platform.GlStateManager;
import net.coderbot.iris.config.IrisConfig;
import net.coderbot.iris.pipeline.ShaderPipeline;
import net.coderbot.iris.postprocess.CompositeRenderer;
import net.coderbot.iris.rendertarget.RenderTargets;
import net.coderbot.iris.shaderpack.ShaderPack;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.opengl.GL20C;

@Environment(EnvType.CLIENT)
public class Iris implements ClientModInitializer {
	public static final String MODID = "iris";
	public static final Logger logger = LogManager.getLogger(MODID);

	private static final Path shaderpacksDirectory = FabricLoader.getInstance().getGameDir().resolve("shaderpacks");

	private static ShaderPack currentPack;
	private static ShaderPipeline pipeline;
	private static RenderTargets renderTargets;
	private static CompositeRenderer compositeRenderer;
	private static IrisConfig irisConfig;
	private static FileSystem zipFileSystem;
	public static KeyBinding reloadKeybind;


	/**
	 * Controls whether directional shading was previously disabled
	 */
	private static boolean wasDisablingDirectionalShading = false;

	/**
	 * Controls whether BakedQuad will or will not use directional shading.
	 */
	private static boolean disableDirectionalShading = false;

	@Override
	public void onInitializeClient() {
		FabricLoader.getInstance().getModContainer("sodium").ifPresent(
			modContainer -> {
				String versionString = modContainer.getMetadata().getVersion().getFriendlyString();

				// A lot of people are reporting visual bugs with Iris + Sodium. This makes it so that if we don't have
				// the right fork of Sodium, it will just crash.
				if (!versionString.startsWith("IRIS-SNAPSHOT")) {
					throw new IllegalStateException("You do not have a compatible version of Sodium installed! You have " + versionString + " but IRIS-SNAPSHOT is expected");
				}
			}
		);

		try {
			Files.createDirectories(shaderpacksDirectory);
		} catch (IOException e) {
			Iris.logger.warn("Failed to create shaderpacks directory!");
			Iris.logger.catching(Level.WARN, e);
		}

		irisConfig = new IrisConfig();

		try {
			irisConfig.initialize();
		} catch (IOException e) {
			logger.error("Failed to initialize Iris configuration, default values will be used instead");
			logger.catching(Level.ERROR, e);
		}


		loadShaderpack();
		wasDisablingDirectionalShading = disableDirectionalShading;

		reloadKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding("iris.keybind.reload", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "iris.keybinds"));

		ClientTickEvents.END_CLIENT_TICK.register(minecraftClient -> {
			if (reloadKeybind.wasPressed()){

				try {
					reload();

					if (minecraftClient.player != null){
						minecraftClient.player.sendMessage(new TranslatableText("iris.shaders.reloaded"), false);
					}

				} catch (Exception e) {
					Iris.logger.error("Error while reloading Shaders for Iris!", e);

					if (minecraftClient.player != null) {
						minecraftClient.player.sendMessage(new TranslatableText("iris.shaders.reloaded.failure", Throwables.getRootCause(e).getMessage()).formatted(Formatting.RED), false);
					}
				}
			}
		});
	}

	public static void loadShaderpack() {
		// Attempt to load an external shaderpack if it is available
		if (!irisConfig.isInternal()) {
			if (!loadExternalShaderpack(irisConfig.getShaderPackName())) {
				loadInternalShaderpack();
			}
		} else {
			loadInternalShaderpack();
		}
	}

	private static boolean loadExternalShaderpack(String name) {
		Path shaderPackRoot = shaderpacksDirectory.resolve(name);
		Path shaderPackPath = shaderPackRoot.resolve("shaders");

		if (shaderPackRoot.toString().endsWith(".zip")) {
			Optional<Path> optionalPath = loadExternalZipShaderpack(shaderPackRoot);
			if (optionalPath.isPresent()) {
				shaderPackPath = optionalPath.get();
			}
		}
		if (!Files.exists(shaderPackPath)) {
			logger.warn("The shaderpack " + name + " does not have a shaders directory, falling back to internal shaders");
			return false;
		}

		try {
			currentPack = new ShaderPack(shaderPackPath);
		} catch (IOException e) {
			logger.error(String.format("Failed to load shaderpack \"%s\"! Falling back to internal shaders", irisConfig.getShaderPackName()));
			logger.catching(Level.ERROR, e);

			return false;
		}

		logger.info("Using shaderpack: " + name);
		disableDirectionalShading = true;

		return true;
	}

	private static Optional<Path> loadExternalZipShaderpack(Path shaderpackPath) {
		try {
			FileSystem zipSystem = FileSystems.newFileSystem(shaderpackPath, Iris.class.getClassLoader());
			zipFileSystem = zipSystem;
			Path root = zipSystem.getRootDirectories().iterator().next();//should only be one root directory for a zip shaderpack

			Path potentialShaderDir = zipSystem.getPath("shaders");
			//if the shaders dir was immediatly found return it
			//otherwise, manually search through each directory path until it ends with "shaders"
			if (Files.exists(potentialShaderDir)) {
				return Optional.of(potentialShaderDir);
			}

			//sometimes shaderpacks have their shaders directory within another folder in the shaderpack
			//for example Sildurs-Vibrant-Shaders.zip/shaders
			//while other packs have Trippy-Shaderpack-master.zip/Trippy-Shaderpack-master/shaders
			//this makes it hard to determine what is the actual shaders dir
			return Files.walk(root)
				.filter(Files::isDirectory)
				.filter(path -> path.endsWith("shaders"))
				.findFirst();
		} catch (IOException e) {
			if (e instanceof ZipException) {
				logger.error("The shaderpack appears to be corrupted, please try downloading it again {}", shaderpackPath);
			} else {
				logger.error("Error while finding shaderpack for zip directory {}", shaderpackPath);
			}
			logger.catching(Level.ERROR, e);
		}
		return Optional.empty();
	}

	private static void loadInternalShaderpack() {
		Path root = FabricLoader.getInstance().getModContainer("iris")
			.orElseThrow(() -> new RuntimeException("Failed to get the mod container for Iris!")).getRootPath();

		try {
			currentPack = new ShaderPack(root.resolve("shaders"));
		} catch (IOException e) {
			logger.error("Failed to load internal shaderpack!");
			throw new RuntimeException("Failed to load internal shaderpack!", e);
		}

		logger.info("Using internal shaders");
		disableDirectionalShading = false;
	}

	public static void reload() throws IOException {
		wasDisablingDirectionalShading = disableDirectionalShading;

		// allows shaderpacks to be changed at runtime
		irisConfig.initialize();

		// Destroy all allocated resources
		destroyEverything();

		// Load the new shaderpack
		loadShaderpack();

		// If Sodium is loaded, we need to reload the world renderer to properly recreate the ChunkRenderBackend
		// Otherwise, the terrain shaders won't be changed properly.
		// We also need to re-render all of the chunks if there is a change in the directional shading setting
		if (FabricLoader.getInstance().isModLoaded("sodium") || wasDisablingDirectionalShading != disableDirectionalShading) {
			MinecraftClient.getInstance().worldRenderer.reload();
		}
	}

	/**
	 * Destroys and deallocates all created OpenGL resources. Useful as part of a reload.
	 */
	private static void destroyEverything() {
		currentPack = null;

		// Unbind all textures
		//
		// This is necessary because we don't want destroyed render target textures to remain bound to certain texture
		// units. Vanilla appears to properly rebind all textures as needed, and we do so too, so this does not cause
		// issues elsewhere.
		//
		// Without this code, there will be weird issues when reloading certain shaderpacks.
		for (int i = 0; i < 16; i++) {
			GlStateManager.activeTexture(GL20C.GL_TEXTURE0 + i);
			GlStateManager.bindTexture(0);
		}

		// Set the active texture unit to unit 0
		//
		// This seems to be what most code expects. It's a sane default in any case.
		GlStateManager.activeTexture(GL20C.GL_TEXTURE0);

		// Destroy our render targets
		//
		// While it's possible to just clear them instead, we'd need to investigate whether or not this would help
		// performance.
		if (renderTargets != null) {
			renderTargets.destroy();
			renderTargets = null;
		}

		// Destroy the old world rendering pipeline
		//
		// This destroys all of the loaded gbuffer programs as well.
		if (pipeline != null) {
			pipeline.destroy();
			pipeline = null;
		}

		// Destroy the composite rendering pipeline
		//
		// This destroys all of the loaded composite programs as well.
		if (compositeRenderer != null) {
			compositeRenderer.destroy();
			compositeRenderer = null;
		}

		// Close the zip filesystem that the shaderpack was loaded from
		//
		// This prevents a FileSystemAlreadyExistsException when reloading shaderpacks.
		if (zipFileSystem != null) {
			try {
				zipFileSystem.close();
			} catch (IOException e) {
				Iris.logger.error("Failed to close zip file system?", e);
			}
		}
	}

	public static RenderTargets getRenderTargets() {
		if (renderTargets == null) {
			renderTargets = new RenderTargets(MinecraftClient.getInstance().getFramebuffer(), Objects.requireNonNull(currentPack));
		}

		return renderTargets;
	}

	public static ShaderPipeline getPipeline() {
		if (pipeline == null) {
			pipeline = new ShaderPipeline(Objects.requireNonNull(currentPack), getRenderTargets());
		}

		return pipeline;
	}

	public static ShaderPack getCurrentPack() {
		return currentPack;
	}

	public static CompositeRenderer getCompositeRenderer() {
		if (compositeRenderer == null) {
			compositeRenderer = new CompositeRenderer(Objects.requireNonNull(currentPack), getRenderTargets());
		}

		return compositeRenderer;
	}

	public static IrisConfig getIrisConfig() {
		return irisConfig;
	}

	public static boolean shouldDisableDirectionalShading() {
		return disableDirectionalShading;
	}
}
