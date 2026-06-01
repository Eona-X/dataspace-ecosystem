/********************************************************************************
 * Copyright (c) 2024 Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ********************************************************************************/

package org.eclipse.edc.dse.agreements.retirement.store.sql;

import org.eclipse.edc.dse.agreements.retirement.spi.store.AgreementsRetirementStore;
import org.eclipse.edc.dse.agreements.retirement.store.AgreementsRetirementStoreTestBase;
import org.eclipse.edc.json.JacksonTypeManager;
import org.eclipse.edc.junit.annotations.PostgresqlIntegrationTest;
import org.eclipse.edc.spi.types.TypeManager;
import org.eclipse.edc.sql.QueryExecutor;
import org.eclipse.edc.sql.testfixtures.PostgresqlStoreSetupExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@PostgresqlIntegrationTest
@ExtendWith(PostgresqlStoreSetupExtension.class)
class SqlAgreementsRetirementStoreTest extends AgreementsRetirementStoreTestBase {

    private final TypeManager typeManager = new JacksonTypeManager();
    private final SqlAgreementsRetirementStatements statements = new PostgresAgreementRetirementStatements();
    private SqlAgreementsRetirementStore store;

    @BeforeEach
    void setUp(PostgresqlStoreSetupExtension extension, QueryExecutor queryExecutor) throws IOException {
        store = new SqlAgreementsRetirementStore(extension.getDataSourceRegistry(), extension.getDatasourceName(),
                extension.getTransactionContext(), typeManager.getMapper(), queryExecutor, statements);

        try (var stream = getClass().getResourceAsStream("/schema.sql")) {
            if (stream == null) {
                throw new FileNotFoundException("schema.sql not found in the classpath");
            }
            var schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            extension.runQuery(schema);
        }
    }

    @AfterEach
    void tearDown(PostgresqlStoreSetupExtension extension) {
        extension.runQuery("DROP TABLE " + statements.getTable() + " CASCADE");
    }

    @Override
    protected AgreementsRetirementStore getStore() {
        return store;
    }
}