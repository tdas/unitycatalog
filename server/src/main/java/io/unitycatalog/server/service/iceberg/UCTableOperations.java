package io.unitycatalog.server.service.iceberg;

import io.unitycatalog.server.persist.IcebergTableCatalogRepository;
import org.apache.iceberg.BaseMetastoreTableOperations;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.io.FileIO;

/**
 * {@link org.apache.iceberg.TableOperations} for a UC-backed Iceberg table, following the
 * Polaris/JDBC pattern: the metadata pointer lives in the metastore (UC's {@code uc_tables}) and
 * the actual {@code metadata.json} lives in object storage (here, local FS via {@link
 * SimpleLocalFileIO}).
 *
 * <ul>
 *   <li>{@link #doRefresh()} reads the pointer from UC and loads that {@code metadata.json}.
 *   <li>{@link #doCommit(TableMetadata, TableMetadata)} writes a new {@code metadata.json} and then
 *       atomically compare-and-swaps the UC pointer; a lost race raises {@link
 *       CommitFailedException} (mapped by {@code IcebergRestExceptionHandler} to 409).
 * </ul>
 *
 * <p>Driven by {@code org.apache.iceberg.rest.CatalogHandlers} via {@link UCIcebergCatalog}, so the
 * requirement validation (assert-table-uuid, assert-ref-snapshot-id, ...) and the metadata-update
 * application are handled by Iceberg core; this class only supplies the metastore + storage glue.
 */
public class UCTableOperations extends BaseMetastoreTableOperations {

  private final IcebergTableCatalogRepository repository;
  private final FileIO fileIO;
  private final String catalog;
  private final TableIdentifier tableIdentifier;

  public UCTableOperations(
      IcebergTableCatalogRepository repository,
      FileIO fileIO,
      String catalog,
      TableIdentifier tableIdentifier) {
    this.repository = repository;
    this.fileIO = fileIO;
    this.catalog = catalog;
    this.tableIdentifier = tableIdentifier;
  }

  @Override
  protected String tableName() {
    return catalog + "." + tableIdentifier;
  }

  @Override
  public FileIO io() {
    return fileIO;
  }

  @Override
  protected void doRefresh() {
    String metadataLocation =
        repository.getMetadataLocation(catalog, namespace(), tableIdentifier.name());
    if (metadataLocation == null) {
      // IMPORTANT: BaseMetastoreTableOperations.refresh() in Iceberg 1.9.2 re-throws any
      // NoSuchTableException from doRefresh() (it does NOT swallow it on first load). So the
      // "table not created yet" case (create path: current() must return null) must NOT throw --
      // it disables refresh and returns, mirroring JdbcTableOperations.doRefresh. Only a table
      // that previously existed (currentMetadataLocation != null) but is now gone is a real 404.
      if (currentMetadataLocation() != null) {
        throw new NoSuchTableException("Table does not exist: %s", tableName());
      }
      disableRefresh();
      return;
    }
    refreshFromMetadataLocation(metadataLocation);
  }

  @Override
  protected void doCommit(TableMetadata base, TableMetadata metadata) {
    String newMetadataLocation = writeNewMetadataIfRequired(base == null, metadata);

    boolean success;
    if (base == null) {
      // Create: insert the pointer only if the table row does not already exist.
      success =
          repository.createIfAbsent(
              catalog,
              namespace(),
              tableIdentifier.name(),
              metadata.location(),
              newMetadataLocation);
    } else {
      // Update: atomic compare-and-set of the pointer from the loaded location to the new one.
      success =
          repository.compareAndSwapMetadataLocation(
              catalog,
              namespace(),
              tableIdentifier.name(),
              base.metadataFileLocation(),
              newMetadataLocation);
    }

    if (!success) {
      throw new CommitFailedException(
          "Cannot commit %s: the metadata pointer changed concurrently (or the table already"
              + " exists)",
          tableName());
    }
  }

  private String namespace() {
    return String.join(".", tableIdentifier.namespace().levels());
  }
}
