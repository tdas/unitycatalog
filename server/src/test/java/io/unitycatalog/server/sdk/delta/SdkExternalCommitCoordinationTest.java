package io.unitycatalog.server.sdk.delta;

import static org.assertj.core.api.Assertions.assertThat;

import io.unitycatalog.client.ApiException;
import io.unitycatalog.client.delta.api.DeltaTemporaryCredentialsApi;
import io.unitycatalog.client.delta.model.DeltaAddCommitUpdate;
import io.unitycatalog.client.delta.model.DeltaAssertEtag;
import io.unitycatalog.client.delta.model.DeltaAssertTableUUID;
import io.unitycatalog.client.delta.model.DeltaCommit;
import io.unitycatalog.client.delta.model.DeltaCredentialOperation;
import io.unitycatalog.client.delta.model.DeltaCredentialsResponse;
import io.unitycatalog.client.delta.model.DeltaErrorType;
import io.unitycatalog.client.delta.model.DeltaLoadTableResponse;
import io.unitycatalog.client.delta.model.DeltaSetLatestBackfilledVersionUpdate;
import io.unitycatalog.client.delta.model.DeltaSetPropertiesUpdate;
import io.unitycatalog.client.delta.model.DeltaTableRequirement;
import io.unitycatalog.client.delta.model.DeltaTableUpdate;
import io.unitycatalog.client.delta.model.DeltaUpdateTableRequest;
import io.unitycatalog.server.base.ServerConfig;
import io.unitycatalog.server.base.catalog.CatalogOperations;
import io.unitycatalog.server.base.delta.DeltaBaseTableCRUDTestEnv;
import io.unitycatalog.server.base.schema.SchemaOperations;
import io.unitycatalog.server.sdk.catalog.SdkCatalogOperations;
import io.unitycatalog.server.sdk.schema.SdkSchemaOperations;
import io.unitycatalog.server.service.delta.DeltaConsts.TableProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import io.unitycatalog.server.utils.TestUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * [PROTOTYPE / SPIKE] End-to-end integration test for catalog-managed commit coordination on
 * EXTERNAL Delta tables, exercising the same {@code POST /delta/v1/.../tables/{table}} update
 * endpoint used for MANAGED CCv2 commits.
 *
 * <p>The server is started with:
 *
 * <ul>
 *   <li>{@code server.external-delta.commit-coordination.enabled=true} -- the new prototype flag;
 *   <li>{@code server.managed-table.enabled=false} -- deliberately OFF to prove the EXTERNAL commit
 *       path does not depend on the MANAGED-table feature flag.
 * </ul>
 *
 * <p>Flow: create an EXTERNAL Delta table on a temp local-FS dir -> confirm add-commit is rejected
 * before onboarding -> onboard by setting a reserved marker property -> onboarding commit (v1) ->
 * normal (+1) commit (v2) -> conflicting commit (replay v2, expect COMMIT_VERSION_CONFLICT) ->
 * loadTable surfaces the coordinated commits -> vend temporary credentials for the external path.
 */
