//
//  Author: Hari Sekhon
//  Date: 2016-06-06 22:43:57 +0100 (Mon, 06 Jun 2016)
//
//  vim:ts=4:sts=4:sw=4:et
//
//  https://github.com/harisekhon/nagios-plugin-kafka
//
//  License: see accompanying Hari Sekhon LICENSE file
//
//  If you're using my code you're welcome to connect with me on LinkedIn and optionally send me feedback to help steer this or other code I publish
//
//  https://www.linkedin.com/in/harisekhon
//

package com.linkedin.harisekhon.kafka

import com.linkedin.harisekhon.CLI
import com.linkedin.harisekhon.Utils._
import java.io.{File, InputStream, PipedInputStream, PipedOutputStream}
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.{Arrays, Properties}

import org.apache.kafka.common.{KafkaException, TopicPartition}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.clients.consumer.{ConsumerRecord, ConsumerRecords, KafkaConsumer}

import scala.util.control.NonFatal

//import org.apache.log4j.Logger

import scala.collection.JavaConversions._

object CheckKafka extends App {
    new CheckKafka().main2(args)
}

class CheckKafka extends CLI {
    // using utils logger for uniformly increasing logging level for all logging via --verbose
//    val log = Logger.getLogger("CheckKafka")
    // TODO: replace scalaz.ValidationNel / cats.Validated and combine with |@|
    var brokers: String = ""
    var topic: String = ""
    var partition: Int = 0
    var lastOffset: Long = 0
    var jaasConfig: Option[String] = None
    val consumerProps = new Properties
    val producerProps = new Properties
    if (consumerProps eq producerProps) {
        throw new IllegalArgumentException("Consumer + Producer props should not be the same object")
    }
    val producerProperties: Option[InputStream] = Option(getClass.getResourceAsStream("/producer.properties"))
    if (producerProperties.isEmpty) {
        log.error("could not find producer.properties file")
        System.exit(2)
    }
    val consumerProperties: Option[InputStream] = Option(getClass.getResourceAsStream("/consumer.properties"))
    if (consumerProperties.isEmpty) {
        log.error("could not find consumer.properties file")
        System.exit(2)
    }
    val uuid = java.util.UUID.randomUUID.toString
    val epoch = System.currentTimeMillis()
    val date = new SimpleDateFormat("yyyy-dd-MM HH:MM:ss.SSS Z").format(epoch)
    val id: String = s"Hari Sekhon check_kafka (scala) - random token=$uuid, $date"

    val msg = s"test message generated by $id"
    log.info(s"test message => '$msg'")

    override def addOptions(): Unit = {
        options.addOption("B", "brokers", true, "Kafka broker list in the format host1:port1,host2:port2 ...")
        options.addOption("T", "topic", true, "Kafka topic to test")
        // TODO: consider round robin partitions for each run
        options.addOption("P", "partition", true, "Kafka partition to test (default: 0)")
        options.addOption("l", "list-topics", true, "List Kafka topics and exit")
        options.addOption("p", "list-partitions", true, "List Kafka partitions for the given topic and exit (requires --topic)")
    }

    override def processArgs(): Unit = {
        if (cmd.hasOption("brokers")) {
            brokers = cmd.getOptionValue("brokers", "")
        }
        println(s"brokers are $brokers")
        validateNodePortList(brokers, "kafka")
        if (cmd.hasOption("topic")) {
            topic = cmd.getOptionValue("topic", "")
        }
        if(topic.isEmpty){
            usage("topic not defined")
        }
        val partitionStr = cmd.getOptionValue("partition", "0")
        // if you have more than 10000 partitions please contact me to explain and get this limit increased!
        validateInt(partition, "partition", 0, 10000)
        partition = Integer.parseInt(partitionStr)
        loadProps()
        setupJaas()
    }

