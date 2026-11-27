package io.github.kook1023.lumen.iris;

import io.github.kook1023.lumen.LumenShaderLights;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Finds out whether the active shader pack mentions Iris' {@code heldBlockLightColor} uniform anywhere in its
 * source. If it does, the pack can show our exact colour, so IrisBridge stops pretending the item is a different
 * item for coloured entries. (None of the packs I checked read it today - this is so the mod switches over by
 * itself the day one does.)
 *
 * The scan runs once per pack on a background thread. It reads the pack straight from the shaderpacks folder
 * (a folder or a .zip), asking Iris for the pack name via reflection so it works across Iris versions.
 */
public final class PackScanner {
    private static final String NEEDLE = "heldBlockLightColor";
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    private static volatile Boolean result;      // null = unknown / still scanning
    private static volatile String scannedFor = "";
    private static int generation;

    private PackScanner() {
    }

    /** @return true only when a finished scan found the uniform in the pack. */
    public static boolean readsColorUniform() {
        return Boolean.TRUE.equals(result);
    }

    /** Starts a background scan if the active pack is not the one we last scanned. Cheap to call often. */
    public static synchronized void ensureScanned() {
        String name = currentPackName();
        if (name == null || name.equals(scannedFor)) {
            return;
        }
        scannedFor = name;
        result = null;
        final int gen = ++generation;
        Thread thread = new Thread(() -> {
            Boolean found;
            try {
                found = scan(name);
            } catch (Exception | LinkageError e) {
                found = Boolean.FALSE;
                LumenShaderLights.LOGGER.warn("Could not scan shader pack '{}' for heldBlockLightColor: {}", name, e.toString());
            }
            synchronized (PackScanner.class) {
                if (gen == generation) {   // ignore a stale scan if the pack changed meanwhile
                    result = found;
                }
            }
        }, "headys-lumen-pack-scan");
        thread.setDaemon(true);
        thread.start();
    }

    /** For /lumen status. */
    public static String describe() {
        Boolean r = result;
        if (r == null) {
            return scannedFor.isEmpty() ? "unknown" : "scanning...";
        }
        return r ? "YES - exact colours are used (no item-id tricks for coloured items)" : "no";
    }

    private static volatile String markerCheckedFor;
    private static volatile boolean markerFound;

    /**
     * Does the active pack contain {@code shaders/lumen.properties}? That file marks packs (the PhotonFork) that
     * understand colour-coded light ids for placed blocks. Checked synchronously and cached per pack, because it is
     * needed while Iris loads the pack.
     */
    public static boolean packUnderstandsColorIds() {
        String name = currentPackName();
        if (name == null) {
            return false;
        }
        if (name.equals(markerCheckedFor)) {
            return markerFound;
        }
        boolean found = false;
        try {
            Class<?> iris = findIrisClass();
            Path dir = iris == null ? null : (Path) iris.getMethod("getShaderpacksDirectory").invoke(null);
            Path pack = dir == null ? null : dir.resolve(name);
            if (pack != null && Files.isDirectory(pack)) {
                found = Files.exists(pack.resolve("shaders").resolve("lumen.properties"));
            } else if (pack != null && Files.isRegularFile(pack)) {
                try (FileSystem zip = FileSystems.newFileSystem(pack)) {
                    found = Files.exists(zip.getPath("/shaders/lumen.properties"));
                }
            }
        } catch (Exception | LinkageError e) {
            found = false;
        }
        markerFound = found;
        markerCheckedFor = name;
        return found;
    }

    private static String currentPackName() {
        try {
            Class<?> iris = findIrisClass();
            return iris == null ? null : (String) iris.getMethod("getCurrentPackName").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Boolean scan(String name) throws Exception {
        Class<?> iris = findIrisClass();
        if (iris == null) {
            return Boolean.FALSE;
        }
        Path dir = (Path) iris.getMethod("getShaderpacksDirectory").invoke(null);
        if (dir == null) {
            return Boolean.FALSE;
        }
        Path pack = dir.resolve(name);
        if (!Files.exists(pack)) {
            return Boolean.FALSE; // e.g. "(off)" or the built-in internal pack
        }
        boolean found;
        if (Files.isDirectory(pack)) {
            found = searchTree(pack);
        } else {
            try (FileSystem zip = FileSystems.newFileSystem(pack)) {
                found = searchTree(zip.getPath("/"));
            }
        }
        LumenShaderLights.LOGGER.info("Shader pack '{}' {} heldBlockLightColor", name, found ? "READS" : "does not read");
        return found;
    }

    private static Class<?> findIrisClass() {
        for (String name : new String[] {"net.irisshaders.iris.Iris", "net.coderbot.iris.Iris"}) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {
                // try the next package layout
            }
        }
        return null;
    }

    private static boolean searchTree(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).anyMatch(PackScanner::fileMentionsUniform);
        }
    }

    private static boolean fileMentionsUniform(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".ogg") || name.endsWith(".lang")) {
            return false;
        }
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return false;
            }
            // ISO-8859-1 maps every byte to a char, so odd encodings can never throw.
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(NEEDLE);
        } catch (IOException e) {
            return false;
        }
    }
}
