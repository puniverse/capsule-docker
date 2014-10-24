/*
 * Copyright (c) 2014, Parallel Universe Software Co. and Contributors. All rights reserved.
 * 
 * This program and the accompanying materials are licensed under the terms 
 * of the Eclipse Public License v1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 * @author pron
 */
public class DockerCapsule extends Capsule {
    private static final String CONF_FILE = "Dockerfile";

    private static final String PROP_BUILD_IMAGE = "capsule.image";

    private static final String PROP_FILE_SEPARATOR = "file.separator";

    private static final String ATTR_JAVA_VERSION = "Java-Version";
    private static final String ATTR_JDK_REQUIRED = "JDK-Required";
    private static final String ATTR_EXPOSE = "Expose-Ports";

    private static final String LATEST_JDK = "8";

    private static final String PATH_ROOT = "/";
    private static final String PATH_APP = PATH_ROOT + "app";
    private static final String PATH_DEP = PATH_ROOT + "dep";

    private static final String DOCKERIZED_FILE_NAME = ".dockerized";

    private static final String FILE_SEPARATOR = System.getProperty(PROP_FILE_SEPARATOR);

    private final Path localRepo;
    private final Set<Path> deps = new HashSet<>();
    private Path context;

    public DockerCapsule(Path jarFile, Path cacheDir) {
        super(jarFile, cacheDir);
        this.localRepo = getLocalRepo();
    }

    @Override
    protected boolean needsAppCache() {
        return true;
    }

    @Override
    protected void markCache() throws IOException {
        super.markCache();
        Files.createFile(getAppCache().resolve(DOCKERIZED_FILE_NAME));
    }

    private boolean needsBuild() {
        return !isAppCacheUpToDate() || !Files.exists(getAppCache().resolve(DOCKERIZED_FILE_NAME));
    }

    private Path createContextDir() throws IOException {
        final Path contextDir = Files.createTempDirectory("docker-");
        Files.copy(getJarFile(), contextDir.resolve(getJarFile().getFileName()));
        if (getAppCache() != null) {
            final Path contextApp = contextDir.resolve("app");
            copy(getAppCache(), contextApp);
        }
        Path contextDep = contextDir.resolve("dep");
        Files.createDirectory(contextDep);
        for (Path d : deps)
            Files.copy(d, contextDep.resolve(d.getFileName()));
        return contextDir;
    }

    @Override
    protected String processOutgoingPath(Path p) {
        if (p == null)
            return null;
        p = p.normalize().toAbsolutePath();
        if (p.startsWith(localRepo))
            deps.add(p);
        return move(p);
    }

    @Override
    protected List<Path> getPlatformNativeLibraryPath() {
        return Arrays.asList(Paths.get("/usr/java/packages/lib/amd64"), Paths.get("/usr/lib/x86_64-linux-gnu/jni"),
                Paths.get("/lib/x86_64-linux-gnu"), Paths.get("/usr/lib/x86_64-linux-gnu"), Paths.get("/usr/lib/jni"), Paths.get("/lib"),
                Paths.get("/usr/lib"));
    }

//    @Override
//    protected Map<String, String> buildSystemProperties() {
//        Map<String, String> res = super.buildSystemProperties();
//        res.remove("java.library.path");
//        return res;
//    }
    @Override
    protected Map<String, String> buildEnvironmentVariables(Map<String, String> env) {
        return super.buildEnvironmentVariables(new HashMap<String, String>()); // don't import host's environment
    }

    @Override
    protected final Path getJavaExecutable() {
        return Paths.get("java");
    }

