/*
 * Copyright (C) 2017 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.service.jobs;

import static com.dremio.common.utils.Protos.listNotNull;
import static com.dremio.service.job.proto.JobState.RUNNING;
import static com.dremio.service.job.proto.QueryType.UI_EXPORT;
import static com.dremio.service.job.proto.QueryType.UI_INITIAL_PREVIEW;
import static com.dremio.service.job.proto.QueryType.UI_INTERNAL_PREVIEW;
import static com.dremio.service.job.proto.QueryType.UI_INTERNAL_RUN;
import static com.dremio.service.job.proto.QueryType.UI_PREVIEW;
import static com.dremio.service.job.proto.QueryType.UI_RUN;
import static com.dremio.service.jobs.JobIndexKeys.ALL_DATASETS;
import static com.dremio.service.jobs.JobIndexKeys.DATASET;
import static com.dremio.service.jobs.JobIndexKeys.DATASET_VERSION;
import static com.dremio.service.jobs.JobIndexKeys.DURATION;
import static com.dremio.service.jobs.JobIndexKeys.END_TIME;
import static com.dremio.service.jobs.JobIndexKeys.JOBID;
import static com.dremio.service.jobs.JobIndexKeys.JOB_STATE;
import static com.dremio.service.jobs.JobIndexKeys.PARENT_DATASET;
import static com.dremio.service.jobs.JobIndexKeys.QUERY_TYPE;
import static com.dremio.service.jobs.JobIndexKeys.SPACE;
import static com.dremio.service.jobs.JobIndexKeys.SQL;
import static com.dremio.service.jobs.JobIndexKeys.START_TIME;
import static com.dremio.service.jobs.JobIndexKeys.USER;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nullable;
import javax.inject.Provider;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.NullableVarBinaryVector;
import org.apache.arrow.vector.ValueVector;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlNode;

import com.dremio.common.AutoCloseables;
import com.dremio.common.DeferredException;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.perf.Timer;
import com.dremio.common.perf.Timer.TimedBlock;
import com.dremio.common.utils.PathUtils;
import com.dremio.common.utils.ProtostuffUtil;
import com.dremio.common.utils.SqlUtils;
import com.dremio.datastore.IndexedStore;
import com.dremio.datastore.IndexedStore.FindByCondition;
import com.dremio.datastore.KVStore;
import com.dremio.datastore.KVStoreProvider;
import com.dremio.datastore.KVStoreProvider.DocumentWriter;
import com.dremio.datastore.ProtostuffSerializer;
import com.dremio.datastore.SearchQueryUtils;
import com.dremio.datastore.SearchTypes.SearchFieldSorting;
import com.dremio.datastore.SearchTypes.SearchQuery;
import com.dremio.datastore.SearchTypes.SortOrder;
import com.dremio.datastore.Serializer;
import com.dremio.datastore.StoreBuildingFactory;
import com.dremio.datastore.StoreCreationFunction;
import com.dremio.datastore.StringSerializer;
import com.dremio.datastore.indexed.IndexKey;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.planner.PlannerPhase;
import com.dremio.exec.planner.acceleration.substitution.SubstitutionInfo;
import com.dremio.exec.planner.fragment.PlanningSet;
import com.dremio.exec.planner.observer.AbstractAttemptObserver;
import com.dremio.exec.planner.observer.AbstractQueryObserver;
import com.dremio.exec.planner.observer.AttemptObserver;
import com.dremio.exec.planner.observer.QueryObserver;
import com.dremio.exec.planner.observer.QueryObserverFactory;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.exec.proto.GeneralRPCProtos.Ack;
import com.dremio.exec.proto.SchemaUserBitShared;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.proto.UserBitShared.ExternalId;
import com.dremio.exec.proto.UserBitShared.QueryProfile;
import com.dremio.exec.proto.UserBitShared.QueryResult.QueryState;
import com.dremio.exec.proto.UserBitShared.RpcEndpointInfos;
import com.dremio.exec.proto.UserBitShared.WorkloadClass;
import com.dremio.exec.proto.UserProtos.CreatePreparedStatementReq;
import com.dremio.exec.proto.UserProtos.QueryPriority;
import com.dremio.exec.proto.UserProtos.RunQuery;
import com.dremio.exec.proto.UserProtos.SubmissionSource;
import com.dremio.exec.proto.beans.NodeEndpoint;
import com.dremio.exec.proto.helper.QueryIdHelper;
import com.dremio.exec.record.RecordBatchLoader;
import com.dremio.exec.rpc.RpcException;
import com.dremio.exec.rpc.RpcOutcomeListener;
import com.dremio.exec.serialization.InstanceSerializer;
import com.dremio.exec.serialization.ProtoSerializer;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.store.dfs.FileSystemPlugin;
import com.dremio.exec.store.easy.arrow.ArrowFileFormat;
import com.dremio.exec.store.easy.arrow.ArrowFileMetadata;
import com.dremio.exec.work.AttemptId;
import com.dremio.exec.work.ExternalIdHelper;
import com.dremio.exec.work.foreman.ExecutionPlan;
import com.dremio.exec.work.protector.ForemenTool;
import com.dremio.exec.work.protector.UserRequest;
import com.dremio.exec.work.protector.UserResponseHandler;
import com.dremio.exec.work.protector.UserResult;
import com.dremio.exec.work.rpc.CoordTunnel;
import com.dremio.exec.work.rpc.CoordTunnelCreator;
import com.dremio.exec.work.user.LocalQueryExecutor;
import com.dremio.exec.work.user.LocalQueryExecutor.LocalExecutionConfig;
import com.dremio.exec.work.user.LocalUserUtil;
import com.dremio.proto.model.attempts.AttemptReason;
import com.dremio.sabot.op.screen.QueryWritableBatch;
import com.dremio.sabot.op.sort.external.RecordBatchData;
import com.dremio.sabot.rpc.user.QueryDataBatch;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.BindingCreator;
import com.dremio.service.job.proto.Acceleration;
import com.dremio.service.job.proto.DownloadInfo;
import com.dremio.service.job.proto.JobAttempt;
import com.dremio.service.job.proto.JobDetails;
import com.dremio.service.job.proto.JobId;
import com.dremio.service.job.proto.JobInfo;
import com.dremio.service.job.proto.JobResult;
import com.dremio.service.job.proto.JobState;
import com.dremio.service.job.proto.JoinInfo;
import com.dremio.service.job.proto.MaterializationSummary;
import com.dremio.service.job.proto.ParentDatasetInfo;
import com.dremio.service.job.proto.QueryType;
import com.dremio.service.jobs.metadata.QueryMetadata;
import com.dremio.service.jobs.metadata.RootSchemaFinder;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.dataset.DatasetVersion;
import com.dremio.service.namespace.dataset.proto.FieldOrigin;
import com.dremio.service.namespace.dataset.proto.Origin;
import com.dremio.service.namespace.dataset.proto.ParentDataset;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.scheduler.Schedule;
import com.dremio.service.scheduler.SchedulerService;
import com.dremio.service.users.SystemUser;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.protobuf.InvalidProtocolBufferException;

import io.protostuff.LinkedBuffer;
import io.protostuff.ProtobufIOUtil;

/**
 * Submit and monitor jobs from DAC.
 */
