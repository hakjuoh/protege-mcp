package io.github.hakjuoh.protege_mcp.reasoner;

import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

/** Digests every class in the reviewed runtime package scopes, including inner classes. */
final class RuntimeCodeEvidence {

    private static final Limits PRODUCTION_LIMITS = new Limits(24L * 1024 * 1024,
            64L * 1024 * 1024, 128L * 1024 * 1024, 4 * 1024 * 1024,
            6_000, 150_000, 15_000L);
    private static final long MAX_PIN_BYTES = 512L * 1024 * 1024;
    private static final Set<String> OSGI_RESOURCE_PROTOCOLS = Set.of(
            "bundle", "bundleresource", "bundleentry");
    private static final Set<String> TRUSTED_OSGI_CONNECTION_TYPES = Set.of(
            "org.apache.felix.framework.URLHandlersBundleURLConnection",
            "org.eclipse.osgi.storage.url.BundleURLConnection",
            "org.eclipse.osgi.framework.internal.core.BundleURLConnection");
    private static final Set<String> TRUSTED_OSGI_CLASS_LOADER_TYPES = Set.of(
            "org.apache.felix.framework.BundleWiringImpl$BundleClassLoader",
            "org.eclipse.osgi.internal.loader.EquinoxClassLoader",
            "org.eclipse.osgi.internal.baseadaptor.DefaultClassLoader");
    private static final EvidenceCache CACHE = new EvidenceCache(
            RuntimeCodeEvidence::captureCached);

    private RuntimeCodeEvidence() {
    }

    static Evidence capture(Class<?> factoryType) {
        CachedEvidence cached = CACHE.get(factoryType);
        if (!cached.cacheable()) {
            CACHE.remove(factoryType);
            return cached.evidence();
        }
        List<CodeSourcePin> current = codeSourcePins(factoryType,
                new PinBudget(deadlineAfterMillis(PRODUCTION_LIMITS.captureMillis)));
        if (current == null || !cached.pins().equals(current)) {
            CACHE.remove(factoryType);
            return Evidence.unknown();
        }
        return cached.evidence();
    }

    private static CachedEvidence captureCached(Class<?> factoryType) {
        long deadlineNanos = deadlineAfterMillis(PRODUCTION_LIMITS.captureMillis);
        PinBudget pinBudget = new PinBudget(deadlineNanos);
        List<CodeSourcePin> before = codeSourcePins(factoryType, pinBudget);
        Evidence evidence = captureUncached(factoryType, deadlineNanos);
        List<CodeSourcePin> after = codeSourcePins(factoryType, pinBudget);
        boolean stable = before != null && before.equals(after);
        return new CachedEvidence(evidence,
                stable ? after : List.of(), stable);
    }

    private static Evidence captureUncached(Class<?> factoryType, long deadlineNanos) {
        ClassLoader loader = factoryType.getClassLoader();
        TreeMap<String, String> classDigests = new TreeMap<>();
        Set<String> activeMultiReleaseEntries = new LinkedHashSet<>();
        ByteBudget budget = new ByteBudget(
                System::nanoTime, PRODUCTION_LIMITS, deadlineNanos);
        try {
            List<Scope> scopes = scopes(factoryType);
            if (scopes.isEmpty()) return Evidence.unknown();
            Map<URL, Set<String>> locationPrefixes = new TreeMap<>(
                    (left, right) -> left.toExternalForm().compareTo(right.toExternalForm()));
            Map<String, ClassLoader> scopeLoaders = new TreeMap<>();
            for (Scope scope : scopes) {
                for (String anchorName : scope.anchors) {
                    Class<?> anchor = Class.forName(anchorName, false, loader);
                    URL location = codeLocation(anchor);
                    if (location == null) return Evidence.unknown();
                    locationPrefixes.computeIfAbsent(location, ignored -> new LinkedHashSet<>())
                            .add(scope.prefix);
                    ClassLoader previous = scopeLoaders.putIfAbsent(scope.prefix,
                            anchor.getClassLoader());
                    if (previous != null && previous != anchor.getClassLoader()) {
                        return Evidence.unknown();
                    }
                }
            }
            for (Map.Entry<URL, Set<String>> entry : locationPrefixes.entrySet()) {
                scan(entry.getKey(), List.copyOf(entry.getValue()), classDigests,
                        activeMultiReleaseEntries, budget);
            }
            for (Scope scope : scopes) {
                if (!containsScope(classDigests, scope.prefix)) return Evidence.unknown();
                if (!effectiveResourcesMatch(classDigests, scope.prefix,
                        scopeLoaders.get(scope.prefix), activeMultiReleaseEntries, budget)) {
                    return Evidence.unknown();
                }
            }
            return finish(classDigests, activeMultiReleaseEntries, scopes.stream()
                    .map(scope -> scope.prefix + "**").toList());
        } catch (IOException | ReflectiveOperationException | RuntimeException
                | LinkageError unavailable) {
            return Evidence.unknown();
        }
    }

