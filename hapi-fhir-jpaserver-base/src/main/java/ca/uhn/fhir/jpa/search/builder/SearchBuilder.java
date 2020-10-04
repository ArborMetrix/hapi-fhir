package ca.uhn.fhir.jpa.search.builder;

/*
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2020 University Health Network
 * %%
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
 * #L%
 */

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.interceptor.api.HookParams;
import ca.uhn.fhir.interceptor.api.IInterceptorBroadcaster;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.config.DaoConfig;
import ca.uhn.fhir.jpa.api.dao.IDao;
import ca.uhn.fhir.jpa.config.HibernateDialectProvider;
import ca.uhn.fhir.jpa.dao.IFulltextSearchSvc;
import ca.uhn.fhir.jpa.dao.IResultIterator;
import ca.uhn.fhir.jpa.dao.ISearchBuilder;
import ca.uhn.fhir.jpa.dao.data.IResourceSearchViewDao;
import ca.uhn.fhir.jpa.dao.data.IResourceTagDao;
import ca.uhn.fhir.jpa.dao.index.IdHelperService;
import ca.uhn.fhir.jpa.entity.ResourceSearchView;
import ca.uhn.fhir.jpa.interceptor.JpaPreResourceAccessDetails;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.jpa.model.entity.ResourceTag;
import ca.uhn.fhir.jpa.model.search.SearchRuntimeDetails;
import ca.uhn.fhir.jpa.model.search.StorageProcessingMessage;
import ca.uhn.fhir.jpa.search.builder.sql.GeneratedSql;
import ca.uhn.fhir.jpa.search.builder.sql.SearchQueryBuilder;
import ca.uhn.fhir.jpa.search.builder.sql.SearchQueryExecutor;
import ca.uhn.fhir.jpa.search.builder.sql.SqlObjectFactory;
import ca.uhn.fhir.jpa.search.lastn.IElasticsearchSvc;
import ca.uhn.fhir.jpa.searchparam.JpaRuntimeSearchParam;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.searchparam.registry.ISearchParamRegistry;
import ca.uhn.fhir.jpa.searchparam.util.Dstu3DistanceHelper;
import ca.uhn.fhir.jpa.searchparam.util.LastNParameterHelper;
import ca.uhn.fhir.jpa.util.BaseIterator;
import ca.uhn.fhir.jpa.util.CurrentThreadCaptureQueriesListener;
import ca.uhn.fhir.jpa.util.JpaInterceptorBroadcaster;
import ca.uhn.fhir.jpa.util.QueryChunker;
import ca.uhn.fhir.jpa.util.SqlQueryList;
import ca.uhn.fhir.model.api.IQueryParameterType;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.model.api.Include;
import ca.uhn.fhir.model.api.ResourceMetadataKeyEnum;
import ca.uhn.fhir.model.primitive.InstantDt;
import ca.uhn.fhir.model.valueset.BundleEntrySearchModeEnum;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;
import ca.uhn.fhir.rest.api.SortOrderEnum;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.api.server.IPreResourceAccessDetails;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.ResourcePersistentId;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import ca.uhn.fhir.util.StopWatch;
import ca.uhn.fhir.util.UrlUtil;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.healthmarketscience.sqlbuilder.Condition;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SingleColumnRowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Nonnull;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceContextType;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.From;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.apache.commons.lang3.StringUtils.defaultString;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * The SearchBuilder is responsible for actually forming the SQL query that handles
 * searches for resources
 */
public class SearchBuilder implements ISearchBuilder {

	/**
	 * See loadResourcesByPid
	 * for an explanation of why we use the constant 800
	 */
	// NB: keep public
	public static final int MAXIMUM_PAGE_SIZE = 800;
	public static final int MAXIMUM_PAGE_SIZE_FOR_TESTING = 50;
	private static final Logger ourLog = LoggerFactory.getLogger(SearchBuilder.class);
	private static final ResourcePersistentId NO_MORE = new ResourcePersistentId(-1L);
	public static boolean myUseMaxPageSize50ForTest = false;
	private final String myResourceName;
	private final Class<? extends IBaseResource> myResourceType;
	private final IDao myCallingDao;
	@Autowired
	protected IInterceptorBroadcaster myInterceptorBroadcaster;
	@Autowired
	protected IResourceTagDao myResourceTagDao;
	@PersistenceContext(type = PersistenceContextType.TRANSACTION)
	protected EntityManager myEntityManager;
	@Autowired
	private DaoConfig myDaoConfig;
	@Autowired
	private IResourceSearchViewDao myResourceSearchViewDao;
	@Autowired
	private FhirContext myContext;
	@Autowired
	private IdHelperService myIdHelperService;
	@Autowired(required = false)
	private IFulltextSearchSvc myFulltextSearchSvc;
	@Autowired(required = false)
	private IElasticsearchSvc myIElasticsearchSvc;
	@Autowired
	private ISearchParamRegistry mySearchParamRegistry;
	private List<ResourcePersistentId> myAlsoIncludePids;
	private CriteriaBuilder myCriteriaBuilder;
	private SearchParameterMap myParams;
	private String mySearchUuid;
	private int myFetchSize;
	private Integer myMaxResultsToFetch;
	private Set<ResourcePersistentId> myPidSet;
	private RequestPartitionId myRequestPartitionId;
	@Autowired
	private PartitionSettings myPartitionSettings;
	@Autowired
	private DataSource myDataSource;
	@Autowired
	private SqlObjectFactory mySqlBuilderFactory;
	@Autowired
	private HibernateDialectProvider myDialectProvider;

	/**
	 * Constructor
	 */
	public SearchBuilder(IDao theDao, String theResourceName, Class<? extends IBaseResource> theResourceType) {
		myCallingDao = theDao;
		myResourceName = theResourceName;
		myResourceType = theResourceType;
	}

	@Override
	public void setMaxResultsToFetch(Integer theMaxResultsToFetch) {
		myMaxResultsToFetch = theMaxResultsToFetch;
	}

