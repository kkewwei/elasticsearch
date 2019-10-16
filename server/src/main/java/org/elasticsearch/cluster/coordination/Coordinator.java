/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.cluster.coordination;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateTaskConfig;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.LocalClusterUpdateTask;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.coordination.ClusterFormationFailureHelper.ClusterFormationState;
import org.elasticsearch.cluster.coordination.CoordinationMetaData.VotingConfigExclusion;
import org.elasticsearch.cluster.coordination.CoordinationMetaData.VotingConfiguration;
import org.elasticsearch.cluster.coordination.CoordinationState.VoteCollection;
import org.elasticsearch.cluster.coordination.FollowersChecker.FollowerCheckRequest;
import org.elasticsearch.cluster.coordination.JoinHelper.InitialJoinAccumulator;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.RerouteService;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.service.ClusterApplier;
import org.elasticsearch.cluster.service.ClusterApplier.ClusterApplyListener;
import org.elasticsearch.cluster.service.MasterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.util.concurrent.ListenableFuture;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.discovery.Discovery;
import org.elasticsearch.discovery.DiscoveryModule;
import org.elasticsearch.discovery.DiscoveryStats;
import org.elasticsearch.discovery.HandshakingTransportAddressConnector;
import org.elasticsearch.discovery.PeerFinder;
import org.elasticsearch.discovery.SeedHostsProvider;
import org.elasticsearch.discovery.SeedHostsResolver;
import org.elasticsearch.threadpool.Scheduler;
import org.elasticsearch.threadpool.ThreadPool.Names;
import org.elasticsearch.transport.TransportResponse.Empty;
import org.elasticsearch.transport.TransportService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.elasticsearch.cluster.coordination.NoMasterBlockService.NO_MASTER_BLOCK_ID;
import static org.elasticsearch.gateway.ClusterStateUpdaters.hideStateIfNotRecovered;
import static org.elasticsearch.gateway.GatewayService.STATE_NOT_RECOVERED_BLOCK;

public class Coordinator extends AbstractLifecycleComponent implements Discovery {

    private static final Logger logger = LogManager.getLogger(Coordinator.class);

    // the timeout before emitting an info log about a slow-running publication
    public static final Setting<TimeValue> PUBLISH_INFO_TIMEOUT_SETTING =
        Setting.timeSetting("cluster.publish.info_timeout",
            TimeValue.timeValueMillis(10000), TimeValue.timeValueMillis(1), Setting.Property.NodeScope);

    // the timeout for the publication of each value
    public static final Setting<TimeValue> PUBLISH_TIMEOUT_SETTING =
        Setting.timeSetting("cluster.publish.timeout",
            TimeValue.timeValueMillis(30000), TimeValue.timeValueMillis(1), Setting.Property.NodeScope);

    private final Settings settings;
    private final boolean singleNodeDiscovery;  // 为false
    private final ElectionStrategy electionStrategy;
    private final TransportService transportService;
    private final MasterService masterService;
    private final AllocationService allocationService;
    private final JoinHelper joinHelper;
    private final NodeRemovalClusterStateTaskExecutor nodeRemovalExecutor;
    private final Supplier<CoordinationState.PersistedState> persistedStateSupplier;
    private final NoMasterBlockService noMasterBlockService;
    // TODO: the following field is package-private as some tests require access to it
    // These tests can be rewritten to use public methods once Coordinator is more feature-complete
    final Object mutex = new Object();
    private final SetOnce<CoordinationState> coordinationState = new SetOnce<>(); // initialized on start-up (see doStart)   本节点角色
    private volatile ClusterState applierState; // the state that should be exposed to the cluster state applier

    private final PeerFinder peerFinder;  // CoordinatorPeerFinder
    private final PreVoteCollector preVoteCollector; //上一次本地记录的投票情况
    private final Random random;
    private final ElectionSchedulerFactory electionSchedulerFactory;
    private final SeedHostsResolver configuredHostsResolver;
    private final TimeValue publishTimeout;
    private final TimeValue publishInfoTimeout;
    private final PublicationTransportHandler publicationHandler;
    private final LeaderChecker leaderChecker; // LeaderChecker
    private final FollowersChecker followersChecker;
    private final ClusterApplier clusterApplier;
    private final Collection<BiConsumer<DiscoveryNode, ClusterState>> onJoinValidators;
    @Nullable
    private Releasable electionScheduler; // 退避周期选举
    @Nullable
    private Releasable prevotingRound; // 当前轮训正在跑的选举给暂停 PreVotingRound
    private long maxTermSeen; // 更新见过的最大maxTermSeen
    private final Reconfigurator reconfigurator;
    private final ClusterBootstrapService clusterBootstrapService;
    private final LagDetector lagDetector; //
    private final ClusterFormationFailureHelper clusterFormationFailureHelper;// 集群选举失败日志打印器

    private Mode mode; // 默认为null
    private Optional<DiscoveryNode> lastKnownLeader;
    private Optional<Join> lastJoin;
    private JoinHelper.JoinAccumulator joinAccumulator;
    private Optional<CoordinatorPublication> currentPublication = Optional.empty(); // master节点鸡进行publish前，生成该对象保存起来，表示正在publish

    /**
     * @param nodeName The name of the node, used to name the {@link java.util.concurrent.ExecutorService} of the {@link SeedHostsResolver}.
     * @param onJoinValidators A collection of join validators to restrict which nodes may join the cluster.
     */
    public Coordinator(String nodeName, Settings settings, ClusterSettings clusterSettings, TransportService transportService,
                       NamedWriteableRegistry namedWriteableRegistry, AllocationService allocationService, MasterService masterService,
                       Supplier<CoordinationState.PersistedState> persistedStateSupplier, SeedHostsProvider seedHostsProvider,
                       ClusterApplier clusterApplier, Collection<BiConsumer<DiscoveryNode, ClusterState>> onJoinValidators, Random random,
                       RerouteService rerouteService, ElectionStrategy electionStrategy) {
        this.settings = settings;
        this.transportService = transportService;
        this.masterService = masterService;
        this.allocationService = allocationService;
        this.onJoinValidators = JoinTaskExecutor.addBuiltInJoinValidators(onJoinValidators); // 增加验证节点版本，索引版本条件
        this.singleNodeDiscovery = DiscoveryModule.SINGLE_NODE_DISCOVERY_TYPE.equals(DiscoveryModule.DISCOVERY_TYPE_SETTING.get(settings));
        this.electionStrategy = electionStrategy;
        this.joinHelper = new JoinHelper(settings, allocationService, masterService, transportService,
            this::getCurrentTerm, this::getStateForMasterService, this::handleJoinRequest, this::joinLeaderInTerm, this.onJoinValidators,
            rerouteService);
        this.persistedStateSupplier = persistedStateSupplier;
        this.noMasterBlockService = new NoMasterBlockService(settings, clusterSettings);
        this.lastKnownLeader = Optional.empty();
        this.lastJoin = Optional.empty();
        this.joinAccumulator = new InitialJoinAccumulator();
        this.publishTimeout = PUBLISH_TIMEOUT_SETTING.get(settings);
        this.publishInfoTimeout = PUBLISH_INFO_TIMEOUT_SETTING.get(settings);
        this.random = random;
        this.electionSchedulerFactory = new ElectionSchedulerFactory(settings, random, transportService.getThreadPool());
        this.preVoteCollector = new PreVoteCollector(transportService, this::startElection, this::updateMaxTermSeen, electionStrategy);  // 预投票
        configuredHostsResolver = new SeedHostsResolver(nodeName, settings, transportService, seedHostsProvider);
        this.peerFinder = new CoordinatorPeerFinder(settings, transportService,
            new HandshakingTransportAddressConnector(settings, transportService), configuredHostsResolver);
        this.publicationHandler = new PublicationTransportHandler(transportService, namedWriteableRegistry,
            this::handlePublishRequest, this::handleApplyCommit);
        this.leaderChecker = new LeaderChecker(settings, transportService, this::onLeaderFailure);
        this.followersChecker = new FollowersChecker(settings, transportService, this::onFollowerCheckRequest, this::removeNode);
        this.nodeRemovalExecutor = new NodeRemovalClusterStateTaskExecutor(allocationService, logger);
        this.clusterApplier = clusterApplier;
        masterService.setClusterStateSupplier(this::getStateForMasterService);
        this.reconfigurator = new Reconfigurator(settings, clusterSettings);
        this.clusterBootstrapService = new ClusterBootstrapService(settings, transportService, this::getFoundPeers,
            this::isInitialConfigurationSet, this::setInitialConfiguration);
        this.lagDetector = new LagDetector(settings, transportService.getThreadPool(), n -> removeNode(n, "lagging"),
            transportService::getLocalNode);
        this.clusterFormationFailureHelper = new ClusterFormationFailureHelper(settings, this::getClusterFormationState,
            transportService.getThreadPool(), joinHelper::logLastFailedJoinAttempt);
    }

