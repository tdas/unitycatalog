package io.unitycatalog.server.persist;

import io.unitycatalog.server.persist.dao.TableInfoDAO;
import io.unitycatalog.server.persist.utils.TransactionManager;
import java.util.Date;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPIKE persistence helper for native Iceberg REST catalog writes.
 *
 * <p>Owns the three DB operations the Iceberg write path needs, expressed directly against the
 * existing {@code uc_tables} table / {@link TableInfoDAO}:
 *
 * <ul>
 *   <li>{@link #getMetadataLocation} -- read the current metadata.json pointer (doRefresh).
 *   <li>{@link #createIfAbsent} -- insert a new Iceberg table row (create commit, base == null).
 *   <li>{@link #compareAndSwapMetadataLocation} -- atomic compare-and-set of the pointer (update
 *       commit), the storage-level OCC primitive.
 * </ul>
 *
 * <p>SHORTCUTS (documented for the production design):
 *
 * <ul>
 *   <li>The pointer is overloaded onto {@code uniform_iceberg_metadata_location}, the column the
 *       Delta-UniForm converter also writes. Native Iceberg tables and Delta-UniForm tables are
 *       therefore indistinguishable by this column alone. Production needs a first-class metadata-
 *       location column decoupled from Delta-conversion semantics.
 *   <li>{@code data_source_format} is left null because the {@code DataSourceFormat} enum has no
 *       {@code ICEBERG} value; {@code table_type} is set to {@code EXTERNAL}. Production needs
 *       first-class ICEBERG format/type enum values.
 *   <li>{@code createIfAbsent} is a check-then-insert with no DB unique constraint on {@code
 *       (schema_id, name)}, so two racing creates can both insert. Production needs a unique
 *       constraint (and/or upsert) to make create atomic.
 *   <li>UC column rows and auth-hierarchy wiring are skipped; the Iceberg schema lives only in
 *       metadata.json.
 * </ul>
 */
public class IcebergTableCatalogRepository {

  private static final Logger LOGGER = LoggerFactory.getLogger(IcebergTableCatalogRepository.class);

  private final SessionFactory sessionFactory;
  private final SchemaRepository schemaRepository;
  private final TableRepository tableRepository;

  public IcebergTableCatalogRepository(Repositories repositories) {
    this.sessionFactory = repositories.getSessionFactory();
    this.schemaRepository = repositories.getSchemaRepository();
    this.tableRepository = repositories.getTableRepository();
  }

  /** Return the current metadata.json pointer for the table, or null if the table row is absent. */
  public String getMetadataLocation(String catalog, String namespace, String table) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          UUID schemaId = schemaRepository.getSchemaIdOrThrow(session, catalog, namespace);
          TableInfoDAO dao = tableRepository.findBySchemaIdAndName(session, schemaId, table);
          return dao == null ? null : dao.getUniformIcebergMetadataLocation();
        },
        "Failed to read Iceberg metadata location",
        /* readOnly = */ true);
  }

  /**
   * Insert a new Iceberg table row pointing at {@code metadataLocation}. Returns false if a row
   * with the same (schema, name) already exists (create should surface AlreadyExists). Racy without
   * a DB unique constraint -- see class-level shortcut note.
   */
  public boolean createIfAbsent(
      String catalog,
      String namespace,
      String table,
      String tableLocation,
      String metadataLocation) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          UUID schemaId = schemaRepository.getSchemaIdOrThrow(session, catalog, namespace);
          if (tableRepository.findBySchemaIdAndName(session, schemaId, table) != null) {
            return false;
          }
          Date now = new Date();
          TableInfoDAO dao =
              TableInfoDAO.builder()
                  .id(UUID.randomUUID())
                  .name(table)
                  .schemaId(schemaId)
                  .type("EXTERNAL")
                  .dataSourceFormat(null) // no ICEBERG enum value -- shortcut
                  .url(tableLocation)
                  .uniformIcebergMetadataLocation(metadataLocation)
                  .columnCount(0)
                  .createdAt(now)
                  .updatedAt(now)
                  .build();
          session.persist(dao);
          LOGGER.debug("Created Iceberg table row {}.{}.{}", catalog, namespace, table);
          return true;
        },
        "Failed to create Iceberg table",
        /* readOnly = */ false);
  }

  /**
   * Atomic compare-and-set of the metadata pointer: swaps {@code expected} -> {@code newLocation}
   * in a single {@code UPDATE ... WHERE uniform_iceberg_metadata_location = :expected} and returns
   * whether exactly one row changed. This is the storage-level optimistic-concurrency primitive for
   * the Iceberg commit path: a concurrent committer that already advanced the pointer makes the
   * WHERE match zero rows, so this returns false and the caller raises CommitFailedException.
   */
  public boolean compareAndSwapMetadataLocation(
      String catalog, String namespace, String table, String expected, String newLocation) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          UUID schemaId = schemaRepository.getSchemaIdOrThrow(session, catalog, namespace);
          int updated =
              session
                  .createMutationQuery(
                      "UPDATE TableInfoDAO t "
                          + "SET t.uniformIcebergMetadataLocation = :newLoc, t.updatedAt = :now "
                          + "WHERE t.schemaId = :schemaId AND t.name = :name "
                          + "AND t.uniformIcebergMetadataLocation = :expected")
                  .setParameter("newLoc", newLocation)
                  .setParameter("now", new Date())
                  .setParameter("schemaId", schemaId)
                  .setParameter("name", table)
                  .setParameter("expected", expected)
                  .executeUpdate();
          LOGGER.debug(
              "CAS metadata location {}.{}.{}: {} row(s) updated",
              catalog,
              namespace,
              table,
              updated);
          return updated == 1;
        },
        "Failed to compare-and-swap Iceberg metadata location",
        /* readOnly = */ false);
  }
}
