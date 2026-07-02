package io.unitycatalog.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.unitycatalog.client.model.CreateCatalog;
import io.unitycatalog.client.model.CreateSchema;
import io.unitycatalog.server.base.BaseServerTest;
import io.unitycatalog.server.base.catalog.CatalogOperations;
import io.unitycatalog.server.base.schema.SchemaOperations;
import io.unitycatalog.server.persist.IcebergTableCatalogRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.sdk.catalog.SdkCatalogOperations;
import io.unitycatalog.server.sdk.schema.SdkSchemaOperations;
import io.unitycatalog.server.utils.TestUtils;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SPIKE end-to-end test for native Iceberg REST catalog WRITE support, driving a real {@link
 * RESTCatalog} client against the running Armeria server over a local-FS warehouse: createTable ->
 * loadTable -> commit (set-properties, then schema evolution) -> reload, asserting the change
 * persisted and new metadata.json files were written. Also validates the storage-level
 * compare-and-set OCC primitive directly ({@link IcebergTableCatalogRepository}).
 */
public class IcebergRestCatalogWriteTest extends BaseServerTest {

  private static final Namespace NS = Namespace.of(TestUtils.SCHEMA_NAME);

  private CatalogOperations catalogOperations;
  private SchemaOperations schemaOperations;
  private RESTCatalog restCatalog;
  private String warehouseDir;

  private static final Schema SCHEMA =
      new Schema(
          Types.NestedField.required(1, "id", Types.IntegerType.get()),
          Types.NestedField.optional(2, "data", Types.StringType.get()));

  @BeforeEach
  public void setUp() {
    super.setUp();
    catalogOperations = new SdkCatalogOperations(TestUtils.createApiClient(serverConfig));
    schemaOperations = new SdkSchemaOperations(TestUtils.createApiClient(serverConfig));
    cleanUp();

    try {
      catalogOperations.createCatalog(
          new CreateCatalog().name(TestUtils.CATALOG_NAME).comment(TestUtils.COMMENT));
      schemaOperations.createSchema(
          new CreateSchema().catalogName(TestUtils.CATALOG_NAME).name(TestUtils.SCHEMA_NAME));
    } catch (Exception e) {
      throw new RuntimeException("Failed to set up catalog/schema", e);
    }

    warehouseDir = testDirectoryRoot.resolve("iceberg-warehouse").toString();

    // NOTE: intentionally NOT calling setConf(new Configuration()). Instantiating a Hadoop
    // Configuration triggers NoClassDefFoundError on the shaded Woodstox XML parser bundled in
    // hadoop-client-api (not present at test runtime). The create/update spike path never touches
    // the client-side FileIO, so no Hadoop conf is needed -- see the report's FileIO finding.
    restCatalog = new RESTCatalog();
    Map<String, String> props = new HashMap<>();
    props.put(
        CatalogProperties.URI, serverConfig.getServerUrl() + "/api/2.1/unity-catalog/iceberg");
    props.put(CatalogProperties.WAREHOUSE_LOCATION, TestUtils.CATALOG_NAME);
    if (serverConfig.getAuthToken() != null && !serverConfig.getAuthToken().isEmpty()) {
      props.put("token", serverConfig.getAuthToken());
    }
    restCatalog.initialize("uc", props);
  }

  @AfterEach
  public void cleanUp() {
    try {
      if (catalogOperations.getCatalog(TestUtils.CATALOG_NAME) != null) {
        catalogOperations.deleteCatalog(TestUtils.CATALOG_NAME, Optional.of(true));
      }
    } catch (Exception e) {
      // ignore
    }
  }

