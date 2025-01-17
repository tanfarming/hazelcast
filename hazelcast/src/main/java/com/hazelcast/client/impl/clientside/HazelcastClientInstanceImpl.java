/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client.impl.clientside;

import com.hazelcast.cache.impl.JCacheDetector;
import com.hazelcast.cardinality.CardinalityEstimator;
import com.hazelcast.cardinality.impl.CardinalityEstimatorService;
import com.hazelcast.client.Client;
import com.hazelcast.client.ClientService;
import com.hazelcast.client.LoadBalancer;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.ClientConnectionStrategyConfig;
import com.hazelcast.client.config.ClientFailoverConfig;
import com.hazelcast.client.config.ClientNetworkConfig;
import com.hazelcast.client.cp.internal.CPSubsystemImpl;
import com.hazelcast.client.cp.internal.session.ClientProxySessionManager;
import com.hazelcast.client.impl.ClientExtension;
import com.hazelcast.client.impl.client.DistributedObjectInfo;
import com.hazelcast.client.impl.connection.AddressProvider;
import com.hazelcast.client.impl.connection.ClientConnectionManager;
import com.hazelcast.client.impl.connection.nio.ClientConnectionManagerImpl;
import com.hazelcast.client.impl.management.ManagementCenterService;
import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.protocol.codec.ClientGetDistributedObjectsCodec;
import com.hazelcast.client.impl.proxy.ClientClusterProxy;
import com.hazelcast.client.impl.proxy.PartitionServiceProxy;
import com.hazelcast.client.impl.querycache.ClientQueryCacheContext;
import com.hazelcast.client.impl.spi.ClientClusterService;
import com.hazelcast.client.impl.spi.ClientContext;
import com.hazelcast.client.impl.spi.ClientInvocationService;
import com.hazelcast.client.impl.spi.ClientListenerService;
import com.hazelcast.client.impl.spi.ClientPartitionService;
import com.hazelcast.client.impl.spi.ClientTransactionManagerService;
import com.hazelcast.client.impl.spi.ProxyManager;
import com.hazelcast.client.impl.spi.impl.AbstractClientInvocationService;
import com.hazelcast.client.impl.spi.impl.ClientClusterServiceImpl;
import com.hazelcast.client.impl.spi.impl.ClientExecutionServiceImpl;
import com.hazelcast.client.impl.spi.impl.ClientInvocation;
import com.hazelcast.client.impl.spi.impl.ClientPartitionServiceImpl;
import com.hazelcast.client.impl.spi.impl.ClientTransactionManagerServiceImpl;
import com.hazelcast.client.impl.spi.impl.ClientUserCodeDeploymentService;
import com.hazelcast.client.impl.spi.impl.NonSmartClientInvocationService;
import com.hazelcast.client.impl.spi.impl.SmartClientInvocationService;
import com.hazelcast.client.impl.spi.impl.listener.ClientClusterViewListenerService;
import com.hazelcast.client.impl.spi.impl.listener.ClientListenerServiceImpl;
import com.hazelcast.client.impl.statistics.ClientStatisticsService;
import com.hazelcast.client.util.RoundRobinLB;
import com.hazelcast.cluster.Cluster;
import com.hazelcast.collection.IList;
import com.hazelcast.collection.IQueue;
import com.hazelcast.collection.ISet;
import com.hazelcast.collection.impl.list.ListService;
import com.hazelcast.collection.impl.queue.QueueService;
import com.hazelcast.collection.impl.set.SetService;
import com.hazelcast.config.Config;
import com.hazelcast.core.DistributedObject;
import com.hazelcast.core.DistributedObjectListener;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IExecutorService;
import com.hazelcast.core.LifecycleService;
import com.hazelcast.cp.CPSubsystem;
import com.hazelcast.crdt.pncounter.PNCounter;
import com.hazelcast.durableexecutor.DurableExecutorService;
import com.hazelcast.durableexecutor.impl.DistributedDurableExecutorService;
import com.hazelcast.executor.impl.DistributedExecutorService;
import com.hazelcast.flakeidgen.FlakeIdGenerator;
import com.hazelcast.flakeidgen.impl.FlakeIdGeneratorService;
import com.hazelcast.instance.BuildInfoProvider;
import com.hazelcast.internal.crdt.pncounter.PNCounterService;
import com.hazelcast.internal.diagnostics.BuildInfoPlugin;
import com.hazelcast.internal.diagnostics.ConfigPropertiesPlugin;
import com.hazelcast.internal.diagnostics.Diagnostics;
import com.hazelcast.internal.diagnostics.EventQueuePlugin;
import com.hazelcast.internal.diagnostics.MetricsPlugin;
import com.hazelcast.internal.diagnostics.NetworkingImbalancePlugin;
import com.hazelcast.internal.diagnostics.SystemLogPlugin;
import com.hazelcast.internal.diagnostics.SystemPropertiesPlugin;
import com.hazelcast.internal.metrics.impl.MetricsConfigHelper;
import com.hazelcast.internal.metrics.impl.MetricsRegistryImpl;
import com.hazelcast.internal.metrics.metricsets.ClassLoadingMetricSet;
import com.hazelcast.internal.metrics.metricsets.FileMetricSet;
import com.hazelcast.internal.metrics.metricsets.GarbageCollectionMetricSet;
import com.hazelcast.internal.metrics.metricsets.OperatingSystemMetricSet;
import com.hazelcast.internal.metrics.metricsets.RuntimeMetricSet;
import com.hazelcast.internal.metrics.metricsets.ThreadMetricSet;
import com.hazelcast.internal.nio.Disposable;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.internal.util.ConcurrencyDetection;
import com.hazelcast.internal.util.ServiceLoader;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.LoggingService;
import com.hazelcast.map.IMap;
import com.hazelcast.map.impl.MapService;
import com.hazelcast.multimap.MultiMap;
import com.hazelcast.multimap.impl.MultiMapService;
import com.hazelcast.partition.PartitionService;
import com.hazelcast.replicatedmap.ReplicatedMap;
import com.hazelcast.replicatedmap.impl.ReplicatedMapService;
import com.hazelcast.ringbuffer.Ringbuffer;
import com.hazelcast.ringbuffer.impl.RingbufferService;
import com.hazelcast.scheduledexecutor.IScheduledExecutorService;
import com.hazelcast.scheduledexecutor.impl.DistributedScheduledExecutorService;
import com.hazelcast.spi.impl.SerializationServiceSupport;
import com.hazelcast.spi.impl.executionservice.TaskScheduler;
import com.hazelcast.spi.properties.ClusterProperty;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.splitbrainprotection.SplitBrainProtectionService;
import com.hazelcast.topic.ITopic;
import com.hazelcast.topic.impl.TopicService;
import com.hazelcast.topic.impl.reliable.ReliableTopicService;
import com.hazelcast.transaction.HazelcastXAResource;
import com.hazelcast.transaction.TransactionContext;
import com.hazelcast.transaction.TransactionException;
import com.hazelcast.transaction.TransactionOptions;
import com.hazelcast.transaction.TransactionalTask;
import com.hazelcast.transaction.impl.xa.XAService;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hazelcast.client.properties.ClientProperty.CONCURRENT_WINDOW_MS;
import static com.hazelcast.client.properties.ClientProperty.IO_WRITE_THROUGH_ENABLED;
import static com.hazelcast.client.properties.ClientProperty.MAX_CONCURRENT_INVOCATIONS;
import static com.hazelcast.client.properties.ClientProperty.RESPONSE_THREAD_DYNAMIC;
import static com.hazelcast.internal.metrics.impl.MetricsConfigHelper.clientMetricsLevel;
import static com.hazelcast.internal.util.EmptyStatement.ignore;
import static com.hazelcast.internal.util.ExceptionUtil.rethrow;
import static com.hazelcast.internal.util.Preconditions.checkNotNull;
import static java.lang.System.currentTimeMillis;

