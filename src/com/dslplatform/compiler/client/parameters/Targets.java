package com.dslplatform.compiler.client.parameters;

import com.dslplatform.compiler.client.*;
import com.dslplatform.compiler.client.json.JsonObject;
import com.dslplatform.compiler.client.parameters.build.*;

import java.io.*;
import java.util.*;

public enum Targets implements CompileParameter {
	INSTANCE;

	public static enum Option {
		JAVA_CLIENT("java_client", "Java client", "Java", new CompileJavaClient("Java client", "java-client", "java_client", "dsl-client-http-apache", "./generated-model-java.jar"), true),
		ANDORID("android", "Android", "Android", new CompileJavaClient("Android", "android", "android", "dsl-client-http-android", "./generated-model-android.jar"), true),
		REVENJ("revenj", "Revenj .NET server", "CSharpServer", new CompileRevenj(), false),
		PHP("php", "PHP client", "PHP", new PreparePhp(), true),
		SCALA_CLIENT("scala_client", "Scala client", "ScalaClient", new CompileScalaClient(), false);

		private final String value;
		private final String description;
		private final String platformName;
		private final BuildAction action;
		private final boolean convertToPath;

		Option(
				final String value,
				final String description,
				final String platformName,
				final BuildAction action,
				final boolean convertToPath) {
			this.value = value;
			this.description = description;
			this.platformName = platformName;
			this.action = action;
			this.convertToPath = convertToPath;
		}

		public static Option from(final String value) {
			for (final Option o : Option.values()) {
				if (o.value.equalsIgnoreCase(value)) {
					return o;
				}
			}
			return null;
		}
	}

	private static void listOptions(final Context context) {
		for (final Option o : Option.values()) {
			context.show(o.value + " - " + o.description);
		}
		context.show("Example usages:");
		context.show("	-target=java_client,revenj");
		context.show("	-java_client -revenj=./model/SeverModel.dll");
	}

	private static final String CACHE_NAME = "target_option_cache";

	@Override
	public boolean check(final Context context) throws ExitException {
		final List<String> targets = new ArrayList<String>();
		if (context.contains(InputParameter.TARGET)) {
			final String value = context.get(InputParameter.TARGET);
			if (value == null || value.length() == 0) {
				context.error("Targets not provided. Available targets: ");
				listOptions(context);
				return false;
			}
			Collections.addAll(targets, value.split(","));
		}
		for(final Option o : Option.values()) {
			if (context.contains(o.value) && !targets.contains(o.value)) {
				targets.add(o.value);
			}
		}
		if(targets.size() == 0) {
			if (context.contains(InputParameter.TARGET)) {
				context.error("Targets not provided. Available targets: ");
				listOptions(context);
				return false;
			}
			return true;
		}
		final List<Option> options = new ArrayList<Option>(targets.size());
		for(final String name : targets) {
			final Option o = Option.from(name);
			if (o == null) {
				context.error("Unknown target: " + name);
				listOptions(context);
				return false;
			}
			options.add(o);
		}
		final Map<String, String> dsls = DslPath.getCurrentDsl(context);
		if (dsls.size() == 0) {
			context.error("Can't compile DSL to targets since no DSL was provided.");
			context.error("Please check your DSL folder: " + context.get(InputParameter.DSL));
			return false;
		}
		for(final Option o : options) {
			if (!o.action.check(context)) {
				return false;
			}
		}
		context.cache(CACHE_NAME, options);
		return true;
	}

	@Override
	public void run(final Context context) throws ExitException {
		final List<Option> targets = context.load(CACHE_NAME);
		if (targets == null) {
			return;
		}
		final StringBuilder sb = new StringBuilder();
		for(final Option t : targets) {
			sb.append(t.platformName);
			sb.append(',');
		}
		final Map<String, String> dsls = DslPath.getCurrentDsl(context);
		final StringBuilder url = new StringBuilder("Platform.svc/unmanaged/source?targets=");
		url.append(sb.substring(0, sb.length() - 1));
		if (context.contains(InputParameter.NAMESPACE)) {
			url.append("&namespace=").append(context.get(InputParameter.NAMESPACE));
		}
		final String settings = Settings.parseAndConvert(context);
		if (settings.length() > 0) {
			url.append("&options=").append(settings);
		}
		context.start("Compiling DSL");
		final Either<String> response = DslServer.put(url.toString(), context, Utils.toJson(dsls));
		if (!response.isSuccess()) {
			context.error("Error compiling DSL to specified target.");
			context.error(response.whyNot());
			throw new ExitException();
		}
		final JsonObject files = JsonObject.readFrom(response.get());
		final String temp = TempPath.getTempPath(context).getAbsolutePath();
		final Set<String> escapeNames = new HashSet<String>();
		for (final Option t : targets) {
			if (t.convertToPath) {
				escapeNames.add(t.platformName);
			}
		}
		try {
			for (final String name : files.names()) {
				final String nameOnly = name.substring(0, name.lastIndexOf('.'));
				final File file = name.contains("/") && escapeNames.contains(name.substring(0, name.indexOf("/")))
					? new File(temp, nameOnly.replace(".", "/") + name.substring(nameOnly.length()))
					: new File(temp, name);
				final File parentPath = file.getParentFile();
				if (!parentPath.exists()) {
					if (!parentPath.mkdirs()) {
						context.error("Failed creating path for target file: " + parentPath.getAbsolutePath());
						throw new ExitException();
					}
				}
				if (!file.createNewFile()) {
					context.error("Failed creating target file: " + file.getAbsolutePath());
					throw new ExitException();
				}
				Utils.saveFile(file, files.get(name).asString());
			}
		} catch (IOException e) {
			context.error("Can't create temporary target file. Compilation results can't be saved locally.");
			context.error(e);
			throw new ExitException();
		}
		for (final Option t : targets) {
			if (t.action != null) {
				t.action.build(new File(temp, t.platformName), context);
			}
		}
	}

	@Override
	public String getShortDescription() {
		return "Convert DSL to specified target (Java client, PHP, Revenj server, ...)";
	}

	@Override
	public String getDetailedDescription() {
		final StringBuilder sb = new StringBuilder();
		sb.append("DSL Platform converts DSL model to various target sources which are then locally compiled (if possible).\n\n");
		sb.append("Custom output name can be specified with as java_client=/home/model.jar,revenj=/home/revenj.dll\n\n");
		sb.append("This option specifies which target sources are available.\n");
		sb.append("---------------------------------------------------------\n");
		for (final Option o : Option.values()) {
			sb.append(o.value).append(" - ").append(o.description).append("\n");
		}
		return sb.toString();
	}
}