public class LocalJobsService implements JobsService {
  private static final long ONE_DAY_IN_MILLIS = TimeUnit.DAYS.toMillis(1);

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(LocalJobsService.class);

  public static final String JOBS_NAME = "jobs";
  public static final String PROFILES_NAME = "profiles";
  public static final Set<QueryType> UI_QUERY_TYPES = ImmutableSet.of(UI_PREVIEW, UI_INTERNAL_PREVIEW, UI_RUN, UI_INTERNAL_RUN, UI_INITIAL_PREVIEW);

  // Sort by descending order of start time. (recently submitted jobs come on top)
  private static final List<SearchFieldSorting> DEFAULT_SORTER = ImmutableList.of(
      START_TIME.toSortField(SortOrder.DESCENDING),
      END_TIME.toSortField(SortOrder.DESCENDING),
      JOBID.toSortField(SortOrder.DESCENDING));

  private final Provider<LocalQueryExecutor> queryExecutor;
  private final Provider<SabotContext> contextProvider;
  private final Provider<KVStoreProvider> kvStoreProvider;
  private final Provider<FileSystemPlugin> fileSystemPluginProvider;
  private final ConcurrentHashMap<JobId, QueryListener> runningJobs;
  private final BufferAllocator allocator;
  private final BindingCreator bindingCreator;
  private final Provider<ForemenTool> foremenTool;
  private final Provider<CoordTunnelCreator> coordTunnelCreator;
  private final Provider<SchedulerService> schedulerService;

  private NamespaceService namespaceService;
  private IndexedStore<JobId, JobResult> store;
  private KVStore<AttemptId, QueryProfile> profileStore;
  private NodeEndpoint identity;
  private String storageName;

  private JobResultsStore jobResultsStore;

  public LocalJobsService(
      final BindingCreator bindingCreator,
      final Provider<KVStoreProvider> kvStoreProvider,
      final BufferAllocator allocator,
      final Provider<FileSystemPlugin> fileSystemPluginProvider,
      final Provider<LocalQueryExecutor> queryExecutor,
      final Provider<CoordTunnelCreator> coordTunnelCreator,
      final Provider<ForemenTool> foremenTool,
      final Provider<SabotContext> contextProvider,
      final Provider<SchedulerService> schedulerService
      ) {
    this.kvStoreProvider = kvStoreProvider;
    this.allocator = checkNotNull(allocator).newChildAllocator("jobs-service", 0, Long.MAX_VALUE);
    this.queryExecutor = checkNotNull(queryExecutor);
    this.fileSystemPluginProvider = checkNotNull(fileSystemPluginProvider);
    this.bindingCreator = bindingCreator;
    this.runningJobs = new ConcurrentHashMap<>();
    this.contextProvider = contextProvider;
    this.foremenTool = foremenTool;
    this.coordTunnelCreator = coordTunnelCreator;
    this.schedulerService = schedulerService;
  }

  @Override
  public void registerListener(JobId jobId, ExternalStatusListener listener){
    QueryListener queryListener = runningJobs.get(jobId);
    if(queryListener != null){
      queryListener.listeners.register(listener);
    }else{
      Job job = getJob(jobId);

      if(job != null){
        listener.queryCompleted(job);
        return;
      }

      throw UserException.validationError()
        .message("Status requested for unknown job %s.", jobId.getId())
        .build(logger);
    }
  }

  @Override
  public void start() throws Exception {
    logger.info("Starting JobsService");
    this.identity = toStuff(contextProvider.get().getEndpoint());
    this.store = kvStoreProvider.get().getStore(JobsStoreCreator.class);
    this.profileStore = kvStoreProvider.get().getStore(JobsProfileCreator.class);
    this.namespaceService = contextProvider.get().getNamespaceService(SystemUser.SYSTEM_USERNAME);

    FileSystemPlugin fileSystemPlugin = fileSystemPluginProvider.get();
    this.storageName = fileSystemPlugin.getStorageName();
    this.jobResultsStore = new JobResultsStore(fileSystemPlugin, store, allocator);

    // register to listen to query lifecycle
    bindingCreator.replace(QueryObserverFactory.class, new JobsObserverFactory());

    long maxAgeInMillis = contextProvider.get().getOptionManager().getOption(ExecConstants.DEBUG_RESULTS_MAX_AGE_IN_MILLISECONDS);
    long maxAgeInDays = contextProvider.get().getOptionManager().getOption(ExecConstants.RESULTS_MAX_AGE_IN_DAYS);
    long jobResultsMaxAgeInMillis = (maxAgeInDays* ONE_DAY_IN_MILLIS) + maxAgeInMillis;

    Schedule schedule = null;

    //Schedule cleanup to run every day unless the max age is less than a day
    if (jobResultsMaxAgeInMillis < ONE_DAY_IN_MILLIS) {
      schedule = Schedule.Builder.everyMillis(jobResultsMaxAgeInMillis).build();
    } else {
      schedule = Schedule.Builder.everyDays(1).build();
    }
    schedulerService.get().schedule(schedule, new CleanupTask());

    // grab tools for cancellation and remote active query profile retrieval.
    logger.info("JobsService is up");
  }

  /**
   * Creator for jobs.
   */
  public static class JobsStoreCreator implements StoreCreationFunction<IndexedStore<JobId, JobResult>> {

    @Override
    public IndexedStore<JobId, JobResult> build(StoreBuildingFactory factory) {
      return factory.<JobId, JobResult>newStore()
          .name(JOBS_NAME)
          .keySerializer(JobIdSerializer.class)
          .valueSerializer(JobResultSerializer.class)
          .buildIndexed(JobConverter.class);
    }
  }

  /**
   * Creator for profiles.
   */
  public static class JobsProfileCreator implements StoreCreationFunction<KVStore<AttemptId, QueryProfile>> {

    @Override
    public KVStore<AttemptId, QueryProfile> build(StoreBuildingFactory factory) {
      return factory.<AttemptId, QueryProfile>newStore()
          .name(PROFILES_NAME)
          .keySerializer(AttemptIdSerializer.class)
          .valueSerializer(QueryProfileSerializer.class)
          .build();
    }
  }

  @Override
  public void close() throws Exception {
    logger.info("Stopping JobsService");
    AutoCloseables.close(jobResultsStore, allocator);
    logger.info("Stopped JobsService");
  }

