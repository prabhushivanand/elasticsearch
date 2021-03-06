/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.integration;

import org.elasticsearch.action.admin.cluster.node.tasks.list.ListTasksRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterModule;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.common.io.PathUtils;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.reindex.ReindexPlugin;
import org.elasticsearch.license.LicenseService;
import org.elasticsearch.persistent.PersistentTaskParams;
import org.elasticsearch.persistent.PersistentTaskState;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.SecuritySettingsSourceField;
import org.elasticsearch.transport.Netty4Plugin;
import org.elasticsearch.xpack.core.XPackSettings;
import org.elasticsearch.xpack.core.ilm.DeleteAction;
import org.elasticsearch.xpack.core.ilm.IndexLifecycleMetadata;
import org.elasticsearch.xpack.core.ilm.LifecycleAction;
import org.elasticsearch.xpack.core.ilm.LifecycleSettings;
import org.elasticsearch.xpack.core.ilm.LifecycleType;
import org.elasticsearch.xpack.core.ilm.RolloverAction;
import org.elasticsearch.xpack.core.ilm.TimeseriesLifecycleType;
import org.elasticsearch.xpack.core.ml.MachineLearningField;
import org.elasticsearch.xpack.core.ml.MlMetadata;
import org.elasticsearch.xpack.core.ml.MlTasks;
import org.elasticsearch.xpack.core.ml.action.DeleteExpiredDataAction;
import org.elasticsearch.xpack.core.ml.action.GetFiltersAction;
import org.elasticsearch.xpack.core.ml.action.OpenJobAction;
import org.elasticsearch.xpack.core.ml.action.PutFilterAction;
import org.elasticsearch.xpack.core.ml.action.SetUpgradeModeAction;
import org.elasticsearch.xpack.core.ml.action.StartDataFrameAnalyticsAction;
import org.elasticsearch.xpack.core.ml.action.StartDatafeedAction;
import org.elasticsearch.xpack.core.ml.datafeed.DatafeedState;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsTaskState;
import org.elasticsearch.xpack.core.ml.job.config.JobTaskState;
import org.elasticsearch.xpack.core.ml.job.config.MlFilter;
import org.elasticsearch.xpack.core.security.SecurityField;
import org.elasticsearch.xpack.core.security.authc.TokenMetadata;
import org.elasticsearch.xpack.ilm.IndexLifecycle;
import org.elasticsearch.xpack.ml.LocalStateMachineLearning;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.elasticsearch.test.XContentTestUtils.convertToMap;
import static org.elasticsearch.test.XContentTestUtils.differenceBetweenMapsIgnoringArrayOrder;
import static org.elasticsearch.xpack.core.security.authc.support.UsernamePasswordToken.basicAuthHeaderValue;
import static org.elasticsearch.xpack.security.test.SecurityTestUtils.writeFile;
import static org.hamcrest.Matchers.is;

/**
 * Base class of ML integration tests that use a native autodetect process
 */
abstract class MlNativeIntegTestCase extends ESIntegTestCase {

    @Override
    protected NamedXContentRegistry xContentRegistry() {
        SearchModule searchModule = new SearchModule(Settings.EMPTY, Collections.emptyList());
        return new NamedXContentRegistry(searchModule.getNamedXContents());
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(
            LocalStateMachineLearning.class,
            Netty4Plugin.class,
            ReindexPlugin.class,
            // ILM is required for .ml-state template index settings
            IndexLifecycle.class);
    }

