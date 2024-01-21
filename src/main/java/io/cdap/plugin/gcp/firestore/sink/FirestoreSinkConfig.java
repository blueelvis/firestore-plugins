/*
 * Copyright © 2020 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.plugin.gcp.firestore.sink;

import com.google.cloud.firestore.Firestore;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Macro;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.api.plugin.PluginConfig;
import io.cdap.cdap.etl.api.FailureCollector;
import io.cdap.plugin.gcp.firestore.common.FirestoreConfig;
import io.cdap.plugin.gcp.firestore.sink.util.FirestoreSinkConstants;
import io.cdap.plugin.gcp.firestore.sink.util.SinkIdType;
import io.cdap.plugin.gcp.firestore.util.FirestoreConstants;
import io.cdap.plugin.gcp.firestore.util.FirestoreUtil;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Defines a base {@link PluginConfig} that Firestore Source and Sink can re-use.
 */
public class FirestoreSinkConfig extends FirestoreConfig {
  @Name(FirestoreConstants.PROPERTY_COLLECTION)
  @Description("Name of the database collection. If the collection name does not exist in Firestore " +
    "then it will create a new collection and then the data will be written to it.")
  @Macro
  private String collection;

  @Name(FirestoreSinkConstants.PROPERTY_ID_TYPE)
  @Macro
  @Description("Type of id assigned to documents written to the Firestore. The type can be one of two values: "
    + "`Auto-generated id` - id will be auto-generated as a Alpha-numeric ID, `Custom name` - id "
    + "will be provided as a field in the input records. The id field must not be nullable and must be "
    + "of type STRING.")
  private String idType;

  @Name(FirestoreSinkConstants.PROPERTY_ID_ALIAS)
  @Macro
  @Nullable
  @Description("The field that will be used as the document id when writing to Cloud Firestore. This must be provided "
    + "when the Id Type is not auto generated.")
  private String idAlias;

  @Name(FirestoreSinkConstants.PROPERTY_BATCH_SIZE)
  @Macro
  @Description("Maximum number of documents that can be passed in one batch to a Commit operation. "
    + "The minimum value is 1 and maximum value is 500")
  private int batchSize;

  public FirestoreSinkConfig() {
    // needed for initialization
  }

  /**
   * Constructor for FirestoreSinkConfig object.
   * @param referenceName the reference name
   * @param project the project id
   * @param serviceFilePath the service file path
   * @param databaseName the name of the database
   * @param collection the collection
   * @param idType the id type
   * @param idAlias the id alias
   * @param batchSize the batch size
   */
  @VisibleForTesting
  public FirestoreSinkConfig(String referenceName, String project, String serviceFilePath, String databaseName,
                             String collection, String idType, String idAlias, int batchSize) {
    this.referenceName = referenceName;
    this.project = project;
    this.serviceFilePath = serviceFilePath;
    this.databaseName = databaseName;
    this.collection = collection;
    this.idType = idType;
    this.idAlias = idAlias;
    this.batchSize = batchSize;
  }

  public String getReferenceName() {
    return referenceName;
  }

  public String getCollection() {
    return collection;
  }

  /**
   * Returns the id type chosen.
   *
   * @param collector The failure collector to collect the errors
   * @return An instance of SinkIdType
   */
  public SinkIdType getIdType(FailureCollector collector) {
    SinkIdType sinkIdType = getIdType();
    if (sinkIdType != null) {
      return sinkIdType;
    }

    collector.addFailure("Unsupported id type value: " + idType,
                         String.format("Supported types are: %s", SinkIdType.getSupportedTypes()))
      .withConfigProperty(FirestoreSinkConstants.PROPERTY_ID_TYPE);
    collector.getOrThrowException();
    return null;
  }

  /**
   * Returns the id type chosen.
   *
   * @return An instance of SinkIdType
   */
  public SinkIdType getIdType() {
    Optional<SinkIdType> sinkIdType = SinkIdType.fromValue(idType);

    return sinkIdType.isPresent() ? sinkIdType.get() : null;
  }