  private String getSpaceName(List<String> datasetPath) {
    if (datasetPath != null && !datasetPath.isEmpty()
      && namespaceService.exists(new NamespaceKey(datasetPath.get(0)), NameSpaceContainer.Type.SPACE)) {
      return datasetPath.get(0);
    }

    return null;
  }

  private static NodeEndpoint toStuff(com.dremio.exec.proto.CoordinationProtos.NodeEndpoint pb){
    // TODO use schemas to do this...
    NodeEndpoint ep = new NodeEndpoint();
    ProtobufIOUtil.mergeFrom(pb.toByteArray(), ep, NodeEndpoint.getSchema());
    return ep;
  }

  private static CoordinationProtos.NodeEndpoint toPB(NodeEndpoint stuf) {
    // TODO use schemas to do this...
    LinkedBuffer buffer = LinkedBuffer.allocate();
    byte[] bytes = ProtobufIOUtil.toByteArray(stuf, stuf.cachedSchema(), buffer);
    try {
      return CoordinationProtos.NodeEndpoint.PARSER.parseFrom(bytes);
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException("Cannot convert from protostuff to protobuf");
    }
  }

  private Job startJob(final SqlQuery query, QueryType queryType, String downloadId, String fileName, List<String> datasetPath,
                       String version, final List<String> exclusions, JobStatusListener statusListener, MaterializationSummary MaterializationSummary) {
    final ExternalId externalId = ExternalIdHelper.generateExternalId();
    final JobId jobId = getExternalIdAsJobId(externalId);
    final JobInfo jobInfo = new JobInfo(jobId, query.getSql(), version, queryType)
            .setSpace(getSpaceName(datasetPath))
            .setUser(query.getUsername())
            .setStartTime(System.currentTimeMillis())
            .setDatasetPathList(datasetPath)
            .setResultMetadataList(new ArrayList<ArrowFileMetadata>());

    if (downloadId != null) {
      jobInfo.setDownloadInfo(new DownloadInfo()
        .setDownloadId(downloadId)
        .setFileName(fileName));
    }

    if (MaterializationSummary != null) {
      jobInfo.setMaterializationFor(MaterializationSummary);
    }

    final JobAttempt jobAttempt = new JobAttempt()
            .setInfo(jobInfo)
            .setEndpoint(identity)
            .setState(RUNNING)
            .setDetails(new JobDetails());
    final Job job = new Job(jobInfo.getJobId(), jobAttempt);

    final QueryListener jobObserver = new QueryListener(job, statusListener);

    final WorkloadClass workloadClass;

    switch (queryType) {
    case ACCELERATOR_CREATE:
    case ACCELERATOR_DROP:
    case ACCELERATOR_EXPLAIN:
      workloadClass = WorkloadClass.BACKGROUND;
      break;

    case PREPARE_INTERNAL:
    case UI_INTERNAL_PREVIEW:
    case UI_INITIAL_PREVIEW:
    case UI_PREVIEW:
      workloadClass = WorkloadClass.NRT;
      break;

    case UNKNOWN:
    case JDBC:
    case ODBC:
    case REST:
    case UI_EXPORT:
    case UI_INTERNAL_RUN:
    case UI_RUN:
    default:
      workloadClass = WorkloadClass.GENERAL;
      break;
    }

    boolean isPrepare = queryType.equals(QueryType.PREPARE_INTERNAL);

    Object queryCmd;

    if (isPrepare) {
      queryCmd = CreatePreparedStatementReq
        .newBuilder()
        .setSqlQuery(query.getSql())
        .build();
    } else {
      queryCmd = RunQuery
        .newBuilder()
        .setType(UserBitShared.QueryType.SQL)
        .setSource(SubmissionSource.LOCAL)
        .setPlan(query.getSql())
        .setPriority(QueryPriority.newBuilder()
          .setWorkloadClass(workloadClass)
        )
        .build();
    }

    final boolean enableLeafLimits = queryType == UI_PREVIEW || queryType == UI_INTERNAL_PREVIEW || queryType == UI_INITIAL_PREVIEW;
    // for UI queries, we should allow reattempts even if data has been returned from query
    final boolean failIfNonEmptySent = !UI_QUERY_TYPES.contains(queryType);
    final boolean internalSingleThreaded = queryType == UI_INITIAL_PREVIEW;
    final String queryResultsStorePath = String.format("%s.%s", storageName, SqlUtils.quoteIdentifier(jobId.getId()));
    final boolean enablePartitionPruning = queryType != QueryType.ACCELERATOR_EXPLAIN;

    final LocalExecutionConfig config = new LocalExecutionConfig(enableLeafLimits, enableLeafLimits ? 10L : 0L,
        failIfNonEmptySent, query.getUsername(),
        query.getContext(), true, internalSingleThreaded, queryResultsStorePath, exclusions, enablePartitionPruning);

    Preconditions.checkArgument(store.checkAndPut(job.getJobId(), null, toJobResult(job)), "Job had a duplicate jobId. " + job);
    runningJobs.put(jobId, jobObserver);

    queryExecutor.get().submitLocalQuery(externalId, jobObserver, queryCmd, isPrepare, config);

    return job;
  }

  public static JobId getExternalIdAsJobId(ExternalId id){
    return new JobId(new UUID(id.getPart1(), id.getPart2()).toString());
  }

  public static ExternalId getJobIdAsExternalId(JobId jobId) {
    UUID id = UUID.fromString(jobId.getId());
    return ExternalId.newBuilder()
      .setPart1(id.getMostSignificantBits())
      .setPart2(id.getLeastSignificantBits())
      .build();
  }

  @Override
  public Job submitJobWithExclusions(final SqlQuery query, final QueryType queryType, final NamespaceKey datasetPath,
                                     final DatasetVersion version, final List<String> exclusions,
                                     final JobStatusListener statusListener, final MaterializationSummary materializationSummary) {
    final Job job = startJob(
        query,
        queryType,
        null,
        null,
        datasetPath == null ? Arrays.asList("UNKNOWN") : datasetPath.getPathComponents(),
        version == null ? "UNKNOWN" : version.getVersion(),
        exclusions,
        statusListener,
        materializationSummary);
    logger.debug(format("Submitted new %s job: %s for %s",
        queryType, job.getJobId().getId(), datasetPath + (version == null ? "" : "/" + version)));
    return job;
  }

  @Override
  public Job submitJob(final SqlQuery query, final QueryType queryType, final NamespaceKey datasetPath,
                       final DatasetVersion version, final JobStatusListener statusListener) {
    return submitJobWithExclusions(query, queryType, datasetPath, version, ImmutableList.<String>of(), statusListener, null);
  }