    @Override
    protected Function<Client, Client> getClientWrapper() {
        final Map<String, String> headers =
            Map.of("Authorization", basicAuthHeaderValue("x_pack_rest_user", SecuritySettingsSourceField.TEST_PASSWORD_SECURE_STRING));
        // we need to wrap node clients because we do not specify a user for nodes and all requests will use the system
        // user. This is ok for internal n2n stuff but the test framework does other things like wiping indices, repositories, etc
        // that the system user cannot do. so we wrap the node client with a user that can do these things since the client() calls
        // return a node client
        return client -> client.filterWithHeader(headers);
    }
    @Override
    protected Settings externalClusterClientSettings() {
        final Path home = createTempDir();
        final Path xpackConf = home.resolve("config");
        try {
            Files.createDirectories(xpackConf);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        writeFile(xpackConf, "users", "x_pack_rest_user" + ":" + SecuritySettingsSourceField.TEST_PASSWORD_SECURE_STRING + "\n");
        writeFile(xpackConf, "users_roles", "superuser:x_pack_rest_user\n");

        Path key;
        Path certificate;
        try {
            key = PathUtils.get(getClass().getResource("/testnode.pem").toURI());
            certificate = PathUtils.get(getClass().getResource("/testnode.crt").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("error trying to get keystore path", e);
        }

        Settings.Builder builder = Settings.builder();
        builder.put("node.ml", false);
        builder.put(NetworkModule.TRANSPORT_TYPE_KEY, SecurityField.NAME4);
        builder.put(XPackSettings.MACHINE_LEARNING_ENABLED.getKey(), true);
        builder.put(XPackSettings.SECURITY_ENABLED.getKey(), true);
        builder.put(MachineLearningField.AUTODETECT_PROCESS.getKey(), false);
        builder.put(XPackSettings.WATCHER_ENABLED.getKey(), false);
        builder.put(XPackSettings.MONITORING_ENABLED.getKey(), false);
        builder.put(LifecycleSettings.LIFECYCLE_HISTORY_INDEX_ENABLED_SETTING.getKey(), false);
        builder.put(LicenseService.SELF_GENERATED_LICENSE_TYPE.getKey(), "trial");
        builder.put(Environment.PATH_HOME_SETTING.getKey(), home);
        builder.put("xpack.security.transport.ssl.enabled", true);
        builder.put("xpack.security.transport.ssl.key", key.toAbsolutePath().toString());
        builder.put("xpack.security.transport.ssl.certificate", certificate.toAbsolutePath().toString());
        builder.put("xpack.security.transport.ssl.key_passphrase", "testnode");
        builder.put("xpack.security.transport.ssl.verification_mode", "certificate");
        return builder.build();
    }

    protected void cleanUp() {
        setUpgradeModeTo(false);
        cleanUpResources();
        waitForPendingTasks();
    }

    protected abstract void cleanUpResources();

    private void waitForPendingTasks() {
        ListTasksRequest listTasksRequest = new ListTasksRequest();
        listTasksRequest.setWaitForCompletion(true);
        listTasksRequest.setDetailed(true);
        listTasksRequest.setTimeout(TimeValue.timeValueSeconds(10));
        try {
            admin().cluster().listTasks(listTasksRequest).get();
        } catch (Exception e) {
            throw new AssertionError("Failed to wait for pending tasks to complete", e);
        }
    }

    protected void setUpgradeModeTo(boolean enabled) {
        AcknowledgedResponse response =
            client().execute(SetUpgradeModeAction.INSTANCE, new SetUpgradeModeAction.Request(enabled)).actionGet();
        assertThat(response.isAcknowledged(), is(true));
        assertThat(upgradeMode(), is(enabled));
    }

    protected boolean upgradeMode() {
        ClusterState masterClusterState = client().admin().cluster().prepareState().all().get().getState();
        MlMetadata mlMetadata = MlMetadata.getMlMetadata(masterClusterState);
        return mlMetadata.isUpgradeMode();
    }

    protected DeleteExpiredDataAction.Response deleteExpiredData() throws Exception {
        DeleteExpiredDataAction.Response response = client().execute(DeleteExpiredDataAction.INSTANCE,
            new DeleteExpiredDataAction.Request()).get();

        // We need to refresh to ensure the deletion is visible
        refresh("*");

        return response;
    }

    protected PutFilterAction.Response putMlFilter(MlFilter filter) {
        return client().execute(PutFilterAction.INSTANCE, new PutFilterAction.Request(filter)).actionGet();
    }

    protected GetFiltersAction.Response getMlFilters() {
        return client().execute(GetFiltersAction.INSTANCE, new GetFiltersAction.Request()).actionGet();
    }

    @Override
    protected void ensureClusterStateConsistency() throws IOException {
        if (cluster() != null && cluster().size() > 0) {
            List<NamedWriteableRegistry.Entry> entries = new ArrayList<>(ClusterModule.getNamedWriteables());
            entries.addAll(new SearchModule(Settings.EMPTY, Collections.emptyList()).getNamedWriteables());
            entries.add(new NamedWriteableRegistry.Entry(Metadata.Custom.class, "ml", MlMetadata::new));
            entries.add(new NamedWriteableRegistry.Entry(Metadata.Custom.class, IndexLifecycleMetadata.TYPE, IndexLifecycleMetadata::new));
            entries.add(new NamedWriteableRegistry.Entry(LifecycleType.class, TimeseriesLifecycleType.TYPE,
                (in) -> TimeseriesLifecycleType.INSTANCE));
            entries.add(new NamedWriteableRegistry.Entry(LifecycleAction.class, DeleteAction.NAME, DeleteAction::new));
            entries.add(new NamedWriteableRegistry.Entry(LifecycleAction.class, RolloverAction.NAME, RolloverAction::new));
            entries.add(new NamedWriteableRegistry.Entry(PersistentTaskParams.class, MlTasks.DATAFEED_TASK_NAME,
                    StartDatafeedAction.DatafeedParams::new));
            entries.add(new NamedWriteableRegistry.Entry(PersistentTaskParams.class, MlTasks.DATA_FRAME_ANALYTICS_TASK_NAME,
                StartDataFrameAnalyticsAction.TaskParams::new));
            entries.add(new NamedWriteableRegistry.Entry(PersistentTaskParams.class, MlTasks.JOB_TASK_NAME,
                    OpenJobAction.JobParams::new));
            entries.add(new NamedWriteableRegistry.Entry(PersistentTaskState.class, JobTaskState.NAME, JobTaskState::new));
            entries.add(new NamedWriteableRegistry.Entry(PersistentTaskState.class, DatafeedState.NAME, DatafeedState::fromStream));
            entries.add(new NamedWriteableRegistry.Entry(PersistentTaskState.class, DataFrameAnalyticsTaskState.NAME,
                DataFrameAnalyticsTaskState::new));
            entries.add(new NamedWriteableRegistry.Entry(ClusterState.Custom.class, TokenMetadata.TYPE, TokenMetadata::new));
            final NamedWriteableRegistry namedWriteableRegistry = new NamedWriteableRegistry(entries);
            ClusterState masterClusterState = client().admin().cluster().prepareState().all().get().getState();
            byte[] masterClusterStateBytes = ClusterState.Builder.toBytes(masterClusterState);
            // remove local node reference
            masterClusterState = ClusterState.Builder.fromBytes(masterClusterStateBytes, null, namedWriteableRegistry);
            Map<String, Object> masterStateMap = convertToMap(masterClusterState);
            int masterClusterStateSize = ClusterState.Builder.toBytes(masterClusterState).length;
            String masterId = masterClusterState.nodes().getMasterNodeId();
            for (Client client : cluster().getClients()) {
                ClusterState localClusterState = client.admin().cluster().prepareState().all().setLocal(true).get().getState();
                byte[] localClusterStateBytes = ClusterState.Builder.toBytes(localClusterState);
                // remove local node reference
                localClusterState = ClusterState.Builder.fromBytes(localClusterStateBytes, null, namedWriteableRegistry);
                final Map<String, Object> localStateMap = convertToMap(localClusterState);
                final int localClusterStateSize = ClusterState.Builder.toBytes(localClusterState).length;
                // Check that the non-master node has the same version of the cluster state as the master and
                // that the master node matches the master (otherwise there is no requirement for the cluster state to match)
                if (masterClusterState.version() == localClusterState.version() &&
                        masterId.equals(localClusterState.nodes().getMasterNodeId())) {
                    try {
                        assertEquals("clusterstate UUID does not match", masterClusterState.stateUUID(), localClusterState.stateUUID());
                        // We cannot compare serialization bytes since serialization order of maps is not guaranteed
                        // but we can compare serialization sizes - they should be the same
                        assertEquals("clusterstate size does not match", masterClusterStateSize, localClusterStateSize);
                        // Compare JSON serialization
                        assertNull("clusterstate JSON serialization does not match",
                                differenceBetweenMapsIgnoringArrayOrder(masterStateMap, localStateMap));
                    } catch (AssertionError error) {
                        logger.error("Cluster state from master:\n{}\nLocal cluster state:\n{}",
                                masterClusterState.toString(), localClusterState.toString());
                        throw error;
                    }
                }
            }
        }
    }
}
