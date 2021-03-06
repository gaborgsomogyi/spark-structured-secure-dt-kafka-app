/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloudera.spark.examples

import java.util.UUID

import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.common.config.{SaslConfigs, SslConfigs}

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.streaming.OutputMode

object StructuredKafkaWordCount {
  val TRUSTSTORE_LOCATION = "/etc/cdep-ssl-conf/CA_STANDARD/truststore.jks"
  val TRUSTSTORE_PASSWORD = "cloudera"

  def main(args: Array[String]) {
    if (args.length < 5) {
      System.err.println(s"""
                            |Usage: StructuredKafkaWordCount <cluster-id> <bootstrap-servers> <protocol> <subscribe-type> <topics> [<checkpoint-location>]
                            |  <bootstrap-servers> The Kafka "bootstrap.servers" configuration.
                            |  A comma-separated list of host:port.
                            |  <protocol> Protocol used to communicate with brokers.
                            |  Valid values are: 'PLAINTEXT', 'SSL', 'SASL_PLAINTEXT', 'SASL_SSL'.
                            |  <subscribe-type> There are three kinds of type, i.e.
                            |  'assign', 'subscribe', 'subscribePattern'.
                            |    - <assign> Specific TopicPartitions to consume. Json string
                            |      {"topicA":[0,1],"topicB":[2,4]}.
                            |    - <subscribe> The topic list to subscribe. A comma-separated list
                            |      of topics.
                            |    - <subscribePattern> The pattern used to subscribe to topic(s).
                            |      Java regex string.
                            |    - Only one of "assign, "subscribe" or "subscribePattern" options
                            |      can be specified for Kafka source.
                            |  <topics> Different value format depends on the value of 'subscribe-type'.
                            |  <checkpoint-location> Directory in which to create checkpoints.
                            |  If not provided, defaults to a randomized directory in /tmp.
                            |
      """.stripMargin)
      System.exit(1)
    }

    val Array(clusterId, bootstrapServers, protocol, subscribeType, topics) = args
    val checkpointLocation =
      if (args.length > 5) args(5) else "/tmp/temporary-" + UUID.randomUUID.toString

    val isUsingSsl = protocol.endsWith("SSL")

    val commonParams = Map[String, String](
      CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG -> bootstrapServers,
      subscribeType -> topics,
      CommonClientConfigs.SECURITY_PROTOCOL_CONFIG -> protocol,
      SaslConfigs.SASL_KERBEROS_SERVICE_NAME -> "kafka",
      "startingoffsets" -> "earliest"
    )

    val additionalSslParams = if (isUsingSsl) {
      Map(
        SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG -> TRUSTSTORE_LOCATION,
        SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG -> TRUSTSTORE_PASSWORD
      )
    } else {
      Map.empty
    }

    val kafkaParams = commonParams ++ additionalSslParams

    var builder = SparkSession
      .builder
      .config(s"spark.kafka.clusters.$clusterId.auth.${CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG}", bootstrapServers)
      .appName("StructuredKafkaWordCount")
    if (isUsingSsl) {
      builder = builder
        .config(s"spark.kafka.clusters.$clusterId.${SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG}", TRUSTSTORE_LOCATION)
        .config(s"spark.kafka.clusters.$clusterId.${SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG}", TRUSTSTORE_PASSWORD)
    }
    val spark = builder.getOrCreate()

    import spark.implicits._

    // Create DataSet representing the stream of input lines from kafka
    val lines = spark
      .readStream
      .format("kafka")
      .options(kafkaParams)
      .load()
      .selectExpr("CAST(value AS STRING)")
      .as[String]

    // Generate running word count
    val wordCounts = lines.flatMap(_.split(" ")).groupBy("value").count().coalesce(1)

    // Start running the query that prints the running counts to the console
    val query = wordCounts.writeStream
      .outputMode(OutputMode.Complete)
      .format("console")
      .option("checkpointLocation", checkpointLocation)
      .start()

    query.awaitTermination()
  }
}
