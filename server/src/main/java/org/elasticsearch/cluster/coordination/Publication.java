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
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.coordination.ClusterStatePublisher.AckListener;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

public abstract class Publication {

    protected final Logger logger = LogManager.getLogger(getClass());

    private final List<PublicationTarget> publicationTargets; // publish目标节点
    private final PublishRequest publishRequest;
    private final AckListener ackListener;
    private final LongSupplier currentTimeSupplier;
    private final long startTime;
    // 只要master接收到data publish 第一次响应个数过半，就赋值了
    private Optional<ApplyCommitRequest> applyCommitRequest; // set when state is committed  // master第二次确认时候发送的请求
    private boolean isCompleted; // set when publication is completed 所有publish已经完成，就是master第二次确认已经完成了
    private boolean cancelled; // set when publication is cancelled 是否有人主动调用了cancel之后才会失败，比如30s超时

    public Publication(PublishRequest publishRequest, AckListener ackListener, LongSupplier currentTimeSupplier) {
        this.publishRequest = publishRequest;
        this.ackListener = ackListener;
        this.currentTimeSupplier = currentTimeSupplier;
        startTime = currentTimeSupplier.getAsLong();
        applyCommitRequest = Optional.empty();
        publicationTargets = new ArrayList<>(publishRequest.getAcceptedState().getNodes().getNodes().size());
        publishRequest.getAcceptedState().getNodes().mastersFirstStream().forEach(n -> publicationTargets.add(new PublicationTarget(n)));
    }

    public void start(Set<DiscoveryNode> faultyNodes) {
        logger.trace("publishing {} to {}", publishRequest, publicationTargets);

        for (final DiscoveryNode faultyNode : faultyNodes) {
            onFaultyNode(faultyNode);
        }
        onPossibleCommitFailure();
        publicationTargets.forEach(PublicationTarget::sendPublishRequest);
    }
    // 超时之后将状态置为完成
    public void cancel(String reason) {
        if (isCompleted) {
            return;
        }

        assert cancelled == false;
        cancelled = true;
        if (applyCommitRequest.isPresent() == false) { // 已经开始第二次确认阶段了
            logger.debug("cancel: [{}] cancelled before committing (reason: {})", this, reason);
            // fail all current publications
            final Exception e = new ElasticsearchException("publication cancelled before committing: " + reason);
            publicationTargets.stream().filter(PublicationTarget::isActive).forEach(pt -> pt.setFailed(e)); // 标记每一个PublicationTarget为失败
        }
        onPossibleCompletion(); //
    }

    public void onFaultyNode(DiscoveryNode faultyNode) {
        publicationTargets.forEach(t -> t.onFaultyNode(faultyNode));
        onPossibleCompletion();
    }

    public List<DiscoveryNode> completedNodes() {
        return publicationTargets.stream()
            .filter(PublicationTarget::isSuccessfullyCompleted)
            .map(PublicationTarget::getDiscoveryNode)
            .collect(Collectors.toList());
    }

    public boolean isCommitted() {
        return applyCommitRequest.isPresent();
    }
    // 检查下是否整个过程完成了，若完成了，则isCompleted置位
    private void onPossibleCompletion() {
        if (isCompleted) {
            return;
        }

        if (cancelled == false) { // 还没有失败
            for (final PublicationTarget target : publicationTargets) {  // 遍历每一个target
                if (target.isActive()) { //// 只要还有一个没有第二次确认完成，就退出
                    return;
                }
            }
        }

        if (applyCommitRequest.isPresent() == false) { // 还没有开始第二次确认阶段，就已经全部完成了
            logger.debug("onPossibleCompletion: [{}] commit failed", this);
            assert isCompleted == false;
            isCompleted = true;
            onCompletion(false);// 很重要
            return;
        }

        assert isCompleted == false;
        isCompleted = true;
        onCompletion(true); //全部完成了才会去调用 这里还有大作用，会去调用IndicesClusterStateService.applyClusterState()
        assert applyCommitRequest.isPresent();
        logger.trace("onPossibleCompletion: [{}] was successful", this);
    }

    // For assertions only: verify that this invariant holds
    private boolean publicationCompletedIffAllTargetsInactiveOrCancelled() {
        if (cancelled == false) {
            for (final PublicationTarget target : publicationTargets) {
                if (target.isActive()) {
                    return isCompleted == false;
                }
            }
        }
        return isCompleted;
    }

    // For assertions
    ClusterState publishedState() {
        return publishRequest.getAcceptedState();
    }

    private void onPossibleCommitFailure() {
        if (applyCommitRequest.isPresent()) { //  做个检查
            onPossibleCompletion();
            return;
        }

        final CoordinationState.VoteCollection possiblySuccessfulNodes = new CoordinationState.VoteCollection();
        for (PublicationTarget publicationTarget : publicationTargets) {
            if (publicationTarget.mayCommitInFuture()) {
                possiblySuccessfulNodes.addVote(publicationTarget.discoveryNode);
            } else {
                assert publicationTarget.isFailed() : publicationTarget;
            }
        }

        if (isPublishQuorum(possiblySuccessfulNodes) == false) {
            logger.debug("onPossibleCommitFailure: non-failed nodes {} do not form a quorum, so {} cannot succeed",
                possiblySuccessfulNodes, this);
            Exception e = new FailedToCommitClusterStateException("non-failed nodes do not form a quorum");
            publicationTargets.stream().filter(PublicationTarget::isActive).forEach(pt -> pt.setFailed(e));
            onPossibleCompletion();
        }
    }