    def loadProps(): Unit = {
        consumerProps.put("bootstrap.servers", brokers)
        producerProps.put("bootstrap.servers", brokers)

        val consumerPropsArgs = consumerProps.clone().asInstanceOf[Properties]
        consumerProps.load(consumerProperties.get) // throw Exception as I tested this is not None already
        if (log.isDebugEnabled) {
            log.debug("Loaded Consumer Properties from resource file:")
            consumerProps.foreach({ case (k, v) => log.debug(s"  $k = $v") })
            log.debug("Loading Consumer Property args:")
            consumerPropsArgs.foreach({ case (k, v) => log.debug(s"  $k = $v") })
        }
        val consumerIn = new PipedInputStream
        val consumerOut = new PipedOutputStream(consumerIn)
        new Thread(
            new Runnable() {
                def run(): Unit = {
                    consumerPropsArgs.store(consumerOut, "")
                    consumerOut.close()
                }
            }
        ).start()
        consumerProps.load(consumerIn)
        // enforce unique group to make sure we are guaranteed to received our unique message back
        val groupId: String = s"$uuid, $date"
        log.info(s"group id='$groupId'")
        consumerProps.put("group.id", groupId)

        val producerPropsArgs = producerProps.clone().asInstanceOf[Properties]
        producerProps.load(producerProperties.get) // throw Exception as I tested this is not None already
        if (log.isDebugEnabled) {
            log.debug("Loaded Producer Properties from resource file:")
            producerProps.foreach({ case (k, v) => log.debug(s"  $k = $v") })
            log.debug("Loading Producer Property args:")
            producerPropsArgs.foreach({ case (k, v) => log.debug(s"  $k = $v") })
        }
        val producerIn = new PipedInputStream()
        val producerOut = new PipedOutputStream(producerIn)
        new Thread(
            new Runnable() {
                def run(): Unit = {
                    producerPropsArgs.store(producerOut, "")
                    producerOut.close()
                }
            }
        ).start()
        producerProps.load(producerIn)
    }

    def setupJaas(): Unit = {
        log.debug("setting up JAAS for Kerberos security")
        val defaultJaasFile = "kafka_cli_jaas.conf"
        val hdpJaasPath = "/usr/hdp/current/kafka-broker/config/kafka_client_jaas.conf"

//        val srcpath = new File(classOf[CheckKafka].getProtectionDomain.getCodeSource.getLocation.toURI.getPath)
        val srcpath = new File(getClass.getProtectionDomain.getCodeSource.getLocation.toURI.getPath)
        val jar = if (srcpath.toString.contains("/target/")) {
            srcpath.getParentFile.getParentFile
        } else {
            srcpath
        }
        val jaasDefaultConfig = Paths.get(jar.getParentFile.getAbsolutePath, "conf", defaultJaasFile).toString
        val jaasProp = Option(System.getProperty("java.security.auth.login.config"))
        if (jaasConfig.nonEmpty && jaasConfig.getOrElse("").nonEmpty) {
            log.info(s"using JAAS config file arg '$jaasConfig'")
        } else if (jaasProp.nonEmpty) {
            val jaasFilePath = jaasProp.getOrElse("")
            val jaasFile = new File(jaasFilePath)
            if (jaasFile.exists() && jaasFile.isFile) {
                jaasConfig = Option(jaasFilePath)
                log.info(s"using JAAS config file from System property java.security.auth.login.config = '$jaasConfig'")
            } else {
                log.warn(s"JAAS path specified in System property java.security.auth.login.config = '$jaasProp' does not exist!")
            }
        }
        if (jaasConfig.isEmpty || jaasConfig.get.toString.isEmpty) {
            val hdpJaasFile = new File(hdpJaasPath)
            if (hdpJaasFile.exists() && hdpJaasFile.isFile()) {
                log.info(s"found HDP Kafka kerberos config '$hdpJaasPath'")
                jaasConfig = Option(hdpJaasPath)
            }
        }
        if (jaasConfig.isEmpty || jaasConfig.get.toString.isEmpty) {
            val jaasDefaultFile = new File(jaasDefaultConfig)
            if (jaasDefaultFile.exists() && jaasDefaultFile.isFile()) {
                log.info(s"using default JaaS config file '$jaasDefaultConfig'")
                jaasConfig = Option(jaasDefaultConfig)
            } else {
                log.warn("cannot find default JAAS file and none supplied")
            }
        }
        if (jaasConfig.nonEmpty && jaasConfig.get.toString.nonEmpty) {
            System.setProperty("java.security.auth.login.config", jaasConfig.get)
        } else {
            log.warn("no JAAS config defined")
        }
    }

    override def run(): Unit = {
        try {
            // without port suffix raises the following exception, which we intend to catch and print nicely
            // Exception in thread "main" org.apache.kafka.common.KafkaException: Failed to construct kafka consumer
            // ...
            // org.apache.kafka.common.config.ConfigException: Invalid url in bootstrap.servers: 192.168.99.100
            runTest()
        } catch {
            case e: KafkaException => {
                println("Caught Kafka Exception: ")
                e.printStackTrace()
                System.exit(2)
            }
            case NonFatal(e) => {
                println("Caught unexpected Exception: ")
                e.printStackTrace()
                System.exit(2)
            }
        }
    }