	private void searchForIdsWithAndOr(SearchQueryBuilder theSearchSqlBuilder, QueryStack theQueryStack3, @Nonnull SearchParameterMap theParams, RequestDetails theRequest) {
		myParams = theParams;

		// Remove any empty parameters
		theParams.clean();

		// For DSTU3, pull out near-distance first so when it comes time to evaluate near, we already know the distance
		if (myContext.getVersion().getVersion() == FhirVersionEnum.DSTU3) {
			Dstu3DistanceHelper.setNearDistance(myResourceType, theParams);
		}

		// Attempt to lookup via composite unique key.
		if (isCompositeUniqueSpCandidate()) {
			attemptCompositeUniqueSpProcessing(theQueryStack3, theParams, theRequest);
		}

		// Handle each parameter
		for (Map.Entry<String, List<List<IQueryParameterType>>> nextParamEntry : myParams.entrySet()) {
			String nextParamName = nextParamEntry.getKey();
			if (myParams.isLastN() && LastNParameterHelper.isLastNParameter(nextParamName, myContext)) {
				// Skip parameters for Subject, Patient, Code and Category for LastN as these will be filtered by Elasticsearch
				continue;
			}
			List<List<IQueryParameterType>> andOrParams = nextParamEntry.getValue();
			Condition predicate = theQueryStack3.searchForIdsWithAndOr(null, myResourceName, nextParamName, andOrParams, theRequest, myRequestPartitionId);
			if (predicate != null) {
				theSearchSqlBuilder.addPredicate(predicate);
			}
		}
	}

	/**
	 * A search is a candidate for Composite Unique SP if unique indexes are enabled, there is no EverythingMode, and the
	 * parameters all have no modifiers.
	 */
	private boolean isCompositeUniqueSpCandidate() {
		return myDaoConfig.isUniqueIndexesEnabled() &&
			myParams.getEverythingMode() == null &&
			myParams.isAllParametersHaveNoModifier();
	}

	@SuppressWarnings("ConstantConditions")
	@Override
	public Iterator<Long> createCountQuery(SearchParameterMap theParams, String theSearchUuid, RequestDetails theRequest, @Nonnull RequestPartitionId theRequestPartitionId) {
		assert theRequestPartitionId != null;
		assert TransactionSynchronizationManager.isActualTransactionActive();

		init(theParams, theSearchUuid, theRequestPartitionId);

		ArrayList<SearchQueryExecutor> queries = createQuery(myParams, null, null, true, theRequest, null);
		try (SearchQueryExecutor queryExecutor = queries.get(0)) {
			return Lists.newArrayList(queryExecutor.next()).iterator();
		}
	}

	/**
	 * @param thePidSet May be null
	 */
	@Override
	public void setPreviouslyAddedResourcePids(@Nonnull List<ResourcePersistentId> thePidSet) {
		myPidSet = new HashSet<>(thePidSet);
	}

	@SuppressWarnings("ConstantConditions")
	@Override
	public IResultIterator createQuery(SearchParameterMap theParams, SearchRuntimeDetails theSearchRuntimeDetails, RequestDetails theRequest, @Nonnull RequestPartitionId theRequestPartitionId) {
		assert theRequestPartitionId != null;
		assert TransactionSynchronizationManager.isActualTransactionActive();

		init(theParams, theSearchRuntimeDetails.getSearchUuid(), theRequestPartitionId);

		if (myPidSet == null) {
			myPidSet = new HashSet<>();
		}

		return new QueryIterator(theSearchRuntimeDetails, theRequest);
	}

	private void init(SearchParameterMap theParams, String theSearchUuid, RequestPartitionId theRequestPartitionId) {
		myCriteriaBuilder = myEntityManager.getCriteriaBuilder();
		myParams = theParams;
		mySearchUuid = theSearchUuid;
		myRequestPartitionId = theRequestPartitionId;
	}

	private ArrayList<SearchQueryExecutor> createQuery(SearchParameterMap theParams, SortSpec sort, Integer theMaximumResults, boolean theCount, RequestDetails theRequest,
																		SearchRuntimeDetails theSearchRuntimeDetails) {

		List<ResourcePersistentId> pids = new ArrayList<>();

		/*
		 * Fulltext or lastn search
		 */
		if (myParams.containsKey(Constants.PARAM_CONTENT) || myParams.containsKey(Constants.PARAM_TEXT) || myParams.isLastN()) {
			if (myParams.containsKey(Constants.PARAM_CONTENT) || myParams.containsKey(Constants.PARAM_TEXT)) {
				if (myFulltextSearchSvc == null) {
					if (myParams.containsKey(Constants.PARAM_TEXT)) {
						throw new InvalidRequestException("Fulltext search is not enabled on this service, can not process parameter: " + Constants.PARAM_TEXT);
					} else if (myParams.containsKey(Constants.PARAM_CONTENT)) {
						throw new InvalidRequestException("Fulltext search is not enabled on this service, can not process parameter: " + Constants.PARAM_CONTENT);
					}
				}

				if (myParams.getEverythingMode() != null) {
					pids = myFulltextSearchSvc.everything(myResourceName, myParams, theRequest);
				} else {
					pids = myFulltextSearchSvc.search(myResourceName, myParams);
				}
			} else if (myParams.isLastN()) {
				if (myIElasticsearchSvc == null) {
					if (myParams.isLastN()) {
						throw new InvalidRequestException("LastN operation is not enabled on this service, can not process this request");
					}
				}
				List<String> lastnResourceIds = myIElasticsearchSvc.executeLastN(myParams, myContext, theMaximumResults);
				for (String lastnResourceId : lastnResourceIds) {
					pids.add(myIdHelperService.resolveResourcePersistentIds(myRequestPartitionId, myResourceName, lastnResourceId));
				}
			}
			if (theSearchRuntimeDetails != null) {
				theSearchRuntimeDetails.setFoundIndexMatchesCount(pids.size());
				HookParams params = new HookParams()
					.add(RequestDetails.class, theRequest)
					.addIfMatchesType(ServletRequestDetails.class, theRequest)
					.add(SearchRuntimeDetails.class, theSearchRuntimeDetails);
				JpaInterceptorBroadcaster.doCallHooks(myInterceptorBroadcaster, theRequest, Pointcut.JPA_PERFTRACE_INDEXSEARCH_QUERY_COMPLETE, params);
			}

			if (pids.isEmpty()) {
				// Will never match
				pids = Collections.singletonList(new ResourcePersistentId(-1L));
			}

		}

		ArrayList<SearchQueryExecutor> queries = new ArrayList<>();

		if (!pids.isEmpty()) {
			new QueryChunker<Long>().chunk(ResourcePersistentId.toLongList(pids), t -> doCreateChunkedQueries(theParams, t, sort, theCount, theRequest, queries));
		} else {
			Optional<SearchQueryExecutor> query = createChunkedQuery(theParams, sort, theMaximumResults, theCount, theRequest, null);
			query.ifPresent(t -> queries.add(t));
		}

		return queries;
	}