    @Override
    protected ProcessBuilder prelaunch(List<String> args) {
        final String imageName = getImageName();
        if (needsBuild()) {
            log(LOG_VERBOSE, "Building docker image");
            // Use the original ProcessBuilder to create the Dockerfile
            final ProcessBuilder pb = super.prelaunch(args);
            pb.command(pb.command().subList(0, pb.command().size() - args.size())); // remove args
            try {
                this.context = createContextDir();
                writeDockerfile(context.resolve(CONF_FILE), pb);

                log(LOG_VERBOSE, "Dockerfile: " + context.resolve(CONF_FILE));

                // ... and launch docker
                exec(new ProcessBuilder("docker", "build", "-q", "-t", imageName, ".").directory(context.toFile()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            log(LOG_QUIET, "Built docker image " + imageName);
        }

        final boolean build = systemPropertyEmptyOrTrue(PROP_BUILD_IMAGE);
        if (!build) {
            final List<String> command = new ArrayList<>(Arrays.asList("docker", "run"));
            // TODO: add docker options
            command.add(imageName);
            command.addAll(args);
            final ProcessBuilder pb2 = new ProcessBuilder(command);
            return pb2;
        } else
            return null;
    }
    
    private String getImageName() {
        return (getAppName() + (getAppVersion() != null ? ":" + getAppVersion() : "")).replace('.', '_').toLowerCase();
    }

    private void writeDockerfile(Path file, ProcessBuilder pb) throws IOException {
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(Files.newOutputStream(file), Charset.defaultCharset()))) {
            out.println("FROM " + getBaseImage());

            out.println("WORKDIR /");

            out.println("ADD " + getJarFile().getFileName() + " /");
            if (getAppCache() != null)
                out.println("ADD app /app");
            out.println("ADD dep /dep");

            for (Map.Entry<String, String> env : pb.environment().entrySet())
                out.println("ENV " + env.getKey() + " " + env.getValue());

            if (hasAttribute(ATTR_EXPOSE))
                out.println("EXPOSE " + toStringValue(getListAttribute(ATTR_EXPOSE)));

            out.println("ENTRYPOINT " + array(pb.command()));
        }
    }

    private String getBaseImage() {
        final boolean jdk = getAttribute(ATTR_JDK_REQUIRED, false);
        final String version = hasAttribute(ATTR_JAVA_VERSION) ? javaVersion(getAttribute(ATTR_JAVA_VERSION)) : LATEST_JDK;
        return "java:" + version + "-" + (jdk ? "jdk" : "jre");
    }

    private String move(Path p) {
        p = p.normalize().toAbsolutePath();
        if (p.equals(getJavaExecutable().toAbsolutePath()))
            return getJavaExecutable().toString();
        if (p.equals(getJarFile()))
            return moveJarFile(p);
        else if (getAppCache() != null && p.startsWith(getAppCache()))
            return moveAppCache(p);
        else if (p.startsWith(localRepo))
            return moveDep(p);
        else if (getPlatformNativeLibraryPath().contains(p))
            return toString(p);
        else
            throw new IllegalArgumentException("Unexpected file " + p);
    }

    private String moveJarFile(Path p) {
        return PATH_ROOT + p.getFileName();
    }

    private String moveAppCache(Path p) {
        return move(p, getAppCache(), PATH_APP);
    }

    private String moveDep(Path p) {
        return PATH_DEP + "/" + p.getFileName();
    }

    @Override
    @SuppressWarnings("CallToPrintStackTrace")
    protected void cleanup() {
        super.cleanup();

        try {
            if (context != null)
                delete(context);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static String move(Path what, Path fromDir, String toDir) {
        assert what.startsWith(fromDir);
        return toDir + "/" + toString(fromDir.relativize(fromDir));
    }

    private static String toString(Path p) {
        return isWindows() ? p.toString().replace(FILE_SEPARATOR, "/") : p.toString();
    }

//    private static String array(Object... xs) {
//        return array(Arrays.asList(xs));
//    }
    private static String array(List<?> xs) {
        final StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (Object x : xs)
            sb.append(quote(x.toString())).append(',');
        sb.delete(sb.length() - 1, sb.length());
        sb.append(']');
        return sb.toString();
    }

    private static String quote(String x) {
        return "\"" + x + "\"";
    }

    private String javaVersion(String v) {
        final String[] vs = v.split("\\.");
        if (vs.length == 1) {
            if (Integer.parseInt(vs[0]) < 5)
                throw new RuntimeException("Unrecognized major Java version: " + v);
            return vs[0];
        } else
            return vs[1];
    }

    // copied from Capsule
    private boolean getAttribute(String attr, boolean defaultValue) {
        final String val = getAttribute(attr);
        return val != null ? Boolean.parseBoolean(val) : defaultValue;
    }

    private static boolean systemPropertyEmptyOrTrue(String property) {
        final String value = System.getProperty(property);
        if (value == null)
            return false;
        return value.isEmpty() || Boolean.parseBoolean(value);
    }
}