  @Override
  public Job submitExternalJob(final SqlQuery query, QueryType type) {
    final Job job = startJob(query, type, null, null, Arrays.asList("UNKNOWN"), "UNKNOWN", ImmutableList.<String>of(), JobStatusListener.NONE, null);
    logger.debug("Submitted new job for sql %s" + job.getJobId().getId(), query);
    return job;
  }

  @Override
  public Job submitDownloadJob(final SqlQuery query, String downloadId, String fileName) {
    final Job job = startJob(query, UI_EXPORT, downloadId, fileName, Arrays.asList("UNKNOWN"), "UNKNOWN", ImmutableList.<String>of(), JobStatusListener.NONE, null);
    logger.debug("Submitted new download job for sql %s" + job.getJobId().getId(), query);
    return job;
  }

  @Override
  public Job getJob(JobId jobId) {
    QueryListener listener = runningJobs.get(jobId);
    if(listener != null){
      return listener.getJob();
    }

    JobResult jobResult = store.get(jobId);
    if(jobResult == null){
      return null;
    }

    return new Job(jobId, jobResult, jobResultsStore);
  }

  @Override
  public Job getJobChecked(final JobId jobId) throws JobNotFoundException {
    final Job job = getJob(jobId);
    if (job == null) {
      throw new JobNotFoundException(jobId);
    }
    return job;
  }

  @Override
  public JobDataFragment getJobData(JobId jobId, int limit) {
    return jobResultsStore.get(jobId).truncate(limit);
  }

  @VisibleForTesting
  public JobResultsStore getjobResultsStore() {
    return jobResultsStore;
  }

  public static JobState queryStatusToJobStatus(QueryState state) {
    switch (state) {
      case STARTING:
        return JobState.RUNNING;
      case RUNNING:
        return JobState.RUNNING;
      case ENQUEUED:
        return JobState.RUNNING;
      case COMPLETED:
        return JobState.COMPLETED;
      case CANCELED:
        return JobState.CANCELED;
      case FAILED:
        return JobState.FAILED;
      default:
        return JobState.NOT_SUBMITTED;
    }
  }

  //// Search apis
  @Override
  public int getJobsCount(final NamespaceKey datasetPath) {
    return store.getCounts(getFilter(datasetPath, null)).get(0);
  }

  @Override
  public List<Integer> getJobsCount(List<NamespaceKey> datasetPaths) {
    if (datasetPaths.isEmpty()) {
      return new ArrayList<>(0);
    }
    final List<SearchQuery> conditions = Lists.newArrayList();
    for (NamespaceKey datasetPath: datasetPaths) {
      conditions.add(getFilter(datasetPath, null));
    }
    return store.getCounts(conditions.toArray(new SearchQuery[conditions.size()]));
  }

  @Override
  public int getJobsCountForDataset(final NamespaceKey datasetPath, final DatasetVersion datasetVersion) {
    return store.getCounts(getFilter(datasetPath, datasetVersion)).get(0);
  }

  @Override
  public Iterable<Job> getJobsForDataset(final NamespaceKey datasetPath, int limit){
    return getJobsForDataset(datasetPath, null, limit);
  }

  @Override
  public Iterable<Job> getJobsForDataset(final NamespaceKey datasetPath, final DatasetVersion version, int limit){
    FindByCondition condition = new FindByCondition()
        .setCondition(getFilter(datasetPath, version))
        .setLimit(limit)
        .addSortings(DEFAULT_SORTER);
    return findJobs(condition);
  }

  @Override
  public Iterable<Job> getAllJobs(String filterString, String sortColumn, SortOrder sortOrder, int offset, int limit, String userName) {
    FindByCondition condition = new FindByCondition()
      .setOffset(offset)
      .setLimit(limit)
      .addSortings(buildSorter(sortColumn, sortOrder))
      .setCondition(filterString, JobIndexKeys.MAPPING);

    return findJobs(condition);
  }

  protected Iterable<Job> findJobs(FindByCondition condition) {
    return toJobs(store.find(condition));
  }

  @Override
  public Iterable<Job> getJobsForParent(final NamespaceKey datasetPath, int limit) {
    SearchQuery query = SearchQueryUtils.and(
            SearchQueryUtils.newTermQuery(PARENT_DATASET, datasetPath.getSchemaPath()),
            JobIndexKeys.UI_EXTERNAL_JOBS_FILTER);
    FindByCondition condition = new FindByCondition()
        .setCondition(query)
        .setLimit(limit);

    return findJobs(condition);
  }

  private Iterable<Job> toJobs(final Iterable<Entry<JobId, JobResult>> entries) {
    return Iterables.transform(entries, new Function<Entry<JobId, JobResult>, Job>() {
      @Override
      public Job apply(Entry<JobId, JobResult> input) {
        return new Job(input.getKey(), input.getValue(), jobResultsStore);
      }
    });
  }

  private SearchQuery getFilter(final NamespaceKey datasetPath, final DatasetVersion datasetVersion) {
    Preconditions.checkNotNull(datasetPath);


    ImmutableList.Builder<SearchQuery> builder = ImmutableList.<SearchQuery> builder()
        .add(SearchQueryUtils.newTermQuery(ALL_DATASETS, datasetPath.toString()))
        .add(JobIndexKeys.UI_EXTERNAL_JOBS_FILTER);
    if (datasetVersion != null) {
      builder.add(SearchQueryUtils.newTermQuery(DATASET_VERSION, datasetVersion.getVersion()));
    }

    return SearchQueryUtils.and(builder.build());
  }