	private void doCreateChunkedQueries(SearchParameterMap theParams, List<Long> thePids, SortSpec sort, boolean theCount, RequestDetails theRequest, ArrayList<SearchQueryExecutor> theQueries) {
		if (thePids.size() < getMaximumPageSize()) {
			normalizeIdListForLastNInClause(thePids);
		}
		Optional<SearchQueryExecutor> query = createChunkedQuery(theParams, sort, thePids.size(), theCount, theRequest, thePids);
		query.ifPresent(t -> theQueries.add(t));
	}

	private Optional<SearchQueryExecutor> createChunkedQuery(SearchParameterMap theParams, SortSpec sort, Integer theMaximumResults, boolean theCount, RequestDetails theRequest, List<Long> thePidList) {
		String sqlBuilderResourceName = myParams.getEverythingMode() == null ? myResourceName : null;
		SearchQueryBuilder sqlBuilder = new SearchQueryBuilder(myContext, myDaoConfig.getModelConfig(), myPartitionSettings, myRequestPartitionId, sqlBuilderResourceName, mySqlBuilderFactory, myDialectProvider, theCount);
		QueryStack queryStack3 = new QueryStack(theParams, myDaoConfig, myDaoConfig.getModelConfig(), myContext, sqlBuilder, mySearchParamRegistry, myPartitionSettings);

		JdbcTemplate jdbcTemplate = new JdbcTemplate(myDataSource);
		jdbcTemplate.setFetchSize(myFetchSize);
		if (theMaximumResults != null) {
			jdbcTemplate.setMaxRows(theMaximumResults);
		}

		if (myParams.getEverythingMode() != null) {
			Long targetPid = null;
			if (myParams.get(IAnyResource.SP_RES_ID) != null) {
				StringParam idParam = (StringParam) myParams.get(IAnyResource.SP_RES_ID).get(0).get(0);
				ResourcePersistentId pid = myIdHelperService.resolveResourcePersistentIds(myRequestPartitionId, myResourceName, idParam.getValue());
				if (myAlsoIncludePids == null) {
					myAlsoIncludePids = new ArrayList<>(1);
				}
				myAlsoIncludePids.add(pid);
				targetPid = pid.getIdAsLong();
			} else {

				// For Everything queries, we make the query root by the ResourceLink table, since this query
				// is basically a reverse-include search. For type/Everything (as opposed to instance/Everything)
				// the one problem with this approach is that it doesn't catch Patients that have absolutely
				// nothing linked to them. So we do one additional query to make sure we catch those too.
				SearchQueryBuilder fetchPidsSqlBuilder = new SearchQueryBuilder(myContext, myDaoConfig.getModelConfig(), myPartitionSettings, myRequestPartitionId, myResourceName, mySqlBuilderFactory, myDialectProvider, theCount);
				GeneratedSql allTargetsSql = fetchPidsSqlBuilder.generate(myMaxResultsToFetch);
				String sql = allTargetsSql.getSql();
				Object[] args = allTargetsSql.getBindVariables().toArray(new Object[0]);
				List<Long> output = jdbcTemplate.query(sql, args, new SingleColumnRowMapper<>(Long.class));
				if (myAlsoIncludePids == null) {
					myAlsoIncludePids = new ArrayList<>(output.size());
				}
				myAlsoIncludePids.addAll(ResourcePersistentId.fromLongList(output));

			}
			queryStack3.addPredicateEverythingOperation(myResourceName, targetPid);

		} else {

			/*
			 * If we're doing a filter, always use the resource table as the root - This avoids the possibility of
			 * specific filters with ORs as their root from working around the natural resource type / deletion
			 * status / partition IDs built into queries.
			 */
			if (theParams.containsKey(Constants.PARAM_FILTER)) {
				Condition partitionIdPredicate = sqlBuilder.getOrCreateResourceTablePredicateBuilder().createPartitionIdPredicate(myRequestPartitionId);
				if (partitionIdPredicate != null) {
					sqlBuilder.addPredicate(partitionIdPredicate);
				}
			}

			// Normal search
			searchForIdsWithAndOr(sqlBuilder, queryStack3, myParams, theRequest);

		}

		// If we haven't added any predicates yet, we're doing a search for all resources. Make sure we add the
		// partition ID predicate in that case.
		if (!sqlBuilder.haveAtLeastOnePredicate()) {
			Condition partitionIdPredicate = sqlBuilder.getOrCreateResourceTablePredicateBuilder().createPartitionIdPredicate(myRequestPartitionId);
			if (partitionIdPredicate != null) {
				sqlBuilder.addPredicate(partitionIdPredicate);
			}
		}

		// Add PID list predicate for full text search and/or lastn operation
		if (thePidList != null && thePidList.size() > 0) {
			sqlBuilder.addResourceIdsPredicate(thePidList);
		}

		// Last updated
		DateRangeParam lu = myParams.getLastUpdated();
		if (lu != null && !lu.isEmpty()) {
			Condition lastUpdatedPredicates = sqlBuilder.addPredicateLastUpdated(lu);
			sqlBuilder.addPredicate(lastUpdatedPredicates);
		}

		/*
		 * Sort
		 *
		 * If we have a sort, we wrap the criteria search (the search that actually
		 * finds the appropriate resources) in an outer search which is then sorted
		 */
		if (sort != null) {
			assert !theCount;

			createSort(queryStack3, sort);
		}


		/*
		 * Now perform the search
		 */
		GeneratedSql generatedSql = sqlBuilder.generate(myMaxResultsToFetch);
		if (generatedSql.isMatchNothing()) {
			return Optional.empty();
		}

		SearchQueryExecutor executor = mySqlBuilderFactory.newSearchQueryExecutor(generatedSql, myMaxResultsToFetch);
		return Optional.of(executor);
	}

	private List<Long> normalizeIdListForLastNInClause(List<Long> lastnResourceIds) {
		/*
			The following is a workaround to a known issue involving Hibernate. If queries are used with "in" clauses with large and varying
			numbers of parameters, this can overwhelm Hibernate's QueryPlanCache and deplete heap space. See the following link for more info:
			https://stackoverflow.com/questions/31557076/spring-hibernate-query-plan-cache-memory-usage.

			Normalizing the number of parameters in the "in" clause stabilizes the size of the QueryPlanCache, so long as the number of
			arguments never exceeds the maximum specified below.
		 */
		int listSize = lastnResourceIds.size();

		if (listSize > 1 && listSize < 10) {
			padIdListWithPlaceholders(lastnResourceIds, 10);
		} else if (listSize > 10 && listSize < 50) {
			padIdListWithPlaceholders(lastnResourceIds, 50);
		} else if (listSize > 50 && listSize < 100) {
			padIdListWithPlaceholders(lastnResourceIds, 100);
		} else if (listSize > 100 && listSize < 200) {
			padIdListWithPlaceholders(lastnResourceIds, 200);
		} else if (listSize > 200 && listSize < 500) {
			padIdListWithPlaceholders(lastnResourceIds, 500);
		} else if (listSize > 500 && listSize < 800) {
			padIdListWithPlaceholders(lastnResourceIds, 800);
		}

		return lastnResourceIds;
	}