    private ClusterFormationState getClusterFormationState() {
        return new ClusterFormationState(settings, getStateForMasterService(), peerFinder.getLastResolvedAddresses(),
            Stream.concat(Stream.of(getLocalNode()), StreamSupport.stream(peerFinder.getFoundPeers().spliterator(), false))
                    .collect(Collectors.toList()), getCurrentTerm(), electionStrategy);
    }

    private void onLeaderFailure(Exception e) {
        synchronized (mutex) {
            if (mode != Mode.CANDIDATE) {
                assert lastKnownLeader.isPresent();
                logger.info(new ParameterizedMessage("master node [{}] failed, restarting discovery", lastKnownLeader.get()), e);
            }
            becomeCandidate("onLeaderFailure");
        }
    }

    private void removeNode(DiscoveryNode discoveryNode, String reason) {
        synchronized (mutex) {  // 提交data掉线请求
            if (mode == Mode.LEADER) {
                masterService.submitStateUpdateTask("node-left",
                    new NodeRemovalClusterStateTaskExecutor.Task(discoveryNode, reason),
                    ClusterStateTaskConfig.build(Priority.IMMEDIATE),
                    nodeRemovalExecutor,
                    nodeRemovalExecutor);
            }
        }
    }
    // Follower 检查 Leader 发来的FollowerCheckRequest
    void onFollowerCheckRequest(FollowerCheckRequest followerCheckRequest) {
        synchronized (mutex) {
            ensureTermAtLeast(followerCheckRequest.getSender(), followerCheckRequest.getTerm());

            if (getCurrentTerm() != followerCheckRequest.getTerm()) {
                logger.trace("onFollowerCheckRequest: current term is [{}], rejecting {}", getCurrentTerm(), followerCheckRequest);
                throw new CoordinationStateRejectedException("onFollowerCheckRequest: current term is ["
                    + getCurrentTerm() + "], rejecting " + followerCheckRequest);
            }

            // check if node has accepted a state in this term already. If not, this node has never committed a cluster state in this
            // term and therefore never removed the NO_MASTER_BLOCK for this term. This logic ensures that we quickly turn a node
            // into follower, even before receiving the first cluster state update, but also don't have to deal with the situation
            // where we would possibly have to remove the NO_MASTER_BLOCK from the applierState when turning a candidate back to follower.
            if (getLastAcceptedState().term() < getCurrentTerm()) {
                becomeFollower("onFollowerCheckRequest", followerCheckRequest.getSender());
            } else if (mode == Mode.FOLLOWER) {
                logger.trace("onFollowerCheckRequest: responding successfully to {}", followerCheckRequest);
            } else if (joinHelper.isJoinPending()) {
                logger.trace("onFollowerCheckRequest: rejoining master, responding successfully to {}", followerCheckRequest);
            } else {
                logger.trace("onFollowerCheckRequest: received check from faulty master, rejecting {}", followerCheckRequest);
                throw new CoordinationStateRejectedException(
                    "onFollowerCheckRequest: received check from faulty master, rejecting " + followerCheckRequest);
            }
        }
    }
    // 从数据节点接收到第二次确认响应
    private void handleApplyCommit(ApplyCommitRequest applyCommitRequest, ActionListener<Void> applyListener) {
        synchronized (mutex) {
            logger.trace("handleApplyCommit: applying commit {}", applyCommitRequest);

            coordinationState.get().handleCommit(applyCommitRequest);
            final ClusterState committedState = hideStateIfNotRecovered(coordinationState.get().getLastAcceptedState());
            applierState = mode == Mode.CANDIDATE ? clusterStateWithNoMasterBlock(committedState) : committedState;
            if (applyCommitRequest.getSourceNode().equals(getLocalNode())) { // 这里都是相等的，俩节点都是maser节点
                // master node applies the committed state at the end of the publication process, not here.
                applyListener.onResponse(null);
            } else {
                clusterApplier.onNewClusterState(applyCommitRequest.toString(), () -> applierState,
                    new ClusterApplyListener() {

                        @Override
                        public void onFailure(String source, Exception e) {
                            applyListener.onFailure(e);
                        }

                        @Override
                        public void onSuccess(String source) {
                            applyListener.onResponse(null);
                        }
                    });
            }
        }
    }
    //  收到publish发送过来的更新集群状态请求时
    PublishWithJoinResponse handlePublishRequest(PublishRequest publishRequest) {
        assert publishRequest.getAcceptedState().nodes().getLocalNode().equals(getLocalNode()) :
            publishRequest.getAcceptedState().nodes().getLocalNode() + " != " + getLocalNode();

        synchronized (mutex) {
            final DiscoveryNode sourceNode = publishRequest.getAcceptedState().nodes().getMasterNode();
            logger.trace("handlePublishRequest: handling [{}] from [{}]", publishRequest, sourceNode);

            if (sourceNode.equals(getLocalNode()) && mode != Mode.LEADER) {
                // Rare case in which we stood down as leader between starting this publication and receiving it ourselves. The publication
                // is already failed so there is no point in proceeding.
                throw new CoordinationStateRejectedException("no longer leading this publication's term: " + publishRequest);
            }

            final ClusterState localState = coordinationState.get().getLastAcceptedState();

            if (localState.metaData().clusterUUIDCommitted() &&
                localState.metaData().clusterUUID().equals(publishRequest.getAcceptedState().metaData().clusterUUID()) == false) {
                logger.warn("received cluster state from {} with a different cluster uuid {} than local cluster uuid {}, rejecting",
                    sourceNode, publishRequest.getAcceptedState().metaData().clusterUUID(), localState.metaData().clusterUUID());
                throw new CoordinationStateRejectedException("received cluster state from " + sourceNode +
                    " with a different cluster uuid " + publishRequest.getAcceptedState().metaData().clusterUUID() +
                    " than local cluster uuid " + localState.metaData().clusterUUID() + ", rejecting");
            }

            if (publishRequest.getAcceptedState().term() > localState.term()) {
                // only do join validation if we have not accepted state from this master yet
                onJoinValidators.forEach(a -> a.accept(getLocalNode(), publishRequest.getAcceptedState()));
            }

            ensureTermAtLeast(sourceNode, publishRequest.getAcceptedState().term());
            final PublishResponse publishResponse = coordinationState.get().handlePublishRequest(publishRequest);

            if (sourceNode.equals(getLocalNode())) {
                preVoteCollector.update(getPreVoteResponse(), getLocalNode());
            } else {  // 本节点变成了一个follower
                becomeFollower("handlePublishRequest", sourceNode); // also updates preVoteCollector
            }

            return new PublishWithJoinResponse(publishResponse, // 恢复master请求
                joinWithDestination(lastJoin, sourceNode, publishRequest.getAcceptedState().term()));
        }
    }
    // 构造一个选票
    private static Optional<Join> joinWithDestination(Optional<Join> lastJoin, DiscoveryNode leader, long term) {
        if (lastJoin.isPresent() //
            && lastJoin.get().targetMatches(leader) // 上次的leader就是自己
            && lastJoin.get().getTerm() == term) { // 上次term和本次term一样
            return lastJoin;
        }

        return Optional.empty();
    }

    private void closePrevotingAndElectionScheduler() {
        if (prevotingRound != null) {
            prevotingRound.close();
            prevotingRound = null;
        }

        if (electionScheduler != null) {
            electionScheduler.close();
            electionScheduler = null;
        }
    }
    // 更新本地的见过的最大maxTermSeen
    private void updateMaxTermSeen(final long term) { //
        synchronized (mutex) {
            maxTermSeen = Math.max(maxTermSeen, term);
            final long currentTerm = getCurrentTerm();
            if (mode == Mode.LEADER && maxTermSeen > currentTerm) { // 如果自己是leader,而maxTermSeen大于currentTerm，则当前leader身份已经过期,尝试开始进行新一轮选举
                // Bump our term. However if there is a publication in flight then doing so would cancel the publication, so don't do that
                // since we check whether a term bump is needed at the end of the publication too.
                if (publicationInProgress()) { // 正有一个正在广播，先让他继续广播，广播完了还会检查
                    logger.debug("updateMaxTermSeen: maxTermSeen = {} > currentTerm = {}, enqueueing term bump", maxTermSeen, currentTerm);
                } else {
                    try {// master挺不要脸，更新自己的term后，立马再邀请节点加入自己的集群
                        logger.debug("updateMaxTermSeen: maxTermSeen = {} > currentTerm = {}, bumping term", maxTermSeen, currentTerm);
                        ensureTermAtLeast(getLocalNode(), maxTermSeen); // 很耐人寻味，要选票的是自己getLocalNode()
                        startElection(); // 开始发起选举
                    } catch (Exception e) {
                        logger.warn(new ParameterizedMessage("failed to bump term to {}", maxTermSeen), e);
                        becomeCandidate("updateMaxTermSeen");
                    }
                }
            }
        }
    }
   // 可以看下选举文章：https://www.easyice.cn/archives/332   。自己发起选举时， term+1都会执行，挺重要
    private void startElection() {// 开始发起选举，一般节点还需要预选投票，master可以直接发起
        synchronized (mutex) {
            // The preVoteCollector is only active while we are candidate, but it does not call this method with synchronisation, so we have
            // to check our mode again here.
            if (mode == Mode.CANDIDATE) {
                if (localNodeMayWinElection(getLastAcceptedState()) == false) {
                    logger.trace("skip election as local node may not win it: {}", getLastAcceptedState().coordinationMetaData());
                    return;
                }

                final StartJoinRequest startJoinRequest
                    = new StartJoinRequest(getLocalNode(), Math.max(getCurrentTerm(), maxTermSeen) + 1); // term+1  开始选举时候都会自己增加term1
                logger.debug("starting election with {}", startJoinRequest);
                getDiscoveredNodes().forEach(node -> joinHelper.sendStartJoinRequest(startJoinRequest, node));
            }//只用向每个peer节点发送，你们可以加入我这个集群了，快来向我发送加入请求吧
        }
    }

