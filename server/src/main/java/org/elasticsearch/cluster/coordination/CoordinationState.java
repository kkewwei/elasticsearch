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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.coordination.CoordinationMetadata.VotingConfiguration;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.node.DiscoveryNode;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.elasticsearch.cluster.coordination.Coordinator.ZEN1_BWC_TERM;

/**
 * The core class of the cluster state coordination algorithm, directly implementing the
 * <a href="https://github.com/elastic/elasticsearch-formal-models/blob/master/ZenWithTerms/tla/ZenWithTerms.tla">formal model</a>
 */
public class CoordinationState {

    private static final Logger logger = LogManager.getLogger(CoordinationState.class);

    private final DiscoveryNode localNode;

    private final ElectionStrategy electionStrategy;

    // persisted state
    private final PersistedState persistedState; // GatewayPersistedState

    // transient state
    private VoteCollection joinVotes; // 累计当前申请加入的选票 / 每次pulish完成后，用户的响应也会加入
    private boolean startedJoinSinceLastReboot;
    private boolean electionWon;
    private long lastPublishedVersion;
    private VotingConfiguration lastPublishedConfiguration;
    private VoteCollection publishVotes;

    public CoordinationState(DiscoveryNode localNode, PersistedState persistedState, ElectionStrategy electionStrategy) {
        this.localNode = localNode;

        // persisted state
        this.persistedState = persistedState;
        this.electionStrategy = electionStrategy;

        // transient state
        this.joinVotes = new VoteCollection();
        this.startedJoinSinceLastReboot = false;
        this.electionWon = false;
        this.lastPublishedVersion = 0L;
        this.lastPublishedConfiguration = persistedState.getLastAcceptedState().getLastAcceptedConfiguration();
        this.publishVotes = new VoteCollection();
    }

    public long getCurrentTerm() {
        return persistedState.getCurrentTerm();
    }
    // 一定是上次持久化磁盘的元数据
    public ClusterState getLastAcceptedState() {
        return persistedState.getLastAcceptedState();  //// GatewayPersistedState
    }

    public long getLastAcceptedTerm() {
        return getLastAcceptedState().term();
    }

    public long getLastAcceptedVersion() {
        return getLastAcceptedState().version();
    }

    private long getLastAcceptedVersionOrMetadataVersion() {
        return getLastAcceptedState().getVersionOrMetadataVersion();
    }

    public VotingConfiguration getLastCommittedConfiguration() {
        return getLastAcceptedState().getLastCommittedConfiguration();
    }

    public VotingConfiguration getLastAcceptedConfiguration() {
        return getLastAcceptedState().getLastAcceptedConfiguration();
    }

    public long getLastPublishedVersion() {
        return lastPublishedVersion;
    }

    public boolean electionWon() {
        return electionWon;
    }
    // 达到了上次永久化选举节点的一半，就举办了选举master的资格了，强依赖上次的持久化数据，不到上次持久化master个数的一半，还不能选举
    public boolean isElectionQuorum(VoteCollection joinVotes) {
        return electionStrategy.isElectionQuorum(localNode, getCurrentTerm(), getLastAcceptedTerm(), getLastAcceptedVersion(),
            getLastCommittedConfiguration(), getLastAcceptedConfiguration(), joinVotes);
    }

    public boolean isPublishQuorum(VoteCollection votes) {
        return votes.isQuorum(getLastCommittedConfiguration()) && votes.isQuorum(lastPublishedConfiguration);
    }

    public boolean containsJoinVoteFor(DiscoveryNode node) {
        return joinVotes.containsVoteFor(node);
    }

    // used for tests
    boolean containsJoin(Join join) {
        return joinVotes.getJoins().contains(join);
    }

    public boolean joinVotesHaveQuorumFor(VotingConfiguration votingConfiguration) {
        return joinVotes.isQuorum(votingConfiguration);
    }