    /** Package-scoped seam for deterministic scanner fixtures and diagnostics. */
    static Evidence captureLocations(List<URL> locations, List<String> prefixes) {
        return captureLocations(locations, prefixes, System::nanoTime, PRODUCTION_LIMITS);
    }

    static Evidence captureLocations(List<URL> locations, List<String> prefixes,
            LongSupplier nanoTime, long captureMillis) {
        return captureLocations(locations, prefixes, nanoTime,
                PRODUCTION_LIMITS.withCaptureMillis(captureMillis));
    }

    static Evidence captureLocations(List<URL> locations, List<String> prefixes,
            LongSupplier nanoTime, Limits limits) {
        return captureLocations(locations, prefixes, nanoTime, limits, Map.of());
    }

    static Evidence captureLocations(List<URL> locations, List<String> prefixes,
            LongSupplier nanoTime, Limits limits,
            Map<String, ClassLoader> effectiveLoaders) {
        TreeMap<String, String> classDigests = new TreeMap<>();
        Set<String> activeMultiReleaseEntries = new LinkedHashSet<>();
        ByteBudget budget = new ByteBudget(nanoTime, limits);
        try {
            for (URL location : new LinkedHashSet<>(locations)) {
                scan(location, prefixes, classDigests, activeMultiReleaseEntries, budget);
            }
            for (String prefix : prefixes) {
                if (!containsScope(classDigests, prefix)) return Evidence.unknown();
                if (effectiveLoaders.containsKey(prefix)
                        && !effectiveResourcesMatch(classDigests, prefix,
                                effectiveLoaders.get(prefix), activeMultiReleaseEntries, budget)) {
                    return Evidence.unknown();
                }
            }
            return finish(classDigests, activeMultiReleaseEntries,
                    prefixes.stream().map(prefix -> prefix + "**").toList());
        } catch (IOException | RuntimeException unavailable) {
            return Evidence.unknown();
        }
    }

    private static Evidence finish(Map<String, String> classDigests,
            Set<String> activeMultiReleaseEntries, List<String> scopes) {
        MessageDigest aggregate = sha256();
        for (Map.Entry<String, String> entry : classDigests.entrySet()) {
            add(aggregate, entry.getKey());
            add(aggregate, entry.getValue());
        }
        for (String name : activeMultiReleaseEntries.stream().sorted().toList()) {
            add(aggregate, "active-multi-release");
            add(aggregate, name);
        }
        return new Evidence(hex(aggregate), scopes, classDigests.size());
    }