    private void abdicateTo(DiscoveryNode newMaster) {
        assert Thread.holdsLock(mutex);
        assert mode == Mode.LEADER : "expected to be leader on abdication but was " + mode;
        assert newMaster.isMasterNode() : "should only abdicate to master-eligible node but was " + newMaster;
        final StartJoinRequest startJoinRequest = new StartJoinRequest(newMaster, Math.max(getCurrentTerm(), maxTermSeen) + 1);
        logger.info("abdicating to {} with term {}", newMaster, startJoinRequest.getTerm());
        getLastAcceptedState().nodes().mastersFirstStream().forEach(node -> joinHelper.sendStartJoinRequest(startJoinRequest, node));
        // handling of start join messages on the local node will be dispatched to the generic thread-pool
        assert mode == Mode.LEADER : "should still be leader after sending abdication messages " + mode;
        // explicitly move node to candidate state so that the next cluster state update task yields an onNoLongerMaster event
        becomeCandidate("after abdicating to " + newMaster);
    }

    private static boolean localNodeMayWinElection(ClusterState lastAcceptedState) {
        final DiscoveryNode localNode = lastAcceptedState.nodes().getLocalNode();
        assert localNode != null;
        return nodeMayWinElection(lastAcceptedState, localNode);
    }
    // 全局同步里包含该节点||持久化中包含该节点||本节点没有被上次master排除在外
    private static boolean nodeMayWinElection(ClusterState lastAcceptedState, DiscoveryNode node) {
        final String nodeId = node.getId();
        return lastAcceptedState.getLastCommittedConfiguration().getNodeIds().contains(nodeId) //包含本节点
            || lastAcceptedState.getLastAcceptedConfiguration().getNodeIds().contains(nodeId)
            || lastAcceptedState.getVotingConfigExclusions().stream().noneMatch(vce -> vce.getNodeId().equals(nodeId)); // 本节点没被排除在外
    }
    // // 将请求term与本地term比较，确保本地term最小
    private Optional<Join> ensureTermAtLeast(DiscoveryNode sourceNode, long targetTerm) {
        assert Thread.holdsLock(mutex) : "Coordinator mutex not held";
        if (getCurrentTerm() < targetTerm) { // 只有对方比自己低，我们才会去构建选票
            return Optional.of(joinLeaderInTerm(new StartJoinRequest(sourceNode, targetTerm)));
        }
        return Optional.empty();
    }
    // 此时一定是本地term小于目标term。那么本节点就只能变成coordinator了
    private Join joinLeaderInTerm(StartJoinRequest startJoinRequest) {
        synchronized (mutex) {
            logger.debug("joinLeaderInTerm: for [{}] with term {}", startJoinRequest.getSourceNode(), startJoinRequest.getTerm());
            final Join join = coordinationState.get().handleStartJoin(startJoinRequest); // 构建选票，若远程term比本地小，那么直接抛出异常
            lastJoin = Optional.of(join);
            peerFinder.setCurrentTerm(getCurrentTerm());
            if (mode != Mode.CANDIDATE) {
                becomeCandidate("joinLeaderInTerm"); // updates followersChecker and preVoteCollector
            } else {
                followersChecker.updateFastResponseState(getCurrentTerm(), mode);
                preVoteCollector.update(getPreVoteResponse(), null); // 更新本地回复
            }
            return join;
        }
    }

    // 接收到来自coordinator的请求，说要加入集群。
    private void handleJoinRequest(JoinRequest joinRequest, JoinHelper.JoinCallback joinCallback) {
        assert Thread.holdsLock(mutex) == false;
        assert getLocalNode().isMasterNode() : getLocalNode() + " received a join but is not master-eligible";
        logger.trace("handleJoinRequest: as {}, handling {}", mode, joinRequest);

        if (singleNodeDiscovery && joinRequest.getSourceNode().equals(getLocalNode()) == false) { //为false
            joinCallback.onFailure(new IllegalStateException("cannot join node with [" + DiscoveryModule.DISCOVERY_TYPE_SETTING.getKey() +
                "] set to [" + DiscoveryModule.SINGLE_NODE_DISCOVERY_TYPE  + "] discovery"));
            return;
        }

        transportService.connectToNode(joinRequest.getSourceNode(), ActionListener.wrap(ignore -> { // 和数据节点连接连接
            final ClusterState stateForJoinValidation = getStateForMasterService();

            if (stateForJoinValidation.nodes().isLocalNodeElectedMaster()) {  // 当前节点已经是master了
                onJoinValidators.forEach(a -> a.accept(joinRequest.getSourceNode(), stateForJoinValidation));
                if (stateForJoinValidation.getBlocks().hasGlobalBlock(STATE_NOT_RECOVERED_BLOCK) == false) {
                    // we do this in a couple of places including the cluster update thread. This one here is really just best effort
                    // to ensure we fail as fast as possible.
                    JoinTaskExecutor.ensureMajorVersionBarrier(joinRequest.getSourceNode().getVersion(),
                        stateForJoinValidation.getNodes().getMinNodeVersion());
                }// master在收到data发送的加入集群的请求时，发送自己的请求，验证clusterUUId,接收到请求后，再响应data加入集群的请求
                sendValidateJoinRequest(stateForJoinValidation, joinRequest, joinCallback);
            } else {// 当前节点正在竞选leader
                processJoinRequest(joinRequest, joinCallback); // 等master向data发送请求响应后，master才会去继续响应之前的data的请求
            }
        }, joinCallback::onFailure));
    }