	private void padIdListWithPlaceholders(List<Long> theIdList, int preferredListSize) {
		while (theIdList.size() < preferredListSize) {
			theIdList.add(-1L);
		}
	}

	private void createSort(QueryStack theQueryStack, SortSpec theSort) {
		if (theSort == null || isBlank(theSort.getParamName())) {
			return;
		}

		boolean ascending = (theSort.getOrder() == null) || (theSort.getOrder() == SortOrderEnum.ASC);

		if (IAnyResource.SP_RES_ID.equals(theSort.getParamName())) {

			theQueryStack.addSortOnResourceId(ascending);

		} else if (Constants.PARAM_LASTUPDATED.equals(theSort.getParamName())) {

			theQueryStack.addSortOnLastUpdated(ascending);

		} else {

			RuntimeResourceDefinition resourceDef = myContext.getResourceDefinition(myResourceName);
			RuntimeSearchParam param = mySearchParamRegistry.getSearchParamByName(resourceDef, theSort.getParamName());
			if (param == null) {
				throw new InvalidRequestException("Unknown sort parameter '" + theSort.getParamName() + "'");
			}

			switch (param.getParamType()) {
				case STRING:
					theQueryStack.addSortOnString(myResourceName, theSort.getParamName(), ascending);
					break;
				case DATE:
					theQueryStack.addSortOnDate(myResourceName, theSort.getParamName(), ascending);
					break;
				case REFERENCE:
					theQueryStack.addSortOnResourceLink(myResourceName, theSort.getParamName(), ascending);
					break;
				case TOKEN:
					theQueryStack.addSortOnToken(myResourceName, theSort.getParamName(), ascending);
					break;
				case NUMBER:
					theQueryStack.addSortOnNumber(myResourceName, theSort.getParamName(), ascending);
					break;
				case URI:
					theQueryStack.addSortOnUri(myResourceName, theSort.getParamName(), ascending);
					break;
				case QUANTITY:
					theQueryStack.addSortOnQuantity(myResourceName, theSort.getParamName(), ascending);
					break;
				case SPECIAL:
				case COMPOSITE:
				case HAS:
				default:
					throw new InvalidRequestException("This server does not support _sort specifications of type " + param.getParamType() + " - Can't serve _sort=" + theSort.getParamName());
			}

		}

		// Recurse
		createSort(theQueryStack, theSort.getChain());

	}

	private void doLoadPids(Collection<ResourcePersistentId> thePids, Collection<ResourcePersistentId> theIncludedPids, List<IBaseResource> theResourceListToPopulate, boolean theForHistoryOperation,
									Map<ResourcePersistentId, Integer> thePosition) {

		List<Long> myLongPersistentIds;
		if (thePids.size() < getMaximumPageSize()) {
			myLongPersistentIds = normalizeIdListForLastNInClause(ResourcePersistentId.toLongList(thePids));
		} else {
			myLongPersistentIds = ResourcePersistentId.toLongList(thePids);
		}

		// -- get the resource from the searchView
		Collection<ResourceSearchView> resourceSearchViewList = myResourceSearchViewDao.findByResourceIds(myLongPersistentIds);

		//-- preload all tags with tag definition if any
		Map<ResourcePersistentId, Collection<ResourceTag>> tagMap = getResourceTagMap(resourceSearchViewList);

		ResourcePersistentId resourceId;
		for (ResourceSearchView next : resourceSearchViewList) {
			if (next.getDeleted() != null) {
				continue;
			}

			Class<? extends IBaseResource> resourceType = myContext.getResourceDefinition(next.getResourceType()).getImplementingClass();

			resourceId = new ResourcePersistentId(next.getId());

			IBaseResource resource = myCallingDao.toResource(resourceType, next, tagMap.get(resourceId), theForHistoryOperation);
			if (resource == null) {
				ourLog.warn("Unable to find resource {}/{}/_history/{} in database", next.getResourceType(), next.getIdDt().getIdPart(), next.getVersion());
				continue;
			}
			Integer index = thePosition.get(resourceId);
			if (index == null) {
				ourLog.warn("Got back unexpected resource PID {}", resourceId);
				continue;
			}

			if (resource instanceof IResource) {
				if (theIncludedPids.contains(resourceId)) {
					ResourceMetadataKeyEnum.ENTRY_SEARCH_MODE.put((IResource) resource, BundleEntrySearchModeEnum.INCLUDE);
				} else {
					ResourceMetadataKeyEnum.ENTRY_SEARCH_MODE.put((IResource) resource, BundleEntrySearchModeEnum.MATCH);
				}
			} else {
				if (theIncludedPids.contains(resourceId)) {
					ResourceMetadataKeyEnum.ENTRY_SEARCH_MODE.put((IAnyResource) resource, BundleEntrySearchModeEnum.INCLUDE.getCode());
				} else {
					ResourceMetadataKeyEnum.ENTRY_SEARCH_MODE.put((IAnyResource) resource, BundleEntrySearchModeEnum.MATCH.getCode());
				}
			}

			theResourceListToPopulate.set(index, resource);
		}
	}

	private Map<ResourcePersistentId, Collection<ResourceTag>> getResourceTagMap(Collection<ResourceSearchView> theResourceSearchViewList) {

		List<Long> idList = new ArrayList<>(theResourceSearchViewList.size());

		//-- find all resource has tags
		for (ResourceSearchView resource : theResourceSearchViewList) {
			if (resource.isHasTags())
				idList.add(resource.getId());
		}

		Map<ResourcePersistentId, Collection<ResourceTag>> tagMap = new HashMap<>();

		//-- no tags
		if (idList.size() == 0)
			return tagMap;

		//-- get all tags for the idList
		Collection<ResourceTag> tagList = myResourceTagDao.findByResourceIds(idList);

		//-- build the map, key = resourceId, value = list of ResourceTag
		ResourcePersistentId resourceId;
		Collection<ResourceTag> tagCol;
		for (ResourceTag tag : tagList) {

			resourceId = new ResourcePersistentId(tag.getResourceId());
			tagCol = tagMap.get(resourceId);
			if (tagCol == null) {
				tagCol = new ArrayList<>();
				tagCol.add(tag);
				tagMap.put(resourceId, tagCol);
			} else {
				tagCol.add(tag);
			}
		}

		return tagMap;
	}

