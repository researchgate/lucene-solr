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
package org.apache.solr.handler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Adler32;
import java.util.zip.Checksum;
import java.util.zip.DeflaterOutputStream;

import org.apache.commons.io.IOUtils;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexDeletionPolicy;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.RateLimiter;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.FastOutputStream;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.SuppressForbidden;
import org.apache.solr.core.CloseHook;
import org.apache.solr.core.DirectoryFactory;
import org.apache.solr.core.DirectoryFactory.DirContext;
import org.apache.solr.core.IndexDeletionPolicyWrapper;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrDeletionPolicy;
import org.apache.solr.core.SolrEventListener;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.update.CdcrUpdateLog;
import org.apache.solr.update.SolrIndexWriter;
import org.apache.solr.update.UpdateLog;
import org.apache.solr.util.DefaultSolrThreadFactory;
import org.apache.solr.util.NumberUtils;
import org.apache.solr.util.PropertiesInputStream;
import org.apache.solr.util.RefCounted;
import org.apache.solr.util.plugin.SolrCoreAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.apache.solr.common.params.CommonParams.NAME;

/**
 * <p> A Handler which provides a REST API for replication and serves replication requests from Slaves. </p>
 * <p>When running on the master, it provides the following commands <ol> <li>Get the current replicable index version
 * (command=indexversion)</li> <li>Get the list of files for a given index version
 * (command=filelist&amp;indexversion=&lt;VERSION&gt;)</li> <li>Get full or a part (chunk) of a given index or a config
 * file (command=filecontent&amp;file=&lt;FILE_NAME&gt;) You can optionally specify an offset and length to get that
 * chunk of the file. You can request a configuration file by using "cf" parameter instead of the "file" parameter.</li>
 * <li>Get status/statistics (command=details)</li> </ol> <p>When running on the slave, it provides the following
 * commands <ol> <li>Perform an index fetch now (command=snappull)</li> <li>Get status/statistics (command=details)</li>
 * <li>Abort an index fetch (command=abort)</li> <li>Enable/Disable polling the master for new versions (command=enablepoll
 * or command=disablepoll)</li> </ol>
 *
 *
 * @since solr 1.4
 */
public class ReplicationHandler extends RequestHandlerBase implements SolrCoreAware {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  SolrCore core;
  
  private volatile boolean closed = false;

  private static final class CommitVersionInfo {
    public final long version;
    public final long generation;
    private CommitVersionInfo(long g, long v) {
      generation = g;
      version = v;
    }
    /**
     * builds a CommitVersionInfo data for the specified IndexCommit.
     * Will never be null, ut version and generation may be zero if
     * there are problems extracting them from the commit data
     */
    public static CommitVersionInfo build(IndexCommit commit) {
      long generation = commit.getGeneration();
      long version = 0;
      try {
        final Map<String,String> commitData = commit.getUserData();
        String commitTime = commitData.get(SolrIndexWriter.COMMIT_TIME_MSEC_KEY);
        if (commitTime != null) {
          try {
            version = Long.parseLong(commitTime);
          } catch (NumberFormatException e) {
            LOG.warn("Version in commitData was not formated correctly: " + commitTime, e);
          }
        }
      } catch (IOException e) {
        LOG.warn("Unable to get version from commitData, commit: " + commit, e);
      }
      return new CommitVersionInfo(generation, version);
    }
  }

  private IndexFetcher pollingIndexFetcher;

  private ReentrantLock indexFetchLock = new ReentrantLock();

  private ExecutorService restoreExecutor = ExecutorUtil.newMDCAwareSingleThreadExecutor(
      new DefaultSolrThreadFactory("restoreExecutor"));

  private volatile Future<Boolean> restoreFuture;

  private volatile String currentRestoreName;

  private String includeConfFiles;

  private NamedList<String> confFileNameAlias = new NamedList<>();

  private boolean isMaster = false;

  private boolean isSlave = false;

  private boolean replicateOnOptimize = false;

  private boolean replicateOnCommit = false;

  private boolean replicateOnStart = false;

  private ScheduledExecutorService executorService;

  private volatile long executorStartTime;

  private int numberBackupsToKeep = 0; //zero: do not delete old backups

  private int numTimesReplicated = 0;

  private final Map<String, FileInfo> confFileInfoCache = new HashMap<>();

  private Integer reserveCommitDuration = readIntervalMs("00:00:10");

  volatile IndexCommit indexCommitPoint;

  volatile NamedList<Object> snapShootDetails;

  private AtomicBoolean replicationEnabled = new AtomicBoolean(true);

  private Long pollIntervalNs;
  private String pollIntervalStr;

  /**
   * Disable the timer task for polling
   */
  private AtomicBoolean pollDisabled = new AtomicBoolean(false);

