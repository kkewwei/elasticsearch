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

package org.elasticsearch.discovery;

import com.carrotsearch.hppc.cursors.ObjectCursor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.coordination.PeersResponse;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.threadpool.ThreadPool.Names;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportRequestOptions;
import org.elasticsearch.transport.TransportResponseHandler;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;

public abstract class PeerFinder {

    private static final Logger logger = LogManager.getLogger(PeerFinder.class);

    public static final String REQUEST_PEERS_ACTION_NAME = "internal:discovery/request_peers"; // 向peer节点发送请求，获取集群节点状态及当前term

    // the time between attempts to find all peers
    public static final Setting<TimeValue> DISCOVERY_FIND_PEERS_INTERVAL_SETTING =
        Setting.timeSetting("discovery.find_peers_interval",
            TimeValue.timeValueMillis(1000), TimeValue.timeValueMillis(1), Setting.Property.NodeScope);

    public static final Setting<TimeValue> DISCOVERY_REQUEST_PEERS_TIMEOUT_SETTING =
        Setting.timeSetting("discovery.request_peers_timeout",
            TimeValue.timeValueMillis(3000), TimeValue.timeValueMillis(1), Setting.Property.NodeScope);

    private final TimeValue findPeersInterval;  // 默认1s
    private final TimeValue requestPeersTimeout;  // 超时3s

    private final Object mutex = new Object();
    private final TransportService transportService;
    private final TransportAddressConnector transportAddressConnector; // HandshakingTransportAddressConnector
    private final ConfiguredHostsResolver configuredHostsResolver;  ; // SeedHostsResolver

    private volatile long currentTerm;
    private boolean active; // 本节点Coordinate节点，正在积极和别的节点进行交流，以便进入master或者follower
    private DiscoveryNodes lastAcceptedNodes;  //  只有当转变为coordinate时才会第一次赋值，是上次持久化时候的存活的节点
    private final Map<TransportAddress, Peer> peersByAddress = new LinkedHashMap<>(); // 都是已知存活的节点， 还不包含本节点
    private Optional<DiscoveryNode> leader = Optional.empty();  //leader节点是哪个
    private volatile List<TransportAddress> lastResolvedAddresses = emptyList();

    public PeerFinder(Settings settings, TransportService transportService, TransportAddressConnector transportAddressConnector,
                      ConfiguredHostsResolver configuredHostsResolver) {
        findPeersInterval = DISCOVERY_FIND_PEERS_INTERVAL_SETTING.get(settings);
        requestPeersTimeout = DISCOVERY_REQUEST_PEERS_TIMEOUT_SETTING.get(settings);
        this.transportService = transportService;
        this.transportAddressConnector = transportAddressConnector;
        this.configuredHostsResolver = configuredHostsResolver;

        transportService.registerRequestHandler(REQUEST_PEERS_ACTION_NAME, Names.GENERIC, false, false,
            PeersRequest::new,
            (request, channel, task) -> channel.sendResponse(handlePeersRequest(request)));
    }
    // 只有当前节点进入coordinate时才会调用， 加入集群时才需要
    public void activate(final DiscoveryNodes lastAcceptedNodes) {
        logger.trace("activating with {}", lastAcceptedNodes);

        synchronized (mutex) {
            assert assertInactiveWithNoKnownPeers();
            active = true;  // 积极和别的已知节点进行交流，以便进入下一个角色
            this.lastAcceptedNodes = lastAcceptedNodes; // 在获取集群元数据时，把本节点当做nodes节点放入了
            leader = Optional.empty();
            handleWakeUp(); // return value discarded: there are no known peers, so none can be disconnected
        }
       //
        onFoundPeersUpdated(); // trigger a check for a quorum already  开始
    }

    public void deactivate(DiscoveryNode leader) {
        final boolean peersRemoved;
        synchronized (mutex) {
            logger.trace("deactivating and setting leader to {}", leader);
            active = false;
            peersRemoved = handleWakeUp();
            this.leader = Optional.of(leader);
            assert assertInactiveWithNoKnownPeers();
        }
        if (peersRemoved) {
            onFoundPeersUpdated();
        }
    }

