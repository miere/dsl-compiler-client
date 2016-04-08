package com.dslplatform.compiler.client.parameters;

import com.dslplatform.compiler.client.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;

public enum Download implements CompileParameter {
	INSTANCE;

	@Override
	public String getAlias() {
		return "download";
	}

	@Override
	public String getUsage() {
		return null;
	}

	public static boolean downloadZip(
			final File dependencies,
			final Context context,
			final String name,
			final String zip) {
		try {
			context.show("Downloading " + name + " from DSL Platform...");
			final long lastModified = Utils.downloadAndUnpack(context, zip, dependencies);
			if (!dependencies.setLastModified(lastModified)) {
				context.error("Unable to set last modified info on: " + dependencies.getAbsolutePath());
			}
		} catch (IOException ex) {
			context.error("Error downloading dependencies from DSL Platform.");
			context.error(ex);
			return false;
		}
		return true;
	}

	private static boolean promptForAlternative(
			final File dependencies,
			final Context context,
			final String name,
			final String zip) throws ExitException {
		final String answer;
		if (!context.contains(INSTANCE)) {
			if (!context.canInteract()) {
				throw new ExitException();
			}
			answer = context.ask("Try alternative download from DSL Platform (y/N):");
		} else {
			answer = "y";
		}
		if (!"y".equalsIgnoreCase(answer)) {
			throw new ExitException();
		}
		return downloadZip(dependencies, context, name, zip);
	}

	public static boolean checkJars(
			final Context context,
			final String name,
			final String zip,
			final String id,
			final String path,
			final String... libraries) throws ExitException {
		final File dependencies = Dependencies.getDependencies(context, name, id, zip, true);
		final File[] found = dependencies.listFiles(new FilenameFilter() {
			@Override
			public boolean accept(File dir, String name) {
				return name.toLowerCase().endsWith(".jar");
			}
		});
		if (found.length == 0) {
			if (zip == null && libraries.length == 0) {
				context.log("No dependencies defined for: " + name);
				return true;
			}
			context.error(name + " not found in: " + dependencies.getAbsolutePath());
			if (!context.contains(INSTANCE)) {
				if (!context.canInteract()) {
					context.error("Download option not enabled. Enable download option, change dependencies path or place " + name + " files in specified folder.");
					throw new ExitException();
				}
				final String answer = context.ask("Do you wish to download latest " + name + " version from the Internet (y/N):");
				if (!"y".equalsIgnoreCase(answer)) {
					throw new ExitException();
				}
			}
			final Either<String> tryMaven = libraries.length > 0 && path != null ? Maven.findMaven(context) : Either.<String>fail("Library not defined");
			if (!tryMaven.isSuccess()) {
				if (zip == null) {
					context.error("Unable to find Maven. Dependency can't be downloaded.");
					throw new ExitException();
				}
				return downloadZip(dependencies, context, name, zip);
			}
			for (final String library : libraries) {
				if (!downloadLibrary(context, name, path, dependencies, tryMaven, library, zip)) {
					return false;
				}
			}
			final Either<Long> lastModified = Utils.lastModified(context, zip, name, 0);
			if (lastModified.isSuccess()) {
				if (!dependencies.setLastModified(lastModified.get())) {
					context.error("Unable to set last modified info on: " + dependencies.getAbsolutePath());
				}
			}
		}
		return true;
	}

	private static boolean downloadLibrary(
			final Context context,
			final String name,
			final String path,
			final File dependencies,
			final Either<String> tryMaven,
			final String library,
			final String zip) throws ExitException {
		context.show("Downloading " + name + " (" + library + ") from Sonatype...");
		try {
			final URL maven = new URL("https://oss.sonatype.org/content/repositories/releases/" + path + "/" + library + "/maven-metadata.xml");
			final Either<Document> doc = Utils.readXml(maven.openConnection().getInputStream());
			if (!doc.isSuccess()) {
				context.error("Error downloading library info from Sonatype.");
				context.error(doc.whyNot());
				return false;
			}
			final Element root = doc.get().getDocumentElement();
			final Element versioning = (Element) root.getElementsByTagName("versioning").item(0);
			final String version = versioning.getElementsByTagName("release").item(0).getTextContent();
			final String sharedUrl = "https://oss.sonatype.org/content/repositories/releases/" +
					path + "/" + library + "/" + version + "/" + library + "-" + version;
			final URL pomUrl = new URL(sharedUrl + ".pom");
			final File pomFile = new File(dependencies, library + "-" + version + ".pom");
			Utils.downloadFile(pomFile, pomUrl);
			final URL jarUrl = new URL(sharedUrl + ".jar");
			Utils.downloadFile(new File(dependencies, library + "-" + version + ".jar"), jarUrl);
			context.show("Downloading " + name + " library dependencies with Maven...");
			final Either<Utils.CommandResult> gatherDeps =
					Utils.runCommand(
							context,
							tryMaven.get(),
							pomFile.getParentFile(),
							Arrays.asList(
									"dependency:copy-dependencies",
									"\"-DoutputDirectory=" + dependencies.getAbsolutePath() + "\"",
									"\"-f=" + pomFile.getAbsolutePath() + "\""));
			if (!gatherDeps.isSuccess()) {
				context.error("Error gathering dependencies with Maven.");
				context.error(gatherDeps.whyNot());
				return promptForAlternative(dependencies, context, name, zip);
			}
			final String result = gatherDeps.get().output + gatherDeps.get().error;
			if (!result.contains("BUILD SUCCESS")) {
				context.error("Maven error during dependency download.");
				context.show(result);
				return promptForAlternative(dependencies, context, name, zip);
			}
		} catch (IOException ex) {
			context.error("Unable to download " + name + " from Sonatype.");
			context.error(ex);
			return promptForAlternative(dependencies, context, name, zip);
		}
		return true;
	}

	@Override
	public boolean check(final Context context) {
		return true;
	}

	@Override
	public void run(final Context context) {
	}

	@Override
	public String getShortDescription() {
		return "Download library dependencies if not available";
	}

	@Override
	public String getDetailedDescription() {
		return "Always download missing dependencies.\n" +
				"Dependencies will be checked for latest version.\n" +
				"Dependencies will be downloaded through Maven or from DSL Platform website.";
	}
}