	@Override
	public void loadResourcesByPid(Collection<ResourcePersistentId> thePids, Collection<ResourcePersistentId> theIncludedPids, List<IBaseResource> theResourceListToPopulate, boolean theForHistoryOperation, RequestDetails theDetails) {
		if (thePids.isEmpty()) {
			ourLog.debug("The include pids are empty");
			// return;
		}

		// Dupes will cause a crash later anyhow, but this is expensive so only do it
		// when running asserts
		assert new HashSet<>(thePids).size() == thePids.size() : "PID list contains duplicates: " + thePids;

		Map<ResourcePersistentId, Integer> position = new HashMap<>();
		for (ResourcePersistentId next : thePids) {
			position.put(next, theResourceListToPopulate.size());
			theResourceListToPopulate.add(null);
		}

		List<ResourcePersistentId> pids = new ArrayList<>(thePids);
		new QueryChunker<ResourcePersistentId>().chunk(pids, t -> doLoadPids(t, theIncludedPids, theResourceListToPopulate, theForHistoryOperation, position));

	}

	/**
	 * THIS SHOULD RETURN HASHSET and not just Set because we add to it later
	 * so it can't be Collections.emptySet() or some such thing
	 */
	@Override
	public HashSet<ResourcePersistentId> loadIncludes(FhirContext theContext, EntityManager theEntityManager, Collection<ResourcePersistentId> theMatches, Set<Include> theRevIncludes,
																	  boolean theReverseMode, DateRangeParam theLastUpdated, String theSearchIdOrDescription, RequestDetails theRequest) {
		if (theMatches.size() == 0) {
			return new HashSet<>();
		}
		if (theRevIncludes == null || theRevIncludes.isEmpty()) {
			return new HashSet<>();
		}
		String searchFieldName = theReverseMode ? "myTargetResourcePid" : "mySourceResourcePid";
		String findFieldName = theReverseMode ? "mySourceResourcePid" : "myTargetResourcePid";

		List<ResourcePersistentId> nextRoundMatches = new ArrayList<>(theMatches);
		HashSet<ResourcePersistentId> allAdded = new HashSet<>();
		HashSet<ResourcePersistentId> original = new HashSet<>(theMatches);
		ArrayList<Include> includes = new ArrayList<>(theRevIncludes);

		int roundCounts = 0;
		StopWatch w = new StopWatch();

		boolean addedSomeThisRound;
		do {
			roundCounts++;

			HashSet<ResourcePersistentId> pidsToInclude = new HashSet<>();

			for (Iterator<Include> iter = includes.iterator(); iter.hasNext(); ) {
				Include nextInclude = iter.next();
				if (nextInclude.isRecurse() == false) {
					iter.remove();
				}

				boolean matchAll = "*".equals(nextInclude.getValue());
				if (matchAll) {
					String sql;
					sql = "SELECT r." + findFieldName + " FROM ResourceLink r WHERE r." + searchFieldName + " IN (:target_pids) ";
					List<Collection<ResourcePersistentId>> partitions = partition(nextRoundMatches, getMaximumPageSize());
					for (Collection<ResourcePersistentId> nextPartition : partitions) {
						TypedQuery<Long> q = theEntityManager.createQuery(sql, Long.class);
						q.setParameter("target_pids", ResourcePersistentId.toLongList(nextPartition));
						List<Long> results = q.getResultList();
						for (Long resourceLink : results) {
							if (resourceLink == null) {
								continue;
							}
							if (theReverseMode) {
								pidsToInclude.add(new ResourcePersistentId(resourceLink));
							} else {
								pidsToInclude.add(new ResourcePersistentId(resourceLink));
							}
						}
					}
				} else {

					List<String> paths;
					RuntimeSearchParam param;
					String resType = nextInclude.getParamType();
					if (isBlank(resType)) {
						continue;
					}
					RuntimeResourceDefinition def = theContext.getResourceDefinition(resType);
					if (def == null) {
						ourLog.warn("Unknown resource type in include/revinclude=" + nextInclude.getValue());
						continue;
					}

					String paramName = nextInclude.getParamName();
					if (isNotBlank(paramName)) {
						param = mySearchParamRegistry.getSearchParamByName(def, paramName);
					} else {
						param = null;
					}
					if (param == null) {
						ourLog.warn("Unknown param name in include/revinclude=" + nextInclude.getValue());
						continue;
					}

					paths = param.getPathsSplit();

					String targetResourceType = defaultString(nextInclude.getParamTargetType(), null);
					for (String nextPath : paths) {
						String sql;

						boolean haveTargetTypesDefinedByParam = param.hasTargets();
						if (targetResourceType != null) {
							sql = "SELECT r." + findFieldName + " FROM ResourceLink r WHERE r.mySourcePath = :src_path AND r." + searchFieldName + " IN (:target_pids) AND r.myTargetResourceType = :target_resource_type";
						} else if (haveTargetTypesDefinedByParam) {
							sql = "SELECT r." + findFieldName + " FROM ResourceLink r WHERE r.mySourcePath = :src_path AND r." + searchFieldName + " IN (:target_pids) AND r.myTargetResourceType in (:target_resource_types)";
						} else {
							sql = "SELECT r." + findFieldName + " FROM ResourceLink r WHERE r.mySourcePath = :src_path AND r." + searchFieldName + " IN (:target_pids)";
						}

						List<Collection<ResourcePersistentId>> partitions = partition(nextRoundMatches, getMaximumPageSize());
						for (Collection<ResourcePersistentId> nextPartition : partitions) {
							TypedQuery<Long> q = theEntityManager.createQuery(sql, Long.class);
							q.setParameter("src_path", nextPath);
							q.setParameter("target_pids", ResourcePersistentId.toLongList(nextPartition));
							if (targetResourceType != null) {
								q.setParameter("target_resource_type", targetResourceType);
							} else if (haveTargetTypesDefinedByParam) {
								q.setParameter("target_resource_types", param.getTargets());
							}
							List<Long> results = q.getResultList();
							for (Long resourceLink : results) {
								if (resourceLink != null) {
									pidsToInclude.add(new ResourcePersistentId(resourceLink));
								}
							}
						}
					}
				}
			}

			if (theReverseMode) {
				if (theLastUpdated != null && (theLastUpdated.getLowerBoundAsInstant() != null || theLastUpdated.getUpperBoundAsInstant() != null)) {
					pidsToInclude = new HashSet<>(filterResourceIdsByLastUpdated(theEntityManager, theLastUpdated, pidsToInclude));
				}
			}

			nextRoundMatches.clear();
			for (ResourcePersistentId next : pidsToInclude) {
				if (original.contains(next) == false && allAdded.contains(next) == false) {
					theMatches.add(next);
					nextRoundMatches.add(next);
				}
			}

			addedSomeThisRound = allAdded.addAll(pidsToInclude);
		} while (includes.size() > 0 && nextRoundMatches.size() > 0 && addedSomeThisRound);

		allAdded.removeAll(original);

		ourLog.info("Loaded {} {} in {} rounds and {} ms for search {}", allAdded.size(), theReverseMode ? "_revincludes" : "_includes", roundCounts, w.getMillisAndRestart(), theSearchIdOrDescription);

		// Interceptor call: STORAGE_PREACCESS_RESOURCES
		// This can be used to remove results from the search result details before
		// the user has a chance to know that they were in the results
		if (allAdded.size() > 0) {
			List<ResourcePersistentId> includedPidList = new ArrayList<>(allAdded);
			JpaPreResourceAccessDetails accessDetails = new JpaPreResourceAccessDetails(includedPidList, () -> this);
			HookParams params = new HookParams()
				.add(IPreResourceAccessDetails.class, accessDetails)
				.add(RequestDetails.class, theRequest)
				.addIfMatchesType(ServletRequestDetails.class, theRequest);
			JpaInterceptorBroadcaster.doCallHooks(myInterceptorBroadcaster, theRequest, Pointcut.STORAGE_PREACCESS_RESOURCES, params);

			for (int i = includedPidList.size() - 1; i >= 0; i--) {
				if (accessDetails.isDontReturnResourceAtIndex(i)) {
					ResourcePersistentId value = includedPidList.remove(i);
					if (value != null) {
						theMatches.remove(value);
					}
				}
			}

			allAdded = new HashSet<>(includedPidList);
		}

		return allAdded;
	}