    // exposed to subclasses for testing
    protected final boolean holdsLock() {
        return Thread.holdsLock(mutex);
    }

    private boolean assertInactiveWithNoKnownPeers() {
        assert holdsLock() : "PeerFinder mutex not held";
        assert active == false;
        assert peersByAddress.isEmpty() : peersByAddress.keySet();
        return true;
    }

    PeersResponse handlePeersRequest(PeersRequest peersRequest) {
        synchronized (mutex) {
            assert peersRequest.getSourceNode().equals(getLocalNode()) == false;
            final List<DiscoveryNode> knownPeers;
            if (active) {
                assert leader.isPresent() == false : leader; // leader还没有选举出来
                if (peersRequest.getSourceNode().isMasterNode()) { // 若源节点有master属性
                    startProbe(peersRequest.getSourceNode().getAddress());
                }
                peersRequest.getKnownPeers().stream().map(DiscoveryNode::getAddress).forEach(this::startProbe);
                knownPeers = getFoundPeersUnderLock();
            } else {
                assert leader.isPresent() || lastAcceptedNodes == null;
                knownPeers = emptyList();
            }
            return new PeersResponse(leader, knownPeers, currentTerm);
        }
    }

    // exposed for checking invariant in o.e.c.c.Coordinator (public since this is a different package)
    public Optional<DiscoveryNode> getLeader() {
        synchronized (mutex) {
            return leader;
        }
    }

    // exposed for checking invariant in o.e.c.c.Coordinator (public since this is a different package)
    public long getCurrentTerm() {
        return currentTerm;
    }

    public void setCurrentTerm(long currentTerm) {
        this.currentTerm = currentTerm;
    }

    private DiscoveryNode getLocalNode() {
        final DiscoveryNode localNode = transportService.getLocalNode();
        assert localNode != null;
        return localNode;
    }

    /**
     * Invoked on receipt of a PeersResponse from a node that believes it's an active leader, which this node should therefore try and join.
     * Note that invocations of this method are not synchronised. By the time it is called we may have been deactivated.
     */
    protected abstract void onActiveMasterFound(DiscoveryNode masterNode, long term);

    /**
     * Invoked when the set of found peers changes. Note that invocations of this method are not fully synchronised, so we only guarantee
     * that the change to the set of found peers happens before this method is invoked. If there are multiple concurrent changes then there
     * will be multiple concurrent invocations of this method, with no guarantee as to their order. For this reason we do not pass the
     * updated set of peers as an argument to this method, leaving it to the implementation to call getFoundPeers() with appropriate
     * synchronisation to avoid lost updates. Also, by the time this method is invoked we may have been deactivated.
     */   //只是保证发现peer发生在这个函数被调用之前, 同时有很多进程在调用，所以实时peer由getFoundPeers来获取
    protected abstract void onFoundPeersUpdated();

    public List<TransportAddress> getLastResolvedAddresses() {
        return lastResolvedAddresses;
    }

    public interface TransportAddressConnector {
        /**
         * Identify the node at the given address and, if it is a master node and not the local node then establish a full connection to it.
         */
        void connectToRemoteMasterNode(TransportAddress transportAddress, ActionListener<DiscoveryNode> listener);
    }

    public interface ConfiguredHostsResolver {
        /**
         * Attempt to resolve the configured unicast hosts list to a list of transport addresses.
         *
         * @param consumer Consumer for the resolved list. May not be called if an error occurs or if another resolution attempt is in
         *                 progress.
         */
        void resolveConfiguredHosts(Consumer<List<TransportAddress>> consumer);
    }

    public Iterable<DiscoveryNode> getFoundPeers() {
        synchronized (mutex) {
            return getFoundPeersUnderLock();
        }
    }

    private List<DiscoveryNode> getFoundPeersUnderLock() {
        assert holdsLock() : "PeerFinder mutex not held";
        return peersByAddress.values().stream()
            .map(Peer::getDiscoveryNode).filter(Objects::nonNull).distinct().collect(Collectors.toList()); // 得到所有目前已经连接成功的peer节点
    }