public class HazelcastClientInstanceImpl implements HazelcastInstance, SerializationServiceSupport {

    private static final AtomicInteger CLIENT_ID = new AtomicInteger();

    private final ConcurrencyDetection concurrencyDetection;
    private final HazelcastProperties properties;
    private final int id = CLIENT_ID.getAndIncrement();
    private final String instanceName;
    private final ClientFailoverConfig clientFailoverConfig;
    private final ClientConfig config;
    private final LifecycleServiceImpl lifecycleService;
    private final ClientConnectionManagerImpl connectionManager;
    private final ClientClusterServiceImpl clusterService;
    private final ClientPartitionServiceImpl partitionService;
    private final AbstractClientInvocationService invocationService;
    private final ClientExecutionServiceImpl executionService;
    private final ClientListenerServiceImpl listenerService;
    private final ClientClusterViewListenerService clientClusterViewListenerService;
    private final ClientTransactionManagerServiceImpl transactionManager;
    private final ProxyManager proxyManager;
    private final ConcurrentMap<String, Object> userContext = new ConcurrentHashMap<>();
    private final LoadBalancer loadBalancer;
    private final ClientExtension clientExtension;
    private final LoggingService loggingService;
    private final MetricsRegistryImpl metricsRegistry;
    private final ClientStatisticsService clientStatisticsService;
    private final Diagnostics diagnostics;
    private final InternalSerializationService serializationService;
    private final ClientICacheManager hazelcastCacheManager;
    private final ClientQueryCacheContext queryCacheContext;
    private final ClientLockReferenceIdGenerator lockReferenceIdGenerator;
    private final ClientExceptionFactory clientExceptionFactory;
    private final ClientUserCodeDeploymentService userCodeDeploymentService;
    private final ClusterDiscoveryService clusterDiscoveryService;
    private final ClientProxySessionManager proxySessionManager;
    private final CPSubsystemImpl cpSubsystem;
    private final ManagementCenterService managementCenterService;
    private final ConcurrentLinkedQueue<Disposable> onClusterChangeDisposables = new ConcurrentLinkedQueue();
    private final ConcurrentLinkedQueue<Disposable> onClientShutdownDisposables = new ConcurrentLinkedQueue();