  private static class JobConverter implements KVStoreProvider.DocumentConverter<JobId, JobResult> {
    @Override
    public void convert(DocumentWriter writer, JobId key, JobResult job) {
      // TODO Auto-generated method stub
      final Set<NamespaceKey> allDatasets = new HashSet<>();
      // we only care about the last attempt
      final int numAttempts = job.getAttemptsList().size();
      final JobAttempt jobAttempt = job.getAttemptsList().get(numAttempts - 1);


      final JobInfo jobInfo = jobAttempt.getInfo();
      final NamespaceKey datasetPath = new NamespaceKey(jobInfo.getDatasetPathList());
      allDatasets.add(datasetPath);

      writer.write(JOBID, key.getId());
      writer.write(USER, jobInfo.getUser());
      writer.write(SPACE, jobInfo.getSpace());
      writer.write(SQL, jobInfo.getSql());
      writer.write(QUERY_TYPE, jobInfo.getQueryType().name());
      writer.write(JOB_STATE, jobAttempt.getState().name());
      writer.write(DATASET, datasetPath.getSchemaPath());
      writer.write(DATASET_VERSION, jobInfo.getDatasetVersion());
      writer.write(START_TIME, jobInfo.getStartTime());
      writer.write(END_TIME, jobInfo.getFinishTime());

      final Long duration = jobInfo.getStartTime() == null || jobInfo.getFinishTime() == null ? null :
        jobInfo.getFinishTime() - jobInfo.getStartTime();
      writer.write(DURATION, duration);

      Set<NamespaceKey> parentDatasets = new HashSet<>();
      List<ParentDatasetInfo> parentsList = jobInfo.getParentsList();
      if (parentsList != null) {
        for (ParentDatasetInfo parentDatasetInfo : parentsList) {
          final NamespaceKey parentDatasetPath = new NamespaceKey(parentDatasetInfo.getDatasetPathList());
          parentDatasets.add(parentDatasetPath);
          allDatasets.add(parentDatasetPath);
        }
      }
      List<FieldOrigin> fieldOrigins = jobInfo.getFieldOriginsList();
      if (fieldOrigins != null) {
        for (FieldOrigin fieldOrigin : fieldOrigins) {
          for (Origin origin : listNotNull(fieldOrigin.getOriginsList())) {
            List<String> tableList = listNotNull(origin.getTableList());
            if (!tableList.isEmpty()) {
              final NamespaceKey parentDatasetPath = new NamespaceKey(tableList);
              parentDatasets.add(parentDatasetPath);
              allDatasets.add(parentDatasetPath);
            }
          }
        }
      }

      for (NamespaceKey parentDatasetPath : parentDatasets) {
        // DX-5119 Index unquoted dataset names along with quoted ones.
        // TODO (Amit H): DX-1563 We should be using analyzer to match both rather than indexing twice.
        writer.write(
            PARENT_DATASET,
            parentDatasetPath.getSchemaPath(),
            Joiner.on(PathUtils.getPathDelimiter()).join(parentDatasetPath.getPathComponents()));
      }

      // index all datasets accessed by this job
      for (ParentDataset parentDataset : listNotNull(jobInfo.getGrandParentsList())) {
        allDatasets.add(new NamespaceKey(parentDataset.getDatasetPathList()));
      }
      for(NamespaceKey allDatasetPath: allDatasets) {
        // DX-5119 Index unquoted dataset names along with quoted ones.
        // TODO (Amit H): DX-1563 We should be using analyzer to match both rather than indexing twice.
        writer.write(ALL_DATASETS,
            allDatasetPath.getSchemaPath(),
            Joiner.on(PathUtils.getPathDelimiter()).join(allDatasetPath.getPathComponents()));
      }
    }
  }

  /**
   * Serializer that serializes job ids.
   */
  public static final class JobIdSerializer extends Serializer<JobId> {
    public JobIdSerializer() {
    }

    @Override
    public String toJson(JobId v) throws IOException {
      return StringSerializer.INSTANCE.toJson(v.getId());
    }

    @Override
    public JobId fromJson(String v) throws IOException {
      return new JobId(StringSerializer.INSTANCE.fromJson(v));
    }

    @Override
    public byte[] convert(JobId v) {
      return StringSerializer.INSTANCE.convert(v.getId().toString());
    }

    @Override
    public JobId revert(byte[] v) {
      return new JobId(StringSerializer.INSTANCE.revert(v));
    }
  }

  /**
   * Serializer that serializes attempt id.
   */
  public static final class AttemptIdSerializer extends Serializer<AttemptId> {
    public AttemptIdSerializer() {
    }

    @Override
    public String toJson(AttemptId v) throws IOException {
      return StringSerializer.INSTANCE.toJson(AttemptIdUtils.toString(v));
    }

    @Override
    public AttemptId fromJson(String v) throws IOException {
      return AttemptIdUtils.fromString(StringSerializer.INSTANCE.fromJson(v));
    }

    @Override
    public byte[] convert(AttemptId v) {
      return StringSerializer.INSTANCE.convert(AttemptIdUtils.toString(v));
    }

    @Override
    public AttemptId revert(byte[] v) {
      return AttemptIdUtils.fromString(StringSerializer.INSTANCE.revert(v));
    }
  }

  /**
   * Serializer used to serialize profiles
   */
  public static final class QueryProfileSerializer extends Serializer<QueryProfile> {
    private static final InstanceSerializer<QueryProfile> JSON_SERIALIZER = new ProtoSerializer<>(SchemaUserBitShared.QueryProfile.MERGE, SchemaUserBitShared.QueryProfile.WRITE);

    @Override
    public QueryProfile fromJson(String profile) throws IOException {
      return JSON_SERIALIZER.deserialize(profile.getBytes(UTF_8));
    }

    @Override
    public String toJson(QueryProfile profile) throws IOException {
      return new String(JSON_SERIALIZER.serialize(profile), UTF_8);
    }

    @Override
    public byte[] convert(QueryProfile profile) {
      return profile.toByteArray();
    }

    @Override
    public QueryProfile revert(byte[] profile) {
      try {
        return QueryProfile.PARSER.parseFrom(profile);
      } catch (InvalidProtocolBufferException e) {
        throw Throwables.propagate(e);
      }
    }
  }


  private static final class JobResultSerializer extends Serializer<JobResult> {
    private final Serializer<JobResult> serializer = ProtostuffSerializer.of(JobResult.getSchema());

    public JobResultSerializer() {
    }

    @Override
    public String toJson(JobResult v) throws IOException {
      return serializer.toJson(v);
    }

    @Override
    public JobResult fromJson(String v) throws IOException {
      return serializer.fromJson(v);
    }

    @Override
    public byte[] convert(JobResult v) {
      return serializer.convert(v);
    }

    @Override
    public JobResult revert(byte[] v) {
      return serializer.revert(v);
    }
  }

  private static QueryType getQueryType(final RpcEndpointInfos clientInfos) {
    if (clientInfos == null) {
      //TODO Come up with a better way to handle this
      return QueryType.ODBC;
    }

    String name = clientInfos.getName().toLowerCase(Locale.ROOT);
    if (name.contains("jdbc") || name.contains("java")) {
      return QueryType.JDBC;
    }

    if (name.contains("odbc") || name.contains("c++")) {
      return QueryType.ODBC;
    }

    return QueryType.UNKNOWN;
  }

  private final class JobsObserverFactory implements QueryObserverFactory {

    @Override
    public QueryObserver createNewQueryObserver(ExternalId id, UserSession session, UserResponseHandler handler) {
      final JobId jobId = getExternalIdAsJobId(id);


      final RpcEndpointInfos clientInfos = session.getClientInfos();
      final QueryType queryType = getQueryType(clientInfos);

      final JobInfo jobInfo =  new JobInfo(jobId, "UNKNOWN", "UNKNOWN", queryType)
            .setUser(session.getCredentials().getUserName())
            .setDatasetPathList(Arrays.asList("UNKNOWN"))
            .setStartTime(System.currentTimeMillis());
        final JobAttempt jobAttempt = new JobAttempt()
            .setInfo(jobInfo)
            .setEndpoint(identity)
            .setDetails(new JobDetails())
            .setState(JobState.RUNNING);

      final Job job = new Job(jobId, jobAttempt);

      storeJob(job);
      QueryListener listener = new QueryListener(job, handler);
      runningJobs.put(jobId, listener);

      return listener;
    }

  }

