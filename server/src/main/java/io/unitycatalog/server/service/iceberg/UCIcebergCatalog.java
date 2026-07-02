package io.unitycatalog.server.service.iceberg;

import io.unitycatalog.server.persist.IcebergTableCatalogRepository;
import io.unitycatalog.server.utils.NormalizedURL;
import org.apache.iceberg.BaseMetastoreCatalog;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.io.FileIO;

/**
 * SPIKE {@link BaseMetastoreCatalog} that adapts UC to native Iceberg REST writes. Scoped to a
 * single UC catalog (an Iceberg REST "prefix"); UC schemas map to single-level Iceberg namespaces.
 *
 * <p>Constructed per request by {@code IcebergRestCatalogService} and driven by {@code
 * org.apache.iceberg.rest.CatalogHandlers}. Reusing {@code BaseMetastoreCatalog} + {@code
 * CatalogHandlers} means Iceberg core handles initial-metadata construction, requirement
 * validation, and metadata-update application; UC only supplies {@link #newTableOps} (metastore +
 * storage glue via {@link UCTableOperations}) and {@link #defaultWarehouseLocation}.
 *
 * <p>SPIKE SCOPE: local FS only. {@link #io} is built from the warehouse root, so for a local
 * warehouse it is a (now write-capable) {@link SimpleLocalFileIO} and no cloud credential vending
 * is involved. The production design must make the FileIO per-table-location and route it through
 * {@link FileIOFactory} with write-scoped, credential-vended cloud FileIOs.
 */
public class UCIcebergCatalog extends BaseMetastoreCatalog {

  private final String catalog;
  private final String warehouseRoot;
  private final IcebergTableCatalogRepository repository;
  private final FileIOFactory fileIOFactory;

  public UCIcebergCatalog(
      String catalog,
      String warehouseRoot,
      IcebergTableCatalogRepository repository,
      FileIOFactory fileIOFactory) {
    this.catalog = catalog;
    this.warehouseRoot = warehouseRoot;
    this.repository = repository;
    this.fileIOFactory = fileIOFactory;
  }

  @Override
  protected TableOperations newTableOps(TableIdentifier tableIdentifier) {
    return new UCTableOperations(repository, io(), catalog, tableIdentifier);
  }

  @Override
  protected String defaultWarehouseLocation(TableIdentifier tableIdentifier) {
    String namespacePath = String.join("/", tableIdentifier.namespace().levels());
    return warehouseRoot + "/" + namespacePath + "/" + tableIdentifier.name();
  }

  @Override
  public String name() {
    return catalog;
  }

  // Abstract Catalog methods not exercised by the create/update spike. listTables is served by the
  // existing DB-backed IcebergRestCatalogService.listTables; drop/rename are out of spike scope.
  @Override
  public java.util.List<TableIdentifier> listTables(org.apache.iceberg.catalog.Namespace ns) {
    throw new UnsupportedOperationException("listTables not implemented in the Iceberg spike");
  }

  @Override
  public boolean dropTable(TableIdentifier identifier, boolean purge) {
    throw new UnsupportedOperationException("dropTable not implemented in the Iceberg write spike");
  }

  @Override
  public void renameTable(TableIdentifier from, TableIdentifier to) {
    throw new UnsupportedOperationException("renameTable not implemented in the Iceberg spike");
  }

  private FileIO io() {
    // Local FS: FileIOFactory returns a SimpleLocalFileIO for the file/null scheme without vending.
    return fileIOFactory.getFileIO(NormalizedURL.from(warehouseRoot));
  }
}