    // package private for tests
    void sendValidateJoinRequest(ClusterState stateForJoinValidation, JoinRequest joinRequest,
                                 JoinHelper.JoinCallback joinCallback) {
        // validate the join on the joining node, will throw a failure if it fails the validation// 发送请求给peer验证准master节点版本是ok的，
        joinHelper.sendValidateJoinRequest(joinRequest.getSourceNode(), stateForJoinValidation, new ActionListener<Empty>() {
            @Override
            public void onResponse(Empty empty) { // peer ok的话，就回复为null
                try {
                    processJoinRequest(joinRequest, joinCallback);
                } catch (Exception e) {
                    joinCallback.onFailure(e);
                }
            }

            @Override
            public void onFailure(Exception e) {
                logger.warn(() -> new ParameterizedMessage("failed to validate incoming join request from node [{}]",
                    joinRequest.getSourceNode()), e);
                joinCallback.onFailure(new IllegalStateException("failure when sending a validation request to node", e));
            }
        });
    }
    // data想加入集群，准master向peer发送准master版本，peer没问题了，就会向master发送join请求
    private void processJoinRequest(JoinRequest joinRequest, JoinHelper.JoinCallback joinCallback) {
        final Optional<Join> optionalJoin = joinRequest.getOptionalJoin(); // 为空
        synchronized (mutex) {
            final CoordinationState coordState = coordinationState.get();
            final boolean prevElectionWon = coordState.electionWon(); // 为true

            optionalJoin.ifPresent(this::handleJoin);// 也挺重要，会对个数比例判断
            joinAccumulator.handleJoinRequest(joinRequest.getSourceNode(), joinCallback); // 向全局同步，增加节点的publish:   node-join
            //LeaderJoinAccumulator
            if (prevElectionWon == false && coordState.electionWon()) { // 准master变成leader角色
                becomeLeader("handleJoinRequest");
            }
        }
    }
    // 主要是做环境初始化工作，停掉或去除任何不符合 Candidate 角色的操作和状态，创建符合新的候选人角色环境。
    void becomeCandidate(String method) { // 主要是做环境初始化工作，停掉或去除任何不符合 Candidate 角色的操作和状态，创建符合新的候选人角色环境
        assert Thread.holdsLock(mutex) : "Coordinator mutex not held";
        logger.debug("{}: coordinator becoming CANDIDATE in term {} (was {}, lastKnownLeader was [{}])",
            method, getCurrentTerm(), mode, lastKnownLeader);

        if (mode != Mode.CANDIDATE) { // 首先把自己变成Candidate，选举完成后才确定自己是leader还是follower
            final Mode prevMode = mode;
            mode = Mode.CANDIDATE;
            cancelActivePublication("become candidate: " + method); // 取消正在进行的publish操作
            joinAccumulator.close(mode); // 取消  master投票累计
            joinAccumulator = joinHelper.new CandidateJoinAccumulator(); // 建一个新的candidate累计统计器

            peerFinder.activate(coordinationState.get().getLastAcceptedState().nodes()); // 要进去看下, 仅有节点本身（尝试激活这些peer）
            clusterFormationFailureHelper.start(); // 集群选举失败日志打印器

            leaderChecker.setCurrentNodes(DiscoveryNodes.EMPTY_NODES); // 去掉全部节点的check
            leaderChecker.updateLeader(null);

            followersChecker.clearCurrentNodes();
            followersChecker.updateFastResponseState(getCurrentTerm(), mode);
            lagDetector.clearTrackedNodes();

            if (prevMode == Mode.LEADER) { // 之前是leader,则有问题
                cleanMasterService();
            }

            if (applierState.nodes().getMasterNodeId() != null) {
                applierState = clusterStateWithNoMasterBlock(applierState);
                clusterApplier.onNewClusterState("becoming candidate: " + method, () -> applierState, (source, e) -> {
                });
            }
        }

        preVoteCollector.update(getPreVoteResponse(), null); // 这里会初始化
    }
    // 本节点选票够了，就变成leader
    void becomeLeader(String method) {
        assert Thread.holdsLock(mutex) : "Coordinator mutex not held";
        assert mode == Mode.CANDIDATE : "expected candidate but was " + mode;
        assert getLocalNode().isMasterNode() : getLocalNode() + " became a leader but is not master-eligible";

        logger.debug("{}: coordinator becoming LEADER in term {} (was {}, lastKnownLeader was [{}])",
            method, getCurrentTerm(), mode, lastKnownLeader);

        mode = Mode.LEADER;
        joinAccumulator.close(mode); // 这里会提修改元数据的submit,
        joinAccumulator = joinHelper.new LeaderJoinAccumulator();

        lastKnownLeader = Optional.of(getLocalNode());
        peerFinder.deactivate(getLocalNode()); //不应在进行不停地探测peer节点了
        clusterFormationFailureHelper.stop(); //暂停选不出master日志打印
        closePrevotingAndElectionScheduler(); // 停止退避选举的进程
        preVoteCollector.update(getPreVoteResponse(), getLocalNode());

        assert leaderChecker.leader() == null : leaderChecker.leader();
        followersChecker.updateFastResponseState(getCurrentTerm(), mode);
    }

    void becomeFollower(String method, DiscoveryNode leaderNode) {
        assert Thread.holdsLock(mutex) : "Coordinator mutex not held";
        assert leaderNode.isMasterNode() : leaderNode + " became a leader but is not master-eligible";
        assert mode != Mode.LEADER : "do not switch to follower from leader (should be candidate first)";

        if (mode == Mode.FOLLOWER && Optional.of(leaderNode).equals(lastKnownLeader)) {
            logger.trace("{}: coordinator remaining FOLLOWER of [{}] in term {}",
                method, leaderNode, getCurrentTerm());
        } else {
            logger.debug("{}: coordinator becoming FOLLOWER of [{}] in term {} (was {}, lastKnownLeader was [{}])",
                method, leaderNode, getCurrentTerm(), mode, lastKnownLeader);
        }

        final boolean restartLeaderChecker = (mode == Mode.FOLLOWER && Optional.of(leaderNode).equals(lastKnownLeader)) == false;

        if (mode != Mode.FOLLOWER) {
            mode = Mode.FOLLOWER;
            joinAccumulator.close(mode);
            joinAccumulator = new JoinHelper.FollowerJoinAccumulator();
            leaderChecker.setCurrentNodes(DiscoveryNodes.EMPTY_NODES);
        }

        lastKnownLeader = Optional.of(leaderNode);
        peerFinder.deactivate(leaderNode); // 不用再去进行
        clusterFormationFailureHelper.stop();
        closePrevotingAndElectionScheduler();
        cancelActivePublication("become follower: " + method);
        preVoteCollector.update(getPreVoteResponse(), leaderNode);

        if (restartLeaderChecker) {
            leaderChecker.updateLeader(leaderNode);
        }

        followersChecker.clearCurrentNodes();
        followersChecker.updateFastResponseState(getCurrentTerm(), mode);
        lagDetector.clearTrackedNodes();
    }

    private void cleanMasterService() {
        masterService.submitStateUpdateTask("clean-up after stepping down as master",
            new LocalClusterUpdateTask() {
                @Override
                public void onFailure(String source, Exception e) {
                    // ignore
                    logger.trace("failed to clean-up after stepping down as master", e);
                }

                @Override
                public ClusterTasksResult<LocalClusterUpdateTask> execute(ClusterState currentState) {
                    if (currentState.nodes().isLocalNodeElectedMaster() == false) {
                        allocationService.cleanCaches();
                    }
                    return unchanged();
                }

            });
    }

    private PreVoteResponse getPreVoteResponse() {
        return new PreVoteResponse(getCurrentTerm(), coordinationState.get().getLastAcceptedTerm(),
            coordinationState.get().getLastAcceptedState().version());
    }

    // package-visible for testing
    long getCurrentTerm() {
        synchronized (mutex) {
            return coordinationState.get().getCurrentTerm();
        }
    }

    // package-visible for testing
    Mode getMode() {
        synchronized (mutex) {
            return mode;
        }
    }

    // visible for testing
    DiscoveryNode getLocalNode() {
        return transportService.getLocalNode();
    }

    // package-visible for testing
    boolean publicationInProgress() {
        synchronized (mutex) {
            return currentPublication.isPresent();
        }
    }