	private List<Collection<ResourcePersistentId>> partition(Collection<ResourcePersistentId> theNextRoundMatches, int theMaxLoad) {
		if (theNextRoundMatches.size() <= theMaxLoad) {
			return Collections.singletonList(theNextRoundMatches);
		} else {

			List<Collection<ResourcePersistentId>> retVal = new ArrayList<>();
			Collection<ResourcePersistentId> current = null;
			for (ResourcePersistentId next : theNextRoundMatches) {
				if (current == null) {
					current = new ArrayList<>(theMaxLoad);
					retVal.add(current);
				}

				current.add(next);

				if (current.size() >= theMaxLoad) {
					current = null;
				}
			}

			return retVal;
		}
	}

	private void attemptCompositeUniqueSpProcessing(QueryStack theQueryStack3, @Nonnull SearchParameterMap theParams, RequestDetails theRequest) {
		// Since we're going to remove elements below
		theParams.values().forEach(nextAndList -> ensureSubListsAreWritable(nextAndList));

		List<JpaRuntimeSearchParam> activeUniqueSearchParams = mySearchParamRegistry.getActiveUniqueSearchParams(myResourceName, theParams.keySet());
		if (activeUniqueSearchParams.size() > 0) {

			StringBuilder sb = new StringBuilder();
			sb.append(myResourceName);
			sb.append("?");

			boolean first = true;

			ArrayList<String> keys = new ArrayList<>(theParams.keySet());
			Collections.sort(keys);
			for (String nextParamName : keys) {
				List<List<IQueryParameterType>> nextValues = theParams.get(nextParamName);

				nextParamName = UrlUtil.escapeUrlParam(nextParamName);
				if (nextValues.get(0).size() != 1) {
					sb = null;
					break;
				}

				// Reference params are only eligible for using a composite index if they
				// are qualified
				RuntimeSearchParam nextParamDef = mySearchParamRegistry.getActiveSearchParam(myResourceName, nextParamName);
				if (nextParamDef.getParamType() == RestSearchParameterTypeEnum.REFERENCE) {
					ReferenceParam param = (ReferenceParam) nextValues.get(0).get(0);
					if (isBlank(param.getResourceType())) {
						sb = null;
						break;
					}
				}

				List<? extends IQueryParameterType> nextAnd = nextValues.remove(0);
				IQueryParameterType nextOr = nextAnd.remove(0);
				String nextOrValue = nextOr.getValueAsQueryToken(myContext);
				nextOrValue = UrlUtil.escapeUrlParam(nextOrValue);

				if (first) {
					first = false;
				} else {
					sb.append('&');
				}

				sb.append(nextParamName).append('=').append(nextOrValue);

			}

			if (sb != null) {
				String indexString = sb.toString();
				ourLog.debug("Checking for unique index for query: {}", indexString);

				// Interceptor broadcast: JPA_PERFTRACE_INFO
				StorageProcessingMessage msg = new StorageProcessingMessage()
					.setMessage("Using unique index for query for search: " + indexString);
				HookParams params = new HookParams()
					.add(RequestDetails.class, theRequest)
					.addIfMatchesType(ServletRequestDetails.class, theRequest)
					.add(StorageProcessingMessage.class, msg);
				JpaInterceptorBroadcaster.doCallHooks(myInterceptorBroadcaster, theRequest, Pointcut.JPA_PERFTRACE_INFO, params);

				theQueryStack3.addPredicateCompositeUnique(indexString, myRequestPartitionId);

				// Remove any empty parameters remaining after this
				theParams.clean();
			}
		}
	}

	private <T> void ensureSubListsAreWritable(List<List<T>> theListOfLists) {
		for (int i = 0; i < theListOfLists.size(); i++) {
			List<T> oldSubList = theListOfLists.get(i);
			if (!(oldSubList instanceof ArrayList)) {
				List<T> newSubList = new ArrayList<>(oldSubList);
				theListOfLists.set(i, newSubList);
			}
		}
	}

	@Override
	public void setFetchSize(int theFetchSize) {
		myFetchSize = theFetchSize;
	}

	public SearchParameterMap getParams() {
		return myParams;
	}

	public CriteriaBuilder getBuilder() {
		return myCriteriaBuilder;
	}

