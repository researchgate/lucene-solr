package org.apache.solr.cloud;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.http.client.methods.HttpUriRequest;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.store.Directory;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient.HttpUriRequestResponse;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.CoreAdminRequest.WaitForState;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.ClosableThread;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkCoreNodeProps;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.cloud.ZooKeeperException;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.UpdateParams;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.CoreDescriptor;
import org.apache.solr.core.DirectoryFactory.DirContext;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.ReplicationHandler;
import org.apache.solr.logging.MDCLoggingContext;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.update.CommitUpdateCommand;
import org.apache.solr.update.PeerSync;
import org.apache.solr.update.UpdateLog;
import org.apache.solr.update.UpdateLog.RecoveryInfo;
import org.apache.solr.update.processor.DistributedUpdateProcessor;
import org.apache.solr.util.RefCounted;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RecoveryStrategy extends Thread implements ClosableThread {
  private static final int WAIT_FOR_UPDATES_WITH_STALE_STATE_PAUSE = Integer.getInteger("solr.cloud.wait-for-updates-with-stale-state-pause", 7000);
  private static final int MAX_RETRIES = 500;
  private static final int STARTING_RECOVERY_DELAY = 5000;
  
  private static final String REPLICATION_HANDLER = "/replication";

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static interface RecoveryListener {
    public void recovered();
    public void failed();
  }
  
  private volatile boolean close = false;

  private RecoveryListener recoveryListener;
  private ZkController zkController;
  private String baseUrl;
  private String coreZkNodeName;
  private ZkStateReader zkStateReader;
  private volatile String coreName;
  private int retries;
  private boolean recoveringAfterStartup;
  private CoreContainer cc;
  private volatile HttpUriRequest prevSendPreRecoveryHttpUriRequest;
  
  // this should only be used from SolrCoreState
  public RecoveryStrategy(CoreContainer cc, CoreDescriptor cd, RecoveryListener recoveryListener) {
    this.cc = cc;
    this.coreName = cd.getName();
    this.recoveryListener = recoveryListener;
    setName("RecoveryThread-"+this.coreName);
    zkController = cc.getZkController();
    zkStateReader = zkController.getZkStateReader();
    baseUrl = zkController.getBaseUrl();
    coreZkNodeName = cd.getCloudDescriptor().getCoreNodeName();
  }

  public void setRecoveringAfterStartup(boolean recoveringAfterStartup) {
    this.recoveringAfterStartup = recoveringAfterStartup;
  }

  // make sure any threads stop retrying
  @Override
  public void close() {
    close = true;
    try {
      prevSendPreRecoveryHttpUriRequest.abort();
    } catch (NullPointerException e) {
      // okay
    }
    log.warn("Stopping recovery for core={} coreNodeName={}", coreName, coreZkNodeName);
  }

  
  private void recoveryFailed(final SolrCore core,
      final ZkController zkController, final String baseUrl,
      final String shardZkNodeName, final CoreDescriptor cd) throws KeeperException, InterruptedException {
    SolrException.log(log, "Recovery failed - I give up.");
    try {
      zkController.publish(cd, Replica.State.RECOVERY_FAILED);
    } finally {
      close();
      recoveryListener.failed();
    }
  }
  
  private void replicate(String nodeName, SolrCore core, ZkNodeProps leaderprops)
      throws SolrServerException, IOException {

    ZkCoreNodeProps leaderCNodeProps = new ZkCoreNodeProps(leaderprops);
    String leaderUrl = leaderCNodeProps.getCoreUrl();
    
    log.info("Attempting to replicate from " + leaderUrl + ".");
    
    // send commit
    commitOnLeader(leaderUrl);
    
    // use rep handler directly, so we can do this sync rather than async
    SolrRequestHandler handler = core.getRequestHandler(REPLICATION_HANDLER);
    ReplicationHandler replicationHandler = (ReplicationHandler) handler;
    
    if (replicationHandler == null) {
      throw new SolrException(ErrorCode.SERVICE_UNAVAILABLE,
          "Skipping recovery, no " + REPLICATION_HANDLER + " handler found");
    }
    
    ModifiableSolrParams solrParams = new ModifiableSolrParams();
    solrParams.set(ReplicationHandler.MASTER_URL, leaderUrl);
    
    if (isClosed()) return; // we check closed on return
    boolean success = replicationHandler.doFetch(solrParams, false);
    
    if (!success) {
      throw new SolrException(ErrorCode.SERVER_ERROR, "Replication for recovery failed.");
    }
    
    // solrcloud_debug
    if (log.isDebugEnabled()) {
      try {
        RefCounted<SolrIndexSearcher> searchHolder = core
            .getNewestSearcher(false);
        SolrIndexSearcher searcher = searchHolder.get();
        Directory dir = core.getDirectoryFactory().get(core.getIndexDir(), DirContext.META_DATA, null);
        try {
          log.debug(core.getCoreDescriptor().getCoreContainer()
              .getZkController().getNodeName()
              + " replicated "
              + searcher.search(new MatchAllDocsQuery(), 1).totalHits
              + " from "
              + leaderUrl
              + " gen:"
              + core.getDeletionPolicy().getLatestCommit() != null ? "null" : core.getDeletionPolicy().getLatestCommit().getGeneration()
              + " data:" + core.getDataDir()
              + " index:" + core.getIndexDir()
              + " newIndex:" + core.getNewIndexDir()
              + " files:" + Arrays.asList(dir.listAll()));
        } finally {
          core.getDirectoryFactory().release(dir);
          searchHolder.decref();
        }
      } catch (Exception e) {
        log.debug("Error in solrcloud_debug block", e);
      }
    }

  }

  private void commitOnLeader(String leaderUrl) throws SolrServerException,
      IOException {
    try (HttpSolrClient client = new HttpSolrClient(leaderUrl)) {
      client.setConnectionTimeout(30000);
      UpdateRequest ureq = new UpdateRequest();
      ureq.setParams(new ModifiableSolrParams());
      ureq.getParams().set(DistributedUpdateProcessor.COMMIT_END_POINT, true);
      ureq.getParams().set(UpdateParams.OPEN_SEARCHER, false);
      ureq.setAction(AbstractUpdateRequest.ACTION.COMMIT, false, true).process(
          client);
    }
  }

  @Override
  public void run() {

    // set request info for logging
    try (SolrCore core = cc.getCore(coreName)) {

      if (core == null) {
        SolrException.log(log, "SolrCore not found - cannot recover:" + coreName);
        return;
      }
      MDCLoggingContext.setCore(core);

      log.info("Starting recovery process. recoveringAfterStartup=" + recoveringAfterStartup);

      try {
        doRecovery(core);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        SolrException.log(log, "", e);
        throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR, "", e);
      } catch (Exception e) {
        log.error("", e);
        throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR, "", e);
      }
    } finally {
      MDCLoggingContext.clear();
    }
  }

  // TODO: perhaps make this grab a new core each time through the loop to handle core reloads?
  public void doRecovery(SolrCore core) throws KeeperException, InterruptedException {
    boolean replayed = false;
    boolean successfulRecovery = false;

    UpdateLog ulog;
    ulog = core.getUpdateHandler().getUpdateLog();
    if (ulog == null) {
      SolrException.log(log, "No UpdateLog found - cannot recover.");
      recoveryFailed(core, zkController, baseUrl, coreZkNodeName,
          core.getCoreDescriptor());
      return;
    }

    boolean firstTime = true;

    List<Long> recentVersions;
    try (UpdateLog.RecentUpdates recentUpdates = ulog.getRecentUpdates()) {
      recentVersions = recentUpdates.getVersions(ulog.getNumRecordsToKeep());
    } catch (Exception e) {
      SolrException.log(log, "Corrupt tlog - ignoring.", e);
      recentVersions = new ArrayList<>(0);
    }

    List<Long> startingVersions = ulog.getStartingVersions();

    if (startingVersions != null && recoveringAfterStartup) {
      try {
        int oldIdx = 0; // index of the start of the old list in the current
                        // list
        long firstStartingVersion = startingVersions.size() > 0 ? startingVersions
            .get(0) : 0;
        
        for (; oldIdx < recentVersions.size(); oldIdx++) {
          if (recentVersions.get(oldIdx) == firstStartingVersion) break;
        }
        
        if (oldIdx > 0) {
          log.info("####### Found new versions added after startup: num="
              + oldIdx);
          log.info("###### currentVersions=" + recentVersions);
        }
        
        log.info("###### startupVersions=" + startingVersions);
      } catch (Exception e) {
        SolrException.log(log, "Error getting recent versions.", e);
        recentVersions = new ArrayList<>(0);
      }
    }

    if (recoveringAfterStartup) {
      // if we're recovering after startup (i.e. we have been down), then we need to know what the last versions were
      // when we went down.  We may have received updates since then.
      recentVersions = startingVersions;
      try {
        if ((ulog.getStartingOperation() & UpdateLog.FLAG_GAP) != 0) {
          // last operation at the time of startup had the GAP flag set...
          // this means we were previously doing a full index replication
          // that probably didn't complete and buffering updates in the
          // meantime.
          log.info("Looks like a previous replication recovery did not complete - skipping peer sync.");
          firstTime = false; // skip peersync
        }
      } catch (Exception e) {
        SolrException.log(log, "Error trying to get ulog starting operation.", e);
        firstTime = false; // skip peersync
      }
    }

    Future<RecoveryInfo> replayFuture = null;
    while (!successfulRecovery && !isInterrupted() && !isClosed()) { // don't use interruption or it will close channels though
      try {
        CloudDescriptor cloudDesc = core.getCoreDescriptor()
            .getCloudDescriptor();
        ZkNodeProps leaderprops = zkStateReader.getLeaderRetry(
            cloudDesc.getCollectionName(), cloudDesc.getShardId());
      
        final String leaderBaseUrl = leaderprops.getStr(ZkStateReader.BASE_URL_PROP);
        final String leaderCoreName = leaderprops.getStr(ZkStateReader.CORE_NAME_PROP);

        String leaderUrl = ZkCoreNodeProps.getCoreUrl(leaderBaseUrl, leaderCoreName);

        String ourUrl = ZkCoreNodeProps.getCoreUrl(baseUrl, coreName);

        boolean isLeader = leaderUrl.equals(ourUrl);
        if (isLeader && !cloudDesc.isLeader()) {
          throw new SolrException(ErrorCode.SERVER_ERROR, "Cloud state still says we are leader.");
        }
        if (cloudDesc.isLeader()) {
          // we are now the leader - no one else must have been suitable
          log.warn("We have not yet recovered - but we are now the leader!");
          log.info("Finished recovery process.");
          zkController.publish(core.getCoreDescriptor(), Replica.State.ACTIVE);
          return;
        }
        
        log.info("Begin buffering updates. core=" + coreName);
        ulog.bufferUpdates();
        replayed = false;
        
        log.info("Publishing state of core " + core.getName() + " as recovering, leader is " + leaderUrl + " and I am "
            + ourUrl);
        zkController.publish(core.getCoreDescriptor(), Replica.State.RECOVERING);
        
        
        final Slice slice = zkStateReader.getClusterState().getSlice(cloudDesc.getCollectionName(), cloudDesc.getShardId());

        try {
          prevSendPreRecoveryHttpUriRequest.abort();
        } catch (NullPointerException e) {
          // okay
        }
        
        if (isClosed()) {
          log.info("Recovery was cancelled");
          break;
        }

        sendPrepRecoveryCmd(leaderBaseUrl, leaderCoreName, slice);
        
        if (isClosed()) {
          log.info("Recovery was cancelled");
          break;
        }
        
        // we wait a bit so that any updates on the leader
        // that started before they saw recovering state 
        // are sure to have finished (see SOLR-7141 for
        // discussion around current value)
        try {
          Thread.sleep(WAIT_FOR_UPDATES_WITH_STALE_STATE_PAUSE);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }

        // first thing we just try to sync
        if (firstTime) {
          firstTime = false; // only try sync the first time through the loop
          log.info("Attempting to PeerSync from " + leaderUrl + " - recoveringAfterStartup="+recoveringAfterStartup);
          // System.out.println("Attempting to PeerSync from " + leaderUrl
          // + " i am:" + zkController.getNodeName());
          PeerSync peerSync = new PeerSync(core,
              Collections.singletonList(leaderUrl), ulog.getNumRecordsToKeep(), false, false);
          peerSync.setStartingVersions(recentVersions);
          boolean syncSuccess = peerSync.sync();
          if (syncSuccess) {
            SolrQueryRequest req = new LocalSolrQueryRequest(core,
                new ModifiableSolrParams());
            // force open a new searcher
            core.getUpdateHandler().commit(new CommitUpdateCommand(req, false));
            log.info("PeerSync stage of recovery was successful.");

            // solrcloud_debug
            if (log.isDebugEnabled()) {
              try {
                RefCounted<SolrIndexSearcher> searchHolder = core
                    .getNewestSearcher(false);
                SolrIndexSearcher searcher = searchHolder.get();
                try {
                  log.debug(core.getCoreDescriptor()
                      .getCoreContainer().getZkController().getNodeName()
                      + " synched "
                      + searcher.search(new MatchAllDocsQuery(), 1).totalHits);
                } finally {
                  searchHolder.decref();
                }
              } catch (Exception e) {
                log.debug("Error in solrcloud_debug block", e);
              }
            }
            log.info("Replaying updates buffered during PeerSync.");
            replay(core);
            replayed = true;
            
            // sync success
            successfulRecovery = true;
            return;
          }

          log.info("PeerSync Recovery was not successful - trying replication.");
        }

        if (isClosed()) {
          log.info("Recovery was cancelled");
          break;
        }
        
        log.info("Starting Replication Recovery.");

        try {

          replicate(zkController.getNodeName(), core, leaderprops);

          if (isClosed()) {
            log.info("Recovery was cancelled");
            break;
          }

          replayFuture = replay(core);
          replayed = true;
          
          if (isClosed()) {
            log.info("Recovery was cancelled");
            break;
          }

          log.info("Replication Recovery was successful.");
          successfulRecovery = true;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn("Recovery was interrupted", e);
          close = true;
        } catch (Exception e) {
          SolrException.log(log, "Error while trying to recover", e);
        }

      } catch (Exception e) {
        SolrException.log(log, "Error while trying to recover. core=" + coreName, e);
      } finally {
        if (!replayed) {
          try {
            ulog.dropBufferedUpdates();
          } catch (Exception e) {
            SolrException.log(log, "", e);
          }
        }
        if (successfulRecovery) {
          log.info("Registering as Active after recovery.");
          try {
            zkController.publish(core.getCoreDescriptor(), Replica.State.ACTIVE);
          } catch (Exception e) {
            log.error("Could not publish as ACTIVE after succesful recovery", e);
            successfulRecovery = false;
          }
          
          if (successfulRecovery) {
            close = true;
            recoveryListener.recovered();
          }
        }
      }

      if (!successfulRecovery) {
        // lets pause for a moment and we need to try again...
        // TODO: we don't want to retry for some problems?
        // Or do a fall off retry...
        try {

          if (isClosed()) {
            break;
          }
          
          log.error("Recovery failed - trying again... (" + retries + ")");
          
          retries++;
          if (retries >= MAX_RETRIES) {
            SolrException.log(log, "Recovery failed - max retries exceeded (" + retries + ").");
            try {
              recoveryFailed(core, zkController, baseUrl, coreZkNodeName, core.getCoreDescriptor());
            } catch (Exception e) {
              SolrException.log(log, "Could not publish that recovery failed", e);
            }
            break;
          }
        } catch (Exception e) {
          SolrException.log(log, "", e);
        }

        try {
          // start at 1 sec and work up to a min
          double loopCount = Math.min(Math.pow(2, retries), 60);
          log.info("Wait {} seconds before trying to recover again ({})", loopCount, retries);
          for (int i = 0; i < loopCount; i++) {
            if (isClosed()) break; // check if someone closed us
            Thread.sleep(STARTING_RECOVERY_DELAY);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn("Recovery was interrupted.", e);
          close = true;
        }
      }

    }

    // if replay was skipped (possibly to due pulling a full index from the leader),
    // then we still need to update version bucket seeds after recovery
    if (successfulRecovery && replayFuture == null) {
      log.info("Updating version bucket highest from index after successful recovery.");
      core.seedVersionBuckets();
    }

    log.info("Finished recovery process.");

    
  }

  private Future<RecoveryInfo> replay(SolrCore core)
      throws InterruptedException, ExecutionException {
    Future<RecoveryInfo> future = core.getUpdateHandler().getUpdateLog().applyBufferedUpdates();
    if (future == null) {
      // no replay needed\
      log.info("No replay needed.");
    } else {
      log.info("Replaying buffered documents.");
      // wait for replay
      RecoveryInfo report = future.get();
      if (report.failed) {
        SolrException.log(log, "Replay failed");
        throw new SolrException(ErrorCode.SERVER_ERROR, "Replay failed");
      }
    }
    
    // solrcloud_debug
    if (log.isDebugEnabled()) {
      try {
        RefCounted<SolrIndexSearcher> searchHolder = core
            .getNewestSearcher(false);
        SolrIndexSearcher searcher = searchHolder.get();
        try {
          log.debug(core.getCoreDescriptor().getCoreContainer()
              .getZkController().getNodeName()
              + " replayed "
              + searcher.search(new MatchAllDocsQuery(), 1).totalHits);
        } finally {
          searchHolder.decref();
        }
      } catch (Exception e) {
        log.debug("Error in solrcloud_debug block", e);
      }
    }
    
    return future;
  }

  @Override
  public boolean isClosed() {
    return close;
  }
  
  private void sendPrepRecoveryCmd(String leaderBaseUrl, String leaderCoreName, Slice slice)
      throws SolrServerException, IOException, InterruptedException, ExecutionException {

    try (HttpSolrClient client = new HttpSolrClient(leaderBaseUrl)) {
      client.setConnectionTimeout(30000);
      WaitForState prepCmd = new WaitForState();
      prepCmd.setCoreName(leaderCoreName);
      prepCmd.setNodeName(zkController.getNodeName());
      prepCmd.setCoreNodeName(coreZkNodeName);
      prepCmd.setState(Replica.State.RECOVERING);
      prepCmd.setCheckLive(true);
      prepCmd.setOnlyIfLeader(true);
      final Slice.State state = slice.getState();
      if (state != Slice.State.CONSTRUCTION && state != Slice.State.RECOVERY) {
        prepCmd.setOnlyIfLeaderActive(true);
      }
      HttpUriRequestResponse mrr = client.httpUriRequest(prepCmd);
      prevSendPreRecoveryHttpUriRequest = mrr.httpUriRequest;
      
      log.info("Sending prep recovery command to {}; {}", leaderBaseUrl, prepCmd.toString());
      
      mrr.future.get();
    }
  }

}