    private Peer createConnectingPeer(TransportAddress transportAddress) {
        Peer peer = new Peer(transportAddress);
        peer.establishConnection(); // 并没有真正建立连接，只是初始化了Peer里面的DiscoveryNode对象
        return peer;
    }

    /**
     * @return whether any peers were removed due to disconnection
     */
    private boolean handleWakeUp() {
        assert holdsLock() : "PeerFinder mutex not held";

        final boolean peersRemoved = peersByAddress.values().removeIf(Peer::handleWakeUp); // 是否有节点掉线，掉线只是先删掉管道，但是还可能在peersByAddress中

        if (active == false) {
            logger.trace("not active");
            return peersRemoved; // 返回true
        }

        logger.trace("probing master nodes from cluster state: {}", lastAcceptedNodes); // 默认是本节点
        for (ObjectCursor<DiscoveryNode> discoveryNodeObjectCursor : lastAcceptedNodes.getMasterNodes().values()) {
            startProbe(discoveryNodeObjectCursor.value.getAddress()); // 开始探测上次记录的所有master
        }
        // 这里实际是从discovery.seed_hosts里面拿的master列表，然后再去探测目前集群里节点状态
        configuredHostsResolver.resolveConfiguredHosts(providedAddresses -> {
            synchronized (mutex) {
                lastResolvedAddresses = providedAddresses;
                logger.trace("probing resolved transport addresses {}", providedAddresses);
                providedAddresses.forEach(this::startProbe);// 开始探测discovery.seed_hosts里面的master, 探测完成，全方位连接管道也建立成功了
            }
        });
        // 延迟1s执行再进行一次探测，这个函数会循环执行下去，直到active=false,才会停止
        transportService.getThreadPool().scheduleUnlessShuttingDown(findPeersInterval, Names.GENERIC, new AbstractRunnable() {
            @Override
            public boolean isForceExecution() {
                return true;
            }

            @Override
            public void onFailure(Exception e) {
                assert false : e;
                logger.debug("unexpected exception in wakeup", e);
            }

            @Override
            protected void doRun() {
                synchronized (mutex) {
                    if (handleWakeUp() == false) { // 节点个数没有变化，则返回.循环探索
                        return;
                    }
                }
                onFoundPeersUpdated(); // 节点个数若发生了变化，则触发一次
            }

            @Override
            public String toString() {
                return "PeerFinder handling wakeup";
            }
        });

        return peersRemoved;
    }
    // 只能是master节点，才会去startProbe或者已知的peer节点
    protected void startProbe(TransportAddress transportAddress) { // 探测只是对目前节点建立连接，若没有连接，根据这个节点再去窥探集群节点状态
        assert holdsLock() : "PeerFinder mutex not held";
        if (active == false) {
            logger.trace("startProbe({}) not running", transportAddress);
            return;
        }

        if (transportAddress.equals(getLocalNode().getAddress())) { // 本节点就不用探测了
            logger.trace("startProbe({}) not probing local node", transportAddress);
            return;
        }

        peersByAddress.computeIfAbsent(transportAddress, this::createConnectingPeer); // 这里注意，有就算了，没有就得继续探测，这死循环的，除非把所有一致的节点都连接peer
    }

    private class Peer {
        private final TransportAddress transportAddress;
        private SetOnce<DiscoveryNode> discoveryNode = new SetOnce<>(); //
        private volatile boolean peersRequestInFlight; // 是否正在进行请求

        Peer(TransportAddress transportAddress) {
            this.transportAddress = transportAddress;
        }

        @Nullable
        DiscoveryNode getDiscoveryNode() {
            return discoveryNode.get();
        }
        // 是否和上次的持久化的连接管道还在？若还在的话，就去窥探集群状态
        boolean handleWakeUp() {
            assert holdsLock() : "PeerFinder mutex not held";

            if (active == false) {
                return true;
            }

            final DiscoveryNode discoveryNode = getDiscoveryNode();
            // may be null if connection not yet established

            if (discoveryNode != null) {
                if (transportService.nodeConnected(discoveryNode)) {  // 已经建立连接了。
                    if (peersRequestInFlight == false) { // 还没有请求
                        requestPeers(); // 向
                    }
                } else {
                    logger.trace("{} no longer connected", this);
                    return true;
                }
            }

            return false;
        }