    public HazelcastClientInstanceImpl(ClientConfig clientConfig,
                                       ClientFailoverConfig clientFailoverConfig,
                                       ClientConnectionManagerFactory clientConnectionManagerFactory,
                                       AddressProvider externalAddressProvider) {
        assert clientConfig != null || clientFailoverConfig != null : "At most one type of config can be provided";
        assert clientConfig == null || clientFailoverConfig == null : "At least one config should be provided ";
        if (clientConfig != null) {
            this.config = clientConfig;
        } else {
            this.config = clientFailoverConfig.getClientConfigs().get(0);
        }
        this.clientFailoverConfig = clientFailoverConfig;
        if (config.getInstanceName() != null) {
            instanceName = config.getInstanceName();
        } else {
            instanceName = "hz.client_" + id;
        }

        String loggingType = config.getProperty(ClusterProperty.LOGGING_TYPE.getName());
        this.loggingService = new ClientLoggingService(config.getClusterName(),
                loggingType, BuildInfoProvider.getBuildInfo(), instanceName);

        if (clientConfig != null) {
            MetricsConfigHelper.overrideClientMetricsConfig(clientConfig,
                    getLoggingService().getLogger(MetricsConfigHelper.class));
        } else {
            for (ClientConfig failoverClientConfig : clientFailoverConfig.getClientConfigs()) {
                MetricsConfigHelper.overrideClientMetricsConfig(failoverClientConfig,
                        getLoggingService().getLogger(MetricsConfigHelper.class));
            }
        }

        ClassLoader classLoader = config.getClassLoader();
        properties = new HazelcastProperties(config.getProperties());
        concurrencyDetection = initConcurrencyDetection();
        clientExtension = createClientInitializer(classLoader);
        clientExtension.beforeStart(this);
        lifecycleService = new LifecycleServiceImpl(this);
        metricsRegistry = initMetricsRegistry();
        serializationService = clientExtension.createSerializationService((byte) -1);
        proxyManager = new ProxyManager(this);
        executionService = initExecutionService();
        loadBalancer = initLoadBalancer(config);
        transactionManager = new ClientTransactionManagerServiceImpl(this);
        partitionService = new ClientPartitionServiceImpl(this);
        clusterDiscoveryService = initClusterDiscoveryService(externalAddressProvider);
        connectionManager = (ClientConnectionManagerImpl) clientConnectionManagerFactory.createConnectionManager(this);
        invocationService = initInvocationService();
        listenerService = new ClientListenerServiceImpl(this);
        clusterService = new ClientClusterServiceImpl(this);
        clientClusterViewListenerService = new ClientClusterViewListenerService(this);
        userContext.putAll(config.getUserContext());
        diagnostics = initDiagnostics();
        hazelcastCacheManager = new ClientICacheManager(this);
        queryCacheContext = new ClientQueryCacheContext(this);
        lockReferenceIdGenerator = new ClientLockReferenceIdGenerator();
        clientExceptionFactory = initClientExceptionFactory();
        clientStatisticsService = new ClientStatisticsService(this);
        userCodeDeploymentService = new ClientUserCodeDeploymentService(config.getUserCodeDeploymentConfig(), classLoader);
        proxySessionManager = new ClientProxySessionManager(this);
        cpSubsystem = new CPSubsystemImpl(this);
        managementCenterService = new ManagementCenterService(this, serializationService);
    }