  private final class QueryListener extends AbstractQueryObserver {

    private final Job job;
    private final ExternalId externalId;
    private final UserResponseHandler responseHandler;
    private final JobStatusListener statusListener;
    private final boolean isInternal;
    private final ExternalListenerManager listeners = new ExternalListenerManager();
    private final CountDownLatch completionLatch = new CountDownLatch(1);
    private final DeferredException exception = new DeferredException();

    private JobResultListener attemptObserver;

    private QueryListener(Job job, UserResponseHandler connection) {
      this.job = job;
      externalId = getJobIdAsExternalId(job.getJobId());
      this.responseHandler = Preconditions.checkNotNull(connection, "handler cannot be null");
      this.statusListener = null;
      isInternal = false;

      setupJobData();
    }

    private QueryListener(Job job, JobStatusListener statusListener) {
      this.job = job;
      externalId = getJobIdAsExternalId(job.getJobId());
      this.responseHandler = null;
      this.statusListener = Preconditions.checkNotNull(statusListener, "statusListener cannot be null");
      isInternal = true;

      setupJobData();
    }

    private Job getJob(){
      return job;
    }

    private void setupJobData() {
      final JobLoader jobLoader = isInternal ?
          new InternalJobLoader(exception, completionLatch, job.getJobId(), jobResultsStore, store) : new ExternalJobLoader(completionLatch, exception);
      final JobDataImpl result = new JobDataImpl(jobLoader, job.getJobId());
      job.setData(result);
      jobResultsStore.cacheNewJob(job.getJobId(), result);
    }

    @Override
    public AttemptObserver newAttempt(AttemptId attemptId, AttemptReason reason) {
      // first attempt is already part of the job
      if (attemptId.getAttemptNum() > 0) {
        // create a new JobAttempt for the new attempt
        final JobInfo jobInfo = ProtostuffUtil.copy(job.getJobAttempt().getInfo())
          .setStartTime(System.currentTimeMillis()) // use different startTime for every attempt
          .setFailureInfo(null)
          .setResultMetadataList(new ArrayList<ArrowFileMetadata>());

        final JobAttempt jobAttempt = new JobAttempt()
                .setInfo(jobInfo)
                .setReason(reason)
                .setEndpoint(identity)
                .setDetails(new JobDetails())
                .setState(JobState.RUNNING);

        job.addAttempt(jobAttempt);
      }

      job.getJobAttempt().setAttemptId(AttemptIdUtils.toString(attemptId));

      if (isInternal) {
        attemptObserver = new JobResultListener(attemptId, job, allocator, statusListener);
      } else {
        attemptObserver = new ExternalJobResultListener(attemptId, responseHandler, job, allocator);
      }

      return attemptObserver;
    }

    @Override
    public void execCompletion(UserResult userResult) {
      final QueryState state = userResult.getState();
      final QueryProfile profile = userResult.getProfile();
      final UserException ex = userResult.getException();
      try {
        addAttemptToJob(job, state, profile);
      } catch (IOException e) {
        exception.addException(e);
      }

      logger.debug("Removing job from running job list: " + job.getJobId().getId());
      runningJobs.remove(job.getJobId());

      if (ex != null) {
        exception.addException(ex);
      }

      if (attemptObserver.getException() != null) {
        exception.addException(attemptObserver.getException());
      }

      this.completionLatch.countDown();

      if (isInternal) {
        try {
          switch (state) {
            case COMPLETED:
              this.statusListener.jobCompleted();
              break;

            case CANCELED:
              this.statusListener.jobCancelled();
              break;

            case FAILED:
              this.statusListener.jobFailed(ex);
              break;

            default:
              logger.warn("Invalid completed state {}", state);
          }
        } catch (Exception e) {
          exception.addException(e);
        }
      } else {
        // send result to client.
        UserResult newResult = userResult.withNewQueryId(ExternalIdHelper.toQueryId(externalId));
        responseHandler.completed(newResult);
      }

      listeners.close(job);
    }
  }


  private static class ExternalJobLoader implements JobLoader {

    private final CountDownLatch completionLatch;
    private final DeferredException exception;

    public ExternalJobLoader(CountDownLatch completionLatch, DeferredException exception) {
      super();
      this.completionLatch = completionLatch;
      this.exception = exception;
    }

    @Override
    public RecordBatches load(int offset, int limit) {
      throw new UnsupportedOperationException("Loading external job results isn't currently supported.");
    }

    @Override
    public void waitForCompletion() {
      try {
        completionLatch.await();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        exception.addException(ex);
      }
    }

    @Override
    public String getJobResultsTable() {
      throw new UnsupportedOperationException("External job results are not stored.");
    }
  }

  private static class InternalJobLoader implements JobLoader {

    private final DeferredException exception;
    private final CountDownLatch completionLatch;
    private final JobId id;
    private final JobResultsStore jobResultsStore;
    private final IndexedStore<JobId, JobResult> store;


    public InternalJobLoader(DeferredException exception, CountDownLatch completionLatch, JobId id,
        JobResultsStore jobResultsStore, IndexedStore<JobId, JobResult> store) {
      super();
      this.exception = exception;
      this.completionLatch = completionLatch;
      this.id = id;
      this.jobResultsStore = jobResultsStore;
      this.store = store;
    }

    @Override
    public RecordBatches load(int offset, int limit) {
      try {
        completionLatch.await();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        exception.addException(ex);
      }

      exception.throwNoClearRuntime();

      return jobResultsStore.loadJobData(id, store.get(id), offset, limit);
    }

    @Override
    public void waitForCompletion() {
      try {
        completionLatch.await();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        exception.addException(ex);
      }

      exception.throwNoClearRuntime();
    }

    @Override
    public String getJobResultsTable() {
      return jobResultsStore.getJobResultsTableName(id);
    }
  }

  /**
   * A query observer for external queries. Delegates the data back to the original connection.
   */
  private final class ExternalJobResultListener extends JobResultListener {

    private final UserResponseHandler connection;
    private final ExternalId externalId;

    ExternalJobResultListener(AttemptId attemptId, UserResponseHandler connection, Job job, BufferAllocator allocator) {
      super(attemptId, job, allocator, JobStatusListener.NONE);
      this.connection = connection;
      this.externalId = getJobIdAsExternalId(job.getJobId());
    }