    /**
     * Used to bootstrap a cluster by injecting the initial state and configuration.
     *
     * @param initialState The initial state to use. Must have term 0, version equal to the last-accepted version, and non-empty
     *                     configurations.
     * @throws CoordinationStateRejectedException if the arguments were incompatible with the current state of this object.
     */
    public void setInitialState(ClusterState initialState) {

        final VotingConfiguration lastAcceptedConfiguration = getLastAcceptedConfiguration();
        if (lastAcceptedConfiguration.isEmpty() == false) {
            logger.debug("setInitialState: rejecting since last-accepted configuration is nonempty: {}", lastAcceptedConfiguration);
            throw new CoordinationStateRejectedException(
                "initial state already set: last-accepted configuration now " + lastAcceptedConfiguration);
        }

        assert getLastAcceptedTerm() == 0 : getLastAcceptedTerm();
        assert getLastCommittedConfiguration().isEmpty() : getLastCommittedConfiguration();
        assert lastPublishedVersion == 0 : lastPublishedVersion;
        assert lastPublishedConfiguration.isEmpty() : lastPublishedConfiguration;
        assert electionWon == false;
        assert joinVotes.isEmpty() : joinVotes;
        assert publishVotes.isEmpty() : publishVotes;

        assert initialState.term() == 0 : initialState + " should have term 0";
        assert initialState.version() == getLastAcceptedVersion() : initialState + " should have version " + getLastAcceptedVersion();
        assert initialState.getLastAcceptedConfiguration().isEmpty() == false;
        assert initialState.getLastCommittedConfiguration().isEmpty() == false;

        persistedState.setLastAcceptedState(initialState);
    }

    /**
     * May be safely called at any time to move this instance to a new term.
     *
     * @param startJoinRequest The startJoinRequest, specifying the node requesting the join.
     * @return A Join that should be sent to the target node of the join.
     * @throws CoordinationStateRejectedException if the arguments were incompatible with the current state of this object.
     */
    public Join handleStartJoin(StartJoinRequest startJoinRequest) { // 接收到别人的请求
        if (startJoinRequest.getTerm() <= getCurrentTerm()) { // 调用这个函数，这里限定了，若两个一起抢的话，一个term更新后，另外一个就挂了
            logger.debug("handleStartJoin: ignoring [{}] as term provided is not greater than current term [{}]",
                startJoinRequest, getCurrentTerm());
            throw new CoordinationStateRejectedException("incoming term " + startJoinRequest.getTerm() +
                " not greater than current term " + getCurrentTerm()); // 抛异常，应该是没有捕获直接丢失了
        }

        logger.debug("handleStartJoin: leaving term [{}] due to {}", getCurrentTerm(), startJoinRequest);

        if (joinVotes.isEmpty() == false) { // 当前节点接收到了一些选票
            final String reason;
            if (electionWon == false) {
                reason = "failed election";
            } else if (startJoinRequest.getSourceNode().equals(localNode)) {
                reason = "bumping term";
            } else {
                reason = "standing down as leader";
            }
            logger.debug("handleStartJoin: discarding {}: {}", joinVotes, reason);
        }

        persistedState.setCurrentTerm(startJoinRequest.getTerm()); // 将term给持久化
        assert getCurrentTerm() == startJoinRequest.getTerm();
        lastPublishedVersion = 0;
        lastPublishedConfiguration = getLastAcceptedConfiguration();
        startedJoinSinceLastReboot = true;
        electionWon = false;
        joinVotes = new VoteCollection(); // 选票清空
        publishVotes = new VoteCollection();

        return new Join(localNode, startJoinRequest.getSourceNode(), getCurrentTerm(), getLastAcceptedTerm(),
            getLastAcceptedVersionOrMetadataVersion());
    }

