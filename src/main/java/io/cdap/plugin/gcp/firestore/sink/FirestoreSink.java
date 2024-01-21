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

import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.annotation.Plugin;
import io.cdap.cdap.api.data.batch.Output;
import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.api.dataset.lib.KeyValue;
import io.cdap.cdap.etl.api.Emitter;
import io.cdap.cdap.etl.api.FailureCollector;
import io.cdap.cdap.etl.api.PipelineConfigurer;
import io.cdap.cdap.etl.api.StageConfigurer;
import io.cdap.cdap.etl.api.batch.BatchRuntimeContext;
import io.cdap.cdap.etl.api.batch.BatchSink;
import io.cdap.cdap.etl.api.batch.BatchSinkContext;
import io.cdap.plugin.common.LineageRecorder;
import io.cdap.plugin.gcp.firestore.util.FirestoreConstants;
import org.apache.hadoop.io.NullWritable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * A {@link BatchSink} that writes data to Cloud Firestore.
 * This {@link FirestoreSink} takes a {@link StructuredRecord} in, converts it to document, and writes it to the
 * Cloud Firestore collection.
 */
@Plugin(type = BatchSink.PLUGIN_TYPE)
@Name(FirestoreConstants.PLUGIN_NAME)
@Description("The sink writes data to a Google Cloud Firestore Collection.")
public class FirestoreSink extends BatchSink<StructuredRecord, NullWritable, Map<String, Object>> {
  private static final Logger LOG = LoggerFactory.getLogger(FirestoreSink.class);
  private final FirestoreSinkConfig config;
  private RecordToEntityTransformer recordToEntityTransformer;

  public FirestoreSink(FirestoreSinkConfig config) {
    this.config = config;
  }

  @Override
  public void configurePipeline(PipelineConfigurer pipelineConfigurer) {
    super.configurePipeline(pipelineConfigurer);
    StageConfigurer configurer = pipelineConfigurer.getStageConfigurer();
    Schema inputSchema = configurer.getInputSchema();
    FailureCollector collector = configurer.getFailureCollector();
    config.validate(inputSchema, collector);
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public void prepareRun(BatchSinkContext batchSinkContext) throws Exception {
    Schema inputSchema = batchSinkContext.getInputSchema();
    LOG.debug("FirestoreBatchSink `prepareRun` input schema: {}", inputSchema);
    FailureCollector collector = batchSinkContext.getFailureCollector();
    config.validate(inputSchema, collector);
    collector.getOrThrowException();

    String project = config.getProject();
    String databaseName = config.getDatabaseName();
    String serviceAccountFilePath = config.getServiceAccountFilePath();
    String serviceAccountJson = config.getServiceAccountJson();
    String serviceAccountType = config.getServiceAccountType();
    String collection = config.getCollection();
    String shouldAutoGenerateId = Boolean.toString(config.shouldUseAutoGeneratedId());
    String batchSize = Integer.toString(config.getBatchSize());

    batchSinkContext.addOutput(Output.of(config.getReferenceName(),
      new FirestoreOutputFormatProvider(project, databaseName, serviceAccountFilePath, serviceAccountJson,
       serviceAccountType, collection, shouldAutoGenerateId, batchSize)));

    LineageRecorder lineageRecorder = new LineageRecorder(batchSinkContext, config.getReferenceName());
    lineageRecorder.createExternalDataset(inputSchema);
    // Record the field level WriteOperation
    lineageRecorder.recordWrite("Write", "Wrote to Cloud Firestore sink",
      inputSchema.getFields().stream()
        .map(Schema.Field::getName)
        .collect(Collectors.toList()));
  }

  @Override
  public void initialize(BatchRuntimeContext context) throws Exception {
    super.initialize(context);
    FailureCollector collector = context.getFailureCollector();
    this.recordToEntityTransformer = new RecordToEntityTransformer(config.getIdType(collector), config.getIdAlias());
  }

  @Override
  public void transform(StructuredRecord record, Emitter<KeyValue<NullWritable, Map<String, Object>>> emitter) {
    Map<String, Object> fullEntity = recordToEntityTransformer.transformStructuredRecord(record);
    emitter.emit(new KeyValue<>(NullWritable.get(), fullEntity));
  }
}
