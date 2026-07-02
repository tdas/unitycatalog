package io.unitycatalog.server.service.iceberg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;

/**
 * Local-filesystem {@link FileIO} used by the Iceberg REST catalog for local warehouses.
 *
 * <p>SPIKE NOTE (Iceberg REST write support): originally this was read-only ({@code newOutputFile}
 * / {@code deleteFile} threw {@code UnsupportedOperationException}) because the Iceberg REST
 * catalog was read-only. To exercise the write path (create/commit writing {@code metadata.json})
 * without cloud credential vending, write + delete are now implemented against {@link
 * org.apache.iceberg.Files}. This is only safe for the local-FS spike; the production design must
 * route writes through {@link FileIOFactory} with write-scoped, credential-vended cloud FileIOs.
 */
public class SimpleLocalFileIO implements FileIO {
  @Override
  public InputFile newInputFile(String path) {
    return org.apache.iceberg.Files.localInput(stripScheme(path));
  }

  @Override
  public OutputFile newOutputFile(String path) {
    String local = stripScheme(path);
    // Iceberg's commit writes <location>/metadata/<n>.metadata.json; the parent dir may not exist.
    Path parent = Paths.get(local).getParent();
    if (parent != null) {
      try {
        Files.createDirectories(parent);
      } catch (IOException e) {
        throw new RuntimeException("Failed to create parent directory for " + local, e);
      }
    }
    return org.apache.iceberg.Files.localOutput(local);
  }

  @Override
  public void deleteFile(String path) {
    try {
      Files.deleteIfExists(Paths.get(stripScheme(path)));
    } catch (IOException e) {
      throw new RuntimeException("Failed to delete " + path, e);
    }
  }

  /** Accept both {@code file:/...} URIs and bare local paths. */
  private static String stripScheme(String path) {
    if (path.startsWith("file:")) {
      return Paths.get(java.net.URI.create(path)).toString();
    }
    return path;
  }
}
