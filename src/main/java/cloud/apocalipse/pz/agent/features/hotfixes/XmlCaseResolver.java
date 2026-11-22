package cloud.apocalipse.pz.agent.features.hotfixes;

import java.io.File;
import java.nio.file.Path;

/** Resolves missing XML paths with one unambiguous, case-insensitive path-component match. */
public final class XmlCaseResolver {
    private XmlCaseResolver() {
    }

    public static String resolve(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return fileName;
        }

        try {
            File file = new File(fileName);
            if (file.isFile()) {
                return fileName;
            }

            Path absolute = file.toPath().toAbsolutePath().normalize();
            File current = absolute.getRoot() == null
                ? new File(".").getAbsoluteFile()
                : absolute.getRoot().toFile();

            for (Path component : absolute) {
                String name = component.getFileName().toString();
                if (name.isEmpty()) {
                    continue;
                }

                File exact = new File(current, name);
                if (exact.exists()) {
                    current = exact;
                    continue;
                }

                File[] matches = current.listFiles(entry ->
                    entry.getName().equalsIgnoreCase(name));
                if (matches == null || matches.length != 1) {
                    return fileName;
                }
                current = matches[0];
            }

            return current.isFile() ? current.getPath() : fileName;
        } catch (RuntimeException failure) {
            return fileName;
        }
    }
}