    protected abstract void onCompletion(boolean committed);

    protected abstract boolean isPublishQuorum(CoordinationState.VoteCollection votes);

    protected abstract Optional<ApplyCommitRequest> handlePublishResponse(DiscoveryNode sourceNode, PublishResponse publishResponse);

    protected abstract void onJoin(Join join);

    protected abstract void onMissingJoin(DiscoveryNode discoveryNode);

    protected abstract void sendPublishRequest(DiscoveryNode destination, PublishRequest publishRequest,
                                               ActionListener<PublishWithJoinResponse> responseActionListener);

    protected abstract void sendApplyCommit(DiscoveryNode destination, ApplyCommitRequest applyCommit,
                                            ActionListener<TransportResponse.Empty> responseActionListener); // ApplyCommitResponseHandler

    @Override
    public String toString() {
        return "Publication{term=" + publishRequest.getAcceptedState().term() +
            ", version=" + publishRequest.getAcceptedState().version() + '}';
    }

    void logIncompleteNodes(Level level) {
        final String message = publicationTargets.stream().filter(PublicationTarget::isActive).map(publicationTarget ->
            publicationTarget.getDiscoveryNode() + " [" + publicationTarget.getState() + "]").collect(Collectors.joining(", "));
        if (message.isEmpty() == false) {
            final TimeValue elapsedTime = TimeValue.timeValueMillis(currentTimeSupplier.getAsLong() - startTime);
            logger.log(level, "after [{}] publication of cluster state version [{}] is still waiting for {}", elapsedTime,
                publishRequest.getAcceptedState().version(), message);
        }
    }
     //对每个数据节点的请求都有个状态
    enum PublicationTargetState {
        NOT_STARTED,  // not_started
        FAILED,  // failed  // 二次确认失败了
        SENT_PUBLISH_REQUEST,  // send_publish_request
        WAITING_FOR_QUORUM,  // wait_for_quorum 数据节点已经响应， 等待总共的响应的个数达到以前的一半
        SENT_APPLY_COMMIT, // send_apply_commit  // master向数据节点发送请求，确认这个
        APPLIED_COMMIT,  // applied_commit
    }

    class PublicationTarget {
        private final DiscoveryNode discoveryNode;
        private boolean ackIsPending = true;
        private PublicationTargetState state = PublicationTargetState.NOT_STARTED;

        PublicationTarget(DiscoveryNode discoveryNode) {
            this.discoveryNode = discoveryNode;
        }

        PublicationTargetState getState() {
            return state;
        }

        @Override
        public String toString() {
            return "PublicationTarget{" +
                "discoveryNode=" + discoveryNode +
                ", state=" + state +
                ", ackIsPending=" + ackIsPending +
                '}';
        }

        void sendPublishRequest() {
            if (isFailed()) {
                return;
            }
            assert state == PublicationTargetState.NOT_STARTED : state + " -> " + PublicationTargetState.SENT_PUBLISH_REQUEST;
            state = PublicationTargetState.SENT_PUBLISH_REQUEST;
            Publication.this.sendPublishRequest(discoveryNode, publishRequest, new PublishResponseHandler());
            assert publicationCompletedIffAllTargetsInactiveOrCancelled();
        }

        void handlePublishResponse(PublishResponse publishResponse) {
            assert isWaitingForQuorum() : this;
            logger.trace("handlePublishResponse: handling [{}] from [{}])", publishResponse, discoveryNode);
            if (applyCommitRequest.isPresent()) { // 一般为空
                sendApplyCommit();
            } else {
                try {
                    Publication.this.handlePublishResponse(discoveryNode, publishResponse).ifPresent(applyCommit -> { // 响应节点过半的，继续执行
                        assert applyCommitRequest.isPresent() == false;
                        applyCommitRequest = Optional.of(applyCommit);
                        ackListener.onCommit(TimeValue.timeValueMillis(currentTimeSupplier.getAsLong() - startTime)); // 其实啥都没做
                        publicationTargets.stream().filter(PublicationTarget::isWaitingForQuorum) // 那么就对正在等待过半的节点发送commit请求
                            .forEach(PublicationTarget::sendApplyCommit);
                    });
                } catch (Exception e) {
                    setFailed(e);
                    onPossibleCommitFailure();
                }
            }
        }

        void sendApplyCommit() {
            assert state == PublicationTargetState.WAITING_FOR_QUORUM : state + " -> " + PublicationTargetState.SENT_APPLY_COMMIT;
            state = PublicationTargetState.SENT_APPLY_COMMIT; // 修改状态，为向数据节点确
            assert applyCommitRequest.isPresent();
            Publication.this.sendApplyCommit(discoveryNode, applyCommitRequest.get(), new ApplyCommitResponseHandler());
            assert publicationCompletedIffAllTargetsInactiveOrCancelled();
        }