    private ConcurrencyDetection initConcurrencyDetection() {
        boolean writeThrough = properties.getBoolean(IO_WRITE_THROUGH_ENABLED);
        boolean dynamicResponse = properties.getBoolean(RESPONSE_THREAD_DYNAMIC);
        boolean backPressureEnabled = properties.getInteger(MAX_CONCURRENT_INVOCATIONS) < Integer.MAX_VALUE;

        if (writeThrough || dynamicResponse || backPressureEnabled) {
            return ConcurrencyDetection.createEnabled(properties.getInteger(CONCURRENT_WINDOW_MS));
        } else {
            return ConcurrencyDetection.createDisabled();
        }
    }

    private ClusterDiscoveryService initClusterDiscoveryService(AddressProvider externalAddressProvider) {
        int tryCount;
        List<ClientConfig> configs;
        if (clientFailoverConfig == null) {
            tryCount = 0;
            configs = Collections.singletonList(config);
        } else {
            tryCount = clientFailoverConfig.getTryCount();
            configs = clientFailoverConfig.getClientConfigs();
        }
        ClusterDiscoveryServiceBuilder builder = new ClusterDiscoveryServiceBuilder(tryCount, configs, loggingService,
                externalAddressProvider, properties, clientExtension, getLifecycleService());
        return builder.build();
    }

    private Diagnostics initDiagnostics() {
        String name = "diagnostics-client-" + id + "-" + currentTimeMillis();
        ILogger logger = loggingService.getLogger(Diagnostics.class);
        return new Diagnostics(name, logger, instanceName, properties);
    }

    private MetricsRegistryImpl initMetricsRegistry() {
        ILogger logger = loggingService.getLogger(MetricsRegistryImpl.class);
        return new MetricsRegistryImpl(getName(), logger, clientMetricsLevel(properties,
                loggingService.getLogger(MetricsConfigHelper.class)));
    }

    private void startMetrics() {
        RuntimeMetricSet.register(metricsRegistry);
        GarbageCollectionMetricSet.register(metricsRegistry);
        OperatingSystemMetricSet.register(metricsRegistry);
        ThreadMetricSet.register(metricsRegistry);
        ClassLoadingMetricSet.register(metricsRegistry);
        FileMetricSet.register(metricsRegistry);
        metricsRegistry.registerStaticMetrics(clientExtension.getMemoryStats(), "memory");
        metricsRegistry.provideMetrics(clientExtension);
        metricsRegistry.provideMetrics(executionService);
    }

    private LoadBalancer initLoadBalancer(ClientConfig config) {
        LoadBalancer lb = config.getLoadBalancer();
        if (lb == null) {
            lb = new RoundRobinLB();
        }
        return lb;
    }

    @SuppressWarnings("checkstyle:illegaltype")
    private AbstractClientInvocationService initInvocationService() {
        final ClientNetworkConfig networkConfig = config.getNetworkConfig();
        if (networkConfig.isSmartRouting()) {
            return new SmartClientInvocationService(this);
        } else {
            return new NonSmartClientInvocationService(this);
        }
    }

    public int getId() {
        return id;
    }