    /**
     * May be called on receipt of a Join.
     *
     * @param join The Join received.
     * @return true iff this instance does not already have a join vote from the given source node for this term
     * @throws CoordinationStateRejectedException if the arguments were incompatible with the current state of this object.
     */  // 候选人处理投票请求？
    public boolean handleJoin(Join join) {
        assert join.targetMatches(localNode) : "handling join " + join + " for the wrong node " + localNode;

        if (join.getTerm() != getCurrentTerm()) { // 肯定是相等的
            logger.debug("handleJoin: ignored join due to term mismatch (expected: [{}], actual: [{}])",
                getCurrentTerm(), join.getTerm());
            throw new CoordinationStateRejectedException(
                "incoming term " + join.getTerm() + " does not match current term " + getCurrentTerm());
        }

        if (startedJoinSinceLastReboot == false) {
            logger.debug("handleJoin: ignored join as term was not incremented yet after reboot");
            throw new CoordinationStateRejectedException("ignored join as term has not been incremented yet after reboot");
        }

        final long lastAcceptedTerm = getLastAcceptedTerm();
        if (join.getLastAcceptedTerm() > lastAcceptedTerm) {
            logger.debug("handleJoin: ignored join as joiner has a better last accepted term (expected: <=[{}], actual: [{}])",
                lastAcceptedTerm, join.getLastAcceptedTerm());
            throw new CoordinationStateRejectedException("incoming last accepted term " + join.getLastAcceptedTerm() +
                " of join higher than current last accepted term " + lastAcceptedTerm);
        }

        if (join.getLastAcceptedTerm() == lastAcceptedTerm && join.getLastAcceptedVersion() > getLastAcceptedVersionOrMetadataVersion()) {
            logger.debug(
                "handleJoin: ignored join as joiner has a better last accepted version (expected: <=[{}], actual: [{}]) in term {}",
                getLastAcceptedVersionOrMetadataVersion(), join.getLastAcceptedVersion(), lastAcceptedTerm);
            throw new CoordinationStateRejectedException("incoming last accepted version " + join.getLastAcceptedVersion() +
                " of join higher than current last accepted version " + getLastAcceptedVersionOrMetadataVersion()
                + " in term " + lastAcceptedTerm);
        }

        if (getLastAcceptedConfiguration().isEmpty()) {
            // We do not check for an election won on setting the initial configuration, so it would be possible to end up in a state where
            // we have enough join votes to have won the election immediately on setting the initial configuration. It'd be quite
            // complicated to restore all the appropriate invariants when setting the initial configuration (it's not just electionWon)
            // so instead we just reject join votes received prior to receiving the initial configuration.
            logger.debug("handleJoin: rejecting join since this node has not received its initial configuration yet");
            throw new CoordinationStateRejectedException("rejecting join since this node has not received its initial configuration yet");
        }

        boolean added = joinVotes.addJoinVote(join);
        boolean prevElectionWon = electionWon;
        electionWon = isElectionQuorum(joinVotes); // 选举成功了
        assert !prevElectionWon || electionWon : // we cannot go from won to not won
            "locaNode= " + localNode + ", join=" + join + ", joinVotes=" + joinVotes;
        logger.debug("handleJoin: added join {} from [{}] for election, electionWon={} lastAcceptedTerm={} lastAcceptedVersion={}", join,
            join.getSourceNode(), electionWon, lastAcceptedTerm, getLastAcceptedVersion());

        if (electionWon && prevElectionWon == false) {
            logger.debug("handleJoin: election won in term [{}] with {}", getCurrentTerm(), joinVotes);
            lastPublishedVersion = getLastAcceptedVersion();
        }
        return added;
    }

    /**
     * May be called in order to prepare publication of the given cluster state
     *
     * @param clusterState The cluster state to publish.
     * @return A PublishRequest to publish the given cluster state
     * @throws CoordinationStateRejectedException if the arguments were incompatible with the current state of this object.
     */
    public PublishRequest handleClientValue(ClusterState clusterState) {
        if (electionWon == false) {
            logger.debug("handleClientValue: ignored request as election not won");
            throw new CoordinationStateRejectedException("election not won");
        }
        if (lastPublishedVersion != getLastAcceptedVersion()) {
            logger.debug("handleClientValue: cannot start publishing next value before accepting previous one");
            throw new CoordinationStateRejectedException("cannot start publishing next value before accepting previous one");
        }
        if (clusterState.term() != getCurrentTerm()) {
            logger.debug("handleClientValue: ignored request due to term mismatch " +
                    "(expected: [term {} version >{}], actual: [term {} version {}])",
                getCurrentTerm(), lastPublishedVersion, clusterState.term(), clusterState.version());
            throw new CoordinationStateRejectedException("incoming term " + clusterState.term() + " does not match current term " +
                getCurrentTerm());
        }
        if (clusterState.version() <= lastPublishedVersion) {
            logger.debug("handleClientValue: ignored request due to version mismatch " +
                    "(expected: [term {} version >{}], actual: [term {} version {}])",
                getCurrentTerm(), lastPublishedVersion, clusterState.term(), clusterState.version());
            throw new CoordinationStateRejectedException("incoming cluster state version " + clusterState.version() +
                " lower or equal to last published version " + lastPublishedVersion);
        }

        if (clusterState.getLastAcceptedConfiguration().equals(getLastAcceptedConfiguration()) == false
            && getLastCommittedConfiguration().equals(getLastAcceptedConfiguration()) == false) {
            logger.debug("handleClientValue: only allow reconfiguration while not already reconfiguring");
            throw new CoordinationStateRejectedException("only allow reconfiguration while not already reconfiguring");
        }
        if (joinVotesHaveQuorumFor(clusterState.getLastAcceptedConfiguration()) == false) {
            logger.debug("handleClientValue: only allow reconfiguration if joinVotes have quorum for new config");
            throw new CoordinationStateRejectedException("only allow reconfiguration if joinVotes have quorum for new config");
        }

        assert clusterState.getLastCommittedConfiguration().equals(getLastCommittedConfiguration()) :
            "last committed configuration should not change";

        lastPublishedVersion = clusterState.version();
        lastPublishedConfiguration = clusterState.getLastAcceptedConfiguration();
        publishVotes = new VoteCollection();

        logger.trace("handleClientValue: processing request for version [{}] and term [{}]", lastPublishedVersion, getCurrentTerm());

        return new PublishRequest(clusterState);
    }