	public Class<? extends IBaseResource> getResourceType() {
		return myResourceType;
	}

	public String getResourceName() {
		return myResourceName;
	}

	@VisibleForTesting
	public void setDaoConfigForUnitTest(DaoConfig theDaoConfig) {
		myDaoConfig = theDaoConfig;
	}

	public class IncludesIterator extends BaseIterator<ResourcePersistentId> implements Iterator<ResourcePersistentId> {

		private final RequestDetails myRequest;
		private final Set<ResourcePersistentId> myCurrentPids;
		private Iterator<ResourcePersistentId> myCurrentIterator;
		private ResourcePersistentId myNext;

		IncludesIterator(Set<ResourcePersistentId> thePidSet, RequestDetails theRequest) {
			myCurrentPids = new HashSet<>(thePidSet);
			myCurrentIterator = null;
			myRequest = theRequest;
		}

		private void fetchNext() {
			while (myNext == null) {

				if (myCurrentIterator == null) {
					Set<Include> includes = Collections.singleton(new Include("*", true));
					Set<ResourcePersistentId> newPids = loadIncludes(myContext, myEntityManager, myCurrentPids, includes, false, getParams().getLastUpdated(), mySearchUuid, myRequest);
					myCurrentIterator = newPids.iterator();
				}

				if (myCurrentIterator.hasNext()) {
					myNext = myCurrentIterator.next();
				} else {
					myNext = NO_MORE;
				}

			}
		}

		@Override
		public boolean hasNext() {
			fetchNext();
			return !NO_MORE.equals(myNext);
		}

		@Override
		public ResourcePersistentId next() {
			fetchNext();
			ResourcePersistentId retVal = myNext;
			myNext = null;
			return retVal;
		}

	}

	private final class QueryIterator extends BaseIterator<ResourcePersistentId> implements IResultIterator {

		private final SearchRuntimeDetails mySearchRuntimeDetails;
		private final RequestDetails myRequest;
		private final boolean myHaveRawSqlHooks;
		private final boolean myHavePerfTraceFoundIdHook;
		private final SortSpec mySort;
		private boolean myFirst = true;
		private IncludesIterator myIncludesIterator;
		private ResourcePersistentId myNext;
		private Iterator<ResourcePersistentId> myPreResultsIterator;
		private SearchQueryExecutor myResultsIterator;
		private boolean myStillNeedToFetchIncludes;
		private int mySkipCount = 0;
		private int myNonSkipCount = 0;

		private ArrayList<SearchQueryExecutor> myQueryList = new ArrayList<>();

		private QueryIterator(SearchRuntimeDetails theSearchRuntimeDetails, RequestDetails theRequest) {
			mySearchRuntimeDetails = theSearchRuntimeDetails;
			mySort = myParams.getSort();
			myRequest = theRequest;

			// Includes are processed inline for $everything query
			if (myParams.getEverythingMode() != null) {
				myStillNeedToFetchIncludes = true;
			}

			myHavePerfTraceFoundIdHook = JpaInterceptorBroadcaster.hasHooks(Pointcut.JPA_PERFTRACE_SEARCH_FOUND_ID, myInterceptorBroadcaster, myRequest);
			myHaveRawSqlHooks = JpaInterceptorBroadcaster.hasHooks(Pointcut.JPA_PERFTRACE_RAW_SQL, myInterceptorBroadcaster, myRequest);

		}

		private void fetchNext() {

			try {
				if (myHaveRawSqlHooks) {
					CurrentThreadCaptureQueriesListener.startCapturing();
				}

				// If we don't have a query yet, create one
				if (myResultsIterator == null) {
					if (myMaxResultsToFetch == null) {
						myMaxResultsToFetch = myDaoConfig.getFetchSizeDefaultMaximum();
					}

					initializeIteratorQuery(myMaxResultsToFetch);

					// If the query resulted in extra results being requested
					if (myAlsoIncludePids != null) {
						myPreResultsIterator = myAlsoIncludePids.iterator();
					}
				}

				if (myNext == null) {

					if (myPreResultsIterator != null && myPreResultsIterator.hasNext()) {
						while (myPreResultsIterator.hasNext()) {
							ResourcePersistentId next = myPreResultsIterator.next();
							if (next != null)
								if (myPidSet.add(next)) {
									myNext = next;
									break;
								}
						}
					}

					if (myNext == null) {
						while (myResultsIterator.hasNext() || !myQueryList.isEmpty()) {
							// Update iterator with next chunk if necessary.
							if (!myResultsIterator.hasNext()) {
								retrieveNextIteratorQuery();
							}

							Long nextLong = myResultsIterator.next();
							if (myHavePerfTraceFoundIdHook) {
								HookParams params = new HookParams()
									.add(Integer.class, System.identityHashCode(this))
									.add(Object.class, nextLong);
								JpaInterceptorBroadcaster.doCallHooks(myInterceptorBroadcaster, myRequest, Pointcut.JPA_PERFTRACE_SEARCH_FOUND_ID, params);
							}

							if (nextLong != null) {
								ResourcePersistentId next = new ResourcePersistentId(nextLong);
								if (myPidSet.add(next)) {
									myNext = next;
									myNonSkipCount++;
									break;
								} else {
									mySkipCount++;
								}
							}

							if (!myResultsIterator.hasNext()) {
								if (myMaxResultsToFetch != null && (mySkipCount + myNonSkipCount == myMaxResultsToFetch)) {
									if (mySkipCount > 0 && myNonSkipCount == 0) {
										myMaxResultsToFetch += 1000;

										StorageProcessingMessage message = new StorageProcessingMessage();
										String msg = "Pass completed with no matching results. This indicates an inefficient query! Retrying with new max count of " + myMaxResultsToFetch;
										ourLog.warn(msg);
										message.setMessage(msg);
										HookParams params = new HookParams()
											.add(RequestDetails.class, myRequest)
											.addIfMatchesType(ServletRequestDetails.class, myRequest)
											.add(StorageProcessingMessage.class, message);
										JpaInterceptorBroadcaster.doCallHooks(myInterceptorBroadcaster, myRequest, Pointcut.JPA_PERFTRACE_WARNING, params);

										initializeIteratorQuery(myMaxResultsToFetch);
									}
								}
							}
						}
					}

					if (myNext == null) {
						if (myStillNeedToFetchIncludes) {
							myIncludesIterator = new IncludesIterator(myPidSet, myRequest);
							myStillNeedToFetchIncludes = false;
						}
						if (myIncludesIterator != null) {
							while (myIncludesIterator.hasNext()) {
								ResourcePersistentId next = myIncludesIterator.next();
								if (next != null)
									if (myPidSet.add(next)) {
										myNext = next;
										break;
									}
							}
							if (myNext == null) {
								myNext = NO_MORE;
							}
						} else {
							myNext = NO_MORE;
						}
					}

				} // if we need to fetch the next result

				mySearchRuntimeDetails.setFoundMatchesCount(myPidSet.size());

			} finally {
				if (myHaveRawSqlHooks) {
					SqlQueryList capturedQueries = CurrentThreadCaptureQueriesListener.getCurrentQueueAndStopCapturing();
					HookParams params = new HookParams()
						.add(RequestDetails.class, myRequest)
						.addIfMatchesType(ServletRequestDetails.class, myRequest)
						.add(SqlQueryList.class, capturedQueries);
					JpaInterceptorBroadcaster.doCallHooks(myInterceptorBroadcaster, myRequest, Pointcut.JPA_PERFTRACE_RAW_SQL, params);
				}
			}

			if (myFirst) {
				HookParams params = new HookParams()
					.add(RequestDetails.class, myRequest)
					.addIfMatchesType(ServletRequestDetails.class, myRequest)
					.add(SearchRuntimeDetails.class, mySearchRuntimeDetails);
				JpaInterceptorBroadcaster.doCallHooks(myInterceptorBroadcaster, myRequest, Pointcut.JPA_PERFTRACE_SEARCH_FIRST_RESULT_LOADED, params);
				myFirst = false;
			}

			if (NO_MORE.equals(myNext)) {
				HookParams params = new HookParams()
					.add(RequestDetails.class, myRequest)
					.addIfMatchesType(ServletRequestDetails.class, myRequest)
					.add(SearchRuntimeDetails.class, mySearchRuntimeDetails);
				JpaInterceptorBroadcaster.doCallHooks(myInterceptorBroadcaster, myRequest, Pointcut.JPA_PERFTRACE_SEARCH_SELECT_COMPLETE, params);
			}

		}

