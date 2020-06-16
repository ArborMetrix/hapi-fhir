package ca.uhn.fhir.jpa.migrate.taskdef;

import ca.uhn.fhir.jpa.migrate.JdbcUtils;
import org.junit.Test;

import java.sql.SQLException;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertThat;

public class DropColumnTest extends BaseTest {

	public DropColumnTest(Supplier<TestDatabaseDetails> theTestDatabaseDetails) {
		super(theTestDatabaseDetails);
	}

	@Test
	public void testDropColumn() throws SQLException {
		executeSql("create table SOMETABLE (PID bigint not null, TEXTCOL varchar(255))");

		DropColumnTask task = new DropColumnTask("1",  "1");
		task.setTableName("SOMETABLE");
		task.setColumnName("TEXTCOL");
		getMigrator().addTask(task);

		getMigrator().migrate();

		assertThat(JdbcUtils.getColumnNames(getConnectionProperties(), "SOMETABLE"), containsInAnyOrder("PID"));

		// Do it again to make sure there is no error
		getMigrator().migrate();
		getMigrator().migrate();

	}

	@Test
	public void testDropForeignKeyColumn() throws SQLException {
		executeSql("create table PARENT (PID bigint not null, TEXTCOL varchar(255), primary key (PID))");
		executeSql("create table CHILD (PID bigint not null, PARENTREF bigint)");
		executeSql("alter table CHILD add constraint FK_MOM foreign key (PARENTREF) references PARENT(PID)");

		assertThat(JdbcUtils.getForeignKeys(getConnectionProperties(), "PARENT", "CHILD"), hasSize(1));

		assertThat(JdbcUtils.getForeignKeysForColumn(getConnectionProperties(), "PARENTREF", "CHILD"), containsInAnyOrder("FK_MOM"));

		DropColumnTask task = new DropColumnTask("1",  "1");
		task.setTableName("CHILD");
		task.setColumnName("PARENTREF");
		getMigrator().addTask(task);

		getMigrator().migrate();

		assertThat(JdbcUtils.getColumnNames(getConnectionProperties(), "CHILD"), containsInAnyOrder("PID"));

		assertThat(JdbcUtils.getForeignKeys(getConnectionProperties(), "PARENT", "CHILD"), empty());

		// Do it again to make sure there is no error
		getMigrator().migrate();
		getMigrator().migrate();

	}


	/*
			executeSql("create table PARENT (PID bigint not null, TEXTCOL varchar(255), primary key (PID))");
		executeSql("create table CHILD (PID bigint not null, PARENTREF bigint)");
		executeSql("alter table CHILD add constraint FK_MOM foreign key (PARENTREF) references PARENT(PID)");

		assertThat(JdbcUtils.getForeignKeys(getConnectionProperties(), "PARENT", "CHILD"), hasSize(1));

		assertThat(JdbcUtils.getForeignKeysForColumn(getConnectionProperties(), "PARENTREF", "CHILD"), containsInAnyOrder("FK_MOM"));

		DropForeignKeyTask task = new DropForeignKeyTask("1", "1");
		task.setTableName("CHILD");
		task.setParentTableName("PARENT");
		task.setConstraintName("FK_MOM");
		getMigrator().addTask(task);

		getMigrator().migrate();

		assertThat(JdbcUtils.getForeignKeys(getConnectionProperties(), "PARENT", "CHILD"), empty());

		// Make sure additional calls don't crash
		getMigrator().migrate();
		getMigrator().migrate();

	 */
}