    @Override
    protected void doStart() {
        synchronized (mutex) {
            CoordinationState.PersistedState persistedState = persistedStateSupplier.get();
            coordinationState.set(new CoordinationState(getLocalNode(), persistedState, electionStrategy));
            peerFinder.setCurrentTerm(getCurrentTerm()); // 从集群mainfest中获取current_term
            configuredHostsResolver.start();
            final ClusterState lastAcceptedState = coordinationState.get().getLastAcceptedState();
            if (lastAcceptedState.metaData().clusterUUIDCommitted()) {
                logger.info("cluster UUID [{}]", lastAcceptedState.metaData().clusterUUID());
            }
            final VotingConfiguration votingConfiguration = lastAcceptedState.getLastCommittedConfiguration();
            if (singleNodeDiscovery &&
                votingConfiguration.isEmpty() == false &&
                votingConfiguration.hasQuorum(Collections.singleton(getLocalNode().getId())) == false) {
                throw new IllegalStateException("cannot start with [" + DiscoveryModule.DISCOVERY_TYPE_SETTING.getKey() + "] set to [" +
                    DiscoveryModule.SINGLE_NODE_DISCOVERY_TYPE + "] when local node " + getLocalNode() +
                    " does not have quorum in voting configuration " + votingConfiguration);
            }
            ClusterState initialState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.get(settings))
                .blocks(ClusterBlocks.builder()
                    .addGlobalBlock(STATE_NOT_RECOVERED_BLOCK)
                    .addGlobalBlock(noMasterBlockService.getNoMasterBlock()))
                .nodes(DiscoveryNodes.builder().add(getLocalNode()).localNodeId(getLocalNode().getId()))// 只是添加的本节点
                .build();
            applierState = initialState;
            clusterApplier.setInitialState(initialState);
        }
    }

    @Override
    public DiscoveryStats stats() {
        return new DiscoveryStats(new PendingClusterStateStats(0, 0, 0), publicationHandler.stats());
    }

    @Override
    public void startInitialJoin() {  // 这是是加入集群的其实位置，开始啦
        synchronized (mutex) {
            becomeCandidate("startInitialJoin"); // 开始想办法加入集群
        }
        clusterBootstrapService.scheduleUnconfiguredBootstrap(); // 其实啥也没用
    }

    @Override
    protected void doStop() {
        configuredHostsResolver.stop();
    }

    @Override
    protected void doClose() {
        final CoordinationState coordinationState = this.coordinationState.get();
        if (coordinationState != null) {
            // This looks like a race that might leak an unclosed CoordinationState if it's created while execution is here, but this method
            // is synchronized on AbstractLifecycleComponent#lifestyle, as is the doStart() method that creates the CoordinationState, so
            // it's all ok.
            synchronized (mutex) {
                coordinationState.close();
            }
        }
    }

    public void invariant() {
        synchronized (mutex) {
            final Optional<DiscoveryNode> peerFinderLeader = peerFinder.getLeader();
            assert peerFinder.getCurrentTerm() == getCurrentTerm();
            assert followersChecker.getFastResponseState().term == getCurrentTerm() : followersChecker.getFastResponseState();
            assert followersChecker.getFastResponseState().mode == getMode() : followersChecker.getFastResponseState();
            assert (applierState.nodes().getMasterNodeId() == null) == applierState.blocks().hasGlobalBlockWithId(NO_MASTER_BLOCK_ID);
            assert preVoteCollector.getPreVoteResponse().equals(getPreVoteResponse())
                : preVoteCollector + " vs " + getPreVoteResponse();

            assert lagDetector.getTrackedNodes().contains(getLocalNode()) == false : lagDetector.getTrackedNodes();
            assert followersChecker.getKnownFollowers().equals(lagDetector.getTrackedNodes())
                : followersChecker.getKnownFollowers() + " vs " + lagDetector.getTrackedNodes();

            if (mode == Mode.LEADER) {
                final boolean becomingMaster = getStateForMasterService().term() != getCurrentTerm();

                assert coordinationState.get().electionWon();
                assert lastKnownLeader.isPresent() && lastKnownLeader.get().equals(getLocalNode());
                assert joinAccumulator instanceof JoinHelper.LeaderJoinAccumulator;
                assert peerFinderLeader.equals(lastKnownLeader) : peerFinderLeader;
                assert electionScheduler == null : electionScheduler;
                assert prevotingRound == null : prevotingRound;
                assert becomingMaster || getStateForMasterService().nodes().getMasterNodeId() != null : getStateForMasterService();
                assert leaderChecker.leader() == null : leaderChecker.leader();
                assert getLocalNode().equals(applierState.nodes().getMasterNode()) ||
                    (applierState.nodes().getMasterNodeId() == null && applierState.term() < getCurrentTerm());
                assert preVoteCollector.getLeader() == getLocalNode() : preVoteCollector;
                assert clusterFormationFailureHelper.isRunning() == false;

                final boolean activePublication = currentPublication.map(CoordinatorPublication::isActiveForCurrentLeader).orElse(false);
                if (becomingMaster && activePublication == false) {
                    // cluster state update task to become master is submitted to MasterService, but publication has not started yet
                    assert followersChecker.getKnownFollowers().isEmpty() : followersChecker.getKnownFollowers();
                } else {
                    final ClusterState lastPublishedState;
                    if (activePublication) {
                        // active publication in progress: followersChecker is up-to-date with nodes that we're actively publishing to
                        lastPublishedState = currentPublication.get().publishedState();
                    } else {
                        // no active publication: followersChecker is up-to-date with the nodes of the latest publication
                        lastPublishedState = coordinationState.get().getLastAcceptedState();
                    }
                    final Set<DiscoveryNode> lastPublishedNodes = new HashSet<>();
                    lastPublishedState.nodes().forEach(lastPublishedNodes::add);
                    assert lastPublishedNodes.remove(getLocalNode()); // followersChecker excludes local node
                    assert lastPublishedNodes.equals(followersChecker.getKnownFollowers()) :
                        lastPublishedNodes + " != " + followersChecker.getKnownFollowers();
                }

                assert becomingMaster || activePublication ||
                    coordinationState.get().getLastAcceptedConfiguration().equals(coordinationState.get().getLastCommittedConfiguration())
                    : coordinationState.get().getLastAcceptedConfiguration() + " != "
                    + coordinationState.get().getLastCommittedConfiguration();
            } else if (mode == Mode.FOLLOWER) {
                assert coordinationState.get().electionWon() == false : getLocalNode() + " is FOLLOWER so electionWon() should be false";
                assert lastKnownLeader.isPresent() && (lastKnownLeader.get().equals(getLocalNode()) == false);
                assert joinAccumulator instanceof JoinHelper.FollowerJoinAccumulator;
                assert peerFinderLeader.equals(lastKnownLeader) : peerFinderLeader;
                assert electionScheduler == null : electionScheduler;
                assert prevotingRound == null : prevotingRound;
                assert getStateForMasterService().nodes().getMasterNodeId() == null : getStateForMasterService();
                assert leaderChecker.currentNodeIsMaster() == false;
                assert lastKnownLeader.equals(Optional.of(leaderChecker.leader()));
                assert followersChecker.getKnownFollowers().isEmpty();
                assert lastKnownLeader.get().equals(applierState.nodes().getMasterNode()) ||
                    (applierState.nodes().getMasterNodeId() == null &&
                        (applierState.term() < getCurrentTerm() || applierState.version() < getLastAcceptedState().version()));
                assert currentPublication.map(Publication::isCommitted).orElse(true);
                assert preVoteCollector.getLeader().equals(lastKnownLeader.get()) : preVoteCollector;
                assert clusterFormationFailureHelper.isRunning() == false;
            } else {
                assert mode == Mode.CANDIDATE;
                assert joinAccumulator instanceof JoinHelper.CandidateJoinAccumulator;
                assert peerFinderLeader.isPresent() == false : peerFinderLeader;
                assert prevotingRound == null || electionScheduler != null;
                assert getStateForMasterService().nodes().getMasterNodeId() == null : getStateForMasterService();
                assert leaderChecker.currentNodeIsMaster() == false;
                assert leaderChecker.leader() == null : leaderChecker.leader();
                assert followersChecker.getKnownFollowers().isEmpty();
                assert applierState.nodes().getMasterNodeId() == null;
                assert currentPublication.map(Publication::isCommitted).orElse(true);
                assert preVoteCollector.getLeader() == null : preVoteCollector;
                assert clusterFormationFailureHelper.isRunning();
            }
        }
    }

    public boolean isInitialConfigurationSet() {
        return getStateForMasterService().getLastAcceptedConfiguration().isEmpty() == false;
    }

    /**
     * Sets the initial configuration to the given {@link VotingConfiguration}. This method is safe to call
     * more than once, as long as the argument to each call is the same.
     *
     * @param votingConfiguration The nodes that should form the initial configuration.
     * @return whether this call successfully set the initial configuration - if false, the cluster has already been bootstrapped.
     */  // 节点初始化时候会调用     // 是否meta中已经有上次投票资格的记录
    public boolean setInitialConfiguration(final VotingConfiguration votingConfiguration) {
        synchronized (mutex) {
            final ClusterState currentState = getStateForMasterService();

            if (isInitialConfigurationSet()) {
                logger.debug("initial configuration already set, ignoring {}", votingConfiguration);
                return false;
            }

            if (getLocalNode().isMasterNode() == false) { // 本节点是否是master节点
                logger.debug("skip setting initial configuration as local node is not a master-eligible node");
                throw new CoordinationStateRejectedException(
                    "this node is not master-eligible, but cluster bootstrapping can only happen on a master-eligible node");
            }

            if (votingConfiguration.getNodeIds().contains(getLocalNode().getId()) == false) { // 投票节点是否包含本地节点
                logger.debug("skip setting initial configuration as local node is not part of initial configuration");
                throw new CoordinationStateRejectedException("local node is not part of initial configuration");
            }

            final List<DiscoveryNode> knownNodes = new ArrayList<>();
            knownNodes.add(getLocalNode());
            peerFinder.getFoundPeers().forEach(knownNodes::add);

            if (votingConfiguration.hasQuorum(knownNodes.stream().map(DiscoveryNode::getId).collect(Collectors.toList())) == false) {
                logger.debug("skip setting initial configuration as not enough nodes discovered to form a quorum in the " +
                    "initial configuration [knownNodes={}, {}]", knownNodes, votingConfiguration);
                throw new CoordinationStateRejectedException("not enough nodes discovered to form a quorum in the initial configuration " +
                    "[knownNodes=" + knownNodes + ", " + votingConfiguration + "]");
            }

            logger.info("setting initial configuration to {}", votingConfiguration);
            final CoordinationMetaData coordinationMetaData = CoordinationMetaData.builder(currentState.coordinationMetaData())
                .lastAcceptedConfiguration(votingConfiguration)
                .lastCommittedConfiguration(votingConfiguration)
                .build();

            MetaData.Builder metaDataBuilder = MetaData.builder(currentState.metaData());
            // automatically generate a UID for the metadata if we need to
            metaDataBuilder.generateClusterUuidIfNeeded(); // TODO generate UUID in bootstrapping tool?
            metaDataBuilder.coordinationMetaData(coordinationMetaData);

            coordinationState.get().setInitialState(ClusterState.builder(currentState).metaData(metaDataBuilder).build());
            assert localNodeMayWinElection(getLastAcceptedState()) :
                "initial state does not allow local node to win election: " + getLastAcceptedState().coordinationMetaData();
            preVoteCollector.update(getPreVoteResponse(), null); // pick up the change to last-accepted version
            startElectionScheduler(); // 执行选举过程
            return true;
        }
    }

    // Package-private for testing
    ClusterState improveConfiguration(ClusterState clusterState) {
        assert Thread.holdsLock(mutex) : "Coordinator mutex not held";

        // exclude any nodes whose ID is in the voting config exclusions list ...
        final Stream<String> excludedNodeIds = clusterState.getVotingConfigExclusions().stream().map(VotingConfigExclusion::getNodeId);
        // ... and also automatically exclude the node IDs of master-ineligible nodes that were previously master-eligible and are still in
        // the voting config. We could exclude all the master-ineligible nodes here, but there could be quite a few of them and that makes
        // the logging much harder to follow.
        final Stream<String> masterIneligibleNodeIdsInVotingConfig = StreamSupport.stream(clusterState.nodes().spliterator(), false)
            .filter(n -> n.isMasterNode() == false
                && (clusterState.getLastAcceptedConfiguration().getNodeIds().contains(n.getId())
                || clusterState.getLastCommittedConfiguration().getNodeIds().contains(n.getId())))
            .map(DiscoveryNode::getId);

        final Set<DiscoveryNode> liveNodes = StreamSupport.stream(clusterState.nodes().spliterator(), false)
            .filter(DiscoveryNode::isMasterNode).filter(coordinationState.get()::containsJoinVoteFor).collect(Collectors.toSet());
        final VotingConfiguration newConfig = reconfigurator.reconfigure(liveNodes,
            Stream.concat(masterIneligibleNodeIdsInVotingConfig, excludedNodeIds).collect(Collectors.toSet()),
            getLocalNode(), clusterState.getLastAcceptedConfiguration());

        if (newConfig.equals(clusterState.getLastAcceptedConfiguration()) == false) {
            assert coordinationState.get().joinVotesHaveQuorumFor(newConfig);
            return ClusterState.builder(clusterState).metaData(MetaData.builder(clusterState.metaData())
                .coordinationMetaData(CoordinationMetaData.builder(clusterState.coordinationMetaData())
                    .lastAcceptedConfiguration(newConfig).build())).build();
        }
        return clusterState;
    }

    private AtomicBoolean reconfigurationTaskScheduled = new AtomicBoolean();

    private void scheduleReconfigurationIfNeeded() {
        assert Thread.holdsLock(mutex) : "Coordinator mutex not held";
        assert mode == Mode.LEADER : mode;
        assert currentPublication.isPresent() == false : "Expected no publication in progress";

        final ClusterState state = getLastAcceptedState();
        if (improveConfiguration(state) != state && reconfigurationTaskScheduled.compareAndSet(false, true)) {
            logger.trace("scheduling reconfiguration");
            masterService.submitStateUpdateTask("reconfigure", new ClusterStateUpdateTask(Priority.URGENT) {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    reconfigurationTaskScheduled.set(false);
                    synchronized (mutex) {
                        return improveConfiguration(currentState);
                    }
                }

                @Override
                public void onFailure(String source, Exception e) {
                    reconfigurationTaskScheduled.set(false);
                    logger.debug("reconfiguration failed", e);
                }
            });
        }
    }

    // exposed for tests
    boolean missingJoinVoteFrom(DiscoveryNode node) {
        return node.isMasterNode() && coordinationState.get().containsJoinVoteFor(node) == false;
    }
   // 候选人处理投票请求
    private void handleJoin(Join join) {
        synchronized (mutex) {
            ensureTermAtLeast(getLocalNode(), join.getTerm()).ifPresent(this::handleJoin);// 还是对 term 再确认判断

            if (coordinationState.get().electionWon()) { // 是leader了
                // If we have already won the election then the actual join does not matter for election purposes, so swallow any exception
                final boolean isNewJoinFromMasterEligibleNode = handleJoinIgnoringExceptions(join);

                // If we haven't completely finished becoming master then there's already a publication scheduled which will, in turn,
                // schedule a reconfiguration if needed. It's benign to schedule a reconfiguration anyway, but it might fail if it wins the
                // race against the election-winning publication and log a big error message, which we can prevent by checking this here:
                final boolean establishedAsMaster = mode == Mode.LEADER && getLastAcceptedState().term() == getCurrentTerm();
                if (isNewJoinFromMasterEligibleNode && establishedAsMaster && publicationInProgress() == false) {
                    scheduleReconfigurationIfNeeded();
                }
            } else {// 还不是leader了
                coordinationState.get().handleJoin(join); // this might fail and bubble up the exception
            }
        }
    }

    /**
     * @return true iff the join was from a new node and was successfully added
     */
    private boolean handleJoinIgnoringExceptions(Join join) { // 已经选举完了，任何新的投票都没啥意义了，就丢弃异常
        try {
            return coordinationState.get().handleJoin(join);
        } catch (CoordinationStateRejectedException e) {
            logger.debug(new ParameterizedMessage("failed to add {} - ignoring", join), e);
            return false;
        }
    }

    public ClusterState getLastAcceptedState() {
        synchronized (mutex) {
            return coordinationState.get().getLastAcceptedState();
        }
    }

    @Nullable
    public ClusterState getApplierState() {
        return applierState;
    }

    private List<DiscoveryNode> getDiscoveredNodes() {
        final List<DiscoveryNode> nodes = new ArrayList<>();
        nodes.add(getLocalNode());
        peerFinder.getFoundPeers().forEach(nodes::add);
        return nodes;
    }

    ClusterState getStateForMasterService() {// 从持久化中获取本节点的ClusterState
        synchronized (mutex) {
            // expose last accepted cluster state as base state upon which the master service
            // speculatively calculates the next cluster state update
            final ClusterState clusterState = coordinationState.get().getLastAcceptedState();
            if (mode != Mode.LEADER || clusterState.term() != getCurrentTerm()) {
                // the master service checks if the local node is the master node in order to fail execution of the state update early
                return clusterStateWithNoMasterBlock(clusterState); //
            }
            return clusterState;
        }
    }

    private ClusterState clusterStateWithNoMasterBlock(ClusterState clusterState) {
        if (clusterState.nodes().getMasterNodeId() != null) { // 若不是master节点
            // remove block if it already exists before adding new one
            assert clusterState.blocks().hasGlobalBlockWithId(NO_MASTER_BLOCK_ID) == false :
                "NO_MASTER_BLOCK should only be added by Coordinator";
            final ClusterBlocks clusterBlocks = ClusterBlocks.builder().blocks(clusterState.blocks()).addGlobalBlock(
                noMasterBlockService.getNoMasterBlock()).build();
            final DiscoveryNodes discoveryNodes = new DiscoveryNodes.Builder(clusterState.nodes()).masterNodeId(null).build();
            return ClusterState.builder(clusterState).blocks(clusterBlocks).nodes(discoveryNodes).build(); // 更新
        } else {
            return clusterState;
        }
    }
    //每次publis时候都会更新心跳节点
    @Override
    public void publish(ClusterChangedEvent clusterChangedEvent, ActionListener<Void> publishListener, AckListener ackListener) {
        try { // 跑到这里
            synchronized (mutex) {
                if (mode != Mode.LEADER || getCurrentTerm() != clusterChangedEvent.state().term()) { // 会跳过
                    logger.debug(() -> new ParameterizedMessage("[{}] failed publication as node is no longer master for term {}",
                        clusterChangedEvent.source(), clusterChangedEvent.state().term()));
                    publishListener.onFailure(new FailedToCommitClusterStateException("node is no longer master for term " +
                        clusterChangedEvent.state().term() + " while handling publication"));
                    return;
                }

                if (currentPublication.isPresent()) { //
                    assert false : "[" + currentPublication.get() + "] in progress, cannot start new publication";
                    logger.warn(() -> new ParameterizedMessage("[{}] failed publication as already publication in progress",
                        clusterChangedEvent.source()));
                    publishListener.onFailure(new FailedToCommitClusterStateException("publication " + currentPublication.get() +
                        " already in progress"));
                    return;
                }

                assert assertPreviousStateConsistency(clusterChangedEvent);

                final ClusterState clusterState = clusterChangedEvent.state(); // 新的state

                assert getLocalNode().equals(clusterState.getNodes().get(getLocalNode().getId())) :
                    getLocalNode() + " should be in published " + clusterState;

                final PublicationTransportHandler.PublicationContext publicationContext =
                    publicationHandler.newPublicationContext(clusterChangedEvent);

                final PublishRequest publishRequest = coordinationState.get().handleClientValue(clusterState);
                final CoordinatorPublication publication = new CoordinatorPublication(publishRequest, publicationContext,
                    new ListenableFuture<>(), ackListener, publishListener);
                currentPublication = Optional.of(publication);

                final DiscoveryNodes publishNodes = publishRequest.getAcceptedState().nodes(); // 所有的节点
                leaderChecker.setCurrentNodes(publishNodes);
                followersChecker.setCurrentNodes(publishNodes); // 开始真正和data节点打交道
                lagDetector.setTrackedNodes(publishNodes);
                publication.start(followersChecker.getFaultyNodes()); // 开始publish, 还没添加超时监听器
            }
        } catch (Exception e) {
            logger.debug(() -> new ParameterizedMessage("[{}] publishing failed", clusterChangedEvent.source()), e);
            publishListener.onFailure(new FailedToCommitClusterStateException("publishing failed", e));
        }
    }

    // there is no equals on cluster state, so we just serialize it to XContent and compare Maps
    // deserialized from the resulting JSON
    private boolean assertPreviousStateConsistency(ClusterChangedEvent event) {
        assert event.previousState() == coordinationState.get().getLastAcceptedState() ||
            XContentHelper.convertToMap(
                JsonXContent.jsonXContent, Strings.toString(event.previousState()), false
            ).equals(
                XContentHelper.convertToMap(
                    JsonXContent.jsonXContent,
                    Strings.toString(clusterStateWithNoMasterBlock(coordinationState.get().getLastAcceptedState())),
                    false))
            : Strings.toString(event.previousState()) + " vs "
            + Strings.toString(clusterStateWithNoMasterBlock(coordinationState.get().getLastAcceptedState()));
        return true;
    }

    private <T> ActionListener<T> wrapWithMutex(ActionListener<T> listener) {
        return new ActionListener<T>() {
            @Override
            public void onResponse(T t) {
                synchronized (mutex) {
                    listener.onResponse(t);
                }
            }

            @Override
            public void onFailure(Exception e) {
                synchronized (mutex) {
                    listener.onFailure(e);
                }
            }
        };
    }

    private void cancelActivePublication(String reason) {
        assert Thread.holdsLock(mutex) : "Coordinator mutex not held";
        if (currentPublication.isPresent()) { //判断是否有值，默认为Empty
            currentPublication.get().cancel(reason);
        }
    }

    public Collection<BiConsumer<DiscoveryNode, ClusterState>> getOnJoinValidators() {
        return onJoinValidators;
    }

    public enum Mode {
        CANDIDATE, LEADER, FOLLOWER // Raft协议三个角色 follower->condidate->leader
    }

    private class CoordinatorPeerFinder extends PeerFinder {

        CoordinatorPeerFinder(Settings settings, TransportService transportService, TransportAddressConnector transportAddressConnector,
                              ConfiguredHostsResolver configuredHostsResolver) {
            super(settings, transportService, transportAddressConnector,
                singleNodeDiscovery ? hostsResolver -> Collections.emptyList() : configuredHostsResolver);
        }
        // 若发现了存已经存在的master
        @Override
        protected void onActiveMasterFound(DiscoveryNode masterNode, long term) {
            synchronized (mutex) {
                ensureTermAtLeast(masterNode, term);
                joinHelper.sendJoinRequest(masterNode, joinWithDestination(lastJoin, masterNode, term)); // 发出加入master请求
            }
        }

        @Override
        protected void startProbe(TransportAddress transportAddress) {
            if (singleNodeDiscovery == false) {
                super.startProbe(transportAddress);
            }
        }
        // 当和某个节点建立了连接，并根据这个节点获取了它所知道的集群节点， 就会调用一次该函数
        @Override
        protected void onFoundPeersUpdated() {
            synchronized (mutex) {
                final Iterable<DiscoveryNode> foundPeers = getFoundPeers();
                if (mode == Mode.CANDIDATE) { // 当自己是candidate角色时，那么才开始发起投票
                    final VoteCollection expectedVotes = new VoteCollection();
                    foundPeers.forEach(expectedVotes::addVote);
                    expectedVotes.addVote(Coordinator.this.getLocalNode()); // 自己也投一票
                    final boolean foundQuorum = coordinationState.get().isElectionQuorum(expectedVotes); //  并没有针对term大小的判断

                    if (foundQuorum) { // 目前可以选举了, 至少peer里面master已经超过一半旧值了
                        if (electionScheduler == null) { //只能有一个跑
                            startElectionScheduler(); // 开始选举scheduler
                        }
                    } else {// 若不足多数的时候，需要停止掉目前的选举过程。这里比较蛋疼，
                        closePrevotingAndElectionScheduler();
                    }
                }
            }

            clusterBootstrapService.onFoundPeersUpdated();
        }
    }
    // 探测集群，发现没有master节点，那么就开始开始选举
    private void startElectionScheduler() { // 开始周期性选举
        assert electionScheduler == null : electionScheduler;

        if (getLocalNode().isMasterNode() == false) { // 若本节点不具有master属性，那么直接退出
            return;
        }

        final TimeValue gracePeriod = TimeValue.ZERO; // TODO variable grace period
        electionScheduler = electionSchedulerFactory.startElectionScheduler(gracePeriod, new Runnable() { //
            @Override
            public void run() {
                synchronized (mutex) {// 避免选举期间并行执行
                    if (mode == Mode.CANDIDATE) { // 当且自己处于candinator
                        final ClusterState lastAcceptedState = coordinationState.get().getLastAcceptedState();

                        if (localNodeMayWinElection(lastAcceptedState) == false) {
                            logger.trace("skip prevoting as local node may not win election: {}",
                                lastAcceptedState.coordinationMetaData());
                            return;
                        }

                        if (prevotingRound != null) {
                            prevotingRound.close();
                        }
                        prevotingRound = preVoteCollector.start(lastAcceptedState, getDiscoveredNodes());
                    }
                }
            }

            @Override
            public String toString() {
                return "scheduling of new prevoting round";
            }
        });
    }

    public Iterable<DiscoveryNode> getFoundPeers() {
        // TODO everyone takes this and adds the local node. Maybe just add the local node here?
        return peerFinder.getFoundPeers();
    }

    /**
     * If there is any current committed publication, this method cancels it.
     * This method is used exclusively by tests.
     * @return true if publication was cancelled, false if there is no current committed publication.
     */
    boolean cancelCommittedPublication() {
        synchronized (mutex) {
            if (currentPublication.isPresent()) {
                final CoordinatorPublication publication = currentPublication.get();
                if (publication.isCommitted()) {
                    publication.cancel("cancelCommittedPublication");
                    logger.debug("Cancelled publication of [{}].", publication);
                    return true;
                }
            }
            return false;
        }
    }

    class CoordinatorPublication extends Publication {

        private final PublishRequest publishRequest;
        private final ListenableFuture<Void> localNodeAckEvent; // master本身收到了第二次确认完成
        private final AckListener ackListener;
        private final ActionListener<Void> publishListener;
        private final PublicationTransportHandler.PublicationContext publicationContext;
        private final Scheduler.ScheduledCancellable timeoutHandler;
        private final Scheduler.Cancellable infoTimeoutHandler;

        // We may not have accepted our own state before receiving a join from another node, causing its join to be rejected (we cannot
        // safely accept a join whose last-accepted term/version is ahead of ours), so store them up and process them at the end.
        private final List<Join> receivedJoins = new ArrayList<>();
        private boolean receivedJoinsProcessed;

        CoordinatorPublication(PublishRequest publishRequest, PublicationTransportHandler.PublicationContext publicationContext,
                               ListenableFuture<Void> localNodeAckEvent, AckListener ackListener, ActionListener<Void> publishListener) {
            super(publishRequest,
                new AckListener() {
                    @Override
                    public void onCommit(TimeValue commitTime) { // 啥都没做
                        ackListener.onCommit(commitTime);
                    }

                    @Override
                    public void onNodeAck(DiscoveryNode node, Exception e) {  // 节点响应了
                        // acking and cluster state application for local node is handled specially
                        if (node.equals(getLocalNode())) { // 本节点响应了
                            synchronized (mutex) {
                                if (e == null) {
                                    localNodeAckEvent.onResponse(null);// maste本身第二次确认完成
                                } else {
                                    localNodeAckEvent.onFailure(e);
                                }
                            }
                        } else {
                            ackListener.onNodeAck(node, e);
                            if (e == null) {
                                lagDetector.setAppliedVersion(node, publishRequest.getAcceptedState().version());
                            }
                        }
                    }
                },
                transportService.getThreadPool()::relativeTimeInMillis);
            this.publishRequest = publishRequest;
            this.publicationContext = publicationContext;
            this.localNodeAckEvent = localNodeAckEvent;
            this.ackListener = ackListener;
            this.publishListener = publishListener;
            // 是有超时设置的
            this.timeoutHandler = transportService.getThreadPool().schedule(new Runnable() {
                @Override
                public void run() { //每个生成，30s之后就会去cancell
                    synchronized (mutex) {
                        cancel("timed out after " + publishTimeout);
                    }
                }

                @Override
                public String toString() {
                    return "scheduled timeout for " + CoordinatorPublication.this;
                }
            }, publishTimeout, Names.GENERIC);

            this.infoTimeoutHandler = transportService.getThreadPool().schedule(new Runnable() {
                @Override
                public void run() {
                    synchronized (mutex) {
                        logIncompleteNodes(Level.INFO);
                    }
                }

                @Override
                public String toString() {
                    return "scheduled timeout for reporting on " + CoordinatorPublication.this;
                }
            }, publishInfoTimeout, Names.GENERIC);
        }

        private void removePublicationAndPossiblyBecomeCandidate(String reason) {
            assert Thread.holdsLock(mutex) : "Coordinator mutex not held";

            assert currentPublication.get() == this;
            currentPublication = Optional.empty();
            logger.debug("publication ended unsuccessfully: {}", this);

            // check if node has not already switched modes (by bumping term)
            if (isActiveForCurrentLeader()) {
                becomeCandidate(reason);
            }
        }

        boolean isActiveForCurrentLeader() {
            // checks if this publication can still influence the mode of the current publication
            return mode == Mode.LEADER && publishRequest.getAcceptedState().term() == getCurrentTerm();
        }

        @Override// 从第二次确认全部完成了才回来调用
        protected void onCompletion(boolean committed) {
            assert Thread.holdsLock(mutex) : "Coordinator mutex not held";

            localNodeAckEvent.addListener(new ActionListener<Void>() { //
                @Override
                public void onResponse(Void ignore) {
                    assert Thread.holdsLock(mutex) : "Coordinator mutex not held";
                    assert committed; // 若不是全部commit完成，那就直接抛异常了

                    receivedJoins.forEach(CoordinatorPublication.this::handleAssociatedJoin);
                    assert receivedJoinsProcessed == false;
                    receivedJoinsProcessed = true;
                    // 也是比较重要的
                    clusterApplier.onNewClusterState(CoordinatorPublication.this.toString(), () -> applierState, // 进去会去调用
                        new ClusterApplyListener() {
                            @Override
                            public void onFailure(String source, Exception e) {
                                synchronized (mutex) {
                                    removePublicationAndPossiblyBecomeCandidate("clusterApplier#onNewClusterState");
                                }
                                timeoutHandler.cancel();
                                infoTimeoutHandler.cancel();
                                ackListener.onNodeAck(getLocalNode(), e);
                                publishListener.onFailure(e);
                            }

                            @Override
                            public void onSuccess(String source) {
                                synchronized (mutex) {
                                    assert currentPublication.get() == CoordinatorPublication.this;
                                    currentPublication = Optional.empty();
                                    logger.debug("publication ended successfully: {}", CoordinatorPublication.this);
                                    // trigger term bump if new term was found during publication
                                    updateMaxTermSeen(getCurrentTerm());

                                    if (mode == Mode.LEADER) {
                                        // if necessary, abdicate to another node or improve the voting configuration
                                        boolean attemptReconfiguration = true;
                                        final ClusterState state = getLastAcceptedState(); // committed state
                                        if (localNodeMayWinElection(state) == false) {
                                            final List<DiscoveryNode> masterCandidates = completedNodes().stream()
                                                .filter(DiscoveryNode::isMasterNode)
                                                .filter(node -> nodeMayWinElection(state, node))
                                                .filter(node -> {
                                                    // check if master candidate would be able to get an election quorum if we were to
                                                    // abdicate to it. Assume that every node that completed the publication can provide
                                                    // a vote in that next election and has the latest state.
                                                    final long futureElectionTerm = state.term() + 1;
                                                    final VoteCollection futureVoteCollection = new VoteCollection();
                                                    completedNodes().forEach(completedNode -> futureVoteCollection.addJoinVote(
                                                        new Join(completedNode, node, futureElectionTerm, state.term(), state.version())));
                                                    return electionStrategy.isElectionQuorum(node, futureElectionTerm,
                                                        state.term(), state.version(), state.getLastCommittedConfiguration(),
                                                        state.getLastAcceptedConfiguration(), futureVoteCollection);
                                                })
                                                .collect(Collectors.toList());
                                            if (masterCandidates.isEmpty() == false) {
                                                abdicateTo(masterCandidates.get(random.nextInt(masterCandidates.size())));
                                                attemptReconfiguration = false;
                                            }
                                        }
                                        if (attemptReconfiguration) {
                                            scheduleReconfigurationIfNeeded();
                                        }
                                    }
                                    lagDetector.startLagDetector(publishRequest.getAcceptedState().version()); // 开始对滞后的节点进行处理
                                    logIncompleteNodes(Level.WARN);
                                }
                                timeoutHandler.cancel();
                                infoTimeoutHandler.cancel();
                                ackListener.onNodeAck(getLocalNode(), null);
                                publishListener.onResponse(null);
                            }
                        });
                }

                @Override
                public void onFailure(Exception e) {
                    assert Thread.holdsLock(mutex) : "Coordinator mutex not held";
                    removePublicationAndPossiblyBecomeCandidate("Publication.onCompletion(false)");
                    timeoutHandler.cancel();
                    infoTimeoutHandler.cancel();

                    final FailedToCommitClusterStateException exception = new FailedToCommitClusterStateException("publication failed", e);
                    ackListener.onNodeAck(getLocalNode(), exception); // other nodes have acked, but not the master.
                    publishListener.onFailure(exception);
                }
            }, EsExecutors.newDirectExecutorService(), transportService.getThreadPool().getThreadContext());
        }

        private void handleAssociatedJoin(Join join) {
            if (join.getTerm() == getCurrentTerm() && missingJoinVoteFrom(join.getSourceNode())) {
                logger.trace("handling {}", join);
                handleJoin(join);
            }
        }

        @Override
        protected boolean isPublishQuorum(VoteCollection votes) {
            assert Thread.holdsLock(mutex) : "Coordinator mutex not held";
            return coordinationState.get().isPublishQuorum(votes);
        }

        @Override
        protected Optional<ApplyCommitRequest> handlePublishResponse(DiscoveryNode sourceNode,
                                                                     PublishResponse publishResponse) {
            assert Thread.holdsLock(mutex) : "Coordinator mutex not held";
            assert getCurrentTerm() >= publishResponse.getTerm();
            return coordinationState.get().handlePublishResponse(sourceNode, publishResponse);
        }

        @Override
        protected void onJoin(Join join) {
            assert Thread.holdsLock(mutex) : "Coordinator mutex not held";
            if (receivedJoinsProcessed) { // 一般都为false
                // a late response may arrive after the state has been locally applied, meaning that receivedJoins has already been
                // processed, so we have to handle this late response here.
                handleAssociatedJoin(join);
            } else {
                receivedJoins.add(join);
            }
        }

        @Override
        protected void onMissingJoin(DiscoveryNode discoveryNode) {
            assert Thread.holdsLock(mutex) : "Coordinator mutex not held";
            // The remote node did not include a join vote in its publish response. We do not persist joins, so it could be that the remote
            // node voted for us and then rebooted, or it could be that it voted for a different node in this term. If we don't have a copy
            // of a join from this node then we assume the latter and bump our term to obtain a vote from this node.
            if (missingJoinVoteFrom(discoveryNode)) { // 为了获取这个远程节点的选票，我们自动+1
                final long term = publishRequest.getAcceptedState().term();
                logger.debug("onMissingJoin: no join vote from {}, bumping term to exceed {}", discoveryNode, term);
                updateMaxTermSeen(term + 1);
            }
        }

        @Override
        protected void sendPublishRequest(DiscoveryNode destination, PublishRequest publishRequest,
                                          ActionListener<PublishWithJoinResponse> responseActionListener) {
            publicationContext.sendPublishRequest(destination, publishRequest, wrapWithMutex(responseActionListener));
        }

        @Override
        protected void sendApplyCommit(DiscoveryNode destination, ApplyCommitRequest applyCommit,
                                       ActionListener<Empty> responseActionListener) {
            publicationContext.sendApplyCommit(destination, applyCommit, wrapWithMutex(responseActionListener));//ApplyCommitResponseHandler
        }
    }
}