    private ClientExtension createClientInitializer(ClassLoader classLoader) {
        try {
            String factoryId = ClientExtension.class.getName();
            Iterator<ClientExtension> iter = ServiceLoader.iterator(ClientExtension.class, factoryId, classLoader);
            while (iter.hasNext()) {
                ClientExtension initializer = iter.next();
                if (!(initializer.getClass().equals(DefaultClientExtension.class))) {
                    return initializer;
                }
            }
        } catch (Exception e) {
            throw rethrow(e);
        }
        return new DefaultClientExtension();
    }

    private ClientExecutionServiceImpl initExecutionService() {
        return new ClientExecutionServiceImpl(instanceName,
                config.getClassLoader(), properties, loggingService);
    }

    public void start() {
        try {
            lifecycleService.start();
            startMetrics();
            invocationService.start();
            ClientContext clientContext = new ClientContext(this);
            userCodeDeploymentService.start();
            clusterService.start();
            clientClusterViewListenerService.start();
            connectionManager.start();
            diagnostics.start();

            // static loggers at beginning of file
            diagnostics.register(
                    new BuildInfoPlugin(loggingService.getLogger(BuildInfoPlugin.class)));
            diagnostics.register(
                    new ConfigPropertiesPlugin(loggingService.getLogger(ConfigPropertiesPlugin.class), properties));
            diagnostics.register(
                    new SystemPropertiesPlugin(loggingService.getLogger(SystemPropertiesPlugin.class)));

            // periodic loggers
            diagnostics.register(
                    new MetricsPlugin(loggingService.getLogger(MetricsPlugin.class), metricsRegistry, properties));
            diagnostics.register(
                    new SystemLogPlugin(properties, connectionManager, this, loggingService.getLogger(SystemLogPlugin.class)));
            diagnostics.register(
                    new NetworkingImbalancePlugin(properties, connectionManager.getNetworking(),
                            loggingService.getLogger(NetworkingImbalancePlugin.class)));
            diagnostics.register(
                    new EventQueuePlugin(loggingService.getLogger(EventQueuePlugin.class), listenerService.getEventExecutor(),
                            properties));

            metricsRegistry.provideMetrics(listenerService);

            proxyManager.init(config, clientContext);
            listenerService.start();
            if (invocationService instanceof SmartClientInvocationService) {
                ((SmartClientInvocationService) invocationService).addBackupListener();
            }
            ClientConnectionStrategyConfig connectionStrategyConfig = config.getConnectionStrategyConfig();
            if (!connectionStrategyConfig.isAsyncStart()) {
                connectionManager.tryOpenConnectionToAllMembers();
                waitForInitialMembershipEvents();
            }
            loadBalancer.init(getCluster(), config);
            partitionService.start();
            clientStatisticsService.start();
            clientExtension.afterStart(this);
            cpSubsystem.init(clientContext);
            sendStateToCluster();
        } catch (Throwable e) {
            try {
                lifecycleService.terminate();
            } catch (Throwable t) {
                ignore(t);
            }
            throw rethrow(e);
        }
    }

    public void disposeOnClusterChange(Disposable disposable) {
        onClusterChangeDisposables.add(disposable);
    }

    public void disposeOnClientShutdown(Disposable disposable) {
        onClientShutdownDisposables.add(disposable);
    }

    public MetricsRegistryImpl getMetricsRegistry() {
        return metricsRegistry;
    }

    @Nonnull
    @Override
    public HazelcastXAResource getXAResource() {
        return getDistributedObject(XAService.SERVICE_NAME, XAService.SERVICE_NAME);
    }

    @Nonnull
    @Override
    public Config getConfig() {
        return new ClientDynamicClusterConfig(this);
    }

    public HazelcastProperties getProperties() {
        return properties;
    }

    @Nonnull
    @Override
    public String getName() {
        return instanceName;
    }

    @Nonnull
    @Override
    public <E> IQueue<E> getQueue(@Nonnull String name) {
        checkNotNull(name, "Retrieving a queue instance with a null name is not allowed!");
        return getDistributedObject(QueueService.SERVICE_NAME, name);
    }

    @Nonnull
    @Override
    public <E> ITopic<E> getTopic(@Nonnull String name) {
        checkNotNull(name, "Retrieving a topic instance with a null name is not allowed!");
        return getDistributedObject(TopicService.SERVICE_NAME, name);
    }