  @Nullable
  public String getIdAlias() {
    return Strings.isNullOrEmpty(idAlias) ? FirestoreConstants.ID_PROPERTY_NAME : idAlias;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public boolean shouldUseAutoGeneratedId() {
    return getIdType() == SinkIdType.AUTO_GENERATED_ID;
  }

  /**
   * Validates {@link FirestoreSinkConfig} instance.
   */
  public void validate(@Nullable Schema schema, FailureCollector collector) {
    super.validate(collector);

    validateBatchSize(collector);
    validateFirestoreConnection(collector);
    validateDatabaseName(collector);

    if (schema != null) {
      validateSchema(schema, collector);
      validateIdType(schema, collector);
    }
  }

  @VisibleForTesting
  void validateFirestoreConnection(FailureCollector collector) {
    if (!shouldConnect()) {
      return;
    }
    try {
      Firestore db = FirestoreUtil.getFirestore(getServiceAccount(), isServiceAccountFilePath(),
       getProject(), getDatabaseName());
      db.close();
    } catch (Exception e) {
      collector.addFailure(e.getMessage(), "Ensure properties like project, service account " +
        "file path, database name are correct.")
        .withConfigProperty(NAME_SERVICE_ACCOUNT_FILE_PATH)
        .withConfigProperty(NAME_PROJECT)
        .withConfigProperty(NAME_DATABASE)
        .withStacktrace(e.getStackTrace());
    }
  }

  private void validateSchema(Schema schema, FailureCollector collector) {
    List<Schema.Field> fields = schema.getFields();
    if (fields == null || fields.isEmpty()) {
      collector.addFailure("Sink schema must contain at least one field", null);
    } else {
      fields.forEach(f -> validateSinkFieldSchema(f.getName(), f.getSchema(), collector));
    }
  }

  /**
   * Validates given field schema to be compliant with Firestore types.
   * Will throw {@link IllegalArgumentException} if schema contains unsupported type.
   *
   * @param fieldName field name
   * @param fieldSchema schema for CDAP field
   * @param collector failure collector
   */
  private void validateSinkFieldSchema(String fieldName, Schema fieldSchema, FailureCollector collector) {
    Schema.LogicalType logicalType = fieldSchema.getLogicalType();
    if (logicalType != null) {
      switch (logicalType) {
        // timestamps in CDAP are represented as LONG with TIMESTAMP_MICROS logical type
        case TIMESTAMP_MICROS:
        case TIMESTAMP_MILLIS:
          break;
        default:
          collector.addFailure(String.format("Field '%s' is of unsupported type '%s'",
            fieldName, fieldSchema.getDisplayName()),
            "Supported types are: string, double, boolean, bytes, int, float, long, " +
              "record, array, union and timestamp.").withInputSchemaField(fieldName);
      }
      return;
    }

    switch (fieldSchema.getType()) {
      case STRING:
      case DOUBLE:
      case BOOLEAN:
      case BYTES:
      case INT:
      case FLOAT:
      case LONG:
      case NULL:
        return;
      case UNION:
        fieldSchema.getUnionSchemas().forEach(unionSchema ->
          validateSinkFieldSchema(fieldName, unionSchema, collector));
        return;
      default:
        collector.addFailure(String.format("Field '%s' is of unsupported type '%s'",
          fieldName, fieldSchema.getDisplayName()),
          "Supported types are: string, double, boolean and long")
          .withInputSchemaField(fieldName);
    }
  }

  /**
   * If id type is not auto-generated, validates if id alias column is present in the schema
   * and its type is {@link Schema.Type#STRING}.
   *
   * @param schema CDAP schema
   * @param collector failure collector
   */
  private void validateIdType(Schema schema, FailureCollector collector) {
    if (containsMacro(FirestoreSinkConstants.PROPERTY_ID_TYPE) || shouldUseAutoGeneratedId()) {
      return;
    }

    Schema.Field field = schema.getField(idAlias);
    if (field == null) {
      collector.addFailure(String.format("Id field '%s' does not exist in the schema", idAlias),
        "Change the Id field to be one of the schema fields.")
        .withConfigProperty(FirestoreSinkConstants.PROPERTY_ID_ALIAS);
      return;
    }

    Schema fieldSchema = field.getSchema();
    Schema.Type type = fieldSchema.getType();
    if (Schema.Type.STRING != type) {
      fieldSchema = fieldSchema.isNullable() ? fieldSchema.getNonNullable() : fieldSchema;
      collector.addFailure(
        String.format("Id field '%s' is of unsupported type '%s'", idAlias, fieldSchema.getDisplayName()),
        "Ensure the type is non-nullable string")
        .withConfigProperty(FirestoreSinkConstants.PROPERTY_ID_ALIAS).withInputSchemaField(idAlias);
    }
  }

  /**
   * Validates the given database name to consists of characters allowed to represent a dataset.
   */
  public void validateDatabaseName(FailureCollector collector) {
    if (containsMacro(FirestoreConfig.NAME_DATABASE)) {
      return;
    }

    String databaseName = getDatabaseName();

    // Check if the database name is empty or null.
    if (Strings.isNullOrEmpty(databaseName)) {
      collector.addFailure("Database Name must be specified.", null)
          .withConfigProperty(FirestoreConfig.NAME_DATABASE);
    }

    // Check if database name contains the (default)
    if (databaseName != "(default)") {

      // Ensure database name includes only letters, numbers, and hyphen (-)
      // characters.
      if (!databaseName.matches("^[a-zA-Z0-9-]+$")) {
        collector.addFailure("Database name can only include letters, numbers and hyphen characters.", null)
            .withConfigProperty(FirestoreConfig.NAME_DATABASE);
      }

      // Ensure database name is in lower case.
      if (databaseName != databaseName.toLowerCase()) {
        collector.addFailure("Database name must be in lowercase.", null)
            .withConfigProperty(FirestoreConfig.NAME_DATABASE);
      }

      // The first character must be a letter.
      if (!databaseName.matches("^[a-zA-Z][a-zA-Z0-9-]*$")) {
        collector.addFailure("Database name's first character can only be an alphabet.", null)
            .withConfigProperty(FirestoreConfig.NAME_DATABASE);
      }

      // The last character must be a letter or number.
      if (!databaseName.matches("^[a-zA-Z][a-zA-Z0-9-]*$")) {
        collector.addFailure("Database name's first character can only be an alphabet.", null)
            .withConfigProperty(FirestoreConfig.NAME_DATABASE);
      }

      // Minimum of 4 characters.
      if (databaseName.length() < 4) {
        collector.addFailure("Database name should be at least 4 letters.", null)
            .withConfigProperty(FirestoreConfig.NAME_DATABASE);
      }

      // Maximum of 63 characters.
      if (databaseName.length() > 63) {
        collector.addFailure("Database name cannot be more than 63 characters.", null)
            .withConfigProperty(FirestoreConfig.NAME_DATABASE);
      }

      // Should not be a UUID.
      try {
        UUID.fromString(databaseName);
        collector.addFailure("Database name cannot contain a UUID.", null)
            .withConfigProperty(FirestoreConfig.NAME_DATABASE);
      } catch (IllegalArgumentException e) { }
    }
  }

  private void validateBatchSize(FailureCollector collector) {
    if (containsMacro(FirestoreSinkConstants.PROPERTY_BATCH_SIZE)) {
      return;
    }
    if (batchSize < 1 || batchSize > FirestoreSinkConstants.MAX_BATCH_SIZE) {
      collector.addFailure(String.format("Invalid Firestore batch size '%d'.", batchSize),
        String.format("Ensure the batch size is at least 1 or at most '%d'", FirestoreSinkConstants.MAX_BATCH_SIZE))
        .withConfigProperty(FirestoreSinkConstants.PROPERTY_BATCH_SIZE);
    }
  }

  /**
   * Returns true if Firestore can be connected to or schema is not a macro.
   */
  private boolean shouldConnect() {
    return !containsMacro(NAME_SERVICE_ACCOUNT_FILE_PATH) &&
      !containsMacro(NAME_PROJECT) &&
      tryGetProject() != null &&
      !autoServiceAccountUnavailable();
  }
}