  @Test
  public void testCreateLoadCommitReloadWithRestClient() throws Exception {
    TableIdentifier id = TableIdentifier.of(NS, "iceberg_native");
    String location = warehouseDir + "/iceberg_native";

    // 1. CREATE
    Table created =
        restCatalog.createTable(id, SCHEMA, PartitionSpec.unpartitioned(), location, Map.of());
    assertThat(created.schema().columns()).hasSize(2);
    String createdMetadata = ((BaseTable) created).operations().current().metadataFileLocation();
    assertThat(new File(stripScheme(createdMetadata))).exists();

    // 2. LOAD
    Table loaded = restCatalog.loadTable(id);
    assertThat(loaded.schema().findField("id")).isNotNull();
    String loadedMetadata = ((BaseTable) loaded).operations().current().metadataFileLocation();
    assertThat(loadedMetadata).isEqualTo(createdMetadata);

    // 3. COMMIT #1: set-properties
    loaded.updateProperties().set("owner", "alice").set("purpose", "spike").commit();

    // 4. RELOAD and assert the property change persisted + a new metadata.json was written.
    Table afterProps = restCatalog.loadTable(id);
    assertThat(afterProps.properties()).containsEntry("owner", "alice");
    assertThat(afterProps.properties()).containsEntry("purpose", "spike");
    String afterPropsMetadata =
        ((BaseTable) afterProps).operations().current().metadataFileLocation();
    assertThat(afterPropsMetadata).isNotEqualTo(loadedMetadata);
    assertThat(new File(stripScheme(afterPropsMetadata))).exists();

    // 5. COMMIT #2: schema evolution (add-schema + set-current-schema updates)
    afterProps.updateSchema().addColumn("extra", Types.StringType.get()).commit();

    // 6. RELOAD and assert the schema change persisted.
    Table afterSchema = restCatalog.loadTable(id);
    assertThat(afterSchema.schema().columns()).hasSize(3);
    assertThat(afterSchema.schema().findField("extra")).isNotNull();
    // Properties from the earlier commit are still present (no clobber across commits).
    assertThat(afterSchema.properties()).containsEntry("owner", "alice");
  }

  /**
   * Directly exercise the storage-level compare-and-set OCC primitive on JPA + H2: a swap with the
   * correct expected value succeeds and advances the pointer; a swap with a now-stale expected
   * value updates zero rows and returns false (which the commit path turns into
   * CommitFailedException).
   */
  @Test
  public void testCompareAndSwapMetadataLocationOcc() throws Exception {
    TableIdentifier id = TableIdentifier.of(NS, "cas_table");
    String location = warehouseDir + "/cas_table";
    Table created =
        restCatalog.createTable(id, SCHEMA, PartitionSpec.unpartitioned(), location, Map.of());
    String v0 = ((BaseTable) created).operations().current().metadataFileLocation();

    IcebergTableCatalogRepository repo =
        new IcebergTableCatalogRepository(
            new Repositories(hibernateConfigurator.getSessionFactory(), serverProperties()));

    // Correct expected -> swaps and returns true.
    boolean firstSwap =
        repo.compareAndSwapMetadataLocation(
            TestUtils.CATALOG_NAME, TestUtils.SCHEMA_NAME, "cas_table", v0, v0 + "-v1");
    assertThat(firstSwap).isTrue();
    assertThat(repo.getMetadataLocation(TestUtils.CATALOG_NAME, TestUtils.SCHEMA_NAME, "cas_table"))
        .isEqualTo(v0 + "-v1");

    // Stale expected (still v0, but pointer is now v0-v1) -> no row matches -> returns false.
    boolean staleSwap =
        repo.compareAndSwapMetadataLocation(
            TestUtils.CATALOG_NAME, TestUtils.SCHEMA_NAME, "cas_table", v0, v0 + "-v2");
    assertThat(staleSwap).isFalse();
    assertThat(repo.getMetadataLocation(TestUtils.CATALOG_NAME, TestUtils.SCHEMA_NAME, "cas_table"))
        .isEqualTo(v0 + "-v1");
  }

  /**
   * Two client handles loaded from the same base: after the first commits, the second (whose base
   * is now stale) should still commit successfully for a property-only change, because the SERVER
   * re-loads current metadata, re-validates requirements (assert-table-uuid passes), and re-applies
   * the update on top. This documents that Iceberg-REST OCC for metadata-only edits is
   * last-writer-merges, not conflict-fails -- see the design report.
   */
  @Test
  public void testConcurrentPropertyCommitsMerge() {
    TableIdentifier id = TableIdentifier.of(NS, "merge_table");
    String location = warehouseDir + "/merge_table";
    restCatalog.createTable(id, SCHEMA, PartitionSpec.unpartitioned(), location, Map.of());

    Table t1 = restCatalog.loadTable(id);
    Table t2 = restCatalog.loadTable(id);

    t1.updateProperties().set("a", "1").commit();
    // t2's in-memory base is stale, but the server re-loads current and re-applies -> merges.
    t2.updateProperties().set("b", "2").commit();

    Table reloaded = restCatalog.loadTable(id);
    assertThat(reloaded.properties()).containsEntry("a", "1");
    assertThat(reloaded.properties()).containsEntry("b", "2");
  }

  private io.unitycatalog.server.utils.ServerProperties serverProperties() {
    return new io.unitycatalog.server.utils.ServerProperties(this.serverProperties);
  }

  private static String stripScheme(String path) {
    return path.startsWith("file:") ? new File(java.net.URI.create(path)).toString() : path;
  }
}
