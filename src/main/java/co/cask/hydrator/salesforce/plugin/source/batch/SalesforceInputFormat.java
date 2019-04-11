/*
 * Copyright © 2019 Cask Data, Inc.
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
package co.cask.hydrator.salesforce.plugin.source.batch;

import co.cask.hydrator.salesforce.SalesforceBulkUtil;
import co.cask.hydrator.salesforce.SalesforceConnectionUtil;
import co.cask.hydrator.salesforce.SalesforceQueryUtil;
import co.cask.hydrator.salesforce.authenticator.Authenticator;
import co.cask.hydrator.salesforce.authenticator.AuthenticatorCredentials;
import co.cask.hydrator.salesforce.plugin.source.batch.util.SalesforceSourceConstants;
import com.sforce.async.AsyncApiException;
import com.sforce.async.BatchInfo;
import com.sforce.async.BulkConnection;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Salesforce implementation of InputFormat for mapreduce
 */
public class SalesforceInputFormat extends InputFormat {
  private static final Logger LOG = LoggerFactory.getLogger(SalesforceInputFormat.class);

  @Override
  public List<InputSplit> getSplits(JobContext jobContext) {
    Configuration conf = jobContext.getConfiguration();

    try {
      AuthenticatorCredentials credentials = SalesforceConnectionUtil.getAuthenticatorCredentials(conf);
      BulkConnection bulkConnection = new BulkConnection(Authenticator.createConnectorConfig(credentials));
      String query = conf.get(SalesforceSourceConstants.CONFIG_QUERY);
      if (!SalesforceQueryUtil.isQueryUnderLengthLimit(query)) {
        LOG.debug("Wide object query detected. Query length '{}'", query.length());
        query = SalesforceQueryUtil.createSObjectIdQuery(query);
      }
      BatchInfo[] batches = SalesforceBulkUtil.runBulkQuery(bulkConnection, query);
      LOG.debug("Number of batches received from Salesforce: '{}'", batches.length);

      return Arrays.stream(batches)
        .map(batch -> new SalesforceSplit(batch.getJobId(), batch.getId()))
        .collect(Collectors.toList());
    } catch (AsyncApiException | IOException e) {
      throw new RuntimeException("There was issue communicating with Salesforce", e);
    }
  }

  @Override
  public RecordReader<NullWritable, Map<String, String>> createRecordReader(
    InputSplit inputSplit, TaskAttemptContext taskAttemptContext) {
    Configuration conf = taskAttemptContext.getConfiguration();
    String query = conf.get(SalesforceSourceConstants.CONFIG_QUERY);
    return SalesforceQueryUtil.isQueryUnderLengthLimit(query)
      ? new SalesforceRecordReader()
      : new SalesforceWideRecordReader();
  }
}
