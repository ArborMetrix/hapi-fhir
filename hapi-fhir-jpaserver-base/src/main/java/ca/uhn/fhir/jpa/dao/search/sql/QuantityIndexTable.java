package ca.uhn.fhir.jpa.dao.search.sql;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.dao.predicate.SearchFilterParser;
import ca.uhn.fhir.jpa.model.entity.BaseResourceIndexedSearchParam;
import ca.uhn.fhir.jpa.model.entity.ResourceIndexedSearchParamQuantity;
import ca.uhn.fhir.model.api.IQueryParameterType;
import ca.uhn.fhir.model.base.composite.BaseQuantityDt;
import ca.uhn.fhir.rest.param.ParamPrefixEnum;
import ca.uhn.fhir.rest.param.QuantityParam;
import com.healthmarketscience.sqlbuilder.BinaryCondition;
import com.healthmarketscience.sqlbuilder.ComboCondition;
import com.healthmarketscience.sqlbuilder.Condition;
import com.healthmarketscience.sqlbuilder.dbspec.basic.DbColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.criteria.CriteriaBuilder;
import java.math.BigDecimal;

import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;
import static org.apache.commons.lang3.StringUtils.isBlank;

public class QuantityIndexTable extends BaseSearchParamIndexTable {

	private static final Logger ourLog = LoggerFactory.getLogger(QuantityIndexTable.class);
	private final DbColumn myColumnHashIdentitySystemUnits;
	private final DbColumn myColumnHashIdentityUnits;
	private final DbColumn myColumnValue;

	/**
	 * Constructor
	 */
	public QuantityIndexTable(SearchSqlBuilder theSearchSqlBuilder) {
		super(theSearchSqlBuilder, theSearchSqlBuilder.addTable("HFJ_SPIDX_QUANTITY"));

		myColumnHashIdentitySystemUnits = getTable().addColumn("HASH_IDENTITY_SYS_UNITS");
		myColumnHashIdentityUnits = getTable().addColumn("HASH_IDENTITY_AND_UNITS");
		myColumnValue = getTable().addColumn("SP_VALUE");
	}



	public Condition createPredicateQuantity(IQueryParameterType theParam, String theResourceName, String theParamName, CriteriaBuilder theBuilder, QuantityIndexTable theFrom, SearchFilterParser.CompareOperation theOperation, RequestPartitionId theRequestPartitionId) {

		String systemValue;
		String unitsValue;
		ParamPrefixEnum cmpValue = null;
		BigDecimal valueValue;

		if (theParam instanceof BaseQuantityDt) {
			BaseQuantityDt param = (BaseQuantityDt) theParam;
			systemValue = param.getSystemElement().getValueAsString();
			unitsValue = param.getUnitsElement().getValueAsString();
			if (theOperation == null) {
				cmpValue = ParamPrefixEnum.forValue(param.getComparatorElement().getValueAsString());
			}
			valueValue = param.getValueElement().getValue();
		} else if (theParam instanceof QuantityParam) {
			QuantityParam param = (QuantityParam) theParam;
			systemValue = param.getSystem();
			unitsValue = param.getUnits();
			if (theOperation == null) {
				cmpValue = param.getPrefix();
			}
			valueValue = param.getValue();
		} else {
			throw new IllegalArgumentException("Invalid quantity type: " + theParam.getClass());
		}

		Condition hashPredicate;
		if (!isBlank(systemValue) && !isBlank(unitsValue)) {
			long hash = ResourceIndexedSearchParamQuantity.calculateHashSystemAndUnits(getPartitionSettings(), theRequestPartitionId, theResourceName, theParamName, systemValue, unitsValue);
			hashPredicate = BinaryCondition.equalTo(myColumnHashIdentitySystemUnits, generatePlaceholder(hash));
		} else if (!isBlank(unitsValue)) {
			long hash = ResourceIndexedSearchParamQuantity.calculateHashUnits(getPartitionSettings(), theRequestPartitionId, theResourceName, theParamName, unitsValue);
			hashPredicate = BinaryCondition.equalTo(myColumnHashIdentityUnits, generatePlaceholder(hash));
		} else {
			long hash = BaseResourceIndexedSearchParam.calculateHashIdentity(getPartitionSettings(), theRequestPartitionId, theResourceName, theParamName);
			hashPredicate = BinaryCondition.equalTo(getColumnHashIdentity(), generatePlaceholder(hash));
		}

		SearchFilterParser.CompareOperation operation = defaultIfNull(theOperation, SearchFilterParser.CompareOperation.eq);
		Condition numericPredicate = NumberIndexTable.createPredicateNumeric(this, theResourceName, theParamName, operation, valueValue, theRequestPartitionId, myColumnValue);

		return ComboCondition.and(hashPredicate, numericPredicate);
	}


}