		private void initializeIteratorQuery(Integer theMaxResultsToFetch) {
			if (myQueryList.isEmpty()) {
				// Capture times for Lucene/Elasticsearch queries as well
				mySearchRuntimeDetails.setQueryStopwatch(new StopWatch());
				myQueryList = createQuery(myParams, mySort, theMaxResultsToFetch, false, myRequest, mySearchRuntimeDetails);
			}

			mySearchRuntimeDetails.setQueryStopwatch(new StopWatch());

			retrieveNextIteratorQuery();

			mySkipCount = 0;
			myNonSkipCount = 0;
		}

		private void retrieveNextIteratorQuery() {
			close();
			if (myQueryList != null && myQueryList.size() > 0) {
				myResultsIterator = myQueryList.remove(0);
			} else {
				myResultsIterator = SearchQueryExecutor.EMPTY;
			}

		}

		@Override
		public boolean hasNext() {
			if (myNext == null) {
				fetchNext();
			}
			return !NO_MORE.equals(myNext);
		}

		@Override
		public ResourcePersistentId next() {
			fetchNext();
			ResourcePersistentId retVal = myNext;
			myNext = null;
			Validate.isTrue(!NO_MORE.equals(retVal), "No more elements");
			return retVal;
		}

		@Override
		public int getSkippedCount() {
			return mySkipCount;
		}

		@Override
		public int getNonSkippedCount() {
			return myNonSkipCount;
		}

		@Override
		public Collection<ResourcePersistentId> getNextResultBatch(long theBatchSize) {
			Collection<ResourcePersistentId> batch = new ArrayList<>();
			while (this.hasNext() && batch.size() < theBatchSize) {
				batch.add(this.next());
			}
			return batch;
		}

		@Override
		public void close() {
			if (myResultsIterator != null) {
				myResultsIterator.close();
			}
			myResultsIterator = null;
		}

	}

	public static int getMaximumPageSize() {
		if (myUseMaxPageSize50ForTest) {
			return MAXIMUM_PAGE_SIZE_FOR_TESTING;
		} else {
			return MAXIMUM_PAGE_SIZE;
		}
	}

	public static void setMaxPageSize50ForTest(boolean theIsTest) {
		myUseMaxPageSize50ForTest = theIsTest;
	}

	private static List<Predicate> createLastUpdatedPredicates(final DateRangeParam theLastUpdated, CriteriaBuilder builder, From<?, ResourceTable> from) {
		List<Predicate> lastUpdatedPredicates = new ArrayList<>();
		if (theLastUpdated != null) {
			if (theLastUpdated.getLowerBoundAsInstant() != null) {
				ourLog.debug("LastUpdated lower bound: {}", new InstantDt(theLastUpdated.getLowerBoundAsInstant()));
				Predicate predicateLower = builder.greaterThanOrEqualTo(from.get("myUpdated"), theLastUpdated.getLowerBoundAsInstant());
				lastUpdatedPredicates.add(predicateLower);
			}
			if (theLastUpdated.getUpperBoundAsInstant() != null) {
				Predicate predicateUpper = builder.lessThanOrEqualTo(from.get("myUpdated"), theLastUpdated.getUpperBoundAsInstant());
				lastUpdatedPredicates.add(predicateUpper);
			}
		}
		return lastUpdatedPredicates;
	}

	private static List<ResourcePersistentId> filterResourceIdsByLastUpdated(EntityManager theEntityManager, final DateRangeParam theLastUpdated, Collection<ResourcePersistentId> thePids) {
		if (thePids.isEmpty()) {
			return Collections.emptyList();
		}
		CriteriaBuilder builder = theEntityManager.getCriteriaBuilder();
		CriteriaQuery<Long> cq = builder.createQuery(Long.class);
		Root<ResourceTable> from = cq.from(ResourceTable.class);
		cq.select(from.get("myId").as(Long.class));

		List<Predicate> lastUpdatedPredicates = createLastUpdatedPredicates(theLastUpdated, builder, from);
		lastUpdatedPredicates.add(from.get("myId").as(Long.class).in(ResourcePersistentId.toLongList(thePids)));

		cq.where(SearchBuilder.toPredicateArray(lastUpdatedPredicates));
		TypedQuery<Long> query = theEntityManager.createQuery(cq);

		return ResourcePersistentId.fromLongList(query.getResultList());
	}

	public static Predicate[] toPredicateArray(List<Predicate> thePredicates) {
		return thePredicates.toArray(new Predicate[0]);
	}

}