public class SdkExternalCommitCoordinationTest extends DeltaBaseTableCRUDTestEnv {

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    // Turn ON external commit coordination and turn OFF managed tables, so a green run proves the
    // external path is fully independent of the managed-table gate.
    serverProperties.setProperty(
        Property.EXTERNAL_DELTA_COMMIT_COORDINATION_ENABLED.getKey(), "true");
    serverProperties.setProperty(Property.MANAGED_TABLE_ENABLED.getKey(), "false");
  }

  @Override
  protected CatalogOperations createCatalogOperations(ServerConfig serverConfig) {
    return new SdkCatalogOperations(TestUtils.createApiClient(serverConfig));
  }

  @Override
  protected SchemaOperations createSchemaOperations(ServerConfig serverConfig) {
    return new SdkSchemaOperations(TestUtils.createApiClient(serverConfig));
  }

  @Test
  public void testExternalCommitCoordinationEndToEnd() throws Exception {
    // 1. Create an EXTERNAL Delta table at a fresh local-FS temp dir (empty commit log).
    Handle h = createDeltaExternal("tbl_ext_cc");

    // 2. Before onboarding, an add-commit must be rejected even though the server flag is on.
    TestUtils.assertDeltaApiException(
        () -> updateTable(h, addCommit(1L)),
        DeltaErrorType.INVALID_PARAMETER_VALUE_EXCEPTION,
        "onboarded");

    // 3. Onboard: set the reserved marker property via the Delta set-properties action. This is a
    // metadata change, so it advances updated-time and rolls the etag.
    DeltaLoadTableResponse onboardResp =
        updateTable(
            h,
            new DeltaSetPropertiesUpdate()
                .updates(Map.of(TableProperties.EXTERNAL_COMMIT_COORDINATION_ENABLED, "true")));
    assertThat(onboardResp.getMetadata().getProperties())
        .containsEntry(TableProperties.EXTERNAL_COMMIT_COORDINATION_ENABLED, "true");
    Handle onboarded = h.withEtag(onboardResp.getMetadata().getEtag());

    // 4. Onboarding commit (v1) -- first commit UC coordinates for this table. Routes through the
    // shared postCommitCore -> handleOnboardingCommit path (empty commit log).
    DeltaLoadTableResponse r1 = updateTable(onboarded, addCommit(1L));
    assertThat(r1.getLatestTableVersion()).isEqualTo(1L);
    assertThat(r1.getCommits()).hasSize(1);
    assertThat(r1.getCommits().get(0).getVersion()).isEqualTo(1L);
    // A data-only add-commit changes no catalog-visible metadata, so the etag must not roll.
    assertThat(r1.getMetadata().getEtag()).isEqualTo(onboarded.etag());

    // 5. Normal (+1) commit (v2) -- handleNormalCommit path.
    Handle afterV1 = onboarded.withEtag(r1.getMetadata().getEtag());
    DeltaLoadTableResponse r2 = updateTable(afterV1, addCommit(2L));
    assertThat(r2.getLatestTableVersion()).isEqualTo(2L);
    assertThat(r2.getCommits())
        .extracting(DeltaCommit::getVersion)
        .containsExactlyInAnyOrder(1L, 2L);

    // 6. Conflicting commit -- replay v2. The version-conflict rule (newVersion <= last) fires.
    Handle afterV2 = afterV1.withEtag(r2.getMetadata().getEtag());
    TestUtils.assertDeltaApiException(
        () -> updateTable(afterV2, addCommit(2L)),
        DeltaErrorType.COMMIT_VERSION_CONFLICT_EXCEPTION,
        "already accepted");

    // Also confirm the +1 gap rule still applies on the external path (v4 while on v2).
    TestUtils.assertDeltaApiException(
        () -> updateTable(afterV2, addCommit(4L)),
        DeltaErrorType.INVALID_PARAMETER_VALUE_EXCEPTION,
        "next version");

    // 7. loadTable must surface the UC-coordinated commits for the onboarded external table.
    DeltaLoadTableResponse loaded =
        deltaTablesApi.loadTable(TestUtils.CATALOG_NAME, TestUtils.SCHEMA_NAME, h.name());
    assertThat(loaded.getLatestTableVersion()).isEqualTo(2L);
    assertThat(loaded.getCommits())
        .extracting(DeltaCommit::getVersion)
        .containsExactlyInAnyOrder(1L, 2L);
    assertThat(loaded.getMetadata().getProperties())
        .containsEntry(TableProperties.EXTERNAL_COMMIT_COORDINATION_ENABLED, "true");

    // 8. Backfill notification of v1 then re-load; only v2 remains unbackfilled, version stays 2.
    DeltaLoadTableResponse r3 =
        updateTable(
            afterV2, new DeltaSetLatestBackfilledVersionUpdate().latestPublishedVersion(1L));
    assertThat(r3.getLatestTableVersion()).isEqualTo(2L);
    assertThat(r3.getCommits()).extracting(DeltaCommit::getVersion).containsExactly(2L);

    // 9. Temporary credentials / storage access for the local-FS external path (item 5). No
    // managed gate blocks it and the FILE scheme yields empty (no-op) credentials scoped to the
    // table location.
    DeltaTemporaryCredentialsApi credentialsApi =
        new DeltaTemporaryCredentialsApi(TestUtils.createApiClient(serverConfig));
    DeltaCredentialsResponse creds =
        credentialsApi.getTableCredentials(
            DeltaCredentialOperation.READ_WRITE,
            TestUtils.CATALOG_NAME,
            TestUtils.SCHEMA_NAME,
            h.name());
    assertThat(creds.getStorageCredentials()).hasSize(1);
    assertThat(creds.getStorageCredentials().get(0).getPrefix()).startsWith("file:");
  }

  // ---------------------------------------------------------------- helpers

  /** A data-only add-commit for the given version, with placeholder file metadata. */
  private static DeltaAddCommitUpdate addCommit(long version) {
    return new DeltaAddCommitUpdate()
        .commit(
            new DeltaCommit()
                .version(version)
                .timestamp(1700000000000L + version)
                .fileName(String.format("%020d.json", version))
                .fileSize(1024L)
                .fileModificationTimestamp(1700000000000L + version));
  }

  /** Pin the update to the table's UUID and the Handle's etag. */
  private DeltaLoadTableResponse updateTable(Handle h, DeltaTableUpdate... updates)
      throws ApiException {
    List<DeltaTableRequirement> requirements = new ArrayList<>();
    requirements.add(new DeltaAssertTableUUID().uuid(h.tableId()));
    requirements.add(new DeltaAssertEtag().etag(h.etag()));
    return deltaTablesApi.updateTable(
        TestUtils.CATALOG_NAME,
        TestUtils.SCHEMA_NAME,
        h.name(),
        new DeltaUpdateTableRequest().requirements(requirements).updates(List.of(updates)));
  }
}