        void setAppliedCommit() {
            assert state == PublicationTargetState.SENT_APPLY_COMMIT : state + " -> " + PublicationTargetState.APPLIED_COMMIT;
            state = PublicationTargetState.APPLIED_COMMIT;
            ackOnce(null); // 收到一个节点第二次确认后
        }

        void setFailed(Exception e) {
            assert state != PublicationTargetState.APPLIED_COMMIT : state + " -> " + PublicationTargetState.FAILED;
            state = PublicationTargetState.FAILED;
            ackOnce(e);
        }

        void onFaultyNode(DiscoveryNode faultyNode) {
            if (isActive() && discoveryNode.equals(faultyNode)) {
                logger.debug("onFaultyNode: [{}] is faulty, failing target in publication {}", faultyNode, Publication.this);
                setFailed(new ElasticsearchException("faulty node"));
                onPossibleCommitFailure();
            }
        }

        DiscoveryNode getDiscoveryNode() {
            return discoveryNode;
        }

        private void ackOnce(Exception e) { // 收到一个节点第二次确认后，master会跑到这里
            if (ackIsPending) {
                ackIsPending = false;
                ackListener.onNodeAck(discoveryNode, e); // 跑到Coordinator里面的1237行
            }
        }

        boolean isActive() {
            return state != PublicationTargetState.FAILED
                && state != PublicationTargetState.APPLIED_COMMIT;
        }

        boolean isSuccessfullyCompleted() {
            return state == PublicationTargetState.APPLIED_COMMIT;
        }

        boolean isWaitingForQuorum() {
            return state == PublicationTargetState.WAITING_FOR_QUORUM;
        }

        boolean mayCommitInFuture() {
            return (state == PublicationTargetState.NOT_STARTED
                || state == PublicationTargetState.SENT_PUBLISH_REQUEST
                || state == PublicationTargetState.WAITING_FOR_QUORUM);
        }

        boolean isFailed() {
            return state == PublicationTargetState.FAILED;
        }
        //master接收到用户返回的的publish响应
        private class PublishResponseHandler implements ActionListener<PublishWithJoinResponse> {

            @Override
            public void onResponse(PublishWithJoinResponse response) {
                if (isFailed()) { // 被标记成了失败
                    logger.debug("PublishResponseHandler.handleResponse: already failed, ignoring response from [{}]", discoveryNode);
                    assert publicationCompletedIffAllTargetsInactiveOrCancelled();
                    return;
                }

                if (response.getJoin().isPresent()) { // 从数据节点发送的本地term和上次term一样&&数据节点记录的leader也就是本身
                    final Join join = response.getJoin().get();
                    assert discoveryNode.equals(join.getSourceNode());
                    assert join.getTerm() == response.getPublishResponse().getTerm() : response;
                    logger.trace("handling join within publish response: {}", join);
                    onJoin(join); // 正常收到data的response响应
                } else { // 我们或没有获取这个节点的选票
                    logger.trace("publish response from {} contained no join", discoveryNode);
                    onMissingJoin(discoveryNode); // 若有一个节点返回有问题，我们term+1以颠覆任期重新获取选票
                }

                assert state == PublicationTargetState.SENT_PUBLISH_REQUEST : state + " -> " + PublicationTargetState.WAITING_FOR_QUORUM;
                state = PublicationTargetState.WAITING_FOR_QUORUM; //
                handlePublishResponse(response.getPublishResponse()); // 可以进去看下，响应节点可以过半

                assert publicationCompletedIffAllTargetsInactiveOrCancelled();
            }

            @Override
            public void onFailure(Exception e) {
                assert e instanceof TransportException;
                final TransportException exp = (TransportException) e;
                logger.debug(() -> new ParameterizedMessage("PublishResponseHandler: [{}] failed", discoveryNode), exp);
                assert ((TransportException) e).getRootCause() instanceof Exception;
                setFailed((Exception) exp.getRootCause());
                onPossibleCommitFailure();
                assert publicationCompletedIffAllTargetsInactiveOrCancelled();
            }

        }

        private class ApplyCommitResponseHandler implements ActionListener<TransportResponse.Empty> {

            @Override
            public void onResponse(TransportResponse.Empty ignored) {
                if (isFailed()) {
                    logger.debug("ApplyCommitResponseHandler.handleResponse: already failed, ignoring response from [{}]",
                        discoveryNode);
                    return;
                }
                setAppliedCommit(); // 修改这个确定的二次确认为已完成
                onPossibleCompletion();
                assert publicationCompletedIffAllTargetsInactiveOrCancelled();
            }

            @Override
            public void onFailure(Exception e) {
                assert e instanceof TransportException;
                final TransportException exp = (TransportException) e;
                logger.debug(() -> new ParameterizedMessage("ApplyCommitResponseHandler: [{}] failed", discoveryNode), exp);
                assert ((TransportException) e).getRootCause() instanceof Exception;
                setFailed((Exception) exp.getRootCause());
                onPossibleCompletion();
                assert publicationCompletedIffAllTargetsInactiveOrCancelled();
            }
        }
    }
}
