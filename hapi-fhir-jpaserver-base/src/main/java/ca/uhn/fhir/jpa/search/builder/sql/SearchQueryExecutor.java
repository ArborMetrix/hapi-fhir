package ca.uhn.fhir.jpa.search.builder.sql;

import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.util.IoUtil;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.io.Closeable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;

public class SearchQueryExecutor implements Iterator<Long>, Closeable {

	private static final Long NO_MORE = -1L;
	private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
	public static final SearchQueryExecutor EMPTY = new SearchQueryExecutor();
	private static final Logger ourLog = LoggerFactory.getLogger(SearchQueryExecutor.class);
	private final GeneratedSql myGeneratedSql;
	private final Integer myMaxResultsToFetch;
	@Autowired
	private DataSource myDataSource;
	private boolean myQueryInitialized;
	private Connection myConnection;
	private PreparedStatement myStatement;
	private ResultSet myResultSet;
	private Long myNext;

	/**
	 * Constructor
	 */
	public SearchQueryExecutor(GeneratedSql theGeneratedSql, Integer theMaxResultsToFetch) {
		Validate.notNull(theGeneratedSql, "theGeneratedSql must not be null");
		myGeneratedSql = theGeneratedSql;
		myQueryInitialized = false;
		myMaxResultsToFetch = theMaxResultsToFetch;
	}

	/**
	 * Internal constructor for empty executor
	 */
	private SearchQueryExecutor() {
		assert NO_MORE != null;

		myGeneratedSql = null;
		myMaxResultsToFetch = null;
		myNext = NO_MORE;
	}

	@Override
	public void close() {
		IoUtil.closeQuietly(myResultSet);
		IoUtil.closeQuietly(myStatement);
		IoUtil.closeQuietly(myConnection);
		myResultSet = null;
		myStatement = null;
		myConnection = null;
	}

	@Override
	public boolean hasNext() {
		fetchNext();
		return !NO_MORE.equals(myNext);
	}

	@Override
	public Long next() {
		fetchNext();
		Validate.isTrue(hasNext(), "Can not call next() right now, no data remains");
		Long next = myNext;
		myNext = null;
		return next;
	}

	private void fetchNext() {
		if (myNext == null) {
			String sql = myGeneratedSql.getSql();
			Object[] args = myGeneratedSql.getBindVariables().toArray(EMPTY_OBJECT_ARRAY);

			try {
				if (!myQueryInitialized) {
					myConnection = myDataSource.getConnection();
					myStatement = myConnection.prepareStatement(sql);

					if (myMaxResultsToFetch != null) {
						myStatement.setMaxRows(myMaxResultsToFetch);
					}

					for (int i = 0; i < args.length; i++) {
						myStatement.setObject(i + 1, args[i]);
					}
					myResultSet = myStatement.executeQuery();
					myQueryInitialized = true;
				}

				if (myResultSet == null || !myResultSet.next()) {
					myNext = NO_MORE;
				} else {
					myNext = myResultSet.getLong(1);
				}


			} catch (SQLException e) {
				ourLog.error("Failed to create or execute SQL query", e);
				close();
				throw new InternalErrorException(e);
			}
		}
	}
}

