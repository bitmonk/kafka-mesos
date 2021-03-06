/**
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

package ly.stealth.mesos.kafka

import java.util.concurrent.TimeUnit

import org.apache.log4j.Logger
import java.io.{FileInputStream, File}
import java.net.{URL, URLClassLoader}
import java.util.Properties
import java.util
import scala.collection.JavaConversions._
import ly.stealth.mesos.kafka.BrokerServer.Distro
import net.elodina.mesos.util.{IO, Version}

abstract class BrokerServer {
  def isStarted: Boolean
  def start(broker: Broker, send: Broker.Metrics => Unit, defaults: util.Map[String, String] = new util.HashMap()): Broker.Endpoint
  def stop(): Unit
  def waitFor(): Unit
  def getClassLoader: ClassLoader
}

class KafkaServer extends BrokerServer {
  val logger = Logger.getLogger(classOf[KafkaServer])
  @volatile var server: Object = null
  @volatile var collector: MetricsCollector = null

  def isStarted: Boolean = server != null

  def start(broker: Broker, send: Broker.Metrics => Unit, defaults: util.Map[String, String] = new util.HashMap()): Broker.Endpoint = {
    if (isStarted) throw new IllegalStateException("started")

    BrokerServer.Distro.configureLog4j(broker)
    val options = broker.options(defaults)
    BrokerServer.Distro.startReporters(options)

    logger.info("Starting KafkaServer")
    server = BrokerServer.Distro.newServer(options)
    server.getClass.getMethod("startup").invoke(server)

    collector = BrokerServer.Distro.startCollector(send)

    new Broker.Endpoint(options.get("host.name"), Integer.parseInt(options.get("port")))
  }

  def stop(): Unit = {
    if (!isStarted) throw new IllegalStateException("!started")

    logger.info("Stopping KafkaServer")
    collector.shutdown()
    server.getClass.getMethod("shutdown").invoke(server)

    waitFor()
    server = null
    collector = null
  }

  def waitFor(): Unit = {
    if (server != null)
      server.getClass.getMethod("awaitShutdown").invoke(server)
  }

  def getClassLoader: ClassLoader = Distro.loader
}

object BrokerServer {
  object Distro {
    var dir: File = null
    var loader: URLClassLoader = null
    init()

    def newServer(options: util.Map[String, String]): Object = {
      val serverClass = loader.loadClass("kafka.server.KafkaServerStartable")
      val configClass = loader.loadClass("kafka.server.KafkaConfig")

      val config: Object = newKafkaConfig(this.props(options, "server.properties"))
      val server: Object = serverClass.getConstructor(configClass).newInstance(config).asInstanceOf[Object]

      server
    }

    def startReporters(options: util.Map[String, String]): Object = {
      val metricsReporter = loader.loadClass("kafka.metrics.KafkaMetricsReporter$").getField("MODULE$").get(null)
      val metricsReporterClass = metricsReporter.getClass

      val props = this.props(options, "server.properties")
      val verifiablePropsClass: Class[_] = loader.loadClass("kafka.utils.VerifiableProperties")
      val verifiableProps: Object = verifiablePropsClass.getConstructor(classOf[Properties]).newInstance(props).asInstanceOf[Object]

      metricsReporterClass.getMethod("startReporters", verifiableProps.getClass).invoke(metricsReporter, verifiableProps)
      metricsReporter
    }

    def startCollector(send: Broker.Metrics => Unit): MetricsCollector = {
      val collector = new MetricsCollector(send)
      collector.start(60, TimeUnit.SECONDS)
      collector.run()

      collector
    }

    private def newKafkaConfig(props: Properties): Object = {
      val configClass = loader.loadClass("kafka.server.KafkaConfig")
      var config: Object = null

      // in kafka <= 0.8.x constructor is KafkaConfig(java.util.Properties)
      try { config = configClass.getConstructor(classOf[Properties]).newInstance(props).asInstanceOf[Object] }
      catch { case e: NoSuchMethodException => }

      if (config == null) {
        // in kafka 0.9.0.0 constructor is KafkaConfig(java.util.Map[_,_])
        val map: util.Map[_,_] = props.toMap.asInstanceOf[Map[_,_]]
        try { config = configClass.getConstructor(classOf[util.Map[String, String]]).newInstance(map).asInstanceOf[Object] }
        catch { case e: NoSuchMethodError => }
      }

      if (config == null) throw new IllegalStateException("Can't create KafkaConfig. Unsupported kafka distro?")
      config
    }

    def configureLog4j(broker: Broker): Unit = {
      if (broker.syslog) {
        val pattern = System.getenv("MESOS_SYSLOG_TAG") + ": [%t] %-5p %c %x - %m%n"

        IO.replaceInFile(new File(Distro.dir + "/config/log4j.properties"), Map[String, String](
          "log4j.rootLogger=INFO, stdout" ->
          s"""
            |log4j.rootLogger=INFO, stdout, syslog
            |
            |log4j.appender.syslog=org.apache.log4j.net.SyslogAppender
            |log4j.appender.syslog.syslogHost=localhost
            |log4j.appender.syslog.header=true
            |log4j.appender.syslog.layout=org.apache.log4j.PatternLayout
            |log4j.appender.syslog.layout.conversionPattern=$pattern
          """.stripMargin
        ))
      }

      System.setProperty("kafka.logs.dir", "" + new File(Distro.dir, "log"))
      val props: Properties = this.props(broker.interpolatedLog4jOptions(), "log4j.properties")

      val configurator: Class[_] = loader.loadClass("org.apache.log4j.PropertyConfigurator")
      configurator.getMethod("configure", classOf[Properties]).invoke(null, props)
    }

    private def props(options: util.Map[String, String], defaultsFile: String): Properties = {
      val p: Properties = new Properties()
      val stream: FileInputStream = new FileInputStream(dir + "/config/" + defaultsFile)

      try { p.load(stream) }
      finally { stream.close() }

      for ((k, v) <- options)
        if (v != null) p.setProperty(k, v)
        else p.remove(k)

      p
    }

    private def init(): Unit = {
      // find kafka dir
      for (file <- new File(".").listFiles())
        if (file.isDirectory && file.getName.startsWith("kafka"))
          dir = file

      if (dir == null) throw new IllegalStateException("Kafka distribution dir not found")

      // create kafka classLoader
      val classpath = new util.ArrayList[URL]()
      for (file <- new File(dir, "libs").listFiles())
        classpath.add(new URL("" + file.toURI))

      loader = new Loader(classpath.toArray(Array()))
    }
  }

  // Loader that loads classes in reverse order: 1. from self, 2. from parent.
  // This is required, because current jar have classes incompatible with classes from kafka distro.
  class Loader(urls: Array[URL]) extends URLClassLoader(urls) {
    val snappyHackedClasses = Array[String]("org.xerial.snappy.SnappyNativeAPI", "org.xerial.snappy.SnappyNative", "org.xerial.snappy.SnappyErrorCode")
    var snappyHackEnabled = false
    checkSnappyVersion

    def checkSnappyVersion {
      var jarName: String = null
      for (url <- urls) {
        val fileName = new File(url.getFile).getName
        if (fileName.matches("snappy.*jar")) jarName = fileName
      }

      if (jarName == null) return
      val hIdx = jarName.lastIndexOf("-")
      val extIdx = jarName.lastIndexOf(".jar")
      if (hIdx == -1 || extIdx == -1) return

      val version = new Version(jarName.substring(hIdx + 1, extIdx))
      snappyHackEnabled = version.compareTo(new Version(1,1,0)) <= 0
    }

    override protected def loadClass(name: String, resolve: Boolean): Class[_] = {
      getClassLoadingLock(name) synchronized {
        // Handle Snappy class loading hack:
        // Snappy injects 3 classes and native lib to root ClassLoader
        // See - org.xerial.snappy.SnappyLoader.injectSnappyNativeLoader
        if (snappyHackEnabled && snappyHackedClasses.contains(name))
          return super.loadClass(name, true)
        // Always load com.yammer.metrics from the root ClassLoader.
        // This ensures that both our MetricsCollector and Kafka see the same
        // MetricsRegistry.
        if (name.startsWith("com.yammer.metrics"))
          return super.loadClass(name, true)

        // Check class is loaded
        var c: Class[_] = findLoadedClass(name)

        // Load from self
        try { if (c == null) c = findClass(name) }
        catch { case e: ClassNotFoundException => }

        // Load from parent
        if (c == null) c = super.loadClass(name, true)

        if (resolve) resolveClass(c)
        c
      }
    }
  }
}