    @Override
    public void execDataArrived(RpcOutcomeListener<Ack> outcomeListener, QueryWritableBatch result) {
      connection.sendData(outcomeListener, ExternalIdHelper.replaceQueryId(result, externalId));

      // TODO: maybe capture and write the first few result batches to the job store so we can view those results?
    }
  }

  @VisibleForTesting
  Iterable<Job> getAllJobs(){
    FindByCondition condition = new FindByCondition();
    condition.addSortings(DEFAULT_SORTER);
    return findJobs(condition);
  }

  @VisibleForTesting
  void storeJob(Job job) {
    store.put(job.getJobId(), toJobResult(job));
  }

  /**
   * Listener for internally submitted jobs.
   */
  private class JobResultListener extends AbstractAttemptObserver {
    private final AttemptId attemptId;

    private final DeferredException exception = new DeferredException();
    private final Job job;
    private final JobId jobId;
    private final BufferAllocator allocator;
    private final JobStatusListener statusListener;
    private final QueryMetadata.Builder builder;

    JobResultListener(AttemptId attemptId, Job job, BufferAllocator allocator,
        JobStatusListener statusListener) {
      Preconditions.checkNotNull(jobResultsStore);
      this.attemptId = attemptId;
      this.job = job;
      this.jobId = job.getJobId();
      this.allocator = allocator;
      this.builder = QueryMetadata.builder(namespaceService);
      this.statusListener = statusListener;
    }

    Exception getException() {
      return exception.getException();
    }

    @Override
    public void queryStarted(UserRequest query, String user) {

      job.getJobAttempt().setState(RUNNING);
      job.getJobAttempt().getInfo().setRequestType(query.getRequestType());
      job.getJobAttempt().getInfo().setSql(query.getSql());
      job.getJobAttempt().getInfo().setDescription(query.getDescription());
      statusListener.jobSubmitted(jobId);
    }

    @Override
    public void planValidated(RelDataType rowType, SqlNode node, long millisTaken) {
      builder.addRowType(rowType).addParsedSql(node);
    }

    @Override
    public void planSerializable(RelNode converted) {
      builder.addSerializablePlan(converted);
    }

    @Override
    public void planParallelized(final PlanningSet planningSet) {
      builder.setPlanningSet(planningSet);
    }

    @Override
    public void planAccelerated(final SubstitutionInfo info) {
      final JobInfo jobInfo = job.getJobAttempt().getInfo();
      final Acceleration acceleration = new Acceleration(info.getAcceleratedCost());
      final List<Acceleration.Substitution> substitutions = Lists.transform(info.getSubstitutions(),
          new Function<SubstitutionInfo.Substitution, Acceleration.Substitution>() {
            @Nullable
            @Override
            public Acceleration.Substitution apply(@Nullable final SubstitutionInfo.Substitution sub) {
              final Acceleration.Substitution.Identifier id = new Acceleration.Substitution.Identifier(
                  sub.getMaterialization().getAccelerationId(), sub.getMaterialization().getLayoutId());

              return new Acceleration.Substitution(id, sub.getMaterialization().getOriginalCost(), sub.getSpeedup())
                .setTablePathList(sub.getMaterialization().getPath());
            }
          });
      acceleration.setSubstitutionsList(substitutions);
      jobInfo.setAcceleration(acceleration);
    }

    @Override
    public void planCompleted(final ExecutionPlan plan) {
      if (plan != null) {
        final RootSchemaFinder visitor = new RootSchemaFinder(contextProvider.get().getFunctionImplementationRegistry());
        try {
          plan.getRootOperator().accept(visitor, null);
          builder.addBatchSchema(visitor.getBatchSchema());
        } catch (Exception e) {
          exception.addException(e);
        }
      }
      // plan is parallelized after physical planning is done so we need to finalize metadata here
      finalizeMetadata();
    }

    @Override
    public void execStarted(QueryProfile profile) {
      try (TimedBlock b = Timer.time("execStarted")) {
        b.addID("attempt=" + attemptId);

        final JobInfo jobInfo = job.getJobAttempt().getInfo();
        if(profile != null){
          jobInfo.setStartTime(profile.getStart());
          job.getJobAttempt().setState(JobState.RUNNING);

          final QueryProfileParser profileParser = new QueryProfileParser(jobId, profile);
          job.getJobAttempt().setStats(profileParser.getJobStats());
          job.getJobAttempt().setDetails(profileParser.getJobDetails());
        }
      } catch (IOException e) {
        exception.addException(e);
      }
    }

    private void finalizeMetadata(){
      try {
        QueryMetadata metadata = builder.build();
        Optional<List<ParentDatasetInfo>> parents = metadata.getParents();
        JobInfo jobInfo = job.getJobAttempt().getInfo();
        if (parents.isPresent()) {
          jobInfo.setParentsList(parents.get());
        }
        Optional<List<JoinInfo>> joins = metadata.getJoins();
        if (joins.isPresent()) {
          jobInfo.setJoinsList(joins.get());
        }
        Optional<List<FieldOrigin>> fieldOrigins = metadata.getFieldOrigins();
        if (fieldOrigins.isPresent()) {
          jobInfo.setFieldOriginsList(fieldOrigins.get());
        }
        Optional<List<ParentDataset>> grandParents = metadata.getGrandParents();
        if (grandParents.isPresent()) {
          jobInfo.setGrandParentsList(grandParents.get());
        }
        storeJob(job);
        statusListener.metadataCollected(metadata);
      }catch(Exception ex){
        exception.addException(ex);
      }
    }

    @Override
    public void planRelTransform(PlannerPhase phase, RelNode before, RelNode after, long millisTaken) {
      statusListener.planRelTansform(phase, before, after, millisTaken);
      switch(phase){
      case LOGICAL:
        builder.addLogicalPlan(before);
        // set final pre-accelerated cost
        final RelOptCost cost = after.getCluster().getMetadataQuery().getCumulativeCost(after);
        builder.addCost(cost);
        break;
      case JOIN_PLANNING:
        builder.addPreJoinPlan(before);
        break;
      default:
        return;
      }
    }

    @Override
    public void attemptCompletion(UserResult result) {
      try {
        final QueryState queryState = result.getState();
        addAttemptToJob(job, queryState, result.getProfile());
      } catch (IOException e) {
        exception.addException(e);
      }
    }