    @Nonnull
    @Override
    public <E> ISet<E> getSet(@Nonnull String name) {
        checkNotNull(name, "Retrieving a set instance with a null name is not allowed!");
        return getDistributedObject(SetService.SERVICE_NAME, name);
    }

    @Nonnull
    @Override
    public <E> IList<E> getList(@Nonnull String name) {
        checkNotNull(name, "Retrieving a list instance with a null name is not allowed!");
        return getDistributedObject(ListService.SERVICE_NAME, name);
    }

    @Nonnull
    @Override
    public <K, V> IMap<K, V> getMap(@Nonnull String name) {
        checkNotNull(name, "Retrieving a map instance with a null name is not allowed!");
        return getDistributedObject(MapService.SERVICE_NAME, name);
    }

    @Nonnull
    @Override
    public <K, V> MultiMap<K, V> getMultiMap(@Nonnull String name) {
        checkNotNull(name, "Retrieving a multi-map instance with a null name is not allowed!");
        return getDistributedObject(MultiMapService.SERVICE_NAME, name);

    }

    @Nonnull
    @Override
    public <K, V> ReplicatedMap<K, V> getReplicatedMap(@Nonnull String name) {
        checkNotNull(name, "Retrieving a replicated map instance with a null name is not allowed!");
        return getDistributedObject(ReplicatedMapService.SERVICE_NAME, name);
    }

    @Nonnull
    @Override
    public <E> ITopic<E> getReliableTopic(@Nonnull String name) {
        checkNotNull(name, "Retrieving a topic instance with a null name is not allowed!");
        return getDistributedObject(ReliableTopicService.SERVICE_NAME, name);
    }

    @Nonnull
    @Override
    public <E> Ringbuffer<E> getRingbuffer(@Nonnull String name) {
        checkNotNull(name, "Retrieving a ringbuffer instance with a null name is not allowed!");
        return getDistributedObject(RingbufferService.SERVICE_NAME, name);
    }

    @Override
    public ClientICacheManager getCacheManager() {
        return hazelcastCacheManager;
    }

    @Nonnull
    @Override
    public Cluster getCluster() {
        return new ClientClusterProxy(clusterService);
    }

    @Nonnull
    @Override
    public Client getLocalEndpoint() {
        return clusterService.getLocalClient();
    }

    @Nonnull
    @Override
    public IExecutorService getExecutorService(@Nonnull String name) {
        checkNotNull(name, "Retrieving an executor instance with a null name is not allowed!");
        return getDistributedObject(DistributedExecutorService.SERVICE_NAME, name);
    }

    @Nonnull
    @Override
    public DurableExecutorService getDurableExecutorService(@Nonnull String name) {
        checkNotNull(name, "Retrieving a durable executor instance with a null name is not allowed!");
        return getDistributedObject(DistributedDurableExecutorService.SERVICE_NAME, name);
    }

    @Override
    public <T> T executeTransaction(@Nonnull TransactionalTask<T> task) throws TransactionException {
        return transactionManager.executeTransaction(task);
    }

    @Override
    public <T> T executeTransaction(@Nonnull TransactionOptions options,
                                    @Nonnull TransactionalTask<T> task) throws TransactionException {
        return transactionManager.executeTransaction(options, task);
    }

    @Override
    public TransactionContext newTransactionContext() {
        return transactionManager.newTransactionContext();
    }

    @Override
    public TransactionContext newTransactionContext(@Nonnull TransactionOptions options) {
        checkNotNull(options, "TransactionOptions must not be null!");
        return transactionManager.newTransactionContext(options);
    }

    public ClientTransactionManagerService getTransactionManager() {
        return transactionManager;
    }

    @Nonnull
    @Override
    public FlakeIdGenerator getFlakeIdGenerator(@Nonnull String name) {
        checkNotNull(name, "Retrieving a Flake ID-generator instance with a null name is not allowed!");
        return getDistributedObject(FlakeIdGeneratorService.SERVICE_NAME, name);
    }

    @Nonnull
    @Override
    public CardinalityEstimator getCardinalityEstimator(@Nonnull String name) {
        checkNotNull(name, "Retrieving a cardinality estimator instance with a null name is not allowed!");
        return getDistributedObject(CardinalityEstimatorService.SERVICE_NAME, name);
    }