        void establishConnection() {
            assert holdsLock() : "PeerFinder mutex not held";
            assert getDiscoveryNode() == null : "unexpectedly connected to " + getDiscoveryNode();
            assert active;

            logger.trace("{} attempting connection", this);
            transportAddressConnector.connectToRemoteMasterNode(transportAddress, new ActionListener<DiscoveryNode>() {
                @Override
                public void onResponse(DiscoveryNode remoteNode) {
                    assert remoteNode.isMasterNode() : remoteNode + " is not master-eligible";
                    assert remoteNode.equals(getLocalNode()) == false : remoteNode + " is the local node";
                    synchronized (mutex) {
                        if (active == false) {
                            return;
                        }

                        assert discoveryNode.get() == null : "discoveryNode unexpectedly already set to " + discoveryNode.get();
                        discoveryNode.set(remoteNode);
                        requestPeers(); // 已经同单个节点建立了管道，则向这个节点请求
                    }

                    assert holdsLock() == false : "PeerFinder mutex is held in error";
                    onFoundPeersUpdated(); // 此时还不一定找到了master
                }

                @Override
                public void onFailure(Exception e) {
                    logger.debug(() -> new ParameterizedMessage("{} connection failed", Peer.this), e);
                    synchronized (mutex) {
                        peersByAddress.remove(transportAddress);
                    }
                }
            });
        }

        private void requestPeers() { //通过单个节点，窥探整个集群状态
            assert holdsLock() : "PeerFinder mutex not held";
            assert peersRequestInFlight == false : "PeersRequest already in flight";
            assert active;

            final DiscoveryNode discoveryNode = getDiscoveryNode();
            assert discoveryNode != null : "cannot request peers without first connecting";

            if (discoveryNode.equals(getLocalNode())) {
                logger.trace("{} not requesting peers from local node", this);
                return;
            }

            logger.trace("{} requesting peers", this);
            peersRequestInFlight = true;

            final List<DiscoveryNode> knownNodes = getFoundPeersUnderLock();

            final TransportResponseHandler<PeersResponse> peersResponseHandler = new TransportResponseHandler<PeersResponse>() {

                @Override
                public PeersResponse read(StreamInput in) throws IOException {
                    return new PeersResponse(in);
                }

                @Override
                public void handleResponse(PeersResponse response) {
                    logger.trace("{} received {}", Peer.this, response);
                    synchronized (mutex) {
                        if (active == false) {
                            return;
                        }

                        peersRequestInFlight = false;

                        response.getMasterNode().map(DiscoveryNode::getAddress).ifPresent(PeerFinder.this::startProbe); // master节点单独进行循环
                        response.getKnownPeers().stream().map(DiscoveryNode::getAddress).forEach(PeerFinder.this::startProbe); // 远方已知的节点，每个再继续第二次探测
                    }

                    if (response.getMasterNode().equals(Optional.of(discoveryNode))) { //向这个节点请求，发现了master
                        // Must not hold lock here to avoid deadlock
                        assert holdsLock() == false : "PeerFinder mutex is held in error";
                        onActiveMasterFound(discoveryNode, response.getTerm());  // 直到发现有存活的master节点
                    }
                }

                @Override
                public void handleException(TransportException exp) {
                    peersRequestInFlight = false;
                    logger.debug(new ParameterizedMessage("{} peers request failed", Peer.this), exp);
                }

                @Override
                public String executor() {
                    return Names.GENERIC;
                }
            };
            transportService.sendRequest(discoveryNode, REQUEST_PEERS_ACTION_NAME,
                new PeersRequest(getLocalNode(), knownNodes),
                TransportRequestOptions.builder().withTimeout(requestPeersTimeout).build(),
                peersResponseHandler);
        }

        @Override
        public String toString() {
            return "Peer{" +
                "transportAddress=" + transportAddress +
                ", discoveryNode=" + discoveryNode.get() +
                ", peersRequestInFlight=" + peersRequestInFlight +
                '}';
        }
    }
}