    @Override
    public void execDataArrived(RpcOutcomeListener<Ack> outcomeListener, QueryWritableBatch result) {
      try (TimedBlock b = Timer.time("dataMetadataArrived");
          QueryDataBatch dataBatch = LocalUserUtil.acquireData(allocator, outcomeListener, result);
          RecordBatchLoader loader = new RecordBatchLoader(allocator)) {
        b.addID("attempt=" + attemptId);
        loader.load(dataBatch.getHeader().getDef(), dataBatch.getData());

        // Query output just contains the batch unique id and number of records in the batch.
        try (RecordBatchData batch = new RecordBatchData(loader, allocator)) {
          List<ValueVector> vectors = batch.getVectors();

          if (vectors.size() < 4 || !(vectors.get(3) instanceof NullableVarBinaryVector) ) {
            throw UserException.unsupportedError()
                .message("Job output contains invalid data")
                .build(logger);
          }

          NullableVarBinaryVector metadataVector = (NullableVarBinaryVector) vectors.get(3);

          for (int i = 0; i < batch.getRecordCount(); i++) {
            final ArrowFileFormat.ArrowFileMetadata metadata =
                ArrowFileFormat.ArrowFileMetadata.parseFrom(metadataVector.getAccessor().getObject(i));
            job.getJobAttempt().getInfo().getResultMetadataList().add(ArrowFileReader.toBean(metadata));
          }
        }
      } catch (Exception ex) {
        exception.addException(ex);
      }
    }
  }

  private void addAttemptToJob(Job job, QueryState state, QueryProfile profile) throws IOException {

      job.getJobAttempt().setState(queryStatusToJobStatus(state));
      final JobInfo jobInfo = job.getJobAttempt().getInfo();
      final QueryProfileParser profileParser = new QueryProfileParser(job.getJobId(), profile);
      jobInfo.setStartTime(profile.getStart());
      jobInfo.setFinishTime(profile.getEnd());
      if (state == QueryState.FAILED && profile.hasError()) {
        jobInfo.setFailureInfo(profile.getError());
      }
      job.getJobAttempt().setStats(profileParser.getJobStats());
      job.getJobAttempt().setDetails(profileParser.getJobDetails());

      storeJob(job);

      profileStore.put(AttemptIdUtils.fromString(job.getJobAttempt().getAttemptId()), profile);
  }

  private boolean jobIsDone(JobAttempt config){
    switch(config.getState()){
    case CANCELED:
    case COMPLETED:
    case FAILED:
      return true;
    default:
      return false;
    }
  }

  @Override
  public QueryProfile getProfile(JobId jobId, int attempt) {
    final ForemenTool tool = this.foremenTool.get();

    Job job = getJob(jobId);
    final AttemptId attemptId = new AttemptId(getJobIdAsExternalId(jobId), attempt);
    if(jobIsDone(job.getJobAttempt())){
      return profileStore.get(attemptId);
    }

    // Check if the profile for given attempt already exists. Even if the job is not done, it is possible that
    // profile exists for previous attempts
    final QueryProfile queryProfile = profileStore.get(attemptId);
    if (queryProfile != null) {
      return queryProfile;
    }

    final NodeEndpoint endpoint = job.getJobAttempt().getEndpoint();
    if(endpoint.equals(identity)){
      Optional<QueryProfile> profile = tool.getProfile(attemptId.getExternalId());
      return profile.orNull();
    }
    try{
      CoordTunnel tunnel = coordTunnelCreator.get().getTunnel(toPB(endpoint));
      return tunnel.requestQueryProfile(attemptId.getExternalId()).checkedGet(15, TimeUnit.SECONDS);
    }catch(TimeoutException | RpcException | RuntimeException e){
      logger.info("Unable to retrieve remote query profile for external id: {}",
          ExternalIdHelper.toString(attemptId.getExternalId()), e);
      return null;
    }
  }

  @Override
  public void cancel(String username, JobId jobId) throws JobException {
    final ForemenTool tool = this.foremenTool.get();
    final ExternalId id = ExternalIdHelper.toExternal(QueryIdHelper.getQueryIdFromString(jobId.getId()));
    if(tool.cancel(id)){
      logger.debug("Job cancellation requested on current node.");
      return;
    }

    // now remote...
    final Job job = getJob(jobId);
    NodeEndpoint endpoint = job.getJobAttempt().getEndpoint();
    if(endpoint.equals(identity)){
      throw new JobWarningException(jobId, "Unable to cancel job started on current node. It may have completed before cancellation was request.");
    }

    try{
      final CoordTunnel tunnel = coordTunnelCreator.get().getTunnel(toPB(endpoint));
      Ack ack = tunnel.requestCancelQuery(id).checkedGet(15, TimeUnit.SECONDS);
      if(ack.getOk()){
        logger.debug("Job cancellation requested on {}.", endpoint.getAddress());
        return;
      } else {
        throw new JobWarningException(jobId, String.format("Unable to cancel job started on %s. It may have completed before cancellation was request.", endpoint.getAddress()));
      }
    }catch(TimeoutException | RpcException | RuntimeException e){
      logger.info("Unable to cancel remote job for external id: {}", ExternalIdHelper.toString(id), e);
      throw new JobWarningException(jobId, String.format("Unable to cancel job on node %s.", endpoint.getAddress()));
    }

  }

  protected static List<SearchFieldSorting> buildSorter(final String shortKey, final SortOrder order) {
    if (shortKey != null) {
      final IndexKey key = JobIndexKeys.MAPPING.getKey(shortKey);
      if(key == null || !key.isSorted()){
        throw UserException.functionError().message("Unable to sort by field {}.", shortKey).build(logger);
      }
      return ImmutableList.of(key.toSortField(order));
    }
    return DEFAULT_SORTER;
  }


  private JobResult toJobResult(Job job) {
    return new JobResult().setAttemptsList(job.getAttempts());
  }

  class CleanupTask implements Runnable {

    @Override
    public void run() {
      //obtain the max age values during each cleanup as the values could change.
      long maxAgeInMillis = contextProvider.get().getOptionManager().getOption(ExecConstants.DEBUG_RESULTS_MAX_AGE_IN_MILLISECONDS);
      long maxAgeInDays = contextProvider.get().getOptionManager().getOption(ExecConstants.RESULTS_MAX_AGE_IN_DAYS);
      long jobResultsMaxAgeInMillis = (maxAgeInDays* ONE_DAY_IN_MILLIS) + maxAgeInMillis;
      long cutOffTime = System.currentTimeMillis() - jobResultsMaxAgeInMillis;

      //iterate through the results and remove directories if they are older
      for (Map.Entry<JobId, JobResult> entry : store.find()) {
        JobId jobId = entry.getKey();
        JobResult jobResult = entry.getValue();
        JobInfo jobInfo = jobResult.getAttemptsList().get(jobResult.getAttemptsList().size() - 1).getInfo();
        if (jobInfo != null // TODO: skip cleanup for now, but maybe delete instead (for unknown finish time)?
            && jobInfo.getFinishTime() < cutOffTime) {
          jobResultsStore.cleanup(jobId);
        }
      }
    }
  }
}