    /**
     * May be called on receipt of a PublishRequest.
     *
     * @param publishRequest The publish request received.
     * @return A PublishResponse which can be sent back to the sender of the PublishRequest.
     * @throws CoordinationStateRejectedException if the arguments were incompatible with the current state of this object.
     */
    public PublishResponse handlePublishRequest(PublishRequest publishRequest) {
        final ClusterState clusterState = publishRequest.getAcceptedState();
        if (clusterState.term() != getCurrentTerm()) {
            logger.debug("handlePublishRequest: ignored publish request due to term mismatch (expected: [{}], actual: [{}])",
                getCurrentTerm(), clusterState.term());
            throw new CoordinationStateRejectedException("incoming term " + clusterState.term() + " does not match current term " +
                getCurrentTerm());
        }
        if (clusterState.term() == getLastAcceptedTerm() && clusterState.version() <= getLastAcceptedVersion()) {
            if (clusterState.term() == ZEN1_BWC_TERM
                && clusterState.nodes().getMasterNode().equals(getLastAcceptedState().nodes().getMasterNode()) == false) {
                logger.debug("handling publish request in compatibility mode despite version mismatch (expected: >[{}], actual: [{}])",
                    getLastAcceptedVersion(), clusterState.version());
            } else {
                logger.debug("handlePublishRequest: ignored publish request due to version mismatch (expected: >[{}], actual: [{}])",
                    getLastAcceptedVersion(), clusterState.version());
                throw new CoordinationStateRejectedException("incoming version " + clusterState.version() +
                    " lower or equal to current version " + getLastAcceptedVersion());
            }
        }

        logger.trace("handlePublishRequest: accepting publish request for version [{}] and term [{}]",
            clusterState.version(), clusterState.term());
        persistedState.setLastAcceptedState(clusterState); // 向磁盘写入本次的集群及索引的元数据
        assert getLastAcceptedState() == clusterState;

        return new PublishResponse(clusterState.term(), clusterState.version());
    }

    /**
     * May be called on receipt of a PublishResponse from the given sourceNode.
     *
     * @param sourceNode      The sender of the PublishResponse received.
     * @param publishResponse The PublishResponse received.
     * @return An optional ApplyCommitRequest which, if present, may be broadcast to all peers, indicating that this publication
     * has been accepted at a quorum of peers and is therefore committed.
     * @throws CoordinationStateRejectedException if the arguments were incompatible with the current state of this object.
     */
    public Optional<ApplyCommitRequest> handlePublishResponse(DiscoveryNode sourceNode, PublishResponse publishResponse) {
        if (electionWon == false) {
            logger.debug("handlePublishResponse: ignored response as election not won");
            throw new CoordinationStateRejectedException("election not won");
        }
        if (publishResponse.getTerm() != getCurrentTerm()) {
            logger.debug("handlePublishResponse: ignored publish response due to term mismatch (expected: [{}], actual: [{}])",
                getCurrentTerm(), publishResponse.getTerm());
            throw new CoordinationStateRejectedException("incoming term " + publishResponse.getTerm()
                + " does not match current term " + getCurrentTerm());
        }
        if (publishResponse.getVersion() != lastPublishedVersion) {
            logger.debug("handlePublishResponse: ignored publish response due to version mismatch (expected: [{}], actual: [{}])",
                lastPublishedVersion, publishResponse.getVersion());
            throw new CoordinationStateRejectedException("incoming version " + publishResponse.getVersion() +
                " does not match current version " + lastPublishedVersion);
        }

        logger.trace("handlePublishResponse: accepted publish response for version [{}] and term [{}] from [{}]",
            publishResponse.getVersion(), publishResponse.getTerm(), sourceNode);
        publishVotes.addVote(sourceNode); // 从data节点响应
        if (isPublishQuorum(publishVotes)) { // 若响应节点过半了，来一个，返回一个ApplyCommitRequest
            logger.trace("handlePublishResponse: value committed for version [{}] and term [{}]",
                publishResponse.getVersion(), publishResponse.getTerm());
            return Optional.of(new ApplyCommitRequest(localNode, publishResponse.getTerm(), publishResponse.getVersion()));
        }

        return Optional.empty();
    }