  String getPollInterval() {
    return pollIntervalStr;
  }

  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    rsp.setHttpCaching(false);
    final SolrParams solrParams = req.getParams();
    String command = solrParams.get(COMMAND);
    if (command == null) {
      rsp.add(STATUS, OK_STATUS);
      rsp.add("message", "No command");
      return;
    }
    // This command does not give the current index version of the master
    // It gives the current 'replicateable' index version
    if (command.equals(CMD_INDEX_VERSION)) {
      IndexCommit commitPoint = indexCommitPoint;  // make a copy so it won't change

      if (commitPoint == null) {
        // if this handler is 'lazy', we may not have tracked the last commit
        // because our commit listener is registered on inform
        commitPoint = core.getDeletionPolicy().getLatestCommit();
      }

      if (commitPoint != null && replicationEnabled.get()) {
        //
        // There is a race condition here.  The commit point may be changed / deleted by the time
        // we get around to reserving it.  This is a very small window though, and should not result
        // in a catastrophic failure, but will result in the client getting an empty file list for
        // the CMD_GET_FILE_LIST command.
        //
        core.getDeletionPolicy().setReserveDuration(commitPoint.getGeneration(), reserveCommitDuration);
        rsp.add(CMD_INDEX_VERSION, IndexDeletionPolicyWrapper.getCommitTimestamp(commitPoint));
        rsp.add(GENERATION, commitPoint.getGeneration());
      } else {
        // This happens when replication is not configured to happen after startup and no commit/optimize
        // has happened yet.
        rsp.add(CMD_INDEX_VERSION, 0L);
        rsp.add(GENERATION, 0L);
      }
    } else if (command.equals(CMD_GET_FILE)) {
      getFileStream(solrParams, rsp);
    } else if (command.equals(CMD_GET_FILE_LIST)) {
      getFileList(solrParams, rsp);
    } else if (command.equalsIgnoreCase(CMD_BACKUP)) {
      doSnapShoot(new ModifiableSolrParams(solrParams), rsp, req);
      rsp.add(STATUS, OK_STATUS);
    } else if (command.equalsIgnoreCase(CMD_RESTORE)) {
      restore(new ModifiableSolrParams(solrParams), rsp, req);
      rsp.add(STATUS, OK_STATUS);
    } else if (command.equalsIgnoreCase(CMD_RESTORE_STATUS)) {
      rsp.add(CMD_RESTORE_STATUS, getRestoreStatus());
    } else if (command.equalsIgnoreCase(CMD_DELETE_BACKUP)) {
      deleteSnapshot(new ModifiableSolrParams(solrParams));
      rsp.add(STATUS, OK_STATUS);
    } else if (command.equalsIgnoreCase(CMD_FETCH_INDEX)) {
      String masterUrl = solrParams.get(MASTER_URL);
      if (!isSlave && masterUrl == null) {
        rsp.add(STATUS,ERR_STATUS);
        rsp.add("message","No slave configured or no 'masterUrl' Specified");
        return;
      }
      final SolrParams paramsCopy = new ModifiableSolrParams(solrParams);
      Thread fetchThread = new Thread("explicit-fetchindex-cmd") {
        @Override
        public void run() {
          doFetch(paramsCopy, false);
        }
      };
      fetchThread.setDaemon(false);
      fetchThread.start();
      if (solrParams.getBool(WAIT, false)) {
        fetchThread.join();
      }
      rsp.add(STATUS, OK_STATUS);
    } else if (command.equalsIgnoreCase(CMD_DISABLE_POLL)) {
      if (pollingIndexFetcher != null){
        disablePoll();
        rsp.add(STATUS, OK_STATUS);
      } else {
        rsp.add(STATUS, ERR_STATUS);
        rsp.add("message","No slave configured");
      }
    } else if (command.equalsIgnoreCase(CMD_ENABLE_POLL)) {
      if (pollingIndexFetcher != null){
        enablePoll();
        rsp.add(STATUS, OK_STATUS);
      }else {
        rsp.add(STATUS,ERR_STATUS);
        rsp.add("message","No slave configured");
      }
    } else if (command.equalsIgnoreCase(CMD_ABORT_FETCH)) {
      IndexFetcher fetcher = currentIndexFetcher;
      if (fetcher != null){
        fetcher.abortFetch();
        rsp.add(STATUS, OK_STATUS);
      } else {
        rsp.add(STATUS,ERR_STATUS);
        rsp.add("message","No slave configured");
      }
    } else if (command.equals(CMD_SHOW_COMMITS)) {
      rsp.add(CMD_SHOW_COMMITS, getCommits());
    } else if (command.equals(CMD_DETAILS)) {
      rsp.add(CMD_DETAILS, getReplicationDetails(solrParams.getBool("slave", true)));
    } else if (CMD_ENABLE_REPL.equalsIgnoreCase(command)) {
      replicationEnabled.set(true);
      rsp.add(STATUS, OK_STATUS);
    } else if (CMD_DISABLE_REPL.equalsIgnoreCase(command)) {
      replicationEnabled.set(false);
      rsp.add(STATUS, OK_STATUS);
    }
  }

  private void deleteSnapshot(ModifiableSolrParams params) {
    String name = params.get(NAME);
    if(name == null) {
      throw new SolrException(ErrorCode.BAD_REQUEST, "Missing mandatory param: name");
    }

    SnapShooter snapShooter = new SnapShooter(core, params.get(LOCATION), params.get(NAME));
    snapShooter.validateDeleteSnapshot();
    snapShooter.deleteSnapAsync(this);
  }

  private List<NamedList<Object>> getCommits() {
    Map<Long, IndexCommit> commits = core.getDeletionPolicy().getCommits();
    List<NamedList<Object>> l = new ArrayList<>();

    for (IndexCommit c : commits.values()) {
      try {
        NamedList<Object> nl = new NamedList<>();
        nl.add("indexVersion", IndexDeletionPolicyWrapper.getCommitTimestamp(c));
        nl.add(GENERATION, c.getGeneration());
        List<String> commitList = new ArrayList<>(c.getFileNames().size());
        commitList.addAll(c.getFileNames());
        Collections.sort(commitList);
        nl.add(CMD_GET_FILE_LIST, commitList);
        l.add(nl);
      } catch (IOException e) {
        LOG.warn("Exception while reading files for commit " + c, e);
      }
    }
    return l;
  }

  static Long getCheckSum(Checksum checksum, File f) {
    FileInputStream fis = null;
    checksum.reset();
    byte[] buffer = new byte[1024 * 1024];
    int bytesRead;
    try {
      fis = new FileInputStream(f);
      while ((bytesRead = fis.read(buffer)) >= 0)
        checksum.update(buffer, 0, bytesRead);
      return checksum.getValue();
    } catch (Exception e) {
      LOG.warn("Exception in finding checksum of " + f, e);
    } finally {
      IOUtils.closeQuietly(fis);
    }
    return null;
  }

  private volatile IndexFetcher currentIndexFetcher;

  public boolean doFetch(SolrParams solrParams, boolean forceReplication) {
    String masterUrl = solrParams == null ? null : solrParams.get(MASTER_URL);
    if (!indexFetchLock.tryLock())
      return false;
    try {
      if (masterUrl != null) {
        if (currentIndexFetcher != null && currentIndexFetcher != pollingIndexFetcher) {
          currentIndexFetcher.destroy();
        }
        currentIndexFetcher = new IndexFetcher(solrParams.toNamedList(), this, core);
      } else {
        currentIndexFetcher = pollingIndexFetcher;
      }
      return currentIndexFetcher.fetchLatestIndex(forceReplication);
    } catch (Exception e) {
      SolrException.log(LOG, "Index fetch failed ", e);
    } finally {
      if (pollingIndexFetcher != null) {
        currentIndexFetcher = pollingIndexFetcher;
      }
      indexFetchLock.unlock();
    }
    return false;
  }

  boolean isReplicating() {
    return indexFetchLock.isLocked();
  }

  private void restore(SolrParams params, SolrQueryResponse rsp, SolrQueryRequest req) {
    if (restoreFuture != null && !restoreFuture.isDone()) {
      throw new SolrException(ErrorCode.BAD_REQUEST, "Restore in progress. Cannot run multiple restore operations" +
          "for the same core");
    }
    String name = params.get(NAME);
    String location = params.get(LOCATION);

    //If location is not provided then assume that the restore index is present inside the data directory.
    if (location == null) {
      location = core.getDataDir();
    }

    //If name is not provided then look for the last unnamed( the ones with the snapshot.timestamp format)
    //snapshot folder since we allow snapshots to be taken without providing a name. Pick the latest timestamp.
    if (name == null) {
      File[] files = new File(location).listFiles();
      List<OldBackupDirectory> dirs = new ArrayList<>();
      for (File f : files) {
        OldBackupDirectory obd = new OldBackupDirectory(f);
        if (obd.dir != null) {
          dirs.add(obd);
        }
      }
      Collections.sort(dirs);
      if (dirs.size() == 0) {
        throw new SolrException(ErrorCode.BAD_REQUEST, "No backup name specified and none found in " + core.getDataDir());
      }
      name = dirs.get(0).dir.getName();
    } else {
      //"snapshot." is prefixed by snapshooter
      name = "snapshot." + name;
    }

    RestoreCore restoreCore = new RestoreCore(core, location, name);
    try {
      MDC.put("RestoreCore.core", core.getName());
      MDC.put("RestoreCore.backupLocation", location);
      MDC.put("RestoreCore.backupName", name);
      restoreFuture = restoreExecutor.submit(restoreCore);
      currentRestoreName = name;
    } finally {
      MDC.remove("RestoreCore.core");
      MDC.remove("RestoreCore.backupLocation");
      MDC.remove("RestoreCore.backupName");
    }
  }

  private NamedList<Object> getRestoreStatus() {
    NamedList<Object> status = new SimpleOrderedMap<>();

    if (restoreFuture == null) {
      status.add(STATUS, "No restore actions in progress");
      return status;
    }

    status.add("snapshotName", currentRestoreName);
    if (restoreFuture.isDone()) {
      try {
        boolean success = restoreFuture.get();
        if (success) {
          status.add(STATUS, SUCCESS);
        } else {
          status.add(STATUS, FAILED);
        }
      } catch (Exception e) {
        status.add(STATUS, FAILED);
        status.add(EXCEPTION, e.getMessage());
      }
    } else {
      status.add(STATUS, "In Progress");
    }
    return status;
  }

  private void doSnapShoot(SolrParams params, SolrQueryResponse rsp,
      SolrQueryRequest req) {
    try {
      int numberToKeep = params.getInt(NUMBER_BACKUPS_TO_KEEP_REQUEST_PARAM, 0);
      if (numberToKeep > 0 && numberBackupsToKeep > 0) {
        throw new SolrException(ErrorCode.BAD_REQUEST, "Cannot use "
            + NUMBER_BACKUPS_TO_KEEP_REQUEST_PARAM + " if "
            + NUMBER_BACKUPS_TO_KEEP_INIT_PARAM
            + " was specified in the configuration.");
      }
      numberToKeep = Math.max(numberToKeep, numberBackupsToKeep);
      if (numberToKeep < 1) {
        numberToKeep = Integer.MAX_VALUE;
      }

      IndexDeletionPolicyWrapper delPolicy = core.getDeletionPolicy();
      IndexCommit indexCommit = delPolicy.getLatestCommit();

      if (indexCommit == null) {
        indexCommit = req.getSearcher().getIndexReader().getIndexCommit();
      }

      // small race here before the commit point is saved
      SnapShooter snapShooter = new SnapShooter(core, params.get("location"), params.get(NAME));
      snapShooter.validateCreateSnapshot();
      snapShooter.createSnapAsync(indexCommit, numberToKeep, this);

    } catch (Exception e) {
      LOG.warn("Exception during creating a snapshot", e);
      rsp.add("exception", e);
    }
  }

  /**
   * This method adds an Object of FileStream to the response . The FileStream implements a custom protocol which is
   * understood by IndexFetcher.FileFetcher
   *
   * @see IndexFetcher.LocalFsFileFetcher
   * @see IndexFetcher.DirectoryFileFetcher
   */
  private void getFileStream(SolrParams solrParams, SolrQueryResponse rsp) {
    ModifiableSolrParams rawParams = new ModifiableSolrParams(solrParams);
    rawParams.set(CommonParams.WT, FILE_STREAM);

    String cfileName = solrParams.get(CONF_FILE_SHORT);
    String tlogFileName = solrParams.get(TLOG_FILE);
    if (cfileName != null) {
      rsp.add(FILE_STREAM, new LocalFsConfFileStream(solrParams));
    } else if (tlogFileName != null) {
      rsp.add(FILE_STREAM, new LocalFsTlogFileStream(solrParams));
    } else {
      rsp.add(FILE_STREAM, new DirectoryFileStream(solrParams));
    }
  }

  @SuppressWarnings("unchecked")
  private void getFileList(SolrParams solrParams, SolrQueryResponse rsp) {
    String v = solrParams.get(GENERATION);
    if (v == null) {
      rsp.add("status", "no index generation specified");
      return;
    }
    long gen = Long.parseLong(v);
    IndexCommit commit = core.getDeletionPolicy().getCommitPoint(gen);

    //System.out.println("ask for files for gen:" + commit.getGeneration() + core.getCoreDescriptor().getCoreContainer().getZkController().getNodeName());
    if (commit == null) {
      rsp.add("status", "invalid index generation");
      return;
    }
    // reserve the indexcommit for sometime
    core.getDeletionPolicy().setReserveDuration(gen, reserveCommitDuration);
    List<Map<String, Object>> result = new ArrayList<>();
    Directory dir = null;
    try {
      dir = core.getDirectoryFactory().get(core.getNewIndexDir(), DirContext.DEFAULT, core.getSolrConfig().indexConfig.lockType);
      SegmentInfos infos = SegmentInfos.readCommit(dir, commit.getSegmentsFileName());
      for (SegmentCommitInfo commitInfo : infos) {
        for (String file : commitInfo.files()) {
          Map<String,Object> fileMeta = new HashMap<>();
          fileMeta.put(NAME, file);
          fileMeta.put(SIZE, dir.fileLength(file));

          try (final IndexInput in = dir.openInput(file, IOContext.READONCE)) {
            try {
              long checksum = CodecUtil.retrieveChecksum(in);
              fileMeta.put(CHECKSUM, checksum);
            } catch(Exception e) {
              LOG.warn("Could not read checksum from index file: " + file, e);
            }
          }

          result.add(fileMeta);
        }
      }

      // add the segments_N file

      Map<String,Object> fileMeta = new HashMap<>();
      fileMeta.put(NAME, infos.getSegmentsFileName());
      fileMeta.put(SIZE, dir.fileLength(infos.getSegmentsFileName()));
      if (infos.getId() != null) {
        try (final IndexInput in = dir.openInput(infos.getSegmentsFileName(), IOContext.READONCE)) {
          try {
            fileMeta.put(CHECKSUM, CodecUtil.retrieveChecksum(in));
          } catch(Exception e) {
             LOG.warn("Could not read checksum from index file: " + infos.getSegmentsFileName(), e);
          }
        }
      }
      result.add(fileMeta);
    } catch (IOException e) {
      rsp.add("status", "unable to get file names for given index generation");
      rsp.add(EXCEPTION, e);
      LOG.error("Unable to get file names for indexCommit generation: " + gen, e);
    } finally {
      if (dir != null) {
        try {
          core.getDirectoryFactory().release(dir);
        } catch (IOException e) {
          SolrException.log(LOG, "Could not release directory after fetching file list", e);
        }
      }
    }
    rsp.add(CMD_GET_FILE_LIST, result);

    // fetch list of tlog files only if cdcr is activated
    if (core.getUpdateHandler().getUpdateLog() != null && core.getUpdateHandler().getUpdateLog() instanceof CdcrUpdateLog) {
      List<Map<String, Object>> tlogfiles = getTlogFileList();
      LOG.info("Adding tlog files to list: " + tlogfiles);
      rsp.add(TLOG_FILES, tlogfiles);
    }

    if (confFileNameAlias.size() < 1 || core.getCoreDescriptor().getCoreContainer().isZooKeeperAware())
      return;
    LOG.debug("Adding config files to list: " + includeConfFiles);
    //if configuration files need to be included get their details
    rsp.add(CONF_FILES, getConfFileInfoFromCache(confFileNameAlias, confFileInfoCache));
  }

  List<Map<String, Object>> getTlogFileList() {
    UpdateLog ulog = core.getUpdateHandler().getUpdateLog();
    String[] logList = ulog.getLogList(new File(ulog.getLogDir()));
    List<Map<String, Object>> tlogFiles = new ArrayList<>();
    for (String fileName : logList) {
      Map<String, Object> fileMeta = new HashMap<>();
      fileMeta.put(NAME, fileName);
      fileMeta.put(SIZE, new File(ulog.getLogDir(), fileName).length());
      tlogFiles.add(fileMeta);
    }
    return tlogFiles;
  }

  /**
   * For configuration files, checksum of the file is included because, unlike index files, they may have same content
   * but different timestamps.
   * <p/>
   * The local conf files information is cached so that everytime it does not have to compute the checksum. The cache is
   * refreshed only if the lastModified of the file changes
   */
  List<Map<String, Object>> getConfFileInfoFromCache(NamedList<String> nameAndAlias,
                                                     final Map<String, FileInfo> confFileInfoCache) {
    List<Map<String, Object>> confFiles = new ArrayList<>();
    synchronized (confFileInfoCache) {
      File confDir = new File(core.getResourceLoader().getConfigDir());
      Checksum checksum = null;
      for (int i = 0; i < nameAndAlias.size(); i++) {
        String cf = nameAndAlias.getName(i);
        File f = new File(confDir, cf);
        if (!f.exists() || f.isDirectory()) continue; //must not happen
        FileInfo info = confFileInfoCache.get(cf);
        if (info == null || info.lastmodified != f.lastModified() || info.size != f.length()) {
          if (checksum == null) checksum = new Adler32();
          info = new FileInfo(f.lastModified(), cf, f.length(), getCheckSum(checksum, f));
          confFileInfoCache.put(cf, info);
        }
        Map<String, Object> m = info.getAsMap();
        if (nameAndAlias.getVal(i) != null) m.put(ALIAS, nameAndAlias.getVal(i));
        confFiles.add(m);
      }
    }
    return confFiles;
  }

  static class FileInfo {
    long lastmodified;
    String name;
    long size;
    long checksum;

    public FileInfo(long lasmodified, String name, long size, long checksum) {
      this.lastmodified = lasmodified;
      this.name = name;
      this.size = size;
      this.checksum = checksum;
    }

    Map<String, Object> getAsMap() {
      Map<String, Object> map = new HashMap<>();
      map.put(NAME, name);
      map.put(SIZE, size);
      map.put(CHECKSUM, checksum);
      return map;
    }
  }

  void disablePoll() {
    if (isSlave) {
      pollDisabled.set(true);
      LOG.info("inside disable poll, value of pollDisabled = " + pollDisabled);
    }
  }

  void enablePoll() {
    if (isSlave) {
      pollDisabled.set(false);
      LOG.info("inside enable poll, value of pollDisabled = " + pollDisabled);
    }
  }

  boolean isPollingDisabled() {
    return pollDisabled.get();
  }

  @SuppressForbidden(reason = "Need currentTimeMillis, to output next execution time in replication details")
  private void markScheduledExecutionStart() {
    executorStartTime = System.currentTimeMillis();
  }

  private Date getNextScheduledExecTime() {
    Date nextTime = null;
    if (executorStartTime > 0)
      nextTime = new Date(executorStartTime + TimeUnit.MILLISECONDS.convert(pollIntervalNs, TimeUnit.NANOSECONDS));
    return nextTime;
  }

  int getTimesReplicatedSinceStartup() {
    return numTimesReplicated;
  }

  void setTimesReplicatedSinceStartup() {
    numTimesReplicated++;
  }

  long getIndexSize() {
    Directory dir;
    long size = 0;
    try {
      dir = core.getDirectoryFactory().get(core.getIndexDir(), DirContext.DEFAULT, core.getSolrConfig().indexConfig.lockType);
      try {
        size = DirectoryFactory.sizeOfDirectory(dir);
      } finally {
        core.getDirectoryFactory().release(dir);
      }
    } catch (IOException e) {
      SolrException.log(LOG, "IO error while trying to get the size of the Directory", e);
    }
    return size;
  }

  @Override
  public String getDescription() {
    return "ReplicationHandler provides replication of index and configuration files from Master to Slaves";
  }

  /**
   * returns the CommitVersionInfo for the current searcher, or null on error.
   */
  private CommitVersionInfo getIndexVersion() {
    CommitVersionInfo v = null;
    RefCounted<SolrIndexSearcher> searcher = core.getSearcher();
    try {
      v = CommitVersionInfo.build(searcher.get().getIndexReader().getIndexCommit());
    } catch (IOException e) {
      LOG.warn("Unable to get index commit: ", e);
    } finally {
      searcher.decref();
    }
    return v;
  }

  @Override
  @SuppressWarnings("unchecked")
  public NamedList getStatistics() {
    NamedList list = super.getStatistics();
    if (core != null) {
      list.add("indexSize", NumberUtils.readableSize(getIndexSize()));
      CommitVersionInfo vInfo = (core != null && !core.isClosed()) ? getIndexVersion(): null;
      list.add("indexVersion", null == vInfo ? 0 : vInfo.version);
      list.add(GENERATION, null == vInfo ? 0 : vInfo.generation);

      list.add("indexPath", core.getIndexDir());
      list.add("isMaster", String.valueOf(isMaster));
      list.add("isSlave", String.valueOf(isSlave));

      IndexFetcher fetcher = currentIndexFetcher;
      if (fetcher != null) {
        list.add(MASTER_URL, fetcher.getMasterUrl());
        if (getPollInterval() != null) {
          list.add(POLL_INTERVAL, getPollInterval());
        }
        list.add("isPollingDisabled", String.valueOf(isPollingDisabled()));
        list.add("isReplicating", String.valueOf(isReplicating()));
        long elapsed = fetcher.getReplicationTimeElapsed();
        long val = fetcher.getTotalBytesDownloaded();
        if (elapsed > 0) {
          list.add("timeElapsed", elapsed);
          list.add("bytesDownloaded", val);
          list.add("downloadSpeed", val / elapsed);
        }
        Properties props = loadReplicationProperties();
        addVal(list, IndexFetcher.PREVIOUS_CYCLE_TIME_TAKEN, props, Long.class);
        addVal(list, IndexFetcher.INDEX_REPLICATED_AT, props, Date.class);
        addVal(list, IndexFetcher.CONF_FILES_REPLICATED_AT, props, Date.class);
        addVal(list, IndexFetcher.REPLICATION_FAILED_AT, props, Date.class);
        addVal(list, IndexFetcher.TIMES_FAILED, props, Integer.class);
        addVal(list, IndexFetcher.TIMES_INDEX_REPLICATED, props, Integer.class);
        addVal(list, IndexFetcher.LAST_CYCLE_BYTES_DOWNLOADED, props, Long.class);
        addVal(list, IndexFetcher.TIMES_CONFIG_REPLICATED, props, Integer.class);
        addVal(list, IndexFetcher.CONF_FILES_REPLICATED, props, String.class);
      }
      if (isMaster) {
        if (includeConfFiles != null) list.add("confFilesToReplicate", includeConfFiles);
        list.add(REPLICATE_AFTER, getReplicateAfterStrings());
        list.add("replicationEnabled", String.valueOf(replicationEnabled.get()));
      }
    }
    return list;
  }

  /**
   * Used for showing statistics and progress information.
   */
  private NamedList<Object> getReplicationDetails(boolean showSlaveDetails) {
    NamedList<Object> details = new SimpleOrderedMap<>();
    NamedList<Object> master = new SimpleOrderedMap<>();
    NamedList<Object> slave = new SimpleOrderedMap<>();

    details.add("indexSize", NumberUtils.readableSize(getIndexSize()));
    details.add("indexPath", core.getIndexDir());
    details.add(CMD_SHOW_COMMITS, getCommits());
    details.add("isMaster", String.valueOf(isMaster));
    details.add("isSlave", String.valueOf(isSlave));
    CommitVersionInfo vInfo = getIndexVersion();
    details.add("indexVersion", null == vInfo ? 0 : vInfo.version);
    details.add(GENERATION, null == vInfo ? 0 : vInfo.generation);

    IndexCommit commit = indexCommitPoint;  // make a copy so it won't change

    if (isMaster) {
      if (includeConfFiles != null) master.add(CONF_FILES, includeConfFiles);
      master.add(REPLICATE_AFTER, getReplicateAfterStrings());
      master.add("replicationEnabled", String.valueOf(replicationEnabled.get()));
    }

    if (isMaster && commit != null) {
      CommitVersionInfo repCommitInfo = CommitVersionInfo.build(commit);
      master.add("replicableVersion", repCommitInfo.version);
      master.add("replicableGeneration", repCommitInfo.generation);
    }

    IndexFetcher fetcher = currentIndexFetcher;
    if (fetcher != null) {
      Properties props = loadReplicationProperties();
      if (showSlaveDetails) {
        try {
          NamedList nl = fetcher.getDetails();
          slave.add("masterDetails", nl.get(CMD_DETAILS));
        } catch (Exception e) {
          LOG.warn(
              "Exception while invoking 'details' method for replication on master ",
              e);
          slave.add(ERR_STATUS, "invalid_master");
        }
      }
      slave.add(MASTER_URL, fetcher.getMasterUrl());
      if (getPollInterval() != null) {
        slave.add(POLL_INTERVAL, getPollInterval());
      }
      Date nextScheduled = getNextScheduledExecTime();
      if (nextScheduled != null && !isPollingDisabled()) {
        slave.add(NEXT_EXECUTION_AT, nextScheduled.toString());
      } else if (isPollingDisabled()) {
        slave.add(NEXT_EXECUTION_AT, "Polling disabled");
      }
      addVal(slave, IndexFetcher.INDEX_REPLICATED_AT, props, Date.class);
      addVal(slave, IndexFetcher.INDEX_REPLICATED_AT_LIST, props, List.class);
      addVal(slave, IndexFetcher.REPLICATION_FAILED_AT_LIST, props, List.class);
      addVal(slave, IndexFetcher.TIMES_INDEX_REPLICATED, props, Integer.class);
      addVal(slave, IndexFetcher.CONF_FILES_REPLICATED, props, Integer.class);
      addVal(slave, IndexFetcher.TIMES_CONFIG_REPLICATED, props, Integer.class);
      addVal(slave, IndexFetcher.CONF_FILES_REPLICATED_AT, props, Integer.class);
      addVal(slave, IndexFetcher.LAST_CYCLE_BYTES_DOWNLOADED, props, Long.class);
      addVal(slave, IndexFetcher.TIMES_FAILED, props, Integer.class);
      addVal(slave, IndexFetcher.REPLICATION_FAILED_AT, props, Date.class);
      addVal(slave, IndexFetcher.PREVIOUS_CYCLE_TIME_TAKEN, props, Long.class);

      slave.add("currentDate", new Date().toString());
      slave.add("isPollingDisabled", String.valueOf(isPollingDisabled()));
      boolean isReplicating = isReplicating();
      slave.add("isReplicating", String.valueOf(isReplicating));
      if (isReplicating) {
        try {
          long bytesToDownload = 0;
          List<String> filesToDownload = new ArrayList<>();
          for (Map<String, Object> file : fetcher.getFilesToDownload()) {
            filesToDownload.add((String) file.get(NAME));
            bytesToDownload += (Long) file.get(SIZE);
          }

          //get list of conf files to download
          for (Map<String, Object> file : fetcher.getConfFilesToDownload()) {
            filesToDownload.add((String) file.get(NAME));
            bytesToDownload += (Long) file.get(SIZE);
          }

          slave.add("filesToDownload", filesToDownload);
          slave.add("numFilesToDownload", String.valueOf(filesToDownload.size()));
          slave.add("bytesToDownload", NumberUtils.readableSize(bytesToDownload));

          long bytesDownloaded = 0;
          List<String> filesDownloaded = new ArrayList<>();
          for (Map<String, Object> file : fetcher.getFilesDownloaded()) {
            filesDownloaded.add((String) file.get(NAME));
            bytesDownloaded += (Long) file.get(SIZE);
          }

          //get list of conf files downloaded
          for (Map<String, Object> file : fetcher.getConfFilesDownloaded()) {
            filesDownloaded.add((String) file.get(NAME));
            bytesDownloaded += (Long) file.get(SIZE);
          }

          Map<String, Object> currentFile = fetcher.getCurrentFile();
          String currFile = null;
          long currFileSize = 0, currFileSizeDownloaded = 0;
          float percentDownloaded = 0;
          if (currentFile != null) {
            currFile = (String) currentFile.get(NAME);
            currFileSize = (Long) currentFile.get(SIZE);
            if (currentFile.containsKey("bytesDownloaded")) {
              currFileSizeDownloaded = (Long) currentFile.get("bytesDownloaded");
              bytesDownloaded += currFileSizeDownloaded;
              if (currFileSize > 0)
                percentDownloaded = (currFileSizeDownloaded * 100) / currFileSize;
            }
          }
          slave.add("filesDownloaded", filesDownloaded);
          slave.add("numFilesDownloaded", String.valueOf(filesDownloaded.size()));

          long estimatedTimeRemaining = 0;

          Date replicationStartTimeStamp = fetcher.getReplicationStartTimeStamp();
          if (replicationStartTimeStamp != null) {
            slave.add("replicationStartTime", replicationStartTimeStamp.toString());
          }
          long elapsed = fetcher.getReplicationTimeElapsed();
          slave.add("timeElapsed", String.valueOf(elapsed) + "s");

          if (bytesDownloaded > 0)
            estimatedTimeRemaining = ((bytesToDownload - bytesDownloaded) * elapsed) / bytesDownloaded;
          float totalPercent = 0;
          long downloadSpeed = 0;
          if (bytesToDownload > 0)
            totalPercent = (bytesDownloaded * 100) / bytesToDownload;
          if (elapsed > 0)
            downloadSpeed = (bytesDownloaded / elapsed);
          if (currFile != null)
            slave.add("currentFile", currFile);
          slave.add("currentFileSize", NumberUtils.readableSize(currFileSize));
          slave.add("currentFileSizeDownloaded", NumberUtils.readableSize(currFileSizeDownloaded));
          slave.add("currentFileSizePercent", String.valueOf(percentDownloaded));
          slave.add("bytesDownloaded", NumberUtils.readableSize(bytesDownloaded));
          slave.add("totalPercent", String.valueOf(totalPercent));
          slave.add("timeRemaining", String.valueOf(estimatedTimeRemaining) + "s");
          slave.add("downloadSpeed", NumberUtils.readableSize(downloadSpeed));
        } catch (Exception e) {
          LOG.error("Exception while writing replication details: ", e);
        }
      }
    }

    if (isMaster)
      details.add("master", master);
    if (slave.size() > 0)
      details.add("slave", slave);

    NamedList snapshotStats = snapShootDetails;
    if (snapshotStats != null)
      details.add(CMD_BACKUP, snapshotStats);

    return details;
  }

  private void addVal(NamedList<Object> nl, String key, Properties props, Class clzz) {
    String s = props.getProperty(key);
    if (s == null || s.trim().length() == 0) return;
    if (clzz == Date.class) {
      try {
        Long l = Long.parseLong(s);
        nl.add(key, new Date(l).toString());
      } catch (NumberFormatException e) {/*no op*/ }
    } else if (clzz == List.class) {
      String ss[] = s.split(",");
      List<String> l = new ArrayList<>();
      for (String s1 : ss) {
        l.add(new Date(Long.valueOf(s1)).toString());
      }
      nl.add(key, l);
    } else {
      nl.add(key, s);
    }

  }

  private List<String> getReplicateAfterStrings() {
    List<String> replicateAfter = new ArrayList<>();
    if (replicateOnCommit)
      replicateAfter.add("commit");
    if (replicateOnOptimize)
      replicateAfter.add("optimize");
    if (replicateOnStart)
      replicateAfter.add("startup");
    return replicateAfter;
  }

  Properties loadReplicationProperties() {
    Directory dir = null;
    try {
      try {
        dir = core.getDirectoryFactory().get(core.getDataDir(),
            DirContext.META_DATA, core.getSolrConfig().indexConfig.lockType);
        IndexInput input;
        try {
          input = dir.openInput(
            IndexFetcher.REPLICATION_PROPERTIES, IOContext.DEFAULT);
        } catch (FileNotFoundException | NoSuchFileException e) {
          return new Properties();
        }

        try {
          final InputStream is = new PropertiesInputStream(input);
          Properties props = new Properties();
          props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
          return props;
        } finally {
          input.close();
        }
      } finally {
        if (dir != null) {
          core.getDirectoryFactory().release(dir);
        }
      }
    } catch (IOException e) {
      throw new SolrException(ErrorCode.SERVER_ERROR, e);
    }
  }