    def runTest(): Unit = {
        log.debug("runTest()")
        val startTime = System.currentTimeMillis()
        // Cannot use 0.8 consumers as only new 0.9 API supports Kerberos
        log.info("creating Kafka consumer")
        val consumer = new KafkaConsumer[String, String](consumerProps)
        log.info("creating Kafka producer")
        val producer = new KafkaProducer[String, String](producerProps)
        subscribe(consumer=consumer, topic=topic, partition=partition)
        val startWrite = System.currentTimeMillis()
        produce(producer=producer, topic=topic, partition=partition, msg=msg)
        val writeTime = (System.currentTimeMillis() - startWrite) / 1000.0
        val readStartTime = System.currentTimeMillis()
        consume(consumer=consumer, topic=topic, partition=partition)
        val endTime = System.currentTimeMillis()
        val readTime = (endTime - readStartTime) / 1000.0
        val totalTime = (endTime - startTime) / 1000.0
        val plural =
            if (consumerProps.get("bootstrap.servers").isInstanceOf[String] &&
                consumerProps.get("bootstrap.servers").asInstanceOf[String].split("\\s+,\\s+").length > 1)
            {
                "s"
            } else {
                ""
            }
        val output = s"OK: Kafka broker$plural successfully returned unique message" +
                     s", write time = ${writeTime}s, read time = ${readTime}s, total time = ${totalTime}s " +
                     s"| write_time=${writeTime}s read_time=${readTime}s total_time=${totalTime}s"
//        log.info(output)
        println(output)
    }

    def subscribe(consumer: KafkaConsumer[String, String], topic: String = topic, partition: Int = partition): Unit = {
        log.debug(s"subscribe(consumer, $topic, $partition)")
        val topicPartition = new TopicPartition(topic, partition)
        // conflicts with partition assignment
        // log.debug(s"subscribing to topic $topic")
        // consumer.subscribe(Arrays.asList(topic))
        log.info(s"consumer assigning topic '$topic' partition '$partition'")
        consumer.assign(Arrays.asList(topicPartition))
        // consumer.assign(Arrays.asList(partition))
        // not connected to port so no conn refused at this point
        lastOffset = consumer.position(topicPartition)
    }

    def produce(producer: KafkaProducer[String, String], topic: String = topic, partition: Int = partition, msg: String = msg): Unit = {
        log.debug(s"produce(producer, $topic, $partition, $msg")
        log.info(s"sending message to topic $topic partition $partition")
        producer.send(new ProducerRecord[String, String](topic, partition, id, msg)) // key and partition optional
        log.info("producer.flush()")
        producer.flush()
        log.info("producer.close()")
        producer.close() // blocks until msgs are sent
    }

    def consume(consumer: KafkaConsumer[String, String], topic: String = topic, partition: Int = partition): Unit = {
        log.debug(s"consumer(consumer, $topic, $partition")
        val topicPpartition = new TopicPartition(topic, partition)
        log.info(s"seeking to last known offset $lastOffset")
        consumer.seek(topicPpartition, lastOffset)
        log.info(s"consuming from offset $lastOffset")
        val records: ConsumerRecords[String, String] = consumer.poll(200) // ms
        log.info("closing consumer")
        consumer.close()
        val consumedRecordCount: Int = records.count()
        log.info(s"consumed record count = $consumedRecordCount")
        assert(consumedRecordCount != 0)
        var msg2: Option[String] = None
        for (record: ConsumerRecord[String, String] <- records) {
            val recordTopic = record.topic()
            val value = record.value()
            log.info(s"found message, topic '$recordTopic', value = '$value'")
            assert(topic.equals(recordTopic))
            if (msg.equals(value)) {
                msg2 = Option(value)
            }
        }
        log.info(s"message returned: $msg2")
        log.info(s"message expected: $msg")
        msg2 match {
            case None => {
                println("CRITICAL: message not returned by Kafka")
                System.exit(2)
            }
            case Some(msg) => {
                // good it's the same message
            }
            case _ => {
                println("CRITICAL: message returned does not equal message sent!")
                System.exit (2)
            }
        }
    }

}