    /**
     * May be called on receipt of an ApplyCommitRequest. Updates the committed configuration accordingly.
     *
     * @param applyCommit The ApplyCommitRequest received.
     * @throws CoordinationStateRejectedException if the arguments were incompatible with the current state of this object.
     */
    public void handleCommit(ApplyCommitRequest applyCommit) {
        if (applyCommit.getTerm() != getCurrentTerm()) {
            logger.debug("handleCommit: ignored commit request due to term mismatch " +
                    "(expected: [term {} version {}], actual: [term {} version {}])",
                getLastAcceptedTerm(), getLastAcceptedVersion(), applyCommit.getTerm(), applyCommit.getVersion());
            throw new CoordinationStateRejectedException("incoming term " + applyCommit.getTerm() + " does not match current term " +
                getCurrentTerm());
        }
        if (applyCommit.getTerm() != getLastAcceptedTerm()) {
            logger.debug("handleCommit: ignored commit request due to term mismatch " +
                    "(expected: [term {} version {}], actual: [term {} version {}])",
                getLastAcceptedTerm(), getLastAcceptedVersion(), applyCommit.getTerm(), applyCommit.getVersion());
            throw new CoordinationStateRejectedException("incoming term " + applyCommit.getTerm() + " does not match last accepted term " +
                getLastAcceptedTerm());
        }
        if (applyCommit.getVersion() != getLastAcceptedVersion()) {
            logger.debug("handleCommit: ignored commit request due to version mismatch (term {}, expected: [{}], actual: [{}])",
                getLastAcceptedTerm(), getLastAcceptedVersion(), applyCommit.getVersion());
            throw new CoordinationStateRejectedException("incoming version " + applyCommit.getVersion() +
                " does not match current version " + getLastAcceptedVersion());
        }

        logger.trace("handleCommit: applying commit request for term [{}] and version [{}]", applyCommit.getTerm(),
            applyCommit.getVersion());

        persistedState.markLastAcceptedStateAsCommitted();
        assert getLastCommittedConfiguration().equals(getLastAcceptedConfiguration());
    }

    public void invariant() {
        assert getLastAcceptedTerm() <= getCurrentTerm();
        assert electionWon() == isElectionQuorum(joinVotes);
        if (electionWon()) {
            assert getLastPublishedVersion() >= getLastAcceptedVersion();
        } else {
            assert getLastPublishedVersion() == 0L;
        }
        assert electionWon() == false || startedJoinSinceLastReboot;
        assert publishVotes.isEmpty() || electionWon();
    }

    public void close() throws IOException {
        persistedState.close();
    }

    /**
     * Pluggable persistence layer for {@link CoordinationState}.
     */
    public interface PersistedState extends Closeable {

        /**
         * Returns the current term
         */
        long getCurrentTerm();

        /**
         * Returns the last accepted cluster state
         */
        ClusterState getLastAcceptedState();

        /**
         * Sets a new current term.
         * After a successful call to this method, {@link #getCurrentTerm()} should return the last term that was set.
         * The value returned by {@link #getLastAcceptedState()} should not be influenced by calls to this method.
         */
        void setCurrentTerm(long currentTerm);

