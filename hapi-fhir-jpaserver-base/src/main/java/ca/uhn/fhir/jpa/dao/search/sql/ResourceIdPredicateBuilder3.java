package ca.uhn.fhir.jpa.dao.search.sql;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.dao.index.IdHelperService;
import ca.uhn.fhir.jpa.dao.predicate.SearchFilterParser;
import ca.uhn.fhir.model.api.IQueryParameterType;
import ca.uhn.fhir.rest.api.server.storage.ResourcePersistentId;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.healthmarketscience.sqlbuilder.Condition;
import org.hl7.fhir.r4.model.IdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class ResourceIdPredicateBuilder3 extends BasePredicateBuilder3 {
	private static final Logger ourLog = LoggerFactory.getLogger(ResourceIdPredicateBuilder3.class);

	@Autowired
	private IdHelperService myIdHelperService;

	/**
	 * Constructor
	 */
	public ResourceIdPredicateBuilder3(SearchSqlBuilder theSearchSqlBuilder) {
		super(theSearchSqlBuilder);
	}


	@Nullable
	public Condition createPredicateResourceId(String theResourceName, List<List<IQueryParameterType>> theValues, SearchFilterParser.CompareOperation theOperation, RequestPartitionId theRequestPartitionId) {

		Set<ResourcePersistentId> allOrPids = null;

		for (List<? extends IQueryParameterType> nextValue : theValues) {
			Set<ResourcePersistentId> orPids = new HashSet<>();
			boolean haveValue = false;
			for (IQueryParameterType next : nextValue) {
				String value = next.getValueAsQueryToken(getFhirContext());
				if (value != null && value.startsWith("|")) {
					value = value.substring(1);
				}

				IdType valueAsId = new IdType(value);
				if (isNotBlank(value)) {
					haveValue = true;
					try {
						ResourcePersistentId pid = myIdHelperService.resolveResourcePersistentIds(theRequestPartitionId, theResourceName, valueAsId.getIdPart());
						orPids.add(pid);
					} catch (ResourceNotFoundException e) {
						// This is not an error in a search, it just results in no matches
						ourLog.debug("Resource ID {} was requested but does not exist", valueAsId.getIdPart());
					}
				}
			}
			if (haveValue) {
				if (allOrPids == null) {
					allOrPids = orPids;
				} else {
					allOrPids.retainAll(orPids);
				}

			}
		}

		if (allOrPids != null && allOrPids.isEmpty()) {

			setMatchNothing();

		} else if (allOrPids != null) {

			SearchFilterParser.CompareOperation operation = defaultIfNull(theOperation, SearchFilterParser.CompareOperation.eq);
			assert operation == SearchFilterParser.CompareOperation.eq || operation == SearchFilterParser.CompareOperation.ne;

			BasePredicateBuilder queryRootTable = super.getOrCreateQueryRootTable();

			switch (operation) {
				default:
				case eq:
					return queryRootTable.createPredicateResourceIds(false, ResourcePersistentId.toLongList(allOrPids));
				case ne:
					return queryRootTable.createPredicateResourceIds(true, ResourcePersistentId.toLongList(allOrPids));
			}

		}

		return null;
	}


}