    @Nonnull
    @Override
    public PNCounter getPNCounter(@Nonnull String name) {
        checkNotNull(name, "Retrieving a PN counter instance with a null name is not allowed!");
        return getDistributedObject(PNCounterService.SERVICE_NAME, name);
    }

    @Nonnull
    @Override
    public IScheduledExecutorService getScheduledExecutorService(@Nonnull String name) {
        checkNotNull(name, "Retrieving a scheduled executor instance with a null name is not allowed!");
        return getDistributedObject(DistributedScheduledExecutorService.SERVICE_NAME, name);
    }

    @Override
    public Collection<DistributedObject> getDistributedObjects() {
        try {
            ClientMessage request = ClientGetDistributedObjectsCodec.encodeRequest();
            final Future<ClientMessage> future = new ClientInvocation(this, request, getName()).invoke();
            ClientMessage response = future.get();
            ClientGetDistributedObjectsCodec.ResponseParameters resultParameters =
                    ClientGetDistributedObjectsCodec.decodeResponse(response);

            Collection<? extends DistributedObject> distributedObjects = proxyManager.getDistributedObjects();
            Set<DistributedObjectInfo> localDistributedObjects = new HashSet<>();
            for (DistributedObject localInfo : distributedObjects) {
                localDistributedObjects.add(new DistributedObjectInfo(localInfo.getServiceName(), localInfo.getName()));
            }

            Collection<DistributedObjectInfo> newDistributedObjectInfo = resultParameters.response;
            for (DistributedObjectInfo distributedObjectInfo : newDistributedObjectInfo) {
                localDistributedObjects.remove(distributedObjectInfo);
                getDistributedObject(distributedObjectInfo.getServiceName(), distributedObjectInfo.getName(), false);
            }

            for (DistributedObjectInfo distributedObjectInfo : localDistributedObjects) {
                proxyManager.destroyProxyLocally(distributedObjectInfo.getServiceName(), distributedObjectInfo.getName());
            }
            return (Collection<DistributedObject>) proxyManager.getDistributedObjects();
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    @Override
    public UUID addDistributedObjectListener(@Nonnull DistributedObjectListener distributedObjectListener) {
        checkNotNull(distributedObjectListener, "DistributedObjectListener must not be null!");
        return proxyManager.addDistributedObjectListener(distributedObjectListener);
    }

    @Override
    public boolean removeDistributedObjectListener(@Nonnull UUID registrationId) {
        checkNotNull(registrationId, "Registration ID must not be null!");
        return proxyManager.removeDistributedObjectListener(registrationId);
    }

    @Nonnull
    @Override
    public PartitionService getPartitionService() {
        return new PartitionServiceProxy(partitionService, listenerService);
    }

    @Nonnull
    @Override
    public SplitBrainProtectionService getSplitBrainProtectionService() {
        throw new UnsupportedOperationException();
    }

    @Nonnull
    @Override
    public ClientService getClientService() {
        throw new UnsupportedOperationException();
    }

    @Nonnull
    @Override
    public LoggingService getLoggingService() {
        return loggingService;
    }

    @Nonnull
    @Override
    public LifecycleService getLifecycleService() {
        return lifecycleService;
    }

    @Nonnull
    @Override
    public <T extends DistributedObject> T getDistributedObject(@Nonnull String serviceName,
                                                                @Nonnull String name) {
        return getDistributedObject(serviceName, name, true);
    }

    private <T extends DistributedObject> T getDistributedObject(@Nonnull String serviceName,
                                                                 @Nonnull String name, boolean remote) {
        if (remote) {
            return (T) proxyManager.getOrCreateProxy(serviceName, name);
        }
        return (T) proxyManager.getOrCreateLocalProxy(serviceName, name);
    }

    @Nonnull
    @Override
    public CPSubsystem getCPSubsystem() {
        return cpSubsystem;
    }

    @Nonnull
    @Override
    public ConcurrentMap<String, Object> getUserContext() {
        return userContext;
    }

    public ClientConfig getClientConfig() {
        return config;
    }

    @Override
    public InternalSerializationService getSerializationService() {
        return serializationService;
    }

    public ClientUserCodeDeploymentService getUserCodeDeploymentService() {
        return userCodeDeploymentService;
    }

    public ClientProxySessionManager getProxySessionManager() {
        return proxySessionManager;
    }

    public ProxyManager getProxyManager() {
        return proxyManager;
    }

    public ClientConnectionManager getConnectionManager() {
        return connectionManager;
    }

    public ClientClusterService getClientClusterService() {
        return clusterService;
    }

    public TaskScheduler getTaskScheduler() {
        return executionService;
    }

    public ClientPartitionService getClientPartitionService() {
        return partitionService;
    }

    public ClientInvocationService getInvocationService() {
        return invocationService;
    }

    public ClientListenerService getListenerService() {
        return listenerService;
    }

    public LoadBalancer getLoadBalancer() {
        return loadBalancer;
    }

    public ClientExtension getClientExtension() {
        return clientExtension;
    }

    @Override
    public void shutdown() {
        getLifecycleService().shutdown();
    }

    /**
     * Called during graceful shutdown of client to safely clean up resources on server side.
     * Shutdown process is blocked until this method returns.
     * <p>
     * Current list of cleanups:
     * <ul>
     * <li>Close of CP sessions</li>
     * </ul>
     */
    void onGracefulShutdown() {
        proxySessionManager.shutdownAndAwait();
    }

    public void doShutdown() {
        dispose(onClientShutdownDisposables);
        proxyManager.destroy();
        connectionManager.shutdown();
        clusterDiscoveryService.shutdown();
        transactionManager.shutdown();
        invocationService.shutdown();
        executionService.shutdown();
        listenerService.shutdown();
        clientStatisticsService.shutdown();
        metricsRegistry.shutdown();
        diagnostics.shutdown();
        serializationService.dispose();
    }

    private static void dispose(Queue<Disposable> queue) {
        Disposable disposable;
        while ((disposable = queue.poll()) != null) {
            disposable.dispose();
        }
    }

    public ClientLockReferenceIdGenerator getLockReferenceIdGenerator() {
        return lockReferenceIdGenerator;
    }

    private ClientExceptionFactory initClientExceptionFactory() {
        boolean jCacheAvailable = JCacheDetector.isJCacheAvailable(getClientConfig().getClassLoader());
        return new ClientExceptionFactory(jCacheAvailable);
    }

    public ClientExceptionFactory getClientExceptionFactory() {
        return clientExceptionFactory;
    }

    public ClusterDiscoveryService getClusterDiscoveryService() {
        return clusterDiscoveryService;
    }

    public ClientFailoverConfig getFailoverConfig() {
        return clientFailoverConfig;
    }

    public ClientQueryCacheContext getQueryCacheContext() {
        return queryCacheContext;
    }

    public ManagementCenterService getManagementCenterService() {
        return managementCenterService;
    }

    public ConcurrencyDetection getConcurrencyDetection() {
        return concurrencyDetection;
    }

    public void onClusterChange() {
        ILogger logger = loggingService.getLogger(HazelcastInstance.class);
        logger.info("Resetting local state of the client, because of a cluster change ");

        dispose(onClusterChangeDisposables);
        //clear the member lists
        clusterService.reset();
        //clear partition service
        partitionService.reset();
        //close all the connections, consequently waiting invocations get TargetDisconnectedException
        //non retryable client messages will fail immediately
        //retryable client messages will be retried but they will wait for new partition table
        connectionManager.reset();
    }

    public void onClusterRestart() {
        ILogger logger = loggingService.getLogger(HazelcastInstance.class);
        logger.info("Clearing local state of the client, because of a cluster restart ");

        for (Disposable disposable : onClusterChangeDisposables) {
            disposable.dispose();
        }
        //clear the member list version
        clusterService.clearMemberListVersion();
    }

    public void waitForInitialMembershipEvents() {
        clusterService.waitInitialMemberListFetched();
    }

    public void sendStateToCluster() throws ExecutionException, InterruptedException {
        userCodeDeploymentService.deploy(this);
        proxyManager.createDistributedObjectsOnCluster();
        queryCacheContext.recreateAllCaches();
    }

    // visible for testing
    public ClientStatisticsService getClientStatisticsService() {
        return clientStatisticsService;
    }
}