        /**
         * Sets a new last accepted cluster state.
         * After a successful call to this method, {@link #getLastAcceptedState()} should return the last cluster state that was set.
         * The value returned by {@link #getCurrentTerm()} should not be influenced by calls to this method.
         */
        void setLastAcceptedState(ClusterState clusterState);

        /**
         * Marks the last accepted cluster state as committed.
         * After a successful call to this method, {@link #getLastAcceptedState()} should return the last cluster state that was set,
         * with the last committed configuration now corresponding to the last accepted configuration, and the cluster uuid, if set,
         * marked as committed.
         */
        default void markLastAcceptedStateAsCommitted() {
            final ClusterState lastAcceptedState = getLastAcceptedState();
            Metadata.Builder metadataBuilder = null;
            if (lastAcceptedState.getLastAcceptedConfiguration().equals(lastAcceptedState.getLastCommittedConfiguration()) == false) {
                final CoordinationMetadata coordinationMetadata = CoordinationMetadata.builder(lastAcceptedState.coordinationMetadata())
                        .lastCommittedConfiguration(lastAcceptedState.getLastAcceptedConfiguration())
                        .build();
                metadataBuilder = Metadata.builder(lastAcceptedState.metadata());
                metadataBuilder.coordinationMetadata(coordinationMetadata);
            }
            // if we receive a commit from a Zen1 master that has not recovered its state yet, the cluster uuid might not been known yet.
            assert lastAcceptedState.metadata().clusterUUID().equals(Metadata.UNKNOWN_CLUSTER_UUID) == false ||
                lastAcceptedState.term() == ZEN1_BWC_TERM :
                "received cluster state with empty cluster uuid but not Zen1 BWC term: " + lastAcceptedState;
            if (lastAcceptedState.metadata().clusterUUID().equals(Metadata.UNKNOWN_CLUSTER_UUID) == false &&
                lastAcceptedState.metadata().clusterUUIDCommitted() == false) {
                if (metadataBuilder == null) {
                    metadataBuilder = Metadata.builder(lastAcceptedState.metadata());
                }
                metadataBuilder.clusterUUIDCommitted(true);

                if (lastAcceptedState.term() != ZEN1_BWC_TERM) {
                    // Zen1 masters never publish a committed cluster UUID so if we logged this it'd happen on on every update. Let's just
                    // not log it at all in a 6.8/7.x rolling upgrade.
                    logger.info("cluster UUID set to [{}]", lastAcceptedState.metadata().clusterUUID());
                }
            }
            if (metadataBuilder != null) {
                setLastAcceptedState(ClusterState.builder(lastAcceptedState).metadata(metadataBuilder).build());
            }
        }

        default void close() throws IOException {
        }
    }

    /**
     * A collection of votes, used to calculate quorums. Optionally records the Joins as well.
     */
    public static class VoteCollection {

        private final Map<String, DiscoveryNode> nodes; // 投票结果
        private final Set<Join> joins; //

        public boolean addVote(DiscoveryNode sourceNode) {// 当前节点来说，对连接的每个master属性节点，都让他们给自己投一票
            return sourceNode.isMasterNode() && nodes.put(sourceNode.getId(), sourceNode) == null;
        }

        public boolean addJoinVote(Join join) {
            final boolean added = addVote(join.getSourceNode());
            if (added) {
                joins.add(join);
            }
            return added;
        }

        public VoteCollection() {
            nodes = new HashMap<>();
            joins = new HashSet<>();
        }

        public boolean isQuorum(VotingConfiguration configuration) {
            return configuration.hasQuorum(nodes.keySet()); // 至少大于1半
        }

        public boolean containsVoteFor(DiscoveryNode node) {
            return nodes.containsKey(node.getId());
        }

        public boolean isEmpty() {
            return nodes.isEmpty();
        }

        public Collection<DiscoveryNode> nodes() {
            return Collections.unmodifiableCollection(nodes.values());
        }

        public Set<Join> getJoins() {
            return Collections.unmodifiableSet(joins);
        }

        @Override
        public String toString() {
            return "VoteCollection{votes=" + nodes.keySet() + ", joins=" + joins + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof VoteCollection)) return false;

            VoteCollection that = (VoteCollection) o;

            if (!nodes.equals(that.nodes)) return false;
            return joins.equals(that.joins);
        }

        @Override
        public int hashCode() {
            int result = nodes.hashCode();
            result = 31 * result + joins.hashCode();
            return result;
        }
    }
}