//  void refreshCommitpoint() {
//    IndexCommit commitPoint = core.getDeletionPolicy().getLatestCommit();
//    if(replicateOnCommit || (replicateOnOptimize && commitPoint.getSegmentCount() == 1)) {
//      indexCommitPoint = commitPoint;
//    }
//  }

  private void setupPolling(String intervalStr) {
    pollIntervalStr = intervalStr;
    pollIntervalNs = readIntervalNs(pollIntervalStr);
    if (pollIntervalNs == null || pollIntervalNs <= 0) {
      LOG.info(" No value set for 'pollInterval'. Timer Task not started.");
      return;
    }

    Runnable task = new Runnable() {
      @Override
      public void run() {
        if (pollDisabled.get()) {
          LOG.info("Poll disabled");
          return;
        }
        try {
          LOG.debug("Polling for index modifications");
          markScheduledExecutionStart();
          doFetch(null, false);
        } catch (Exception e) {
          LOG.error("Exception in fetching index", e);
        }
      }
    };
    executorService = Executors.newSingleThreadScheduledExecutor(
        new DefaultSolrThreadFactory("indexFetcher"));
    // Randomize initial delay, with a minimum of 1ms
    long initialDelayNs = new Random().nextLong() % pollIntervalNs
        + TimeUnit.NANOSECONDS.convert(1, TimeUnit.MILLISECONDS);
    executorService.scheduleAtFixedRate(task, initialDelayNs, pollIntervalNs, TimeUnit.NANOSECONDS);
    LOG.info("Poll scheduled at an interval of {}ms",
        TimeUnit.MILLISECONDS.convert(pollIntervalNs, TimeUnit.NANOSECONDS));
  }

  @Override
  @SuppressWarnings("unchecked")
  public void inform(SolrCore core) {
    this.core = core;
    registerCloseHook();
    Object nbtk = initArgs.get(NUMBER_BACKUPS_TO_KEEP_INIT_PARAM);
    if(nbtk!=null) {
      numberBackupsToKeep = Integer.parseInt(nbtk.toString());
    } else {
      numberBackupsToKeep = 0;
    }
    NamedList slave = (NamedList) initArgs.get("slave");
    boolean enableSlave = isEnabled( slave );
    if (enableSlave) {
      currentIndexFetcher = pollingIndexFetcher = new IndexFetcher(slave, this, core);
      setupPolling((String) slave.get(POLL_INTERVAL));
      isSlave = true;
    }
    NamedList master = (NamedList) initArgs.get("master");
    boolean enableMaster = isEnabled( master );

    if (enableMaster || enableSlave) {
      if (core.getCoreDescriptor().getCoreContainer().getZkController() != null) {
        LOG.warn("SolrCloud is enabled for core " + core.getName() + " but so is old-style replication. Make sure you" +
            " intend this behavior, it usually indicates a mis-configuration. Master setting is " +
            Boolean.toString(enableMaster) + " and slave setting is " + Boolean.toString(enableSlave));
      }
    }

    if (!enableSlave && !enableMaster) {
      enableMaster = true;
      master = new NamedList<>();
    }

    if (enableMaster) {
      includeConfFiles = (String) master.get(CONF_FILES);
      if (includeConfFiles != null && includeConfFiles.trim().length() > 0) {
        List<String> files = Arrays.asList(includeConfFiles.split(","));
        for (String file : files) {
          if (file.trim().length() == 0) continue;
          String[] strs = file.trim().split(":");
          // if there is an alias add it or it is null
          confFileNameAlias.add(strs[0], strs.length > 1 ? strs[1] : null);
        }
        LOG.info("Replication enabled for following config files: " + includeConfFiles);
      }
      List backup = master.getAll("backupAfter");
      boolean backupOnCommit = backup.contains("commit");
      boolean backupOnOptimize = !backupOnCommit && backup.contains("optimize");
      List replicateAfter = master.getAll(REPLICATE_AFTER);
      replicateOnCommit = replicateAfter.contains("commit");
      replicateOnOptimize = !replicateOnCommit && replicateAfter.contains("optimize");

      if (!replicateOnCommit && ! replicateOnOptimize) {
        replicateOnCommit = true;
      }

      // if we only want to replicate on optimize, we need the deletion policy to
      // save the last optimized commit point.
      if (replicateOnOptimize) {
        IndexDeletionPolicyWrapper wrapper = core.getDeletionPolicy();
        IndexDeletionPolicy policy = wrapper == null ? null : wrapper.getWrappedDeletionPolicy();
        if (policy instanceof SolrDeletionPolicy) {
          SolrDeletionPolicy solrPolicy = (SolrDeletionPolicy)policy;
          if (solrPolicy.getMaxOptimizedCommitsToKeep() < 1) {
            solrPolicy.setMaxOptimizedCommitsToKeep(1);
          }
        } else {
          LOG.warn("Replication can't call setMaxOptimizedCommitsToKeep on " + policy);
        }
      }

      if (replicateOnOptimize || backupOnOptimize) {
        core.getUpdateHandler().registerOptimizeCallback(getEventListener(backupOnOptimize, replicateOnOptimize));
      }
      if (replicateOnCommit || backupOnCommit) {
        replicateOnCommit = true;
        core.getUpdateHandler().registerCommitCallback(getEventListener(backupOnCommit, replicateOnCommit));
      }
      if (replicateAfter.contains("startup")) {
        replicateOnStart = true;
        RefCounted<SolrIndexSearcher> s = core.getNewestSearcher(false);
        try {
          DirectoryReader reader = s==null ? null : s.get().getIndexReader();
          if (reader!=null && reader.getIndexCommit() != null && reader.getIndexCommit().getGeneration() != 1L) {
            try {
              if(replicateOnOptimize){
                Collection<IndexCommit> commits = DirectoryReader.listCommits(reader.directory());
                for (IndexCommit ic : commits) {
                  if(ic.getSegmentCount() == 1){
                    if(indexCommitPoint == null || indexCommitPoint.getGeneration() < ic.getGeneration()) indexCommitPoint = ic;
                  }
                }
              } else{
                indexCommitPoint = reader.getIndexCommit();
              }
            } finally {
              // We don't need to save commit points for replication, the SolrDeletionPolicy
              // always saves the last commit point (and the last optimized commit point, if needed)
              /***
              if(indexCommitPoint != null){
                core.getDeletionPolicy().saveCommitPoint(indexCommitPoint.getGeneration());
              }
              ***/
            }
          }

          // ensure the writer is init'd so that we have a list of commit points
          RefCounted<IndexWriter> iw = core.getUpdateHandler().getSolrCoreState().getIndexWriter(core);
          iw.decref();

        } catch (IOException e) {
          LOG.warn("Unable to get IndexCommit on startup", e);
        } finally {
          if (s!=null) s.decref();
        }
      }
      String reserve = (String) master.get(RESERVE);
      if (reserve != null && !reserve.trim().equals("")) {
        reserveCommitDuration = readIntervalMs(reserve);
      }
      LOG.info("Commits will be reserved for  " + reserveCommitDuration);
      isMaster = true;
    }
  }

  // check master or slave is enabled
  private boolean isEnabled( NamedList params ){
    if( params == null ) return false;
    Object enable = params.get( "enable" );
    if( enable == null ) return true;
    if( enable instanceof String )
      return StrUtils.parseBool( (String)enable );
    return Boolean.TRUE.equals( enable );
  }

  /**
   * register a closehook
   */
  private void registerCloseHook() {
    core.addCloseHook(new CloseHook() {
      @Override
      public void preClose(SolrCore core) {
        try {
          if (executorService != null) executorService.shutdown(); // we don't wait for shutdown - this can deadlock core reload
        } finally {
            if (pollingIndexFetcher != null) {
              pollingIndexFetcher.destroy();
            }
        }
        if (currentIndexFetcher != null && currentIndexFetcher != pollingIndexFetcher) {
          currentIndexFetcher.destroy();
        }
      }

      @Override
      public void postClose(SolrCore core) {}
    });

    core.addCloseHook(new CloseHook() {
      @Override
      public void preClose(SolrCore core) {
        ExecutorUtil.shutdownAndAwaitTermination(restoreExecutor);
        if (restoreFuture != null) {
          restoreFuture.cancel(false);
        }
      }

      @Override
      public void postClose(SolrCore core) {}
    });
  }

  /**
   * Register a listener for postcommit/optimize
   *
   * @param snapshoot do a snapshoot
   * @param getCommit get a commitpoint also
   *
   * @return an instance of the eventlistener
   */
  private SolrEventListener getEventListener(final boolean snapshoot, final boolean getCommit) {
    return new SolrEventListener() {
      @Override
      public void init(NamedList args) {/*no op*/ }

      /**
       * This refreshes the latest replicateable index commit and optionally can create Snapshots as well
       */
      @Override
      public void postCommit() {
        IndexCommit currentCommitPoint = core.getDeletionPolicy().getLatestCommit();

        if (getCommit) {
          // IndexCommit oldCommitPoint = indexCommitPoint;
          indexCommitPoint = currentCommitPoint;

          // We don't need to save commit points for replication, the SolrDeletionPolicy
          // always saves the last commit point (and the last optimized commit point, if needed)
          /***
          if (indexCommitPoint != null) {
            core.getDeletionPolicy().saveCommitPoint(indexCommitPoint.getGeneration());
          }
          if(oldCommitPoint != null){
            core.getDeletionPolicy().releaseCommitPointAndExtendReserve(oldCommitPoint.getGeneration());
          }
          ***/
        }
        if (snapshoot) {
          try {
            int numberToKeep = numberBackupsToKeep;
            if (numberToKeep < 1) {
              numberToKeep = Integer.MAX_VALUE;
            }
            SnapShooter snapShooter = new SnapShooter(core, null, null);
            snapShooter.validateCreateSnapshot();
            snapShooter.createSnapAsync(currentCommitPoint, numberToKeep, ReplicationHandler.this);
          } catch (Exception e) {
            LOG.error("Exception while snapshooting", e);
          }
        }
      }

      @Override
      public void newSearcher(SolrIndexSearcher newSearcher, SolrIndexSearcher currentSearcher) { /*no op*/}

      @Override
      public void postSoftCommit() {

      }
    };
  }

  /**This class is used to read and send files in the lucene index
   *
   */
  private class DirectoryFileStream implements SolrCore.RawWriter {
    protected SolrParams params;

    protected FastOutputStream fos;

    protected Long indexGen;
    protected IndexDeletionPolicyWrapper delPolicy;

    protected String fileName;
    protected String cfileName;
    protected String tlogFileName;
    protected String sOffset;
    protected String sLen;
    protected String compress;
    protected boolean useChecksum;

    protected long offset = -1;
    protected int len = -1;

    protected Checksum checksum;

    private RateLimiter rateLimiter;

    byte[] buf;

    public DirectoryFileStream(SolrParams solrParams) {
      params = solrParams;
      delPolicy = core.getDeletionPolicy();

      fileName = params.get(FILE);
      cfileName = params.get(CONF_FILE_SHORT);
      tlogFileName = params.get(TLOG_FILE);
      sOffset = params.get(OFFSET);
      sLen = params.get(LEN);
      compress = params.get(COMPRESSION);
      useChecksum = params.getBool(CHECKSUM, false);
      indexGen = params.getLong(GENERATION, null);
      if (useChecksum) {
        checksum = new Adler32();
      }
      //No throttle if MAX_WRITE_PER_SECOND is not specified
      double maxWriteMBPerSec = params.getDouble(MAX_WRITE_PER_SECOND, Double.MAX_VALUE);
      rateLimiter = new RateLimiter.SimpleRateLimiter(maxWriteMBPerSec);
    }

    protected void initWrite() throws IOException {
      if (sOffset != null) offset = Long.parseLong(sOffset);
      if (sLen != null) len = Integer.parseInt(sLen);
      if (fileName == null && cfileName == null && tlogFileName == null) {
        // no filename do nothing
        writeNothingAndFlush();
      }
      buf = new byte[(len == -1 || len > PACKET_SZ) ? PACKET_SZ : len];

      //reserve commit point till write is complete
      if(indexGen != null) {
        delPolicy.saveCommitPoint(indexGen);
      }
    }

    protected void createOutputStream(OutputStream out) {
      if (Boolean.parseBoolean(compress)) {
        fos = new FastOutputStream(new DeflaterOutputStream(out));
      } else {
        fos = new FastOutputStream(out);
      }
    }

    protected void extendReserveAndReleaseCommitPoint() {
      if(indexGen != null) {
        //Reserve the commit point for another 10s for the next file to be to fetched.
        //We need to keep extending the commit reservation between requests so that the replica can fetch
        //all the files correctly.
        delPolicy.setReserveDuration(indexGen, reserveCommitDuration);

        //release the commit point as the write is complete
        delPolicy.releaseCommitPoint(indexGen);
      }

    }
    public void write(OutputStream out) throws IOException {
      createOutputStream(out);

      IndexInput in = null;
      try {
        initWrite();

        RefCounted<SolrIndexSearcher> sref = core.getSearcher();
        Directory dir;
        try {
          SolrIndexSearcher searcher = sref.get();
          dir = searcher.getIndexReader().directory();
        } finally {
          sref.decref();
        }
        in = dir.openInput(fileName, IOContext.READONCE);
        // if offset is mentioned move the pointer to that point
        if (offset != -1) in.seek(offset);

        long filelen = dir.fileLength(fileName);
        long maxBytesBeforePause = 0;

        while (true) {
          offset = offset == -1 ? 0 : offset;
          int read = (int) Math.min(buf.length, filelen - offset);
          in.readBytes(buf, 0, read);

          fos.writeInt(read);
          if (useChecksum) {
            checksum.reset();
            checksum.update(buf, 0, read);
            fos.writeLong(checksum.getValue());
          }
          fos.write(buf, 0, read);
          fos.flush();
          LOG.debug("Wrote {} bytes for file {}", offset + read, fileName);

          //Pause if necessary
          maxBytesBeforePause += read;
          if (maxBytesBeforePause >= rateLimiter.getMinPauseCheckBytes()) {
            rateLimiter.pause(maxBytesBeforePause);
            maxBytesBeforePause = 0;
          }
          if (read != buf.length) {
            writeNothingAndFlush();
            fos.close();
            break;
          }
          offset += read;
          in.seek(offset);
        }
      } catch (IOException e) {
        LOG.warn("Exception while writing response for params: " + params, e);
      } finally {
        if (in != null) {
          in.close();
        }
        extendReserveAndReleaseCommitPoint();
      }
    }


    /**
     * Used to write a marker for EOF
     */
    protected void writeNothingAndFlush() throws IOException {
      fos.writeInt(0);
      fos.flush();
    }
  }

  /**This is used to write files in the conf directory.
   */
  private abstract class LocalFsFileStream extends DirectoryFileStream {

    private File file;

    public LocalFsFileStream(SolrParams solrParams) {
      super(solrParams);
      this.file = this.initFile();
    }

    protected abstract File initFile();

    @Override
    public void write(OutputStream out) throws IOException {
      createOutputStream(out);
      FileInputStream inputStream = null;
      try {
        initWrite();

        if (file.exists() && file.canRead()) {
          inputStream = new FileInputStream(file);
          FileChannel channel = inputStream.getChannel();
          //if offset is mentioned move the pointer to that point
          if (offset != -1)
            channel.position(offset);
          ByteBuffer bb = ByteBuffer.wrap(buf);

          while (true) {
            bb.clear();
            long bytesRead = channel.read(bb);
            if (bytesRead <= 0) {
              writeNothingAndFlush();
              fos.close();
              break;
            }
            fos.writeInt((int) bytesRead);
            if (useChecksum) {
              checksum.reset();
              checksum.update(buf, 0, (int) bytesRead);
              fos.writeLong(checksum.getValue());
            }
            fos.write(buf, 0, (int) bytesRead);
            fos.flush();
          }
        } else {
          writeNothingAndFlush();
        }
      } catch (IOException e) {
        LOG.warn("Exception while writing response for params: " + params, e);
      } finally {
        IOUtils.closeQuietly(inputStream);
        extendReserveAndReleaseCommitPoint();
      }
    }
  }

  private class LocalFsTlogFileStream extends LocalFsFileStream {

    public LocalFsTlogFileStream(SolrParams solrParams) {
      super(solrParams);
    }

    protected File initFile() {
      //if it is a tlog file read from tlog directory
      return new File(core.getUpdateHandler().getUpdateLog().getLogDir(), tlogFileName);
    }

  }

  private class LocalFsConfFileStream extends LocalFsFileStream {

    public LocalFsConfFileStream(SolrParams solrParams) {
      super(solrParams);
    }

    protected File initFile() {
      //if it is a conf file read from config directory
      return new File(core.getResourceLoader().getConfigDir(), cfileName);
    }

  }

  private static Integer readIntervalMs(String interval) {
    return (int) TimeUnit.MILLISECONDS.convert(readIntervalNs(interval), TimeUnit.NANOSECONDS);
  }

  private static Long readIntervalNs(String interval) {
    if (interval == null)
      return null;
    int result = 0;
    Matcher m = INTERVAL_PATTERN.matcher(interval.trim());
    if (m.find()) {
      String hr = m.group(1);
      String min = m.group(2);
      String sec = m.group(3);
      result = 0;
      try {
        if (sec != null && sec.length() > 0)
          result += Integer.parseInt(sec);
        if (min != null && min.length() > 0)
          result += (60 * Integer.parseInt(min));
        if (hr != null && hr.length() > 0)
          result += (60 * 60 * Integer.parseInt(hr));
        return TimeUnit.NANOSECONDS.convert(result, TimeUnit.SECONDS);
      } catch (NumberFormatException e) {
        throw new SolrException(ErrorCode.SERVER_ERROR, INTERVAL_ERR_MSG);
      }
    } else {
      throw new SolrException(ErrorCode.SERVER_ERROR, INTERVAL_ERR_MSG);
    }
  }

  private static final String LOCATION = "location";

  private static final String SUCCESS = "success";

  private static final String FAILED = "failed";

  private static final String EXCEPTION = "exception";

  public static final String MASTER_URL = "masterUrl";

  public static final String STATUS = "status";

  public static final String COMMAND = "command";

  public static final String CMD_DETAILS = "details";

  public static final String CMD_BACKUP = "backup";

  public static final String CMD_RESTORE = "restore";

  public static final String CMD_RESTORE_STATUS = "restorestatus";

  public static final String CMD_FETCH_INDEX = "fetchindex";

  public static final String CMD_ABORT_FETCH = "abortfetch";

  public static final String CMD_GET_FILE_LIST = "filelist";

  public static final String CMD_GET_FILE = "filecontent";

  public static final String CMD_DISABLE_POLL = "disablepoll";

  public static final String CMD_DISABLE_REPL = "disablereplication";

  public static final String CMD_ENABLE_REPL = "enablereplication";

  public static final String CMD_ENABLE_POLL = "enablepoll";

  public static final String CMD_INDEX_VERSION = "indexversion";

  public static final String CMD_SHOW_COMMITS = "commits";

  public static final String CMD_DELETE_BACKUP = "deletebackup";

  public static final String GENERATION = "generation";

  public static final String OFFSET = "offset";

  public static final String LEN = "len";

  public static final String FILE = "file";

  public static final String SIZE = "size";

  public static final String MAX_WRITE_PER_SECOND = "maxWriteMBPerSec";

  public static final String CONF_FILE_SHORT = "cf";

  public static final String TLOG_FILE = "tlogFile";

  public static final String CHECKSUM = "checksum";

  public static final String ALIAS = "alias";

  public static final String CONF_CHECKSUM = "confchecksum";

  public static final String CONF_FILES = "confFiles";

  public static final String TLOG_FILES = "tlogFiles";

  public static final String REPLICATE_AFTER = "replicateAfter";

  public static final String FILE_STREAM = "filestream";

  public static final String POLL_INTERVAL = "pollInterval";

  public static final String INTERVAL_ERR_MSG = "The " + POLL_INTERVAL + " must be in this format 'HH:mm:ss'";

  private static final Pattern INTERVAL_PATTERN = Pattern.compile("(\\d*?):(\\d*?):(\\d*)");

  public static final int PACKET_SZ = 1024 * 1024; // 1MB

  public static final String RESERVE = "commitReserveDuration";

  public static final String COMPRESSION = "compression";

  public static final String EXTERNAL = "external";

  public static final String INTERNAL = "internal";

  public static final String ERR_STATUS = "ERROR";

  public static final String OK_STATUS = "OK";

  public static final String NEXT_EXECUTION_AT = "nextExecutionAt";

  public static final String NUMBER_BACKUPS_TO_KEEP_REQUEST_PARAM = "numberToKeep";

  public static final String NUMBER_BACKUPS_TO_KEEP_INIT_PARAM = "maxNumberOfBackups";

  /**
   * Boolean param for tests that can be specified when using
   * {@link #CMD_FETCH_INDEX} to force the current request to block until
   * the fetch is complete.  <b>NOTE:</b> This param is not advised for
   * non-test code, since the the duration of the fetch for non-trivial
   * indexes will likeley cause the request to time out.
   *
   * @lucene.internal
   */
  public static final String WAIT = "wait";
}