    private static URL codeLocation(Class<?> type) {
        CodeSource source = type.getProtectionDomain() == null
                ? null : type.getProtectionDomain().getCodeSource();
        if (source != null && source.getLocation() != null) return source.getLocation();
        String resourceName = type.getName().replace('.', '/') + ".class";
        ClassLoader loader = type.getClassLoader();
        URL resource = loader == null ? ClassLoader.getSystemResource(resourceName)
                : loader.getResource(resourceName);
        if (resource == null) return null;
        if ("jar".equals(resource.getProtocol())) {
            try {
                return localJarContainer(resource, resourceName).toUri().toURL();
            } catch (IOException | RuntimeException unavailable) {
                return null;
            }
        }
        if (!"file".equals(resource.getProtocol())) return null;
        try {
            Path classFile = localRegularFile(resource);
            Path root = classFile;
            for (int index = 0; index < resourceName.split("/").length; index++) {
                root = root.getParent();
            }
            return root == null ? null : root.toUri().toURL();
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    private static List<CodeSourcePin> codeSourcePins(
            Class<?> factoryType, PinBudget budget) {
        try {
            List<CodeSourcePin> pins = new ArrayList<>();
            Map<String, ContainerPin> containers = new TreeMap<>();
            for (Scope scope : scopes(factoryType)) {
                for (String anchorName : scope.anchors) {
                    Class<?> anchor = Class.forName(
                            anchorName, false, factoryType.getClassLoader());
                    URL location = codeLocation(anchor);
                    if (location == null) return null;
                    CodeSourcePin pin = pin(anchor, location, budget, containers);
                    if (pin == null) return null;
                    pins.add(pin);
                }
            }
            return List.copyOf(pins);
        } catch (IOException | ReflectiveOperationException | RuntimeException
                | LinkageError unavailable) {
            return null;
        }
    }

    static CodeSourcePin pin(Class<?> anchor, URL location) throws IOException {
        return pin(anchor, location,
                new PinBudget(deadlineAfterMillis(PRODUCTION_LIMITS.captureMillis)),
                new TreeMap<>());
    }

    private static CodeSourcePin pin(Class<?> anchor, URL location,
            PinBudget budget, Map<String, ContainerPin> containers) throws IOException {
        URL effective = location;
        if ("jar".equals(location.getProtocol())) {
            URLConnection connection = location.openConnection();
            connection.setUseCaches(false);
            if (!(connection instanceof JarURLConnection jar)) return null;
            effective = jar.getJarFileURL();
        }
        if ("file".equals(effective.getProtocol())) {
            Path path = localFilePath(effective).toRealPath(LinkOption.NOFOLLOW_LINKS);
            String locationKey = path.toString();
            ContainerPin container = containers.get(locationKey);
            if (container == null) {
                BasicFileAttributes attributes = Files.readAttributes(path,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.fileKey() == null) return null;
                if (attributes.isDirectory()) {
                    container = pinDirectory(path, attributes, budget);
                } else if (attributes.isRegularFile()) {
                    String contentDigest = digestRegularFile(
                            path, PRODUCTION_LIMITS.maxContainerBytes, budget);
                    BasicFileAttributes after = Files.readAttributes(path,
                            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.fileKey().equals(after.fileKey())
                            || attributes.size() != after.size()
                            || !attributes.lastModifiedTime().equals(after.lastModifiedTime())) {
                        return null;
                    }
                    container = new ContainerPin(String.valueOf(attributes.fileKey()),
                            attributes.size(), attributes.lastModifiedTime().toMillis(),
                            contentDigest);
                } else {
                    return null;
                }
                if (container == null) return null;
                containers.put(locationKey, container);
            }
            return new CodeSourcePin(anchor, locationKey, container.fileKey(),
                    container.bytes(), container.modifiedMillis(), container.contentDigest());
        }
        if (OSGI_RESOURCE_PROTOCOLS.contains(effective.getProtocol())) {
            // OSGi URLs do not expose a portable immutable bundle-generation identity.
            // Recompute evidence rather than caching it against an assumed generation.
            return null;
        }
        return null;
    }

    private static ContainerPin pinDirectory(Path root, BasicFileAttributes before,
            PinBudget budget) throws IOException {
        MessageDigest digest = sha256();
        long bytes = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(root)) continue;
                BasicFileAttributes attributes = Files.readAttributes(path,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink()) {
                    throw new IOException("runtime-code directory contains a symbolic link");
                }
                if (!attributes.isRegularFile()) continue;
                if (attributes.fileKey() == null) return null;
                String relative = root.relativize(path).toString().replace(
                        path.getFileSystem().getSeparator(), "/");
                String contentDigest = digestRegularFile(
                        path, PRODUCTION_LIMITS.maxContainerBytes, budget);
                BasicFileAttributes after = Files.readAttributes(path,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.fileKey().equals(after.fileKey())
                        || attributes.size() != after.size()
                        || !attributes.lastModifiedTime().equals(after.lastModifiedTime())) {
                    return null;
                }
                add(digest, relative);
                add(digest, String.valueOf(attributes.fileKey()));
                add(digest, String.valueOf(attributes.size()));
                add(digest, String.valueOf(attributes.lastModifiedTime().toMillis()));
                add(digest, contentDigest);
                bytes = Math.addExact(bytes, attributes.size());
            }
        }
        BasicFileAttributes after = Files.readAttributes(root,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.fileKey().equals(after.fileKey())
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
            return null;
        }
        return new ContainerPin(String.valueOf(before.fileKey()), bytes,
                before.lastModifiedTime().toMillis(), hex(digest));
    }

    private static String digestRegularFile(
            Path path, long maximumBytes, PinBudget budget) throws IOException {
        budget.checkTime();
        long size = Files.size(path);
        if (size < 0 || size > maximumBytes) {
            throw new IOException("runtime-code container exceeds evidence budget");
        }
        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(path)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) continue;
                total += read;
                budget.claim(read);
                if (total > maximumBytes) {
                    throw new IOException("runtime-code container exceeds evidence budget");
                }
                digest.update(buffer, 0, read);
            }
        }
        if (total != size) {
            throw new IOException("runtime-code container changed while it was pinned");
        }
        return hex(digest);
    }

    private static long deadlineAfterMillis(long millis) {
        long now = System.nanoTime();
        long duration = TimeUnit.MILLISECONDS.toNanos(millis);
        return now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
    }

    private static boolean containsScope(Map<String, String> digests, String prefix) {
        return digests.keySet().stream().anyMatch(name -> matchesScope(name, prefix));
    }

    private static boolean effectiveResourcesMatch(Map<String, String> digests, String prefix,
            ClassLoader loader, Set<String> activeMultiReleaseEntries, ByteBudget budget)
            throws IOException {
        Map<String, TreeMap<Integer, String>> candidates = new TreeMap<>();
        for (Map.Entry<String, String> entry : digests.entrySet()) {
            if (!matchesScope(entry.getKey(), prefix)) continue;
            TreeMap<Integer, String> logicalCandidates = candidates.computeIfAbsent(
                    logicalClassName(entry.getKey()), ignored -> new TreeMap<>());
            if (!entry.getKey().startsWith("META-INF/versions/")
                    || activeMultiReleaseEntries.contains(entry.getKey())) {
                logicalCandidates.put(multiReleaseVersion(entry.getKey()), entry.getValue());
            }
        }
        int runtimeFeature = Runtime.version().feature();
        for (Map.Entry<String, TreeMap<Integer, String>> entry : candidates.entrySet()) {
            Map.Entry<Integer, String> active = entry.getValue().floorEntry(runtimeFeature);
            if (active == null) return false;
            URL resource = loader == null ? ClassLoader.getSystemResource(entry.getKey())
                    : loader.getResource(entry.getKey());
            if (resource == null) return false;
            try (InputStream in = openLocalResource(
                    resource, entry.getKey(), loader, budget)) {
                String actual = digestEffectiveClass(in, budget);
                if (!active.getValue().equals(actual)) return false;
            }
        }
        return true;
    }

    private static InputStream openLocalResource(URL resource, String logicalName,
            ClassLoader resourceLoader, ByteBudget budget) throws IOException {
        if ("file".equals(resource.getProtocol())) {
            Path path = localRegularFile(resource);
            if (Files.size(path) > budget.limits.maxSingleClassBytes) {
                throw new IOException("effective class exceeds evidence budget");
            }
            return Files.newInputStream(path);
        }
        if ("jar".equals(resource.getProtocol())) {
            return openLocalJarResource(resource, logicalName, budget);
        }
        if (OSGI_RESOURCE_PROTOCOLS.contains(resource.getProtocol())) {
            URL local = localOsgiResource(resource, logicalName, resourceLoader, budget);
            return openLocalResource(local, logicalName, resourceLoader, budget);
        }
        throw new IOException("unsupported effective-resource protocol");
    }

    private static InputStream openLocalJarResource(URL resource, String logicalName,
            ByteBudget budget) throws IOException {
        Path container = localJarContainer(resource, logicalName);
        if (Files.size(container) > budget.limits.maxContainerBytes) {
            throw new IOException("effective container exceeds evidence budget");
        }
        JarFile jar = new JarFile(container.toFile(), true, ZipFile.OPEN_READ,
                Runtime.version());
        try {
            JarEntry entry = jar.getJarEntry(logicalName);
            if (entry == null || entry.isDirectory()) {
                throw new IOException("effective class is absent from its JAR container");
            }
            InputStream in = jar.getInputStream(entry);
            return closingJarStream(in, jar);
        } catch (IOException | RuntimeException unavailable) {
            try {
                jar.close();
            } catch (IOException closeFailure) {
                unavailable.addSuppressed(closeFailure);
            }
            throw unavailable;
        }
    }

    private static Path localJarContainer(URL resource, String logicalName) throws IOException {
        if (resource.getQuery() != null
                || (resource.getRef() != null && !"runtime".equals(resource.getRef()))) {
            throw new IOException("unsupported effective JAR resource suffix");
        }
        String path = resource.getPath();
        int separator = path == null ? -1 : path.indexOf("!/");
        if (separator <= 0 || path.indexOf("!/", separator + 2) >= 0) {
            throw new IOException("invalid effective JAR resource");
        }
        String entryName = path.substring(separator + 2);
        if (!logicalName.equals(logicalClassName(entryName))) {
            throw new IOException("effective JAR entry does not match the requested class");
        }
        final Path container;
        try {
            URI containerUri = new URI(path.substring(0, separator));
            if (!"file".equals(containerUri.getScheme())
                    || containerUri.getQuery() != null || containerUri.getFragment() != null) {
                throw new IOException("effective JAR container is not local");
            }
            container = localFilePath(containerUri);
        } catch (URISyntaxException | IllegalArgumentException invalid) {
            throw new IOException("invalid effective JAR container", invalid);
        }
        if (!Files.isRegularFile(container)) {
            throw new IOException("effective JAR container is not a regular file");
        }
        return container;
    }

    private static InputStream closingJarStream(InputStream in, JarFile jar) {
        return new FilterInputStream(in) {
            @Override
            public void close() throws IOException {
                IOException failure = null;
                try {
                    super.close();
                } catch (IOException unavailable) {
                    failure = unavailable;
                }
                try {
                    jar.close();
                } catch (IOException unavailable) {
                    if (failure == null) failure = unavailable;
                    else failure.addSuppressed(unavailable);
                }
                if (failure != null) throw failure;
            }
        };
    }

    private static URL localOsgiResource(URL resource, String logicalName,
            ClassLoader resourceLoader, ByteBudget budget) throws IOException {
        String path = resource.getPath();
        if (path == null || !(path.equals(logicalName) || path.equals("/" + logicalName))) {
            throw new IOException("OSGi resource path does not match the requested class");
        }
        if (resourceLoader == null
                || !TRUSTED_OSGI_CLASS_LOADER_TYPES.contains(resourceLoader.getClass().getName())) {
            throw new IOException("untrusted OSGi resource class loader");
        }
        requireLocalFrameworkCode(resourceLoader.getClass(), budget);
        URLConnection connection = resource.openConnection();
        Class<?> connectionType = connection.getClass();
        if (!TRUSTED_OSGI_CONNECTION_TYPES.contains(connectionType.getName())) {
            throw new IOException("untrusted OSGi resource connection");
        }
        requireLocalFrameworkCode(connectionType, budget);
        try {
            Method localUrl = connectionType.getDeclaredMethod("getLocalURL");
            if (!localUrl.trySetAccessible()) {
                throw new IOException("OSGi local-resource bridge is inaccessible");
            }
            Object value = localUrl.invoke(connection);
            if (!(value instanceof URL local)
                    || OSGI_RESOURCE_PROTOCOLS.contains(local.getProtocol())) {
                throw new IOException("OSGi resource did not resolve to a local URL");
            }
            return local;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            throw new IOException("OSGi local-resource bridge failed", unavailable);
        }
    }

    private static void requireLocalFrameworkCode(Class<?> connectionType, ByteBudget budget)
            throws IOException {
        CodeSource source = connectionType.getProtectionDomain() == null
                ? null : connectionType.getProtectionDomain().getCodeSource();
        URL location = source == null ? null : source.getLocation();
        if (location == null || !"file".equals(location.getProtocol())) {
            throw new IOException("OSGi resource handler is not local");
        }
        Path path = localFilePath(location);
        if (!Files.isDirectory(path) && !Files.isRegularFile(path)) {
            throw new IOException("OSGi resource handler is not a regular local artifact");
        }
        if (Files.isRegularFile(path) && Files.size(path) > budget.limits.maxContainerBytes) {
            throw new IOException("OSGi resource handler exceeds evidence budget");
        }
    }

    private static Path localRegularFile(URL resource) throws IOException {
        if (!"file".equals(resource.getProtocol())) {
            throw new IOException("effective resource is not local");
        }
        Path path = localFilePath(resource);
        if (!Files.isRegularFile(path)) {
            throw new IOException("effective resource is not a regular file");
        }
        return path;
    }

    /** Package-scoped seam for platform-independent network-file rejection tests. */
    static Path localFilePath(URL resource) throws IOException {
        if (!"file".equals(resource.getProtocol())) {
            throw new IOException("resource is not a local file URL");
        }
        String authority = resource.getAuthority();
        String rawPath = resource.getPath();
        if ((authority != null && !authority.isEmpty()) || isUncText(rawPath)) {
            throw new IOException("network file resources are unsupported");
        }
        try {
            return rejectUncPath(Path.of(resource.toURI()));
        } catch (URISyntaxException | IllegalArgumentException invalid) {
            throw new IOException("invalid local file resource", invalid);
        }
    }

    /** Package-scoped seam for URI forms not representable by one platform's Path implementation. */
    static Path localFilePath(URI resource) throws IOException {
        String authority = resource.getAuthority();
        if (!"file".equals(resource.getScheme())) {
            throw new IOException("resource is not a local file URI");
        }
        if ((authority != null && !authority.isEmpty()) || isUncText(resource.getPath())) {
            throw new IOException("network file resources are unsupported");
        }
        try {
            return rejectUncPath(Path.of(resource));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid local file resource", invalid);
        }
    }

    /** Package-scoped seam for Windows UNC forms on non-Windows test hosts. */
    static Path rejectUncPath(Path path) throws IOException {
        if (isUncText(path.toString())) {
            throw new IOException("network file resources are unsupported");
        }
        return path;
    }

    private static boolean isUncText(String value) {
        return value != null && (value.startsWith("//") || value.startsWith("\\\\"));
    }

    private static String logicalClassName(String name) {
        String marker = "META-INF/versions/";
        if (!name.startsWith(marker)) return name;
        int versionEnd = name.indexOf('/', marker.length());
        return name.substring(versionEnd + 1);
    }

    private static int multiReleaseVersion(String name) throws IOException {
        String marker = "META-INF/versions/";
        if (!name.startsWith(marker)) return 0;
        int versionEnd = name.indexOf('/', marker.length());
        try {
            int version = Integer.parseInt(name.substring(marker.length(), versionEnd));
            if (version < 9) throw new NumberFormatException("version below 9");
            return version;
        } catch (NumberFormatException invalid) {
            throw new IOException("invalid multi-release class version", invalid);
        }
    }

    private static String digestEffectiveClass(InputStream in, ByteBudget budget)
            throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[8192];
        int classBytes = 0;
        for (int read; (read = in.read(buffer)) >= 0;) {
            budget.checkTime();
            classBytes += read;
            budget.claimVerificationBytes(read);
            if (classBytes > budget.limits.maxSingleClassBytes) {
                throw new IOException("effective class exceeds evidence budget");
            }
            digest.update(buffer, 0, read);
        }
        return hex(digest);
    }

    private static void scan(URL location, List<String> prefixes, Map<String, String> digests,
            Set<String> activeMultiReleaseEntries, ByteBudget budget) throws IOException {
        if (!"file".equals(location.getProtocol())) {
            throw new IOException("unsupported code-source protocol");
        }
        Path path = localFilePath(location);
        if (Files.isDirectory(path)) {
            scanDirectory(path, prefixes, digests, activeMultiReleaseEntries, budget);
        } else {
            scanJar(path, prefixes, digests, activeMultiReleaseEntries, budget);
        }
    }

    private static void scanJar(Path path, List<String> prefixes, Map<String, String> digests,
            Set<String> activeMultiReleaseEntries, ByteBudget budget) throws IOException {
        budget.checkTime();
        if (Files.size(path) > budget.limits.maxContainerBytes) {
            throw new IOException("container bytes exceed evidence budget");
        }
        try (JarFile jar = new JarFile(path.toFile())) {
            boolean multiRelease = jar.isMultiRelease();
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                budget.claimEntry();
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !matchesAnyScope(entry.getName(), prefixes)) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    addClass(entry.getName(), in, digests, activeMultiReleaseEntries,
                            multiRelease, budget);
                }
            }
            scanNestedJars(jar, prefixes, digests, activeMultiReleaseEntries, budget);
        }
    }

    private static void scanNestedJars(JarFile outer, List<String> prefixes,
            Map<String, String> digests, Set<String> activeMultiReleaseEntries,
            ByteBudget budget) throws IOException {
        var entries = outer.entries();
        while (entries.hasMoreElements()) {
            budget.claimEntry();
            JarEntry nested = entries.nextElement();
            if (nested.isDirectory() || !nested.getName().endsWith(".jar")) continue;
            try (InputStream bounded = new NestedBudgetInputStream(
                    outer.getInputStream(nested), budget);
                    JarInputStream in = new JarInputStream(bounded)) {
                boolean multiRelease = isMultiRelease(in.getManifest());
                for (JarEntry entry; (entry = in.getNextJarEntry()) != null;) {
                    budget.claimEntry();
                    if (entry.isDirectory()) continue;
                    if (matchesAnyScope(entry.getName(), prefixes)) {
                        addClass(entry.getName(), in, digests, activeMultiReleaseEntries,
                                multiRelease, budget);
                    } else {
                        drainNestedEntry(in, budget);
                    }
                }
            }
        }
    }

    private static boolean matchesAnyScope(String name, List<String> prefixes) {
        return prefixes.stream().anyMatch(prefix -> matchesScope(name, prefix));
    }

    private static boolean matchesScope(String name, String prefix) {
        if (!name.endsWith(".class")) return false;
        if (name.startsWith(prefix)) return true;
        String marker = "META-INF/versions/";
        if (!name.startsWith(marker)) return false;
        int versionEnd = name.indexOf('/', marker.length());
        return versionEnd > marker.length()
                && name.regionMatches(versionEnd + 1, prefix, 0, prefix.length());
    }

    private static void drainNestedEntry(InputStream in, ByteBudget budget) throws IOException {
        byte[] buffer = new byte[8192];
        while (in.read(buffer) >= 0) budget.checkTime();
    }

    private static void scanDirectory(Path root, List<String> prefixes,
            Map<String, String> digests, Set<String> activeMultiReleaseEntries,
            ByteBudget budget) throws IOException {
        boolean multiRelease = isMultiReleaseDirectory(root, budget);
        try (var stream = Files.walk(root)) {
            var files = stream.iterator();
            while (files.hasNext()) {
                budget.claimEntry();
                Path file = files.next();
                String name = root.relativize(file).toString().replace(file.getFileSystem()
                        .getSeparator(), "/");
                if (!Files.isRegularFile(file) || !matchesAnyScope(name, prefixes)) continue;
                try (InputStream in = Files.newInputStream(file)) {
                    addClass(name, in, digests, activeMultiReleaseEntries,
                            multiRelease, budget);
                }
            }
        }
    }

    private static boolean isMultiReleaseDirectory(Path root, ByteBudget budget)
            throws IOException {
        Path manifestPath = root.resolve("META-INF/MANIFEST.MF");
        if (!Files.isRegularFile(manifestPath)) return false;
        if (Files.size(manifestPath) > budget.limits.maxSingleClassBytes) {
            throw new IOException("manifest exceeds evidence budget");
        }
        budget.checkTime();
        try (InputStream in = Files.newInputStream(manifestPath)) {
            return isMultiRelease(new Manifest(in));
        }
    }

    private static boolean isMultiRelease(Manifest manifest) {
        return manifest != null && Boolean.parseBoolean(
                manifest.getMainAttributes().getValue("Multi-Release"));
    }

    private static void addClass(String name, InputStream in, Map<String, String> digests,
            Set<String> activeMultiReleaseEntries, boolean multiRelease,
            ByteBudget budget) throws IOException {
        if (digests.size() >= budget.limits.maxClasses && !digests.containsKey(name)) {
            throw new IOException("class count exceeds evidence budget");
        }
        MessageDigest digest = sha256();
        byte[] buffer = new byte[8192];
        int classBytes = 0;
        for (int read; (read = in.read(buffer)) >= 0;) {
            budget.checkTime();
            classBytes += read;
            budget.total += read;
            if (classBytes > budget.limits.maxSingleClassBytes
                    || budget.total > budget.limits.maxTotalClassBytes) {
                throw new IOException("class bytes exceed evidence budget");
            }
            digest.update(buffer, 0, read);
        }
        String classDigest = hex(digest);
        String previous = digests.putIfAbsent(name, classDigest);
        if (previous != null && !previous.equals(classDigest)) {
            throw new IOException("conflicting duplicate class resource");
        }
        if (multiRelease && name.startsWith("META-INF/versions/")) {
            activeMultiReleaseEntries.add(name);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String hex(MessageDigest digest) {
        StringBuilder out = new StringBuilder("sha256:");
        for (byte value : digest.digest()) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static List<Scope> scopes(Class<?> factoryType) {
        String factoryClass = factoryType.getName();
        List<String> owlApi = List.of("org.semanticweb.owlapi.model.OWLOntology");
        return switch (factoryClass) {
            case "org.semanticweb.HermiT.ReasonerFactory" -> {
                List<Scope> hermit = new ArrayList<>(List.of(
                        new Scope("org/semanticweb/HermiT/",
                                List.of("org.semanticweb.HermiT.ReasonerFactory")),
                        new Scope("rationals/", List.of("rationals.Automaton")),
                        new Scope("dk/brics/automaton/", List.of("dk.brics.automaton.Automaton")),
                        new Scope("org/apache/axiom/", List.of(
                                "org.apache.axiom.om.OMNode",
                                "org.apache.axiom.c14n.Canonicalizer",
                                "org.apache.axiom.om.impl.llom.OMElementImpl",
                                "org.apache.axiom.om.impl.dom.DocumentImpl")),
                        new Scope("org/semanticweb/owlapi/", owlApi)));
                try {
                    Class.forName("net.automatalib.automaton.fsa.impl.CompactNFA", false,
                            factoryType.getClassLoader());
                    hermit.add(new Scope("net/automatalib/", List.of(
                            "net.automatalib.automaton.fsa.impl.CompactNFA")));
                } catch (ClassNotFoundException | LinkageError absent) {
                    // The upstream HermiT bundle uses its own rationals implementation.
                }
                yield List.copyOf(hermit);
            }
            case "org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory" ->
                    List.of(new Scope("org/semanticweb/owlapi/", owlApi));
            case "org.semanticweb.elk.owlapi.ElkReasonerFactory" -> List.of(
                    new Scope("org/semanticweb/elk/", List.of(
                            "org.semanticweb.elk.owlapi.ElkReasonerFactory",
                            "org.semanticweb.elk.owl.filters.ElkSubDataPropertyOfAxiomFilter",
                            "org.semanticweb.elk.owl.implementation.ElkFunctionalDataPropertyAxiomImpl",
                            "org.semanticweb.elk.reasoner.Reasoner",
                            "org.semanticweb.elk.matching.IndexedObjectPropertyRangeAxiomMatch2InferenceVisitor",
                            "org.semanticweb.elk.Reference",
                            "org.semanticweb.elk.util.concurrent.computation.ConcurrentExecutorImpl",
                            "org.semanticweb.elk.util.hashing.Hasher",
                            "org.semanticweb.elk.io.IOUtils",
                            "org.semanticweb.elk.util.collections.LinearProbingIterator",
                            "org.semanticweb.elk.util.logging.Statistics")),
                    new Scope("org/semanticweb/owlapi/", owlApi));
            default -> List.of();
        };
    }

    record Evidence(String digest, List<String> scopes, int classCount) {
        Evidence {
            scopes = List.copyOf(scopes);
        }

        static Evidence unknown() {
            return new Evidence("unknown", List.of(), 0);
        }
    }

    record CachedEvidence(Evidence evidence, List<CodeSourcePin> pins, boolean cacheable) {
        CachedEvidence {
            pins = List.copyOf(pins);
        }
    }

    record CodeSourcePin(Class<?> anchor, String location, String fileKey,
            long bytes, long modifiedMillis, String contentDigest) {
    }

    private record ContainerPin(String fileKey, long bytes,
            long modifiedMillis, String contentDigest) { }

    private static final class PinBudget {
        private final long deadlineNanos;
        private long bytes;

        PinBudget(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }

        void claim(int count) throws IOException {
            checkTime();
            bytes += count;
            if (count < 0 || bytes > MAX_PIN_BYTES) {
                throw new IOException("runtime-code pins exceed cumulative byte budget");
            }
        }

        void checkTime() throws IOException {
            if (System.nanoTime() - deadlineNanos >= 0) {
                throw new IOException("runtime-code pins exceed evidence time budget");
            }
        }
    }

    static final class EvidenceCache {
        private final Function<Class<?>, CachedEvidence> loader;
        private final ClassValue<CachedEvidence> values;

        EvidenceCache(Function<Class<?>, CachedEvidence> loader) {
            this.loader = java.util.Objects.requireNonNull(loader, "loader");
            this.values = new ClassValue<>() {
                @Override
                protected CachedEvidence computeValue(Class<?> type) {
                    return EvidenceCache.this.loader.apply(type);
                }
            };
        }

        CachedEvidence get(Class<?> type) {
            CachedEvidence value = values.get(type);
            if (Evidence.unknown().equals(value.evidence())) values.remove(type);
            return value;
        }

        void remove(Class<?> type) {
            values.remove(type);
        }
    }

    private record Scope(String prefix, List<String> anchors) { }

    record Limits(long maxTotalClassBytes, long maxNestedEntryBytes,
            long maxContainerBytes, int maxSingleClassBytes, int maxClasses,
            int maxContainerEntries, long captureMillis) {
        Limits {
            if (maxTotalClassBytes < 0 || maxNestedEntryBytes < 0 || maxContainerBytes < 0
                    || maxSingleClassBytes < 0 || maxClasses < 0
                    || maxContainerEntries < 0 || captureMillis < 0) {
                throw new IllegalArgumentException("evidence limits must be non-negative");
            }
        }

        Limits withCaptureMillis(long value) {
            return new Limits(maxTotalClassBytes, maxNestedEntryBytes, maxContainerBytes,
                    maxSingleClassBytes, maxClasses, maxContainerEntries, value);
        }
    }

    private static final class ByteBudget {
        private long total;
        private long verificationBytes;
        private long nestedBytes;
        private int entries;
        private final LongSupplier nanoTime;
        private final long deadlineNanos;
        private final Limits limits;

        ByteBudget() {
            this(System::nanoTime, PRODUCTION_LIMITS);
        }

        ByteBudget(LongSupplier nanoTime, Limits limits) {
            this(nanoTime, limits, deadline(nanoTime, limits.captureMillis));
        }

        ByteBudget(LongSupplier nanoTime, Limits limits, long deadlineNanos) {
            if (nanoTime == null || limits == null) {
                throw new IllegalArgumentException("clock and limits are required");
            }
            this.nanoTime = nanoTime;
            this.limits = limits;
            this.deadlineNanos = deadlineNanos;
        }

        private static long deadline(LongSupplier nanoTime, long millis) {
            long now = nanoTime.getAsLong();
            long duration = TimeUnit.MILLISECONDS.toNanos(millis);
            return now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
        }

        void claimEntry() throws IOException {
            checkTime();
            if (++entries > limits.maxContainerEntries) {
                throw new IOException("container entries exceed evidence budget");
            }
        }

        void checkTime() throws IOException {
            if (nanoTime.getAsLong() > deadlineNanos) {
                throw new IOException("runtime-code evidence exceeds time budget");
            }
        }

        void claimNestedBytes(int count) throws IOException {
            checkTime();
            nestedBytes += count;
            if (count < 0 || nestedBytes > limits.maxNestedEntryBytes) {
                throw new IOException("nested entries exceed evidence budget");
            }
        }

        void claimVerificationBytes(int count) throws IOException {
            checkTime();
            verificationBytes += count;
            if (count < 0 || verificationBytes > limits.maxTotalClassBytes) {
                throw new IOException("effective class bytes exceed evidence budget");
            }
        }
    }

    private static final class NestedBudgetInputStream extends FilterInputStream {
        private final ByteBudget budget;

        NestedBudgetInputStream(InputStream delegate, ByteBudget budget) {
            super(delegate);
            this.budget = budget;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) budget.claimNestedBytes(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) budget.claimNestedBytes(count);
            return count;
        }
    }
}
