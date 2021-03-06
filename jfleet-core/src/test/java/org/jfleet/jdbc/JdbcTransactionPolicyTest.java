/**
 * Copyright 2017 Jerónimo López Bezanilla
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
package org.jfleet.jdbc;

import static org.jfleet.util.TransactionPolicyTestHelper.employeesWithConstraintError;
import static org.jfleet.util.TransactionPolicyTestHelper.employeesWithOutErrors;
import static org.jfleet.util.TransactionPolicyTestHelper.numberOfRowsInEmployeeTable;
import static org.jfleet.util.TransactionPolicyTestHelper.setupDatabase;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

import org.jfleet.BulkInsert;
import org.jfleet.JFleetException;
import org.jfleet.entities.Employee;
import org.jfleet.jdbc.JdbcBulkInsert.Configuration;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JdbcTransactionPolicyTest extends JdbcDatabasesBaseTest {

    private static Logger logger = LoggerFactory.getLogger(JdbcTransactionPolicyTest.class);
    private static final long TWO_ROW_BATCH_SIZE = 2;

    @Test
    public void longTransactionExecuteMultipleLoadDataOperationsTransactionaly()
            throws IOException, SQLException, JFleetException {
        try (Connection connection = database.getConnection()) {
            setupDatabase(connection);
            connection.setAutoCommit(false);

            Configuration<Employee> config = new Configuration<>(Employee.class)
                    .batchSize(TWO_ROW_BATCH_SIZE)
                    .autocommit(false);
            BulkInsert<Employee> bulkInsert = database.getBulkInsert(config);

            bulkInsert.insertAll(connection, employeesWithOutErrors());

            // We don't know how many load data operations were executed, but with
            // low batch size, multiple load data are executed with few records.
            assertEquals(7, numberOfRowsInEmployeeTable(connection));
            connection.rollback();
            assertEquals(0, numberOfRowsInEmployeeTable(connection));
        }
    }

    @Test
    public void longTransactionWithConstraintExceptionIsRollbacked() throws IOException, SQLException, JFleetException {
        try (Connection connection = database.getConnection()) {
            setupDatabase(connection);
            connection.setAutoCommit(false);

            Configuration<Employee> config = new Configuration<>(Employee.class)
                    .batchSize(TWO_ROW_BATCH_SIZE)
                    .autocommit(false);
            BulkInsert<Employee> bulkInsert = database.getBulkInsert(config);

            try {
                bulkInsert.insertAll(connection, employeesWithConstraintError());
                connection.commit();
            } catch (SQLException e) {
                logger.info("Expected error on missed FK");
                connection.rollback();
                assertEquals(0, numberOfRowsInEmployeeTable(connection));
                return;
            }
            assertTrue("Expected SQLException exception", false);
        }
    }

    @Test
    public void multipleBatchOperationsExecuteMultipleLoadDataOperationsWithHisOwnTransaction()
            throws IOException, SQLException, JFleetException {
        try (Connection connection = database.getConnection()) {
            setupDatabase(connection);

            Configuration<Employee> config = new Configuration<>(Employee.class)
                    .batchSize(TWO_ROW_BATCH_SIZE)
                    .autocommit(true);
            BulkInsert<Employee> bulkInsert = database.getBulkInsert(config);

            try {
                bulkInsert.insertAll(connection, employeesWithConstraintError());
            } catch (SQLException e) {
                logger.info("Expected error on missed FK");
                assertTrue(numberOfRowsInEmployeeTable(connection) > 0);
                return;
            }
            assertTrue("Expected SQLException exception", false);
        }
    }

}
