spark-structured-secure-dt-kafka-app
============

PLEASE NOTE THE FEATURE IS MERGED BUT NOT YET RELEASED!

### Introduction
This small app shows how to access data from a secure (Kerberized) Kafka cluster from Spark Structured Streaming using delegation token and [the direct connector](http://spark.apache.org/docs/latest/structured-streaming-kafka-integration.html) which uses the [new Kafka Consumer API](https://kafka.apache.org/documentation/#consumerconfigs). In order to use this app, you need to use Cloudera Distribution of Apache Kafka version TODO or later. And, you need to use Cloudera Distribution of Apache Spark TODO release TODO or later.

Currently this example focuses on accessing Kafka securely via Kerberos. It assumes SSL (i.e. encryption over the wire) is configured for Kafka. It assumes that Kafka authorization (via Sentry, for example) is not being used. That can be setup separately.

### Build the app
To build, you need Scala 2.12, git and maven on the box.
Do a git clone of this repo and then run:
```
cd spark-structured-secure-dt-kafka-app
mvn clean package
```
Then, take the generated uber jar from `target/spark-structured-secure-dt-kafka-app-1.0-SNAPSHOT-jar-with-dependencies.jar` to the spark client node (where you are going to launch the query from). Let's assume you place this file in the home directory of this client machine.

### Running the app
#### Creating configuration
Before you run this app, make sure to have access to the keytab needed for secure Kafka connection.

We assume the client user's keytab is called `kafka_client.keytab` and is placed in the home directory on the client box.

### spark-submit
Now run the following command:
```
# set num-executors, num-cores, etc. according to your needs.
# If simply testing, ok to leave the defaults as below
# Change references to kafka_client.keytab to the actual name of the keytab.
# If not using SSL, change the port 9093 below to the 9092.
SPARK_KAFKA_VERSION=0.10 spark2-submit \
  --num-executors 2 \
  --master yarn \
  --deploy-mode cluster \
  --keytab /full/path/to/kafka_client.keytab \
  --principal "user@MY.DOMAIN.COM" \
  --class com.cloudera.spark.examples.StructuredKafkaWordCount \
  spark-structured-secure-dt-kafka-app-1.0-SNAPSHOT-jar-with-dependencies.jar \
  cluster1 \
  <kafka broker>:9093 \
  SASL_SSL \
  <topic> \
```

### Generating some test data
While you run this app, you may want to generate some data in the topic Spark Streaming is reading from, and may want to view the word counts as the data is being generated. To generate data in the Kafka topic, you can use the `kafka-console-producer` using the following command:
```
# Create a Kafka topic
kafka-topics --create --zookeeper <zk-node>:2181 --topic <topic> --partitions 4 --replication-factor 3

cd ~
# Generate the producer.properties file which will be used by the console producer
# to select the appropriate security protocol and mechanism.
# If not using SSL, do not set `ssl.truststore.location` and `ssl.truststore.password` 
# If not using SSL, change security.protocol's value to be SASL_PLAINTEXT (instead of SASL_SSL).
echo "security.protocol=SASL_SSL" >> producer.properties
echo "sasl.kerberos.service.name=kafka" >> producer.properties
echo "ssl.truststore.location=/etc/cdep-ssl-conf/CA_STANDARD/truststore.jks" >> producer.properties
echo "ssl.truststore.password=cloudera" >> producer.properties
```
Populate the following contents in a different JAAS conf, say `producer_jaas.conf`:
```
# Change the /full/path/to/kafka_client.keytab below
# to the full path to the keytab.
# Change the principal name accordingly
cat << 'EOF' > producer_jaas.conf
KafkaClient {
    com.sun.security.auth.module.Krb5LoginModule required
    useKeyTab=true
    storeKey=true
    keyTab="/full/path/to/kafka_client.keytab"
    useTicketCache=false
    serviceName="kafka"
    principal="user@MY.DOMAIN.COM";
}; 
EOF
```

```
# Run the console producer
# If not using SSL, change the port below to 9092
export KAFKA_OPTS="-Djava.security.auth.login.config=producer_jaas.conf"
kafka-console-producer --broker-list <bootstrap-server>:9093 --producer.config producer.properties --topic <topic>

# Now, type in some words on the console, and close the producer.
```

### What's happening under the hood?
For consuming data via SASL/Kerberos, the application master obtains a delegation token from the kafka broker which will be passed to executors.

Before the token expires (by default 0.75 * expiration date) application master recreates it and passes it to executors.

### What you should see
If all goes well, you should see counts of various words in every batch interval, in your spark streaming driver's stdout. To get driver's stdout (when using yarn cluster mode), please get the yarn logs using `yarn logs -applicationId <app ID>`. The `<app ID>` can be obtained through the console output on the client machine where `spark-submit` was launched from. In the retrieved logs, you would see something like:
```
-------------------------------------------
Batch: 1
-------------------------------------------
+-----+-----+
|value|count|
+-----+-----+
|word1|    1|
|word2|    2|
+-----+-----+
```
